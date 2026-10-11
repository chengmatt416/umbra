package app.umbra.core.nodes

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** The Runner Service Protocol between the phone and a node: one JSON object per line, over mutual TLS. */
object Rsp {
    const val VERSION = 1
    const val DEFAULT_PORT = 47100
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
    fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
    fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"
}

/** What a node says about itself at hello: what the placement preflight decides with. */
@Serializable
data class NodeInfo(
    val nodeId: String,
    val name: String,
    val os: String,
    val arch: String,
    val cpus: Int,
    val memoryMb: Long,
    /** Tools found on the node's PATH, with their versions. */
    val tools: Map<String, String>,
    /** Changes whenever the runner restarts (start time and host boot): a lease from another epoch is re-validated. */
    val epoch: String,
    val clockMs: Long,
    val workspace: String,
    val maxManifestBytes: Long,
    val protocol: Int = Rsp.VERSION,
)

@Serializable
enum class JobState {
    RUNNING, EXITED, LEASE_EXPIRED, CANCELLED, FAILED_TO_START,

    /** The runner restarted while it ran: its process is no longer watched (offline is not dead, and not known). */
    LOST,
}

/** A job as the node keeps it, reported at rejoin so the phone can reconcile what happened while it was away. */
@Serializable
data class JobStatus(
    val job: String,
    val state: JobState,
    val exit: Int? = null,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val leaseUntilMs: Long,
    val epoch: String,
    val outputTail: String = "",
    val note: String? = null,
)

class NodeException(message: String) : Exception(message)

/**
 * The phone's end of one connection to a node: requests answered by id, job events as a flow. [peerFingerprint] is
 * the node certificate this connection was made with.
 */
class NodeChannel private constructor(private val socket: SSLSocket, scope: CoroutineScope) : AutoCloseable {
    private val out: OutputStream = socket.outputStream
    private val writeLock = Mutex()
    private val ids = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val eventFlow = MutableSharedFlow<JsonObject>(extraBufferCapacity = 1024)
    private val readerScope = CoroutineScope(scope.coroutineContext + SupervisorJob() + Dispatchers.IO)
    private val reader: Job

    val events: SharedFlow<JsonObject> = eventFlow
    val peerFingerprint: String = (socket.session.peerCertificates.first() as X509Certificate).fingerprint()
    val localFingerprint: String? = (socket.session.localCertificates?.firstOrNull() as? X509Certificate)?.fingerprint()

    @Volatile
    var closed = false
        private set

    init {
        reader = readerScope.launch {
            try {
                val lines = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                while (true) {
                    val line = lines.readLine() ?: break
                    val frame = runCatching { Rsp.json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    val re = (frame["re"] as? JsonPrimitive)?.content?.toLongOrNull()
                    if (re != null) pending.remove(re)?.complete(frame) else eventFlow.emit(frame)
                }
            } catch (_: Exception) {
            } finally {
                closed = true
                pending.values.forEach { it.completeExceptionally(NodeException("the connection to the node closed")) }
                pending.clear()
            }
        }
    }

    /** Sends [op] with [params] and waits for its answer; a refusal (`ok: false`) is thrown as [NodeException]. */
    suspend fun request(op: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 30_000): JsonObject {
        if (closed) throw NodeException("the connection to the node is closed")
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        val frame = JsonObject(params + mapOf("id" to JsonPrimitive(id), "op" to JsonPrimitive(op)))
        try {
            writeLock.withLock { withContext(Dispatchers.IO) { out.write((frame.toString() + "\n").toByteArray()); out.flush() } }
        } catch (e: java.io.IOException) {
            pending.remove(id)
            throw NodeException("the connection to the node closed: ${e.message}")
        }
        val reply = try { withTimeout(timeoutMs) { answer.await() } } finally { pending.remove(id) }
        if ((reply["ok"] as? JsonPrimitive)?.content != "true") throw NodeException((reply["error"] as? JsonPrimitive)?.content ?: "the node refused $op")
        return reply
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        readerScope.cancel()
    }

    companion object {
        /** Connects and completes the TLS handshake (whose trust decides whether this node is the paired one). */
        suspend fun connect(host: String, port: Int, context: SSLContext, scope: CoroutineScope, timeoutMs: Int = 8_000): NodeChannel = withContext(Dispatchers.IO) {
            val s = context.socketFactory.createSocket() as SSLSocket
            try {
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = 0
                s.enabledProtocols = arrayOf("TLSv1.3")
                s.startHandshake()
            } catch (e: Exception) {
                runCatching { s.close() }
                throw NodeException("could not reach $host:$port securely: ${e.message}")
            }
            NodeChannel(s, scope)
        }

        fun params(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)
    }
}
