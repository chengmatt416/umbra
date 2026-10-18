package app.umbra.agent.data

import app.umbra.agent.AppContainer
import app.umbra.core.decision.LayaHttpEngine
import app.umbra.core.decision.SwitchableEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
class LayaSidecar(private val container: AppContainer, private val scope: CoroutineScope) {
    enum class State { NEEDS_LINUX, NOT_INSTALLED, INSTALLING, STOPPED, STARTING, RUNNING, FAILED }

    /** What the router talks to; attached while the sidecar answers. */
    val engine = SwitchableEngine()

    private val mutable = MutableStateFlow(State.NEEDS_LINUX)
    val state: StateFlow<State> = mutable.asStateFlow()

    @Volatile
    var message: String? = null
        private set

    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val busy = Mutex()
    private val port = 8765
    private val url = "http://127.0.0.1:$port"
    private val bin = "\$HOME/.local/bin/laya-serve"

    private fun guestReady() = container.guest.state.value == GuestManager.State.Ready || container.guest.state.value == GuestManager.State.Installed

    /** Looks at the guest and the port: installed? running? */
    suspend fun refresh() {
        if (busy.isLocked) return // an install or start is changing the state itself
        if (!guestReady() || !container.guest.ensureReady()) {
            mutable.value = State.NEEDS_LINUX
            return
        }
        val installed = try {
            container.guest.runtime.exec(listOf("bash", "-c", "test -x $bin")).ok
        } catch (e: app.umbra.platform.api.BackendException) {
            mutable.value = State.NEEDS_LINUX // root went away while asking
            return
        }
        if (!installed) {
            mutable.value = State.NOT_INSTALLED
            return
        }
        val running = LayaHttpEngine(url, http).isAvailable()
        if (running) {
            engine.target = LayaHttpEngine(url, http, model = MODEL)
            mutable.value = State.RUNNING
        } else {
            engine.target = null
            if (mutable.value != State.FAILED) mutable.value = State.STOPPED
        }
    }

    /** Installs the package into the agent's home (about 2 GB with its runtime; needs the network). */
    fun install() = launch { installNow() }

    private suspend fun installNow() {
        mutable.value = State.INSTALLING
        message = null
        val out = container.guest.runtime.exec(
            listOf("bash", "-c", "python3 -m pip install --user --break-system-packages --no-cache-dir 'laya[serve]' 2>&1 | tail -5"),
            cwd = "/home/agent", timeoutMs = 60 * 60_000L, maxOutputBytes = 64 * 1024,
        )
        if (out.ok && container.guest.runtime.exec(listOf("bash", "-c", "test -x $bin")).ok) {
            mutable.value = State.STOPPED
        } else {
            message = out.combined.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" ").take(300).ifBlank { "pip did not finish" }
            mutable.value = State.FAILED
        }
    }

    /**
     * First-run setup's "download": install the package, fetch the weights (the first start does), then stop again.
     * Everything lands in the agent's home, which lives outside the Linux image, so an app update keeps it; tasks start
     * the server when they begin ([startForTask]).
     */
    fun download() {
        if (!downloading.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
          try {
            container.guest.state.first { it == GuestManager.State.Ready || it == GuestManager.State.Installed }
            busy.withLock {
                try {
                    if (!container.guest.ensureReady()) { mutable.value = State.NEEDS_LINUX; return@withLock }
                    if (!container.guest.runtime.exec(listOf("bash", "-c", "test -x $bin")).ok) installNow()
                    if (mutable.value == State.FAILED) return@withLock
                    startNow()
                    if (mutable.value == State.RUNNING && !anyTaskRunning()) stopNow()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = e.message
                    mutable.value = State.FAILED
                }
            }
          } finally {
            downloading.set(false)
          }
        }
    }

    private val downloading = java.util.concurrent.atomic.AtomicBoolean(false)

    /** A task began: start the server if it is installed and not running (the user wanted Laya). */
    fun startForTask() {
        if (!container.settings.value.layaWanted) return
        if (mutable.value == State.STOPPED) start()
    }

    private suspend fun anyTaskRunning() = container.sessionStore.list().any { it.state == app.umbra.core.session.SessionState.RUNNING }

    /** Starts `laya-serve` in the background and waits until it answers (the first start also downloads the weights). */
    fun start() = launch { startNow() }

    private suspend fun startNow() {
        mutable.value = State.STARTING
        message = null
        killServer()
        // The server runs as this exec, in the foreground for as long as it serves: under proot a process left in the
        // background ends with the exec that started it.
        server?.cancel()
        server = scope.launch(Dispatchers.IO) {
            runCatching {
                container.guest.runtime.exec(
                    listOf(
                        "bash", "-c",
                        "mkdir -p \$HOME/.umbra; " +
                            "LAYA_DEVICE=cpu LAYA_HOST=127.0.0.1 LAYA_PORT=$port LAYA_MODELS=$MODEL LAYA_DEFAULT_MODEL=$MODEL LAYA_PRELOAD=1 " +
                            "exec $bin > \$HOME/.umbra/laya.log 2>&1 < /dev/null",
                    ),
                    cwd = "/home/agent", timeoutMs = SERVE_MS,
                )
            }
            if (mutable.value == State.RUNNING) { engine.target = null; mutable.value = State.STOPPED }
        }
        val deadline = System.currentTimeMillis() + 20 * 60_000L // the weights download on the first start
        while (System.currentTimeMillis() < deadline) {
            if (server?.isCompleted == true) break
            if (LayaHttpEngine(url, http).isAvailable()) {
                engine.target = LayaHttpEngine(url, http, model = MODEL)
                mutable.value = State.RUNNING
                return
            }
            delay(2_000)
        }
        message = container.guest.runtime.exec(listOf("bash", "-c", "tail -5 \$HOME/.umbra/laya.log"), cwd = "/home/agent").stdout.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" ").take(300)
        mutable.value = State.FAILED
    }

    fun stop() = launch { stopNow() }

    private suspend fun stopNow() {
        engine.target = null
        killServer()
        server?.cancel()
        server = null
        mutable.value = State.STOPPED
    }

    /**
     * Ends a running server. The pattern's first letter is bracketed so it does not match the command
     * line of the very shell that carries it (`pkill -f` would end that shell first), and the script
     * contains nothing else that looks like the server.
     */
    private suspend fun killServer() {
        container.guest.runtime.exec(listOf("bash", "-c", "pkill -u \$(id -u) -f '[l]aya-serve'; true"), cwd = "/home/agent")
    }

    private fun launch(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            busy.withLock {
                try {
                    block()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = e.message
                    mutable.value = State.FAILED
                }
            }
        }
    }

    @Volatile private var server: kotlinx.coroutines.Job? = null

    private companion object {
        /** How long one start of the server may serve before its exec is ended (it is started again with the next task). */
        const val SERVE_MS = 24 * 60 * 60_000L
        /** The typed-decisions checkpoint of `convaiinnovations/laya` (README: "the typed-decisions workflows"). */
        const val MODEL = "laya-typed-decisions"
    }
}
