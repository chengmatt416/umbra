package app.umbra.tools

import app.umbra.runtime.omp.ContentBlock
import app.umbra.runtime.omp.HostToolCall
import app.umbra.runtime.omp.HostToolPayload
import app.umbra.runtime.omp.HostToolProgress
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Extra, non-tool commands a bridge can serve (session control for development tooling). */
fun interface ControlHandler {
    suspend fun handle(command: String, args: JsonObject): JsonElement
}

/**
 * Serves the host tools over a loopback TCP socket, one JSON object per line. It lets processes that
 * are not omp call the same tools with the same safety checks: scripts running in the Linux guest,
 * and development tooling reaching a phone through `adb forward`. Requests carry a secret token, the
 * socket only listens on loopback, and every call runs through the session's [UmbraToolHost], so
 * approvals and risk classification apply exactly as for the agent.
 *
 * Request:  `{"id":"1","auth":"<token>","session":"s1","tool":"android_observe","args":{...}}`
 * List:     `{"id":"2","auth":"<token>","list":true,"session":"s1"}`
 * Response: `{"id":"1","isError":false,"content":[{"type":"text","text":"..."},{"type":"image","mimeType":"image/jpeg","data":"<base64>"}],"details":{...}}`
 * Control:  `{"id":"3","auth":"<token>","control":"session.list","args":{}}` answers `{"id":"3","ok":true,"result":...}` when a [ControlHandler] is installed.
 */
