package app.umbra.agent.data

import app.umbra.agent.AppContainer
import app.umbra.core.decision.DecisionEngine
import app.umbra.core.decision.LayaHttpEngine
import app.umbra.core.decision.SwitchableEngine
import app.umbra.platform.api.ShellResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The Laya decision model as a sidecar in the Linux environment (spec §4, ADR 0005). It is installed on
 * request: `pip install "laya[serve]"` into the agent's home, with its weights downloaded from
 * Hugging Face on first start, then served on loopback by `laya-serve`. While it runs, small decisions
 * ("which of these two buttons?", "did that work?") are answered in one fast pass by Laya; while it does
 * not, the built-in rules answer them and anything unclear goes to the agent.
 */
class LayaSidecar internal constructor(
    private val host: Host,
    private val scope: CoroutineScope,
    private val endpoint: DecisionEngine = LayaHttpEngine("http://127.0.0.1:8765",
        OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(), model = MODEL),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    internal interface Host {
        var wanted: Boolean
        val ready: Boolean
        suspend fun ensureReady(): Boolean
        suspend fun exec(argv: List<String>, timeoutMs: Long = 30_000, maxOutputBytes: Int = 64 * 1024): ShellResult
    }

    constructor(container: AppContainer, scope: CoroutineScope) : this(object : Host {
        override var wanted: Boolean
            get() = container.settings.value.layaWanted
            set(value) { container.settings.update { it.copy(layaWanted = value, layaOffered = true) } }
        override val ready: Boolean
            get() = container.guest.state.value in setOf(GuestManager.State.Ready, GuestManager.State.Installed)
        override suspend fun ensureReady() = container.guest.ensureReady()
        override suspend fun exec(argv: List<String>, timeoutMs: Long, maxOutputBytes: Int) =
            container.guest.runtime.exec(argv, cwd = "/home/agent", timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes)
    }, scope)

    enum class State { NEEDS_LINUX, NOT_INSTALLED, INSTALLING, STOPPED, STARTING, RUNNING, FAILED }

    /** What the router talks to; attached while the sidecar answers. */
    val engine = SwitchableEngine()

    private val mutable = MutableStateFlow(State.NEEDS_LINUX)
    val state: StateFlow<State> = mutable.asStateFlow()

    @Volatile
    var message: String? = null
        private set

    private val busy = Mutex()
    private val port = 8765
    private val bin = "\$HOME/.local/bin/laya-serve"
    private var retryAtMs = 0L
    private var retryDelayMs = 30_000L
    private var stopPending = false
    @Volatile private var serverGeneration = 0L

    /** Looks at the guest and the port: installed? running? */
    suspend fun refresh() {
        if (!busy.tryLock()) return
        try { refreshNow() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { failed(e) }
        finally { busy.unlock() }
    }

    private suspend fun refreshNow() {
        if (host.ready && mutable.value == State.RUNNING && endpoint.isAvailable()) return
        if (!host.ready || !host.ensureReady()) {
            engine.target = null
            mutable.value = State.NEEDS_LINUX
            return
        }
        val installed = try {
            host.exec(listOf("bash", "-c", "test -x $bin")).ok
        } catch (e: app.umbra.platform.api.BackendException) {
            engine.target = null
            mutable.value = State.NEEDS_LINUX // root went away while asking
            return
        }
        if (!installed) {
            engine.target = null
            mutable.value = State.NOT_INSTALLED
            return
        }
        val running = endpoint.isAvailable()
        if (running) {
            engine.target = endpoint
            mutable.value = State.RUNNING
        } else {
            engine.target = null
            if (mutable.value != State.FAILED) mutable.value = State.STOPPED
        }
    }

    /** Installs the package into the agent's home (about 2 GB with its runtime; needs the network). */
    fun install() = start()

    private suspend fun installNow() {
        mutable.value = State.INSTALLING
        message = null
        val out = host.exec(
            listOf("bash", "-o", "pipefail", "-c", "python3 -m pip install --user --break-system-packages --no-cache-dir 'laya[serve]' 2>&1 | tail -5"),
            timeoutMs = 60 * 60_000L,
        )
        if (out.ok && host.exec(listOf("bash", "-c", "test -x $bin")).ok) {
            mutable.value = State.STOPPED
        } else {
            message = out.combined.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" ").take(300).ifBlank { "pip did not finish" }
            mutable.value = State.FAILED
        }
    }

    /** Install and preload once, then retain the server even between tasks. */
    fun download() = start()

    /** Called periodically by the app service. Failed starts back off to avoid a battery-draining restart loop. */
    suspend fun maintain() {
        if (!busy.tryLock()) return
        try {
            if (!host.wanted) {
                if (stopPending || server != null || mutable.value == State.RUNNING) stopNow()
                retryAtMs = 0
                retryDelayMs = 30_000
                return
            }
            if (nowMs() < retryAtMs) return
            try {
                ensureRunning()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { failed(e) }
            if (mutable.value == State.FAILED) {
                retryAtMs = nowMs() + retryDelayMs
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(5 * 60_000)
            } else {
                retryAtMs = 0
                retryDelayMs = 30_000
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { failed(e) }
        finally { busy.unlock() }
    }

    private suspend fun ensureRunning() {
        refreshNow()
        if (!host.wanted || mutable.value == State.NEEDS_LINUX || mutable.value == State.RUNNING) return
        if (mutable.value == State.NOT_INSTALLED) installNow()
        else if (mutable.value == State.FAILED) mutable.value = State.STOPPED
        if (host.wanted && mutable.value == State.STOPPED) startNow()
    }

    /** Starts `laya-serve` in the background and waits until it answers (the first start also downloads the weights). */
    fun start() {
        host.wanted = true
        launch { retryAtMs = 0; ensureRunning() }
    }

    private suspend fun startNow() {
        mutable.value = State.STARTING
        engine.target = null
        message = null
        val generation = ++serverGeneration
        killServer()
        // The server runs as this exec, in the foreground for as long as it serves: under proot a process left in the
        // background ends with the exec that started it.
        server?.cancelAndJoin()
        server = scope.launch {
            try {
                host.exec(
                    listOf(
                        "bash", "-c",
                        "mkdir -p \$HOME/.umbra; " +
                            "LAYA_DEVICE=cpu LAYA_HOST=127.0.0.1 LAYA_PORT=$port LAYA_MODELS=$MODEL LAYA_DEFAULT_MODEL=$MODEL LAYA_PRELOAD=1 " +
                            "exec $bin > \$HOME/.umbra/laya.log 2>&1 < /dev/null",
                    ),
                    timeoutMs = SERVE_MS,
                )
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* The health probe reports startup failures. */ }
            finally {
                if (generation == serverGeneration && mutable.value == State.RUNNING) {
                    engine.target = null
                    mutable.value = State.FAILED
                    message = "Laya stopped; waiting to restart."
                }
            }
        }
        val deadline = nowMs() + 20 * 60_000L // the weights download on the first start
        while (nowMs() < deadline) {
            if (!host.wanted) { stopNow(); return }
            if (server?.isCompleted == true) break
            if (endpoint.isAvailable()) {
                engine.target = endpoint
                mutable.value = State.RUNNING
                return
            }
            delay(2_000)
        }
        stopNow()
        message = host.exec(listOf("bash", "-c", "tail -5 \$HOME/.umbra/laya.log")).stdout.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" ").take(300)
        mutable.value = State.FAILED
    }

    fun stop() {
        host.wanted = false
        engine.target = null
        launch { stopNow() }
    }

    private suspend fun stopNow() {
        engine.target = null
        stopPending = true
        ++serverGeneration
        killServer()
        server?.cancelAndJoin()
        server = null
        stopPending = false
        mutable.value = State.STOPPED
    }

    /**
     * Ends a running server. The pattern's first letter is bracketed so it does not match the command
     * line of the very shell that carries it (`pkill -f` would end that shell first), and the script
     * contains nothing else that looks like the server.
     */
    private suspend fun killServer() {
        val result = host.exec(listOf("bash", "-c", "pkill -u \$(id -u) -f '[l]aya-serve'; status=\$?; test \"\$status\" -le 1"))
        check(result.ok) { "Could not stop the Laya server; retry when Linux is available." }
    }

    private fun launch(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            busy.withLock {
                try {
                    block()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed(e)
                }
            }
        }
    }

    private fun failed(error: Exception) {
        engine.target = null
        message = error.message
        mutable.value = State.FAILED
    }

    @Volatile private var server: kotlinx.coroutines.Job? = null

    private companion object {
        /** Renewed by maintenance when the guest's execution lease ends. */
        const val SERVE_MS = 24 * 60 * 60_000L
        /** The typed-decisions checkpoint of `convaiinnovations/laya` (README: "the typed-decisions workflows"). */
        const val MODEL = "laya-typed-decisions"
    }
}
