package app.umbra.platform.privileged

import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The protocol between the app and the privileged helper: one JSON object per line over a loopback
 * socket. A connection starts with `{"op":"hello","token":"..."}`; everything else is rejected until
 * the token matches. Requests carry an `id`; the reply carries the same `id`, `ok`, and either the
 * result fields or `error`. Streams (long-running commands) additionally push
 * `{"stream":"<id>","out"|"err":"line"}` and a final `{"stream":"<id>","exit":N}`.
 * Processes (`proc.*`) are streams that also accept input: their `out`/`err` pushes carry base64 chunks
 * (`{"proc":"<id>","out":"<base64>"}`) and end with `{"proc":"<id>","exit":N}`.
 * Binary payloads are base64 strings.
 */
object Wire {
    const val VERSION = 1

    // operations
    const val HELLO = "hello"
    const val EXEC = "exec"
    const val STREAM_START = "stream.start"
    const val STREAM_CANCEL = "stream.cancel"

    /** A long-running process with a live standard input (the agent's own process in the Linux guest). */
    const val PROC_START = "proc.start"
    const val PROC_WRITE = "proc.write"
    const val PROC_CLOSE_INPUT = "proc.close_input"
    const val PROC_KILL = "proc.kill"
    const val DISPLAY_CREATE = "display.create"
    const val DISPLAY_DESTROY = "display.destroy"
    const val DISPLAY_LIST = "display.list"
    const val DISPLAY_SCREENSHOT = "display.screenshot"
    const val SHUTDOWN = "shutdown"

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
}

class ServerException(val code: String, message: String) : Exception(message)

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.arr(name: String): JsonArray? = this[name] as? JsonArray

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) {
        when (v) {
            null -> Unit
            is JsonElement -> put(k, v)
            is String -> put(k, JsonPrimitive(v))
            is Boolean -> put(k, JsonPrimitive(v))
            is Int -> put(k, JsonPrimitive(v))
            is Long -> put(k, JsonPrimitive(v))
            is Map<*, *> -> put(k, buildJsonObject { v.forEach { (mk, mv) -> put(mk.toString(), JsonPrimitive(mv.toString())) } })
            else -> error("unsupported value for $k: ${v::class}")
        }
    }
}
