package app.umbra.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** The call's arguments were unusable; the message is written for the model that made the call. */
class ArgumentException(message: String) : Exception(message)

/**
 * Reads the arguments of a tool call leniently: models pass numbers as strings, booleans as
 * "true", and sometimes wrap a single value in an array. Anything that cannot be understood is an
 * [ArgumentException] with a message that says what was expected.
 */
class ToolArgs(val json: JsonObject) {
    private fun raw(name: String): JsonElement? = json[name]?.takeIf { it !is JsonNull }

    fun has(name: String) = raw(name) != null

    fun str(name: String): String? = when (val v = raw(name)) {
        null -> null
        is JsonPrimitive -> v.content
        is JsonArray -> v.singleOrNull()?.let { (it as? JsonPrimitive)?.content }
        else -> throw ArgumentException("'$name' must be a string")
    }

    fun requireStr(name: String): String = str(name)?.takeIf { it.isNotEmpty() } ?: throw ArgumentException("'$name' is required")

    fun long(name: String): Long? = when (val v = raw(name)) {
        null -> null
        is JsonPrimitive -> v.longOrNull ?: v.doubleOrNull?.toLong() ?: v.content.trim().toLongOrNull() ?: v.content.trim().toDoubleOrNull()?.toLong()
            ?: throw ArgumentException("'$name' must be a number, got \"${v.content}\"")
        else -> throw ArgumentException("'$name' must be a number")
    }

    fun int(name: String): Int? = long(name)?.let {
        if (it > Int.MAX_VALUE || it < Int.MIN_VALUE) throw ArgumentException("'$name' is out of range") else it.toInt()
    }

    fun float(name: String): Float? = when (val v = raw(name)) {
        null -> null
        is JsonPrimitive -> v.doubleOrNull?.toFloat() ?: v.content.trim().toFloatOrNull() ?: throw ArgumentException("'$name' must be a number, got \"${v.content}\"")
        else -> throw ArgumentException("'$name' must be a number")
    }

    fun bool(name: String): Boolean? = when (val v = raw(name)) {
        null -> null
        is JsonPrimitive -> v.booleanOrNull ?: when (v.content.trim().lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> throw ArgumentException("'$name' must be true or false, got \"${v.content}\"")
        }
        else -> throw ArgumentException("'$name' must be true or false")
    }

    fun obj(name: String): ToolArgs? = when (val v = raw(name)) {
        null -> null
        is JsonObject -> ToolArgs(v)
        else -> throw ArgumentException("'$name' must be an object")
    }

    fun strList(name: String): List<String> = when (val v = raw(name)) {
        null -> emptyList()
        is JsonArray -> v.map { (it as? JsonPrimitive)?.content ?: throw ArgumentException("'$name' must be a list of strings") }
        is JsonPrimitive -> v.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        else -> throw ArgumentException("'$name' must be a list of strings")
    }

    fun strMap(name: String): Map<String, String> = when (val v = raw(name)) {
        null -> emptyMap()
        is JsonObject -> v.mapValues { (k, e) -> (e as? JsonPrimitive)?.content ?: throw ArgumentException("'$name.$k' must be a string") }
        else -> throw ArgumentException("'$name' must be an object of strings")
    }

    fun elements(name: String): List<JsonElement> = (raw(name) as? JsonArray)?.toList().orEmpty()

    /** A string restricted to [allowed] (compared case-insensitively, `-` and space accepted for `_`). */
    fun choice(name: String, allowed: Collection<String>, default: String? = null): String {
        val v = str(name)?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_') ?: default ?: throw ArgumentException("'$name' is required (one of: ${allowed.joinToString(", ")})")
        if (v !in allowed) throw ArgumentException("'$name' must be one of: ${allowed.joinToString(", ")} (got \"$v\")")
        return v
    }

    /** Fails on keys the tool does not define, so a misspelled argument is reported instead of silently ignored. */
    fun rejectUnknown(allowed: Set<String>) {
        val unknown = json.keys - allowed
        if (unknown.isNotEmpty()) {
            throw ArgumentException("Unknown argument${if (unknown.size > 1) "s" else ""} ${unknown.joinToString(", ") { "'$it'" }}. Valid arguments: ${allowed.sorted().joinToString(", ")}.")
        }
    }
}