class ToolBridgeServer(
    private val token: String,
    private val hostFor: (sessionId: String) -> UmbraToolHost,
    private val port: Int = 0,
    private val maxFrameBytes: Int = 16 * 1024 * 1024,
    private val control: ControlHandler? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var acceptor: Job? = null
    private val calls = AtomicInteger()

    /** The port actually bound (useful when [port] is 0). */
    val boundPort: Int get() = server?.localPort ?: -1

    fun start(): Int {
        check(server == null) { "already started" }
        // Explicitly IPv4: on Android getLoopbackAddress() is ::1, which clients connecting to 127.0.0.1 never reach.
        val s = ServerSocket(port, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        server = s
        acceptor = scope.launch {
            while (isActive) {
                val client = try {
                    s.accept()
                } catch (_: java.io.IOException) {
                    break
                }
                launch { serve(client) }
            }
        }
        return s.localPort
    }

    override fun close() {
        try {
            server?.close()
        } catch (_: java.io.IOException) {
        }
        server = null
        scope.cancel()
    }

    private suspend fun serve(socket: Socket) = socket.use {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val writeLock = Any()
        fun send(obj: JsonObject) = synchronized(writeLock) {
            writer.write(obj.toString())
            writer.write("\n")
            writer.flush()
        }
        val inFlight = Job()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.length > maxFrameBytes) {
                    send(error(null, "request too large"))
                    break
                }
                val request = try {
                    Json.parseToJsonElement(line).jsonObject
                } catch (e: Exception) {
                    send(error(null, "invalid JSON: ${e.message}"))
                    continue
                }
                val id = (request["id"] as? JsonPrimitive)?.content
                if (!tokenMatches((request["auth"] as? JsonPrimitive)?.content)) {
                    send(error(id, "unauthorized"))
                    continue
                }
                // Each call runs on its own coroutine so a slow tool (or an unanswered approval) does not block the connection.
                scope.launch(inFlight) {
                    try {
                        send(handle(id, request))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        send(error(id, "internal error: ${e.message}"))
                    }
                }
            }
        } catch (_: java.io.IOException) {
            // The client went away.
        } finally {
            inFlight.cancel()
        }
    }

    private fun tokenMatches(given: String?): Boolean =
        given != null && MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))

    private suspend fun handle(id: String?, request: JsonObject): JsonObject {
        (request["control"] as? JsonPrimitive)?.content?.let { command ->
            val handler = control ?: return error(id, "this bridge does not serve control commands")
            return try {
                buildJsonObject { id?.let { put("id", it) }; put("ok", true); put("result", handler.handle(command, request["args"] as? JsonObject ?: JsonObject(emptyMap()))) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                buildJsonObject { id?.let { put("id", it) }; put("ok", false); put("error", e.message ?: e::class.simpleName.orEmpty()) }
            }
        }
        val session = (request["session"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: "bridge"
        val host = hostFor(session)
        if ((request["list"] as? JsonPrimitive)?.content == "true") {
            return buildJsonObject {
                id?.let { put("id", it) }
                put("tools", buildJsonArray {
                    host.definitions().forEach { d -> add(buildJsonObject { put("name", d.name); put("description", d.description); put("parameters", d.parameters) }) }
                })
            }
        }
        val tool = (request["tool"] as? JsonPrimitive)?.content ?: return error(id, "missing 'tool'")
        val args = request["args"] as? JsonObject ?: JsonObject(emptyMap())
        val payload = host.handle(HostToolCall("bridge-${calls.incrementAndGet()}", "bridge-call-${calls.get()}", tool, args), HostToolProgress { })
        return render(id, payload)
    }

    private fun render(id: String?, p: HostToolPayload): JsonObject = buildJsonObject {
        id?.let { put("id", it) }
        put("isError", p.isError)
        put("content", JsonArray(p.content.map { b ->
            when (b) {
                is ContentBlock.Text -> buildJsonObject { put("type", "text"); put("text", b.text) }
                is ContentBlock.Image -> buildJsonObject { put("type", "image"); put("mimeType", b.mimeType); put("data", b.base64) }
            }
        }))
        put("details", p.details ?: JsonNull)
    }

    private fun error(id: String?, message: String): JsonObject = buildJsonObject {
        id?.let { put("id", it) }
        put("isError", true)
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", message) }) })
        put("details", JsonNull)
    }
}

/** What a bridge response looks like to a client. */
class BridgeResponse(val isError: Boolean, val text: String, val images: List<ByteArray>, val raw: JsonObject)

/** Blocking line-based client for [ToolBridgeServer]; used by tooling that reaches a device through `adb forward`. */
class BridgeClient(private val host: String, private val port: Int, private val token: String) : AutoCloseable {
    private val socket = Socket(host, port)
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    private val ids = AtomicInteger()

    @Synchronized
    fun call(tool: String, args: JsonObject = JsonObject(emptyMap()), session: String = "bridge", timeoutMs: Int = 10 * 60_000): BridgeResponse {
        val id = ids.incrementAndGet().toString()
        val request = buildJsonObject { put("id", id); put("auth", token); put("session", session); put("tool", tool); put("args", args) }
        writer.write(request.toString()); writer.write("\n"); writer.flush()
        socket.soTimeout = timeoutMs
        while (true) {
            val line = reader.readLine() ?: error("bridge closed the connection")
            val obj = Json.parseToJsonElement(line).jsonObject
            if ((obj["id"] as? JsonPrimitive)?.content != id) continue
            val content = obj["content"] as? JsonArray ?: JsonArray(emptyList())
            val text = content.mapNotNull { it.jsonObject.takeIf { b -> (b["type"] as? JsonPrimitive)?.content == "text" }?.get("text") }.joinToString("\n") { (it as JsonPrimitive).content }
            val images = content.mapNotNull { it.jsonObject.takeIf { b -> (b["type"] as? JsonPrimitive)?.content == "image" }?.get("data") }.map { Base64.getDecoder().decode((it as JsonPrimitive).content) }
            return BridgeResponse((obj["isError"] as? JsonPrimitive)?.content == "true", text, images, obj)
        }
    }

    /** Sends a control command; throws if the device reports an error. */
    @Synchronized
    fun control(command: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Int = 10 * 60_000): JsonElement {
        val id = ids.incrementAndGet().toString()
        val request = buildJsonObject { put("id", id); put("auth", token); put("control", command); put("args", args) }
        writer.write(request.toString()); writer.write("\n"); writer.flush()
        socket.soTimeout = timeoutMs
        while (true) {
            val obj = Json.parseToJsonElement(reader.readLine() ?: error("bridge closed the connection")).jsonObject
            if ((obj["id"] as? JsonPrimitive)?.content != id) continue
            if ((obj["ok"] as? JsonPrimitive)?.content == "true") return obj["result"] ?: JsonNull
            error("control '$command' failed: " + ((obj["error"] as? JsonPrimitive)?.content ?: ((obj["content"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("text") as? JsonPrimitive)?.content ?: "unknown"))
        }
    }

    @Synchronized
    fun tools(session: String = "bridge"): List<String> {
        val request = buildJsonObject { put("id", "list"); put("auth", token); put("session", session); put("list", true) }
        writer.write(request.toString()); writer.write("\n"); writer.flush()
        val obj = Json.parseToJsonElement(reader.readLine() ?: error("bridge closed the connection")).jsonObject
        return (obj["tools"] as? JsonArray).orEmpty().map { ((it as JsonObject)["name"] as JsonPrimitive).content }
    }

    override fun close() = socket.close()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
}
