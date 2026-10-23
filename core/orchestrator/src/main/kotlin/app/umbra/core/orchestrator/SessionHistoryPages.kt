package app.umbra.core.orchestrator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Page complete message text, including large tool results, without overflowing a host-tool frame. */
object SessionHistoryPages {
    fun page(messages: List<JsonObject>, after: Int, offset: Int, limit: Int): JsonObject {
        require(after in 0..messages.size && offset >= 0)
        var index = after
        var position = offset
        var remaining = 16_000
        var count = 0
        val fragments = buildJsonArray {
            while (index < messages.size && count < limit.coerceIn(1, 100) && remaining > 0) {
                val text = Redaction.text(withoutImages(messages[index]).toString())
                require(position <= text.length)
                var end = (position + remaining).coerceAtMost(text.length)
                if (end < text.length && end > position && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                if (end == position && end != text.length) break
                add(buildJsonObject {
                    put("seq", index + 1); put("offset", position)
                    put("message_fragment", text.substring(position, end)); put("complete", end == text.length)
                })
                count++
                remaining -= end - position
                if (end == text.length) { index++; position = 0 } else { position = end; break }
            }
        }
        return buildJsonObject {
            put("messages", fragments); put("next_after_seq", index); put("next_offset", position)
            put("has_more", index < messages.size)
        }
    }

    private fun withoutImages(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> if (value.str("type") in setOf("image", "image_url")) buildJsonObject {
            put("type", "image"); put("note", "View this image in the original session.")
        } else Redaction.args("session_history", JsonObject(value.mapValues { withoutImages(it.value) }))!!
        is JsonArray -> JsonArray(value.map(::withoutImages))
        else -> value
    }
}
