package app.umbra.agent.data.laya

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import app.umbra.core.decision.LayaGraph
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Laya's own process (`:laya`, ADR 0005 amended). LiteRT's GPU and NPU paths run vendor driver code that can abort the
 * process (a driver assertion, a compiler running out of memory, the low-memory killer taking the biggest process), and
 * an 810 MB model is the biggest thing the app holds. In here, any of that ends only this process: the app sees the
 * binder die, refuses that processor and goes on with the built-in rules or the next processor.
 *
 * One model per process, used only from one thread (LiteRT's GPU backends may bind their context to the thread that
 * created them). The service is not exported and answers only its own uid.
 */
class LayaService : Service() {
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in LayaProtocol.FIRST..LayaProtocol.LAST) return super.onTransact(code, data, reply, flags)
            if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Laya answers only its own app")
            data.enforceInterface(LayaProtocol.DESCRIPTOR)
            try {
                when (code) {
                    LayaProtocol.PID -> { reply?.writeInt(0); reply?.writeInt(Process.myPid()) }
                    LayaProtocol.ACCELERATORS -> {
                        val names = environment.getAvailableAccelerators().mapNotNull {
                            when (it) { Accelerator.NPU -> "npu"; Accelerator.GPU -> "gpu"; Accelerator.CPU -> "cpu"; else -> null }
                        }
                        reply?.writeInt(0); reply?.writeStringList(names)
                    }
                    LayaProtocol.OPEN -> {
                        val path = requireNotNull(data.readString())
                        // Read once: inside the predicate it would read the next field for every entry tried.
                        val key = data.readString()
                        val backend = LayaBackend.entries.firstOrNull { it.key == key } ?: error("unknown processor $key")
                        val onDevice = data.readInt() != 0
                        synchronized(this@LayaService) {
                            check(graph == null) { "a model is already open in this process" }
                            graph = Graph.open(File(path), backend, onDevice, environment)
                        }
                        reply?.writeInt(0)
                    }
                    LayaProtocol.RUN -> {
                        val length = data.readInt()
                        val ids = requireNotNull(data.createIntArray())
                        val keyMask = requireNotNull(data.createFloatArray())
                        val markers = requireNotNull(data.createFloatArray())
                        val qtype = requireNotNull(data.createFloatArray())
                        val g = checkNotNull(graph) { "no model is open" }
                        val logits = g.run(length, ids, keyMask, markers, qtype)
                        reply?.writeInt(0); reply?.writeFloatArray(logits)
                    }
                    LayaProtocol.CLOSE -> {
                        synchronized(this@LayaService) { graph?.close(); graph = null }
                        reply?.writeInt(0)
                    }
                    LayaProtocol.CRASH -> {
                        // Debug builds only: proves that a native crash in here leaves the app running.
                        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) throw SecurityException("debug builds only")
                        Os.kill(Os.getpid(), OsConstants.SIGSEGV)
                    }
                }
            } catch (e: Throwable) {
                // An Error too (UnsatisfiedLinkError from a missing driver library, OutOfMemoryError): it is reported to the
                // app as a refusal, not thrown on the binder thread.
                Log.w(TAG, "laya transaction $code failed", e)
                reply?.writeInt(1)
                reply?.writeString("${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()?.take(300).orEmpty()}")
            }
            return true
        }
    }

    /** With the Context, LiteRT also caches what it compiles for the NPU (in cacheDir), so a phone compiles Laya once. */
    private val environment: Environment by lazy {
        val libs = applicationInfo.nativeLibraryDir
        Environment.create(this, mapOf(Environment.Option.DispatchLibraryDir to libs, Environment.Option.CompilerPluginLibraryDir to libs))
    }

    @Volatile private var graph: Graph? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // Nothing else runs in this process: ending it returns every byte of the model and the accelerator's context to
        // the system at once (closing alone leaves driver memory behind).
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
        return false
    }

    /** One CompiledModel and its buffers per sequence bucket, used only from [thread]. */
    private class Graph private constructor(private val thread: ExecutorService) {
        private lateinit var model: CompiledModel
        private val io = HashMap<Int, Pair<List<TensorBuffer>, List<TensorBuffer>>>()

        fun run(length: Int, inputIds: IntArray, keyMask: FloatArray, markers: FloatArray, qtype: FloatArray): FloatArray = on {
            val signature = "l$length"
            val (ins, outs) = io.getOrPut(length) { model.createInputBuffers(signature) to model.createOutputBuffers(signature) }
            ins[0].writeInt(inputIds)
            ins[1].writeFloat(keyMask)
            ins[2].writeFloat(markers)
            ins[3].writeFloat(qtype)
            model.run(ins, outs, signature)
            outs[0].readFloat()
        }

        fun close() {
            runCatching {
                on {
                    io.values.forEach { (a, b) -> (a + b).forEach { it.close() } }
                    io.clear()
                    if (::model.isInitialized) model.close()
                }
            }
            thread.shutdown()
        }

        private fun <T> on(block: () -> T): T = try { thread.submit(Callable(block)).get() }
            catch (e: ExecutionException) { throw e.cause ?: e }

        companion object {
            fun open(file: File, backend: LayaBackend, onDevice: Boolean, env: Environment): Graph {
                val graph = Graph(Executors.newSingleThreadExecutor { Thread(it, "laya-${backend.key}") })
                try {
                    graph.on {
                        val options = when (backend) {
                            // Compiling on the phone, the NPU must take the whole graph: allowing the CPU too would let
                            // a failed compile run everything on the CPU while it is called the NPU.
                            LayaBackend.NPU -> if (onDevice) CompiledModel.Options(Accelerator.NPU)
                            // A file compiled ahead of time only runs where its NPU code runs; ops left out of it stay
                            // on the CPU inside the same model.
                            else CompiledModel.Options(Accelerator.NPU, Accelerator.CPU)
                            // fp16, like the NPU: on Apple's GPU it moved logits by at most 0.19 (a decision flipped only
                            // where two options were 0.01 apart) at 0.13 s for 256 tokens and 0.8 s for 1024; fp32 was
                            // exact but took 8.7 s for 1024, slower than the CPU (runtime-build/laya/convert.py verify).
                            LayaBackend.GPU -> CompiledModel.Options(Accelerator.GPU, Accelerator.CPU).apply {
                                gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP16)
                            }
                            LayaBackend.CPU -> CompiledModel.Options(Accelerator.CPU).apply {
                                cpuOptions = CompiledModel.CpuOptions(numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                            }
                        }
                        graph.model = CompiledModel.create(file.path, options, env)
                    }
                } catch (e: Throwable) {
                    graph.close()
                    throw e
                }
                return graph
            }
        }
    }

    private companion object {
        const val TAG = "LayaService"
    }
}

