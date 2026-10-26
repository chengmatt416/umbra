package app.umbra.agent.data

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.umbra.agent.AppContainer
import app.umbra.agent.data.laya.LayaExit
import app.umbra.agent.data.laya.LayaNative
import app.umbra.core.decision.DecisionEngine
import app.umbra.core.decision.SwitchableEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The Laya decision model (spec §4, ADR 0005). Downloaded on request and then run inside the app ([LayaNative]): on
 * the NPU when a file is compiled for this phone's SoC, else on the GPU, else on the CPU, in a process of its own. While
 * it runs, small decisions ("which of these two buttons?", "did that work?") are answered in one pass by Laya; while it
 * does not (also the moment its process dies), the built-in rules answer them and anything unclear goes to the agent.
 *
 * (The name is historical: until 0.6.1 Laya ran as a `laya-serve` sidecar in the Linux environment.)
 */
class LayaSidecar internal constructor(
    private val host: Host,
    private val scope: CoroutineScope,
    /** Where explicit requests (download, start, stop) run; tests pass their scheduler's dispatcher. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    internal interface Host {
        /** The person asked for Laya (and has not turned it off). */
        var wanted: Boolean
        /** When the last download or load failed (wall clock), kept across restarts; null after a success or a manual retry. */
        var failedAt: Long?
        /** Failures since the last success or manual start: each one doubles the wait before Laya tries again by itself. */
        var failures: Int
        fun wallNowMs(): Long = System.currentTimeMillis()
        /** Downloads nobody just asked for wait for an unmetered network. */
        fun unmetered(): Boolean
        fun downloaded(): Boolean
        /** Bytes still to download before Laya can run here. */
        fun bytesToDownload(): Long
        val progress: StateFlow<LayaNative.Progress?>
        suspend fun load(download: Boolean): Opened
        fun clear()
        /** The loaded engine's process died (see [Opened.died]): remember what that says about its processor. */
        fun died(opened: Opened, exit: LayaExit) = Unit
        fun processors(inUse: String?): List<LayaNative.Processor> = emptyList()
        fun chip(): String? = null
        fun resetProcessors() = Unit
        fun crashForTest(opened: Opened) = Unit
    }

    /** A loaded engine, where it runs, and how to release it; [died] completes if its process dies on its own. */
    internal class Opened(val engine: DecisionEngine, val backend: String, val checkMs: Long, val skipped: List<String>,
        val died: Deferred<LayaExit>? = null, val loaded: LayaNative.Loaded? = null, val close: suspend () -> Unit)

    constructor(container: AppContainer, scope: CoroutineScope) : this(object : Host {
        private val native = LayaNative(container.context, OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build())
        override var wanted: Boolean
            get() = container.settings.value.layaWanted
            set(value) { container.settings.update { it.copy(layaWanted = value, layaOffered = true) } }
        private val store = container.context.getSharedPreferences("laya", android.content.Context.MODE_PRIVATE)
        override var failedAt: Long?
            get() = store.getLong("failed_at", 0L).takeIf { it > 0 }
            set(value) { store.edit().apply { if (value == null) remove("failed_at") else putLong("failed_at", value) }.apply() }
        override var failures: Int
            get() = store.getInt("failures", 0)
            set(value) { store.edit().putInt("failures", value).apply() }
        override fun unmetered(): Boolean {
            val connectivity = container.context.getSystemService(ConnectivityManager::class.java)
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        override fun downloaded() = native.downloaded()
        override fun bytesToDownload() = native.bytesToDownload()
        override val progress get() = native.progress
        override suspend fun load(download: Boolean): Opened {
            val loaded = native.load(download)
            return Opened(loaded.engine, loaded.backend.key, loaded.checkMs, loaded.skipped, loaded.died, loaded) { loaded.engine.close() }
        }
        override fun clear() = native.clear()
        override fun died(opened: Opened, exit: LayaExit) { opened.loaded?.let { native.died(it, exit) } }
        override fun processors(inUse: String?) = native.processors(inUse)
        override fun chip() = native.chip()
        override fun resetProcessors() = native.resetProcessors()
        override fun crashForTest(opened: Opened) { opened.loaded?.let(native::crashForTest) }
    }, scope)

    /** NEEDS_LINUX is no longer reported: Laya runs in the app. */
    enum class State { NEEDS_LINUX, NOT_INSTALLED, INSTALLING, STOPPED, STARTING, RUNNING, FAILED }

    /** What the router talks to; attached while Laya answers. */
    val engine = SwitchableEngine()

    private val mutable = MutableStateFlow(State.NOT_INSTALLED)
    val state: StateFlow<State> = mutable.asStateFlow()

    /** Where Laya runs while it runs: "npu", "gpu" or "cpu". */
    private val mutableBackend = MutableStateFlow<String?>(null)
    val backend: StateFlow<String?> = mutableBackend.asStateFlow()

    val progress: StateFlow<LayaNative.Progress?> get() = host.progress

    @Volatile
    var message: String? = null
        private set

    private val busy = Mutex()
    @Volatile private var opened: Opened? = null
    private var watch: Job? = null
    /** Wakes [keepUp] early: Laya's process died and the next processor may start. */
    private val mutableChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** NPU, GPU and CPU: which one answers, which failed and why (kept across starts), for Settings. */
    fun processors(): List<LayaNative.Processor> = runCatching { host.processors(backend.value) }.getOrDefault(emptyList())

    /** The phone's chip ("Qualcomm SM8750"), or null. */
    fun chip(): String? = runCatching { host.chip() }.getOrNull()

    /** Tries every processor again on the next start (after a driver update, say). */
    fun retryProcessors() {
        host.resetProcessors()
        if (mutable.value != State.RUNNING) start()
    }

    /** Debug builds: crashes the loaded model's process, to show the app survives and falls back. */
    fun crashForTest() { opened?.let { host.crashForTest(it) } }

    /** Looks at what is downloaded and loaded. Callable from the main thread: the file check runs on [io]. */
    suspend fun refresh() {
        if (!busy.tryLock()) return
        try {
            if (mutable.value == State.RUNNING && opened != null) return
            // Checking a file can read all 810 MB of it (its SHA-256): never on the main thread, where Settings calls this.
            val downloaded = kotlinx.coroutines.withContext(io) { host.downloaded() }
            if (mutable.value != State.FAILED) mutable.value = if (downloaded) State.STOPPED else State.NOT_INSTALLED
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { failed(e) }
        finally { busy.unlock() }
    }

    /** Bytes the next download fetches (the best file this phone can use). */
    fun downloadSize(): Long = runCatching { host.bytesToDownload() }.getOrDefault(0L)

    /** Downloads (if needed) and starts Laya now, whatever the network. */
    fun install() = start()

    /** Downloads (if needed) and starts Laya now, whatever the network. */
    fun download() = start()

    fun start() {
        host.wanted = true
        launch { host.failedAt = null; host.failures = 0; ensureRunning(userAsked = true) }
    }

    /**
     * For the app's life: keeps Laya running while it is wanted. Explicit starts and stops act at once, so this only
     * looks again every [MAINTAIN_MS] (a download waiting for Wi-Fi, an hourly retry) or when Laya's process died; it
     * used to look every 30 s, which kept waking the phone for nothing.
     */
    suspend fun keepUp(): Nothing {
        while (true) {
            maintain()
            withTimeoutOrNull(MAINTAIN_MS) { mutableChanged.first() }
        }
    }

    /** Starts Laya when wanted, retrying a failure at most hourly. */
    suspend fun maintain() {
        if (!busy.tryLock()) return
        try {
            if (!host.wanted) {
                if (opened != null || mutable.value == State.RUNNING) stopNow()
                return
            }
            if (mutable.value == State.RUNNING && opened != null) return
            val failedAt = host.failedAt
            // 1 h after a failure, then 2, 4, 8, 16 and at most 24 h: a phone where Laya cannot run stops loading 810 MB
            // every hour for nothing.
            val wait = (RETRY_MS shl (host.failures - 1).coerceIn(0, 5)).coerceAtMost(24 * RETRY_MS)
            if (failedAt != null && host.wallNowMs() - failedAt in 0 until wait) { mutable.value = State.FAILED; return }
            ensureRunning(userAsked = false)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { failed(e) }
        finally { busy.unlock() }
    }

    private suspend fun ensureRunning(userAsked: Boolean) {
        if (mutable.value == State.RUNNING && opened != null) return
        val downloaded = host.downloaded()
        val mayDownload = userAsked || host.unmetered()
        if (!downloaded && !mayDownload) {
            mutable.value = State.NOT_INSTALLED
            message = null
            return
        }
        mutable.value = if (downloaded) State.STARTING else State.INSTALLING
        message = null
        val next = try {
            host.load(download = mayDownload)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        // Throwable: an Error from a native library (an UnsatisfiedLinkError, say) must end in "failed", not end the app.
        catch (e: Throwable) {
            host.failedAt = host.wallNowMs()
            host.failures = host.failures + 1
            failed(e)
            return
        }
        if (!host.wanted) { next.close(); mutable.value = State.STOPPED; return }
        opened = next
        engine.target = next.engine
        mutableBackend.value = next.backend
        message = next.skipped.takeIf { it.isNotEmpty() }?.joinToString("; ")?.take(300)
        host.failedAt = null
        host.failures = 0
        mutable.value = State.RUNNING
        next.died?.let { died -> watch?.cancel(); watch = scope.launch(io) { onDied(next, died.await()) } }
    }

    /**
     * Laya's process died while loaded: decisions go back to the built-in rules at once. A crash refuses that processor
     * and Laya starts again on the next one at the next [maintain]; Android ending it for memory waits for the hourly
     * retry (loading again at once would only be ended again, and costs battery).
     */
    private suspend fun onDied(dead: Opened, exit: LayaExit) = busy.withLock {
        if (opened !== dead) return@withLock
        engine.target = null
        opened = null
        mutableBackend.value = null
        runCatching { host.died(dead, exit) }
        message = "Laya's process on the ${dead.backend.uppercase()} stopped: ${exit.text}".take(300)
        if (!exit.crashed || dead.backend == "cpu") { host.failedAt = host.wallNowMs(); host.failures = host.failures + 1 }
        mutable.value = State.FAILED
        mutableChanged.tryEmit(Unit)
    }

    fun stop() {
        host.wanted = false
        engine.target = null
        launch { stopNow() }
    }

    /** Stops Laya and deletes its downloaded files. */
    fun remove() {
        host.wanted = false
        engine.target = null
        launch { stopNow(); host.clear(); mutable.value = State.NOT_INSTALLED }
    }

    private suspend fun stopNow() {
        engine.target = null
        mutableBackend.value = null
        watch?.cancel()
        watch = null
        opened?.let { runCatching { it.close() } }
        opened = null
        mutable.value = if (host.downloaded()) State.STOPPED else State.NOT_INSTALLED
    }

    private fun launch(block: suspend () -> Unit) {
        scope.launch(io) {
            busy.withLock {
                try {
                    block()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failed(e)
                }
            }
        }
    }

    private fun failed(error: Throwable) {
        engine.target = null
        mutableBackend.value = null
        message = error.message?.take(300)
        mutable.value = State.FAILED
    }

    private companion object {
        /** A failure retries by itself at most this often; an explicit download or start retries at once. */
        const val RETRY_MS = 60 * 60_000L
        const val MAINTAIN_MS = 15 * 60_000L
    }
}
