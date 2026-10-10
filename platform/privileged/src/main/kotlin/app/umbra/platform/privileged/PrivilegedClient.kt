package app.umbra.platform.privileged

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * One authenticated connection to the privileged helper, opened on demand and reopened after a drop.
 * Requests are matched to replies by id, so any number can be in flight; streams push their events to
 * a channel registered before the request is sent, so none can be missed.
 */
class PrivilegedClient(
    private val endpoint: () -> Endpoint?,
    private val connectTimeoutMs: Int = 3_000,
) : AutoCloseable {
    /** Where the helper listens and the secret it expects. */
    data class Endpoint(val port: Int, val token: String, val host: String = "127.0.0.1")

    constructor(port: Int, token: String, host: String = "127.0.0.1") : this({ Endpoint(port, token, host) })

    private class Connection(val socket: Socket, val writer: BufferedWriter, val endpoint: Endpoint)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectLock = Mutex()
    private val writeLock = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val streams = ConcurrentHashMap<String, Channel<JsonObject>>()
    private val ids = AtomicLong()

    @Volatile
    private var connection: Connection? = null

    /** Details from the server's hello reply on the current connection. */
    @Volatile
    var serverInfo: JsonObject? = null
        private set

    private suspend fun ensureConnected(): Connection {
        val wanted = endpoint() ?: throw ServerException("unavailable", "no privileged helper is configured")
        connection?.let { if (!it.socket.isClosed && it.endpoint == wanted) return it }
        return connectLock.withLock {
            connection?.let {
                if (!it.socket.isClosed && it.endpoint == wanted) return@withLock it
                try { it.socket.close() } catch (_: IOException) {}
            }
            withContext(Dispatchers.IO) { open(wanted) }
        }
    }

    private fun open(ep: Endpoint): Connection {
        val socket = Socket()
        val host = ep.host
        val port = ep.port
        val token = ep.token
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            writer.write(buildJsonObject { put("op", Wire.HELLO.asJson()); put("token", token.asJson()); put("id", "hello".asJson()) }.toString() + "\n")
            writer.flush()
            socket.soTimeout = connectTimeoutMs
            val reply = Json.parseToJsonElement(reader.readLine() ?: throw IOException("the server closed the connection")).jsonObject
            if (reply.bool("ok") != true) throw ServerException(reply.str("code") ?: "unauthorized", reply.str("error") ?: "the server refused the connection")
            socket.soTimeout = 0
            serverInfo = reply
            val c = Connection(socket, writer, ep)
            connection = c
            scope.launch { readLoop(c, reader) }
            return c
        } catch (e: Exception) {
            try { socket.close() } catch (_: IOException) {}
            throw if (e is ServerException) e else ServerException("unavailable", "cannot reach the privileged helper on $host:$port: ${e.message}")
        }
    }

    private fun readLoop(c: Connection, reader: BufferedReader) {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                val obj = Json.parseToJsonElement(line).jsonObject
                val stream = obj.str("stream") ?: obj.str("proc")
                if (stream != null) {
                    streams[stream]?.trySend(obj)
                } else {
                    obj.str("id")?.let { pending[it]?.complete(obj) }
                }
            }
        } catch (_: Exception) {
            // fall through: the connection is gone
        } finally {
            try { c.socket.close() } catch (_: IOException) {}
            if (connection === c) connection = null
            val gone = ServerException("disconnected", "the privileged helper closed the connection")
            pending.values.forEach { it.completeExceptionally(gone) }
            // Streams end with a synthetic exit so collectors do not wait forever.
            streams.values.forEach { it.trySend(buildJsonObject { put("exit", (-1).asJson()); put("disconnected", true.asJson()) }) }
        }
    }

    private suspend fun send(c: Connection, obj: JsonObject) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                c.writer.write(obj.toString())
                c.writer.write("\n")
                c.writer.flush()
            } catch (e: IOException) {
                try { c.socket.close() } catch (_: IOException) {}
                throw ServerException("disconnected", "lost the privileged helper: ${e.message}")
            }
        }
    }

    /** Sends one request and waits for its reply. Throws [ServerException] for refusals and lost connections. */
    suspend fun call(op: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 30_000): JsonObject {
        val c = ensureConnected()
        val id = "r${ids.incrementAndGet()}"
        val reply = CompletableDeferred<JsonObject>()
        pending[id] = reply
        try {
            send(c, buildJsonObject { put("id", id.asJson()); put("op", op.asJson()); params.forEach { (k, v) -> put(k, v) } })
            val answer = try {
                withTimeout(timeoutMs) { reply.await() }
            } catch (e: TimeoutCancellationException) {
                throw ServerException("timeout", "the privileged helper did not answer $op within ${timeoutMs}ms")
            }
            if (answer.bool("ok") != true) throw ServerException(answer.str("code") ?: "error", answer.str("error") ?: "$op failed")
            return answer
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Starts a streaming request. The returned channel receives the server's pushed events (`out`,
     * `err`, `exit`); close it with [endStream]. The channel is registered before the request goes out.
     */
    suspend fun openStream(op: String, params: JsonObject): Pair<String, Channel<JsonObject>> {
        val streamId = "s${ids.incrementAndGet()}"
        val channel = Channel<JsonObject>(Channel.UNLIMITED)
        streams[streamId] = channel
        try {
            call(op, JsonObject(params + mapOf("stream" to streamId.asJson())))
        } catch (e: Exception) {
            streams.remove(streamId)
            channel.close()
            throw e
        }
        return streamId to channel
    }

    /**
     * Starts a process in the helper with a live standard input. Its output arrives on the returned
     * channel as `{"proc":id,"out"|"err":"<base64>"}` objects and a final `{"exit":N}`; feed it with
     * [writeProc] and end its input with [closeProcInput]. Stop it with [killProc].
     */
    suspend fun openProc(command: String, cwd: String? = null, env: Map<String, String> = emptyMap()): Pair<String, Channel<JsonObject>> {
        val procId = "p${ids.incrementAndGet()}"
        val channel = Channel<JsonObject>(Channel.UNLIMITED)
        streams[procId] = channel
        try {
            call(Wire.PROC_START, jsonOf("proc" to procId, "command" to command, "cwd" to cwd, "env" to env.takeIf { it.isNotEmpty() }))
        } catch (e: Exception) {
            streams.remove(procId)
            channel.close()
            throw e
        }
        return procId to channel
    }

    suspend fun writeProc(procId: String, bytes: ByteArray) {
        call(Wire.PROC_WRITE, jsonOf("proc" to procId, "data" to Wire.b64(bytes)), timeoutMs = 30_000)
    }

    suspend fun closeProcInput(procId: String) {
        call(Wire.PROC_CLOSE_INPUT, jsonOf("proc" to procId), timeoutMs = 10_000)
    }

    /** Kills the process and everything it started, and forgets it. */
    suspend fun killProc(procId: String) {
        streams.remove(procId)?.close()
        try {
            call(Wire.PROC_KILL, jsonOf("proc" to procId), timeoutMs = 10_000)
        } catch (_: Exception) {
            // Already gone.
        }
    }

    suspend fun endStream(streamId: String) {
        streams.remove(streamId)?.close()
        try {
            call(Wire.STREAM_CANCEL, jsonOf("stream" to streamId), timeoutMs = 5_000)
        } catch (_: Exception) {
            // The stream is already gone on the server.
        }
    }

    /** True if the helper answers a hello right now. */
    suspend fun isUp(): Boolean = try {
        ensureConnected()
        true
    } catch (_: ServerException) {
        false
    }

    /** Asks the helper to exit and drops the connection; the client stays usable for a helper started later. */
    suspend fun stopServer() {
        try { call(Wire.SHUTDOWN, timeoutMs = 3_000) } catch (_: ServerException) {}
        connection?.let { try { it.socket.close() } catch (_: IOException) {} }
        connection = null
    }

    /** Asks the helper to exit and closes this client for good. (A helper keeps running otherwise, so workspaces survive the app.) */
    suspend fun shutdown() {
        stopServer()
        close()
    }

    override fun close() {
        connection?.let { try { it.socket.close() } catch (_: IOException) {} }
        connection = null
        scope.cancel()
    }
}

internal fun String.asJson() = kotlinx.serialization.json.JsonPrimitive(this)

internal fun Int.asJson() = kotlinx.serialization.json.JsonPrimitive(this)

internal fun Boolean.asJson() = kotlinx.serialization.json.JsonPrimitive(this)
