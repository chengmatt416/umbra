package app.umbra.agent.data

import app.umbra.agent.AppContainer
import app.umbra.core.decision.LayaHttpEngine
import app.umbra.core.decision.SwitchableEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    fun install() = launch {
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

    /** Starts `laya-serve` in the background and waits until it answers (the first start also downloads the weights). */
    fun start() = launch {
        mutable.value = State.STARTING
        message = null
        killServer()
        container.guest.runtime.exec(
            listOf(
                "bash", "-c",
                "mkdir -p \$HOME/.umbra && " +
                    "LAYA_DEVICE=cpu LAYA_HOST=127.0.0.1 LAYA_PORT=$port LAYA_MODELS=$MODEL LAYA_DEFAULT_MODEL=$MODEL LAYA_PRELOAD=1 " +
                    "setsid nohup $bin > \$HOME/.umbra/laya.log 2>&1 < /dev/null & echo started",
            ),
            cwd = "/home/agent", timeoutMs = 30_000,
        )
        val deadline = System.currentTimeMillis() + 20 * 60_000L // the weights download on the first start
        while (System.currentTimeMillis() < deadline) {
            if (LayaHttpEngine(url, http).isAvailable()) {
                engine.target = LayaHttpEngine(url, http, model = MODEL)
                mutable.value = State.RUNNING
                return@launch
            }
            delay(2_000)
        }
        message = container.guest.runtime.exec(listOf("bash", "-c", "tail -5 \$HOME/.umbra/laya.log"), cwd = "/home/agent").stdout.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" ").take(300)
        mutable.value = State.FAILED
    }

    fun stop() = launch {
        engine.target = null
        killServer()
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

    private companion object {
        /** The typed-decisions checkpoint of `convaiinnovations/laya` (README: "the typed-decisions workflows"). */
        const val MODEL = "laya-typed-decisions"
    }
}
