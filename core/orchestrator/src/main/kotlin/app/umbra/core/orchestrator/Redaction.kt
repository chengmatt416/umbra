package app.umbra.core.orchestrator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keeps secrets out of everything that is stored: the session timeline, approval prompts, the ledger.
 * The model itself sees what it asked for; only the stored copy is scrubbed.
 */
object Redaction {
    const val MASK = "[redacted]"

    private val SECRET_KEY = Regex("(?i)(pass(word|wd|phrase)?|secret|token|api[_-]?key|authorization|credential|private[_-]?key|otp|pin)")

    /** `name=value` or `name: value` assignments whose name says "secret", and bearer tokens. */
    private val ASSIGNMENT = Regex("""(?i)\b([A-Za-z0-9_.-]*(?:pass(?:word|wd|phrase)?|secret|token|api[_-]?key|credential)[A-Za-z0-9_.-]*)(\s*[=:]\s*)("[^"]*"|'[^']*'|\S+)""")
    private val BEARER = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{12,}""")
    private val KEY_LIKE = Regex("""\b(sk-[A-Za-z0-9_-]{16,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{30,})""")

    /** Scrubs free text such as shell commands and log lines. */
    fun text(value: String): String = value
        .replace(BEARER) { "${it.groupValues[1]} $MASK" }
        .replace(ASSIGNMENT) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        .replace(KEY_LIKE, MASK)

    /** Scrubs tool-call arguments: typed secrets, values under secret-looking keys, and free text inside. */
    fun args(tool: String, args: JsonElement?): JsonElement? {
        if (args == null) return null
        val obj = args as? JsonObject ?: return walk(args, secretContext = false)
        val typedSecret = (obj["secret"] as? JsonPrimitive)?.content == "true"
        return JsonObject(
            obj.mapValues { (key, value) ->
                when {
                    typedSecret && key == "text" -> JsonPrimitive(MASK)
                    else -> walkEntry(key, value)
                }
            },
        )
    }

    private fun walkEntry(key: String, value: JsonElement): JsonElement =
        if (SECRET_KEY.containsMatchIn(key) && value is JsonPrimitive && value !is JsonNull && value.isString) JsonPrimitive(MASK) else walk(value, false)

    private fun walk(value: JsonElement, secretContext: Boolean): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (k, v) -> walkEntry(k, v) })
        is JsonArray -> JsonArray(value.map { walk(it, secretContext) })
        is JsonPrimitive -> if (value.isString) JsonPrimitive(text(value.content)) else value
    }
}