/** The transactions between the app and [LayaService]; every reply starts with 0 (ok) or 1 and a message. */
internal object LayaProtocol {
    const val DESCRIPTOR = "app.umbra.agent.laya"
    const val PID = IBinder.FIRST_CALL_TRANSACTION
    const val ACCELERATORS = IBinder.FIRST_CALL_TRANSACTION + 1
    const val OPEN = IBinder.FIRST_CALL_TRANSACTION + 2
    const val RUN = IBinder.FIRST_CALL_TRANSACTION + 3
    const val CLOSE = IBinder.FIRST_CALL_TRANSACTION + 4
    const val CRASH = IBinder.FIRST_CALL_TRANSACTION + 5
    const val FIRST = PID
    const val LAST = CRASH
}

/** Laya's process ended under a call or while it was loaded; [reason] says how, as Android recorded it. */
class LayaProcessDied(val reason: LayaExit) : IOException("Laya's process stopped (${reason.text})")

/** How Laya's process ended: a crash in a driver or the runtime, or Android ending it to free memory. */
data class LayaExit(val crashed: Boolean, val lowMemory: Boolean, val text: String)

/**
 * The app's side of one `:laya` process: binds it, calls it with a timeout, and reports when it dies. A process is
 * used for one model; [stop] ends it.
 */
