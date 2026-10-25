package app.umbra.platform.server

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import app.umbra.platform.api.ShellEvent
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.privileged.Wire
import app.umbra.platform.api.BoundedLineReader
import app.umbra.platform.privileged.OwnedResources
import app.umbra.platform.privileged.jsonOf
import app.umbra.platform.shell.ProcessShell
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * The privileged helper (ADR 0004): a small server that runs with the shell user's rights (started
 * through Shizuku, `su 2000` or adb) and does what an app cannot: run commands as that user, create
 * virtual displays any app can be launched on, and photograph them. The app talks to it over a
 * loopback socket (see [Wire]); a connection must present the token it was started with.
 */
class PrivilegedServer(private val port: Int, private val token: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val shell = ProcessShell(ShellIdentity.SHELL, listOf("sh", "-c"), "helper_shell")
    private val displays = VirtualDisplays()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val connectionSlots = java.util.concurrent.Semaphore(8)
    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null

    /** The port the server listens on (differs from the requested one when that was 0). */
    val boundPort: Int get() = server?.localPort ?: -1

    fun start() {
        // Explicitly IPv4: on Android getLoopbackAddress() is ::1, which clients connecting to 127.0.0.1 never reach.
        val s = ServerSocket(port, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        server = s
        Thread({
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (_: IOException) {
                    break
                }
                val accepted = synchronized(clients) {
                    if (s.isClosed || server !== s || !connectionSlots.tryAcquire()) false else { clients += client; true }
                }
                if (!accepted) { client.close(); continue }
                Thread({
                    try { serve(client) } finally { clients.remove(client); connectionSlots.release() }
                }, "umbra-conn").apply { isDaemon = true }.start()
            }
        }, "umbra-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        synchronized(clients) {
            try { server?.close() } catch (_: IOException) {}
            server = null
            clients.forEach { try { it.close() } catch (_: IOException) {} }
        }
        main.post { displays.destroyAll() }
        scope.cancel()
    }

    // ---- connections ----

    private fun serve(socket: Socket) = socket.use {
        socket.soTimeout = 10_000
        val reader = BoundedLineReader(socket.getInputStream())
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val lock = Any()
        fun send(obj: JsonObject) = synchronized(lock) {
            try {
                writer.write(obj.toString()); writer.write("\n"); writer.flush()
            } catch (_: IOException) {
            }
        }

        // The first line must be a hello with the right token.
        val hello = try {
            Json.parseToJsonElement(reader.readLine(4096) ?: return).jsonObject
        } catch (_: Exception) {
            return
        }
        val given = (hello["token"] as? JsonPrimitive)?.content.orEmpty()
        if ((hello["op"] as? JsonPrimitive)?.content != Wire.HELLO || !MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
            send(jsonOf("id" to (hello["id"] as? JsonPrimitive)?.content, "ok" to false, "code" to "unauthorized", "error" to "bad token"))
            return
        }
        send(
            jsonOf(
                "id" to (hello["id"] as? JsonPrimitive)?.content, "ok" to true, "version" to Wire.VERSION, "uid" to Process.myUid(),
                "pid" to Process.myPid(), "sdk" to Build.VERSION.SDK_INT, "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: ""),
                "groups" to supplementaryGroups(), "virtualDisplays" to true,
            ),
        )

        socket.soTimeout = 5_000
        val mine = SupervisorJob(scope.coroutineContext[Job])
        val streams = OwnedResources<Job>(16) { it.cancel() }
        val procs = OwnedResources<java.lang.Process>(16, ::destroyProc)
        val requests = java.util.concurrent.Semaphore(16)
        var lastRead = System.currentTimeMillis()
        try {
            while (true) {
                val line = try { reader.readLine(MAX_FRAME_BYTES) } catch (_: SocketTimeoutException) {
                    if (procs.isEmpty() && streams.isEmpty() && mine.children.none { it.isActive } && System.currentTimeMillis() - lastRead > 5 * 60_000L) break
                    continue
                } ?: break
                lastRead = System.currentTimeMillis()
                val request = try {
                    Json.parseToJsonElement(line).jsonObject
                } catch (_: Exception) {
                    continue
                }
                if (!requests.tryAcquire()) {
                    send(jsonOf("id" to request.string("id"), "ok" to false, "code" to "busy", "error" to "too many requests"))
                    continue
                }
                scope.launch(mine) {
                    try {
                        val id = (request["id"] as? JsonPrimitive)?.content
                        val reply = try {
                            handle(request, ::send, procs, streams, mine)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: SecurityException) {
                            jsonOf("ok" to false, "code" to "permission_denied", "error" to (e.message ?: "permission denied"))
                        } catch (e: Throwable) {
                            jsonOf("ok" to false, "code" to "error", "error" to "${e::class.java.simpleName}: ${e.message}")
                        }
                        send(JsonObject(buildMap { id?.let { put("id", JsonPrimitive(it)) }; putAll(reply) }))
                    } finally { requests.release() }
                }
            }
        } catch (_: IOException) {
        } finally {
            mine.cancel()
            // Streams belong to the connection that started them.
            streams.close()
            // So do processes: one that lost its owner would run on with nobody to read it.
            procs.close()
        }
    }

    // ---- operations ----

    private suspend fun handle(request: JsonObject, push: (JsonObject) -> Unit, procs: OwnedResources<java.lang.Process>, streams: OwnedResources<Job>, connectionJob: Job): JsonObject {
        val op = (request["op"] as? JsonPrimitive)?.content ?: return jsonOf("ok" to false, "code" to "error", "error" to "missing op")
        return when (op) {
            Wire.HELLO -> jsonOf("ok" to true)
            Wire.EXEC -> exec(request)
            Wire.STREAM_START -> startStream(request, push, streams, connectionJob)
            Wire.STREAM_CANCEL -> {
                streams.cancel(request.string("stream"))
                jsonOf("ok" to true)
            }
            Wire.PROC_START -> startProc(request, push, procs)
            Wire.PROC_WRITE -> {
                val process = procs[request.string("proc")] ?: return jsonOf("ok" to false, "code" to "not_found", "error" to "no such process")
                val bytes = Wire.unb64(request.string("data").orEmpty())
                try {
                    kotlinx.coroutines.withContext(Dispatchers.IO) { process.outputStream.write(bytes); process.outputStream.flush() }
                    jsonOf("ok" to true)
                } catch (e: IOException) {
                    jsonOf("ok" to false, "code" to "error", "error" to "the process does not read its input: ${e.message}")
                }
            }
            Wire.PROC_CLOSE_INPUT -> {
                val process = procs[request.string("proc")] ?: return jsonOf("ok" to true)
                try { process.outputStream.close() } catch (_: IOException) {}
                jsonOf("ok" to true)
            }
            Wire.PROC_KILL -> {
                procs.cancel(request.string("proc"))
                jsonOf("ok" to true)
            }
            Wire.DISPLAY_CREATE -> {
                val name = request.string("name") ?: "umbra"
                val width = request.int("width") ?: 1080
                val height = request.int("height") ?: 2400
                val dpi = request.int("dpi") ?: 420
                val entry = onMain { displays.create(name, width, height, dpi) }
                jsonOf("ok" to true, "displayId" to entry.id, "name" to entry.name, "width" to entry.width, "height" to entry.height, "dpi" to entry.dpi)
            }
            Wire.DISPLAY_DESTROY -> {
                val id = request.int("displayId") ?: return jsonOf("ok" to false, "code" to "error", "error" to "displayId required")
                if (onMain { displays.destroy(id) }) jsonOf("ok" to true) else jsonOf("ok" to false, "code" to "not_found", "error" to "no virtual display $id")
            }
            Wire.DISPLAY_LIST -> jsonOf(
                "ok" to true,
                "displays" to JsonArray(
                    displays.list().map { jsonOf("displayId" to it.id, "name" to it.name, "width" to it.width, "height" to it.height, "dpi" to it.dpi) },
                ),
            )
            Wire.DISPLAY_SCREENSHOT -> {
                val id = request.int("displayId") ?: return jsonOf("ok" to false, "code" to "error", "error" to "displayId required")
                val jpeg = request.string("format") == "jpeg"
                // A display only produces a frame when something on it changes, so the first one may take a moment.
                var shot = onMain { displays.screenshot(id, jpeg) }
                var waited = 0L
                while (shot == null && onMain { displays.get(id) != null } && waited < FIRST_FRAME_WAIT_MS) {
                    kotlinx.coroutines.delay(100)
                    waited += 100
                    shot = onMain { displays.screenshot(id, jpeg) }
                }
                if (shot == null) {
                    return jsonOf("ok" to false, "code" to "not_found", "error" to "no picture of display $id (not a helper display, or nothing has been drawn on it yet)")
                }
                jsonOf("ok" to true, "data" to Wire.b64(shot.first), "width" to shot.second, "height" to shot.third, "mime" to if (jpeg) "image/jpeg" else "image/png")
            }
            Wire.SHUTDOWN -> {
                scope.launch { kotlinx.coroutines.delay(200); stop(); Runtime.getRuntime().halt(0) }
                jsonOf("ok" to true)
            }
            else -> jsonOf("ok" to false, "code" to "unsupported", "error" to "unknown operation $op")
        }
    }

    private fun shellRequest(r: JsonObject, streaming: Boolean = false): ShellRequest {
        val requestedTimeout = r.long("timeoutMs") ?: 30_000
        val timeout = if (streaming && requestedTimeout == Int.MAX_VALUE.toLong()) Long.MAX_VALUE else requestedTimeout
        val outputLimit = r.int("maxOutputBytes") ?: 64 * 1024
        require(timeout in 1..3_600_000L || (streaming && timeout == Long.MAX_VALUE)) { "timeoutMs is out of range" }
        val maxOutput = if (r.bool("binary") == true) 32 * 1024 * 1024 else 8 * 1024 * 1024
        require(outputLimit in 1..maxOutput) { "maxOutputBytes is out of range" }
        return ShellRequest(
            command = r.string("command") ?: throw IllegalArgumentException("command required"),
            cwd = r.string("cwd"),
            env = (r["env"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }.orEmpty(),
            stdin = r.string("stdin")?.let { String(Wire.unb64(it), Charsets.UTF_8) },
            timeoutMs = timeout,
            maxOutputBytes = outputLimit,
        )
    }

    private suspend fun exec(r: JsonObject): JsonObject {
        val request = shellRequest(r)
        val started = System.currentTimeMillis()
        return if (r.bool("binary") == true) {
            val out = shell.execBytes(request)
            jsonOf("ok" to true, "exit" to out.exitCode, "stdout" to Wire.b64(out.stdout), "stderr" to Wire.b64(out.stderr.toByteArray()), "timedOut" to out.timedOut, "durationMs" to (System.currentTimeMillis() - started))
        } else {
            val out = shell.exec(request)
            jsonOf(
                "ok" to true, "exit" to out.exitCode, "stdout" to Wire.b64(out.stdout.toByteArray()), "stderr" to Wire.b64(out.stderr.toByteArray()),
                "truncated" to out.truncated, "timedOut" to out.timedOut, "durationMs" to out.durationMs,
            )
        }
    }

    private fun startStream(r: JsonObject, push: (JsonObject) -> Unit, streams: OwnedResources<Job>, connectionJob: Job): JsonObject {
        val id = r.string("stream") ?: return jsonOf("ok" to false, "code" to "error", "error" to "stream id required")
        val request = shellRequest(r, streaming = true)
        val reservation = streams.reserve(id) ?: return jsonOf("ok" to false, "code" to "conflict", "error" to "stream id is in use or the resource limit was reached")
        val job = scope.launch(connectionJob, start = CoroutineStart.LAZY) {
            try {
                shell.stream(request).collect { event ->
                    when (event) {
                        is ShellEvent.Out -> push(jsonOf("stream" to id, "out" to event.line))
                        is ShellEvent.Err -> push(jsonOf("stream" to id, "err" to event.line))
                        is ShellEvent.Exit -> push(jsonOf("stream" to id, "exit" to event.code))
                    }
                }
            } finally {
                streams.release(reservation)
            }
        }
        if (!streams.attach(reservation, job)) return jsonOf("ok" to false, "code" to "disconnected", "error" to "connection closed")
        job.start()
        return jsonOf("ok" to true)
    }

    private fun startProc(r: JsonObject, push: (JsonObject) -> Unit, procs: OwnedResources<java.lang.Process>): JsonObject {
        val id = r.string("proc") ?: return jsonOf("ok" to false, "code" to "error", "error" to "proc id required")
        val command = r.string("command") ?: return jsonOf("ok" to false, "code" to "error", "error" to "command required")
        val builder = ProcessBuilder("sh", "-c", command)
        r.string("cwd")?.let { builder.directory(java.io.File(it)) }
        (r["env"] as? JsonObject)?.forEach { (k, v) -> builder.environment()[k] = (v as JsonPrimitive).content }
        val reservation = procs.reserve(id) ?: return jsonOf("ok" to false, "code" to "conflict", "error" to "process id is in use or the resource limit was reached")
        val process = try {
            builder.start()
        } catch (e: IOException) {
            procs.release(reservation)
            return jsonOf("ok" to false, "code" to "error", "error" to "cannot start: ${e.message}")
        }
        if (!procs.attach(reservation, process)) return jsonOf("ok" to false, "code" to "disconnected", "error" to "connection closed")
        // Output is forwarded as it comes, in chunks, as bytes: the caller decides what a line is.
        fun pump(input: java.io.InputStream, kind: String) = Thread({
            val buffer = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    push(jsonOf("proc" to id, kind to Wire.b64(buffer.copyOf(n))))
                }
            } catch (_: IOException) {
            }
        }, "umbra-proc-$kind").apply { isDaemon = true; start() }
        val readers = listOf(pump(process.inputStream, "out"), pump(process.errorStream, "err"))
        Thread({
            val code = try { process.waitFor() } catch (_: InterruptedException) { -1 }
            readers.forEach { it.join(3_000) }
            push(jsonOf("proc" to id, "exit" to code))
            procs.release(reservation)
        }, "umbra-proc-wait").apply { isDaemon = true; start() }
        return jsonOf("ok" to true, "pid" to (app.umbra.platform.shell.ProcessTree.pidOf(process) ?: -1))
    }

    private fun destroyProc(process: java.lang.Process) {
        val pid = app.umbra.platform.shell.ProcessTree.pidOf(process)
        if (pid != null) app.umbra.platform.shell.ProcessTree.killDescendantsOf(pid)
        process.destroyForcibly()
    }

    /** Runs [block] on the main looper thread, which framework display calls require. */
    private suspend fun <T> onMain(block: () -> T): T = suspendCancellableCoroutine { cont ->
        main.post {
            try {
                cont.resume(block())
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }
    }

    /** The supplementary groups this process has (`log`, `input`, ...), comma separated, as the kernel lists them. */
    private fun supplementaryGroups(): String = try {
        java.io.File("/proc/self/status").readLines().firstOrNull { it.startsWith("Groups:") }
            ?.removePrefix("Groups:")?.trim().orEmpty().replace(Regex("\\s+"), ",")
    } catch (_: IOException) {
        ""
    }

    private companion object {
        const val FIRST_FRAME_WAIT_MS = 4_000L
        const val MAX_FRAME_BYTES = 16 * 1024 * 1024
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
}
