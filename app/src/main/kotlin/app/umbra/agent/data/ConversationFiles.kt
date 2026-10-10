package app.umbra.agent.data

import app.umbra.core.orchestrator.ConversationCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The last transcript of each session, one file per session (`files/conversations/<id>.json`), so a task whose agent
 * is not running still shows its conversation. Pictures and tool details are left out: they can be megabytes per
 * message, and the live conversation has them.
 */
class ConversationFiles(private val dir: File) : ConversationCache {
    override suspend fun load(sessionId: String): List<JsonObject>? = withContext(Dispatchers.IO) {
        val f = file(sessionId)?.takeIf { it.isFile } ?: return@withContext null
        try {
            (Json.parseToJsonElement(f.readText()) as? JsonArray)?.mapNotNull { it as? JsonObject }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun save(sessionId: String, messages: List<JsonObject>) {
        withContext(Dispatchers.IO) {
            val f = file(sessionId) ?: return@withContext
            dir.mkdirs()
            val tmp = File(f.path + ".new")
            tmp.writeText(JsonArray(messages.takeLast(MAX_MESSAGES).map(::slim)).toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    override suspend fun delete(sessionId: String) {
        withContext(Dispatchers.IO) { file(sessionId)?.delete() }
    }

    private fun file(sessionId: String): File? = sessionId.takeIf { SAFE_ID.matches(it) }?.let { File(dir, "$it.json") }

    private fun slim(message: JsonObject): JsonObject = JsonObject(
        message.filterKeys { it != "details" }.mapValues { (key, value) -> if (key == "content") withoutPictures(value) else value },
    )

    private fun withoutPictures(content: JsonElement): JsonElement = when (content) {
        is JsonArray -> JsonArray(
            content.map { block ->
                val o = block as? JsonObject
                if (o != null && (o["type"] as? JsonPrimitive)?.content == "image") JsonObject(o + ("data" to JsonPrimitive(""))) else block
            },
        )
        else -> content
    }

    companion object {
        private const val MAX_MESSAGES = 600
        private val SAFE_ID = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}")
    }
}
