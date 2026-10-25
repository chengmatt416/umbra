package app.umbra.platform.privileged

import app.umbra.platform.api.BoundedLineReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import app.umbra.platform.api.DispatchFence
import kotlinx.coroutines.CancellationException
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
    private val socketFactory: () -> Socket = { Socket() },
) : AutoCloseable {
    /** Where the helper listens and the secret it expects. */
    data class Endpoint(val port: Int, val token: String, val host: String = "127.0.0.1")

    constructor(port: Int, token: String, host: String = "127.0.0.1") : this({ Endpoint(port, token, host) })

    private class Connection(val socket: Socket, val writer: BufferedWriter, val endpoint: Endpoint) {
        val writeLock = Mutex()
        val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        val streams = ConcurrentHashMap<String, Stream>()
        var closed = false
    }

    private class Stream(val id: String, val owner: Connection, val process: Boolean) {
        val channel = Channel<JsonObject>(32)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectLock = Mutex()
    private val streams = ConcurrentHashMap<String, Stream>()
    private val ids = AtomicLong()
    private val connection = AtomicReference<Connection?>()
    @Volatile private var closed = false

    /** Details from the server's hello reply on the current connection. */
    @Volatile
    var serverInfo: JsonObject? = null
        private set

    private suspend fun ensureConnected(): Connection {
        checkOpen()
        val wanted = endpoint() ?: throw ServerException("unavailable", "no privileged helper is configured")
        connection.get()?.let { if (!it.socket.isClosed && it.endpoint == wanted) return it }
        return connectLock.withLock {
            checkOpen()
            connection.get()?.let {
                if (!it.socket.isClosed && it.endpoint == wanted) return@withLock it
                disconnect(it)
            }
            withContext(Dispatchers.IO) { open(wanted) }
        }
    }

    private fun checkOpen() { if (closed) throw ServerException("unavailable", "the privileged client is closed") }

    private fun open(ep: Endpoint): Connection {
        val socket = socketFactory()
        val host = ep.host
        val port = ep.port
        val token = ep.token
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.tcpNoDelay = true
            val reader = BoundedLineReader(socket.getInputStream())
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            writer.write(buildJsonObject { put("op", Wire.HELLO.asJson()); put("token", token.asJson()); put("id", "hello".asJson()) }.toString() + "\n")
            writer.flush()
            socket.soTimeout = connectTimeoutMs
            val reply = Json.parseToJsonElement(reader.readLine(4096) ?: throw IOException("the server closed the connection")).jsonObject
            if (reply.bool("ok") != true) throw ServerException(reply.str("code") ?: "unauthorized", reply.str("error") ?: "the server refused the connection")
            socket.soTimeout = 5_000
            serverInfo = reply
            val c = Connection(socket, writer, ep)
            checkOpen()
            connection.set(c)
            if (closed) { disconnect(c); checkOpen() }
            scope.launch { readLoop(c, reader) }
            return c
        } catch (e: Exception) {
            try { socket.close() } catch (_: IOException) {}
            throw if (e is ServerException) e else ServerException("unavailable", "cannot reach the privileged helper on $host:$port: ${e.message}")
        }
    }

    private fun readLoop(c: Connection, reader: BoundedLineReader) {
        try {
            while (true) {
                val line = try { reader.readLine(16 * 1024 * 1024) } catch (_: SocketTimeoutException) { continue } ?: break
                val obj = Json.parseToJsonElement(line).jsonObject
                val streamId = obj.str("stream") ?: obj.str("proc")
                if (streamId != null) {
                    val stream = c.streams[streamId] ?: continue
                    val size = line.toByteArray(Charsets.UTF_8).size
                    if (size > 128 * 1024 || stream.channel.trySend(obj).isFailure) {
                        detach(stream)
                        stream.channel.close(ServerException("overflow", "The helper produced output faster than it could be consumed"))
                        scope.launch { cancelRemote(stream) }
                    } else if (obj.int("exit") != null) {
                        detach(stream)
                        stream.channel.close()
                    }
                } else {
                    obj.str("id")?.let { c.pending[it]?.complete(obj) }
                }
            }
        } catch (_: Exception) {
            // The connection is gone. Only work registered against this connection is affected.
        } finally { disconnect(c) }
    }

    private fun disconnect(c: Connection) {
        val owned = synchronized(c) {
            if (c.closed) return
            c.closed = true
            val pending = c.pending.values.toList()
            c.pending.clear()
            val active = c.streams.values.toList()
            c.streams.clear()
            pending to active
        }
        connection.compareAndSet(c, null)
        try { c.socket.close() } catch (_: IOException) {}
        val gone = ServerException("disconnected", "the privileged helper closed the connection")
        owned.first.forEach { it.completeExceptionally(gone) }
        owned.second.forEach { stream ->
            streams.remove(stream.id, stream)
            stream.channel.trySend(jsonOf("exit" to -1, "disconnected" to true))
            stream.channel.close()
        }
    }

    private suspend fun send(c: Connection, obj: JsonObject) = c.writeLock.withLock {
        withContext(Dispatchers.IO) {
            DispatchFence.checkCurrent()
            try {
                c.writer.write(obj.toString())
                c.writer.write("\n")
                c.writer.flush()
            } catch (e: IOException) {
                disconnect(c)
                throw ServerException("disconnected", "lost the privileged helper: ${e.message}")
            }
        }
    }

    /** Sends one request and waits for its reply. Throws [ServerException] for refusals and lost connections. */
    suspend fun call(op: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 30_000): JsonObject =
        callOn(ensureConnected(), op, params, timeoutMs)

    private suspend fun callOn(c: Connection, op: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 30_000): JsonObject {
        val id = "r${ids.incrementAndGet()}"
        val reply = CompletableDeferred<JsonObject>()
        synchronized(c) {
            if (c.closed) throw ServerException("disconnected", "the privileged helper closed the connection")
            if (c.pending.size >= 16) throw ServerException("busy", "too many helper requests")
            c.pending[id] = reply
        }
        try {
            send(c, buildJsonObject { params.forEach { (k, v) -> put(k, v) }; put("id", id.asJson()); put("op", op.asJson()) })
            val answer = try { withTimeout(timeoutMs) { reply.await() } } catch (e: TimeoutCancellationException) {
                throw ServerException("timeout", "the privileged helper did not answer $op within ${timeoutMs}ms")
            }
            if (answer.bool("ok") != true) throw ServerException(answer.str("code") ?: "error", answer.str("error") ?: "$op failed")
            return answer
        } finally { c.pending.remove(id, reply) }
    }

    private fun register(c: Connection, process: Boolean): Stream = synchronized(c) {
        if (c.closed) throw ServerException("disconnected", "the privileged helper closed the connection")
        if (c.streams.size >= 16) throw ServerException("busy", "too many helper streams")
        val id = (if (process) "p" else "s") + ids.incrementAndGet()
        Stream(id, c, process).also { c.streams[id] = it; streams[id] = it }
    }

    private fun detach(stream: Stream) {
        stream.owner.streams.remove(stream.id, stream)
        streams.remove(stream.id, stream)
    }

    /** The channel is registered with its connection before the start request is sent. */
    suspend fun openStream(op: String, params: JsonObject): Pair<String, Channel<JsonObject>> {
        val stream = register(ensureConnected(), process = false)
        try { callOn(stream.owner, op, JsonObject(params + ("stream" to stream.id.asJson()))) }
        catch (e: Exception) { detach(stream); stream.channel.close(); throw e }
        return stream.id to stream.channel
    }

    /** Starts a process with live stdin. Bounded output belongs to this connection until closed. */
    suspend fun openProc(command: String, cwd: String? = null, env: Map<String, String> = emptyMap()): Pair<String, Channel<JsonObject>> {
        val stream = register(ensureConnected(), process = true)
        try { callOn(stream.owner, Wire.PROC_START, jsonOf("proc" to stream.id, "command" to command, "cwd" to cwd, "env" to env.takeIf { it.isNotEmpty() })) }
        catch (e: Exception) { detach(stream); stream.channel.close(); throw e }
        return stream.id to stream.channel
    }

    private fun process(id: String): Stream = streams[id]?.takeIf { it.process }
        ?: throw ServerException("not_found", "no such process on this connection")

    suspend fun writeProc(procId: String, bytes: ByteArray) {
        val stream = process(procId)
        callOn(stream.owner, Wire.PROC_WRITE, jsonOf("proc" to procId, "data" to Wire.b64(bytes)))
    }

    suspend fun closeProcInput(procId: String) {
        val stream = process(procId)
        callOn(stream.owner, Wire.PROC_CLOSE_INPUT, jsonOf("proc" to procId), timeoutMs = 10_000)
    }

    private suspend fun cancelRemote(stream: Stream) {
        try {
            callOn(stream.owner, if (stream.process) Wire.PROC_KILL else Wire.STREAM_CANCEL,
                jsonOf((if (stream.process) "proc" else "stream") to stream.id), timeoutMs = 5_000)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { disconnect(stream.owner) }
    }

    private suspend fun end(id: String) {
        val stream = streams[id] ?: return
        detach(stream)
        stream.channel.close()
        cancelRemote(stream)
    }

    suspend fun killProc(procId: String) = end(procId)
    suspend fun endStream(streamId: String) = end(streamId)

    /** True if the helper answers a hello right now. */
    suspend fun isUp(): Boolean = try {
        ensureConnected()
        true
    } catch (_: ServerException) {
        false
    }

    /** Asks the helper to exit and drops the connection; the client stays usable for a helper started later. */
    suspend fun stopServer() {
        val c = try { ensureConnected() } catch (_: ServerException) { return }
        try { callOn(c, Wire.SHUTDOWN, timeoutMs = 3_000) } catch (_: ServerException) {}
        disconnect(c)
    }

    suspend fun shutdown() { stopServer(); close() }

    override fun close() {
        closed = true
        connection.get()?.let(::disconnect)
        scope.cancel()
    }

}

internal fun String.asJson() = kotlinx.serialization.json.JsonPrimitive(this)

internal fun Int.asJson() = kotlinx.serialization.json.JsonPrimitive(this)

internal fun Boolean.asJson() = kotlinx.serialization.json.JsonPrimitive(this)