class LayaProcess private constructor(
    private val context: Context,
    private val binder: IBinder,
    private val connection: ServiceConnection,
    val pid: Int,
) {
    private val stopped = AtomicBoolean(false)
    private val dead = CompletableDeferred<LayaExit>()

    /** Completes when the process dies on its own (not when [stop] ends it). */
    val died: Deferred<LayaExit> get() = dead

    private val death = IBinder.DeathRecipient {
        if (!stopped.get()) calls.execute { dead.complete(exitReason(context, pid)) }
    }

    init {
        binder.linkToDeath(death, 0)
    }

    fun accelerators(): Set<String> = call(binder, LayaProtocol.ACCELERATORS, CALL_MS) { }.let { r ->
        try { r.createStringArrayList().orEmpty().toSet() } finally { r.recycle() }
    }

    /** Opens the model; NPU compiles on the phone take long the first time (LiteRT caches the result). */
    fun open(file: File, backend: LayaBackend, onDevice: Boolean) {
        call(binder, LayaProtocol.OPEN, if (backend == LayaBackend.NPU) NPU_OPEN_MS else OPEN_MS) {
            writeString(file.path); writeString(backend.key); writeInt(if (onDevice) 1 else 0)
        }.recycle()
    }

    fun graph(backend: LayaBackend): LayaGraph = object : LayaGraph {
        override val backend: String = backend.key
        override fun run(length: Int, inputIds: IntArray, keyMask: FloatArray, markers: FloatArray, qtype: FloatArray): FloatArray =
            call(binder, LayaProtocol.RUN, RUN_MS) {
                writeInt(length); writeIntArray(inputIds); writeFloatArray(keyMask); writeFloatArray(markers); writeFloatArray(qtype)
            }.let { r -> try { r.createFloatArray() } finally { r.recycle() } } ?: throw IOException("Laya returned no logits")

        override fun close() = stop()
    }

    /** Debug builds: a native crash inside the process, to check that the app survives it. */
    fun crash() {
        val data = Parcel.obtain().apply { writeInterfaceToken(LayaProtocol.DESCRIPTOR) }
        try { runCatching { binder.transact(LayaProtocol.CRASH, data, null, IBinder.FLAG_ONEWAY) } } finally { data.recycle() }
    }

    /**
     * Ends the process and waits until it is gone, so a process started next is a fresh one (binding while this one
     * still lived would start the service in it again, just before it ends). Unbinding first keeps Android from
     * restarting the service when the process goes.
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { binder.unlinkToDeath(death, 0) }
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        val unbound = CountDownLatch(1)
        main.post { runCatching { context.unbindService(connection) }; unbound.countDown() }
        if (onMain) return
        unbound.await(2, TimeUnit.SECONDS)
        if (alive(pid)) runCatching { Process.killProcess(pid) }
        val deadline = System.nanoTime() + 3_000_000_000L
        while (alive(pid) && System.nanoTime() < deadline) Thread.sleep(20)
    }

    /**
     * One transaction, on a pool thread so that a process that hangs (a driver stuck in a compile) is ended after
     * [timeoutMs] instead of blocking Laya forever. The reply is positioned after its status; the caller recycles it.
     */
    private fun call(binder: IBinder, code: Int, timeoutMs: Long, write: Parcel.() -> Unit): Parcel {
        val reply = transact(binder, code, timeoutMs, write) { if (alive(pid)) Process.killProcess(pid) }
        if (reply == null) {
            stopped.set(true)
            throw LayaProcessDied(exitReason(context, pid))
        }
        return reply
    }

    companion object {
        private const val CALL_MS = 30_000L
        private const val OPEN_MS = 4 * 60_000L
        /** Compiling 810 MB for a Hexagon NPU on the phone; LiteRT caches it, so only the first start waits this long. */
        private const val NPU_OPEN_MS = 12 * 60_000L
        private const val RUN_MS = 60_000L

        private val main = Handler(Looper.getMainLooper())
        private val calls: ExecutorService = Executors.newCachedThreadPool { Thread(it, "laya-call").apply { isDaemon = true } }

        /** Starts a fresh `:laya` process and connects to it. Blocks: call it off the main thread. */
        fun start(context: Context, timeoutMs: Long = 30_000): LayaProcess {
            val ready = CountDownLatch(1)
            var bound: IBinder? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) { bound = service; ready.countDown() }
                // The process died: the binder's death recipient reports it. Unbinding keeps Android from starting
                // the service again by itself; Laya decides when to start another.
                override fun onServiceDisconnected(name: ComponentName?) { runCatching { context.unbindService(this) } }
                override fun onBindingDied(name: ComponentName?) { runCatching { context.unbindService(this) }; ready.countDown() }
                override fun onNullBinding(name: ComponentName?) { ready.countDown() }
            }
            // As important as the app itself: a phone's memory manager otherwise takes the biggest process it may (this
            // one, mid-compile) while the app in front still waits for it.
            if (!context.bindService(Intent(context, LayaService::class.java), connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
                throw IOException("Laya's process could not be started")
            }
            fun unbind() = main.post { runCatching { context.unbindService(connection) } }
            ready.await(timeoutMs, TimeUnit.MILLISECONDS)
            val service = bound
            if (service == null) { unbind(); throw IOException("Laya's process did not start") }
            val reply = transact(service, LayaProtocol.PID, CALL_MS, {}) {}
            if (reply == null) { unbind(); throw IOException("Laya's process stopped as it started") }
            val pid = try { reply.readInt() } finally { reply.recycle() }
            return try { LayaProcess(context, service, connection, pid) } catch (e: Exception) { unbind(); throw e }
        }

        /**
         * Sends one transaction and waits at most [timeoutMs] ([onTimeout] ends the process). Returns the reply after
         * its status, null when the process died under the call, and throws the process's own refusal as an IOException.
         */
        private fun transact(binder: IBinder, code: Int, timeoutMs: Long, write: Parcel.() -> Unit, onTimeout: () -> Unit): Parcel? {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            data.writeInterfaceToken(LayaProtocol.DESCRIPTOR)
            data.write()
            val pending = calls.submit<Boolean> { binder.transact(code, data, reply, 0) }
            try {
                try { pending.get(timeoutMs, TimeUnit.MILLISECONDS) }
                catch (e: java.util.concurrent.TimeoutException) {
                    onTimeout()
                    reply.recycle()
                    throw IOException("Laya did not answer within ${timeoutMs / 1000} s")
                } catch (e: ExecutionException) {
                    reply.recycle()
                    if (e.cause is RemoteException) return null
                    throw IOException(e.cause?.message ?: "Laya's call failed", e.cause)
                }
                reply.setDataPosition(0)
                if (reply.readInt() != 0) {
                    val message = reply.readString() ?: "Laya refused"
                    reply.recycle()
                    throw IOException(message)
                }
                return reply
            } finally {
                data.recycle()
            }
        }

        private fun alive(pid: Int): Boolean = pid > 0 && File("/proc/$pid").exists()

        /**
         * Why [pid] ended, from Android's record of the app's process exits. The record is written a moment after the
         * binder dies, so this waits briefly for it.
         */
        fun exitReason(context: Context, pid: Int): LayaExit {
            val am = context.getSystemService(ActivityManager::class.java)
            repeat(10) {
                val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, pid, 1).firstOrNull() }.getOrNull()
                if (info != null && (pid == 0 || info.pid == pid)) return describe(info)
                Thread.sleep(200)
            }
            return LayaExit(crashed = true, lowMemory = false, text = "it stopped without a reason recorded")
        }

        private fun describe(info: ApplicationExitInfo): LayaExit {
            val memory = info.rss.takeIf { it > 0 }?.let { " at ${it / 1024} MB" }.orEmpty()
            val detail = memory + info.description?.trim()?.takeIf { it.isNotEmpty() && it != "-" }?.take(120)?.let { " — $it" }.orEmpty()
            return when (info.reason) {
                ApplicationExitInfo.REASON_LOW_MEMORY -> LayaExit(false, true, "Android ended it to free memory")
                ApplicationExitInfo.REASON_CRASH_NATIVE -> LayaExit(true, false, "native crash in the accelerator's runtime$detail")
                ApplicationExitInfo.REASON_CRASH -> LayaExit(true, false, "crash$detail")
                ApplicationExitInfo.REASON_SIGNALED -> LayaExit(info.status != OsConstants.SIGKILL, info.status == OsConstants.SIGKILL,
                    if (info.status == OsConstants.SIGKILL) "Android ended it (signal 9)$detail" else "signal ${info.status}$detail")
                ApplicationExitInfo.REASON_ANR -> LayaExit(true, false, "it stopped responding")
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> LayaExit(false, true, "Android ended it for using too much$detail")
                // Some phones' own memory managers end a process without telling Android why.
                ApplicationExitInfo.REASON_UNKNOWN, ApplicationExitInfo.REASON_OTHER -> LayaExit(false, false, "the phone ended it without a reason$detail")
                else -> LayaExit(false, false, "exit reason ${info.reason}$detail")
            }
        }
    }
}
