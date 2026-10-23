package app.umbra.core.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put

class SessionHistoryPagesTest {
    @Test
    fun pagesPreserveAnEntireLargeToolResultAndEmojiWithoutOpeningAProcess() {
        val content = "x".repeat(15970) + "😀" + "y".repeat(25000) + " FINAL_TOOL_OUTPUT"
        val message = buildJsonObject { put("role", "toolResult"); put("content", content) }
        var after = 0
        var offset = 0
        val text = StringBuilder()
        var pages = 0
        do {
            val page = SessionHistoryPages.page(listOf(message), after, offset, 20)
            val fragments = page.getValue("messages") as JsonArray
            for (raw in fragments) text.append(((raw as JsonObject).getValue("message_fragment") as JsonPrimitive).content)
            after = (page.getValue("next_after_seq") as JsonPrimitive).int
            offset = (page.getValue("next_offset") as JsonPrimitive).int
            pages++
        } while ((page.getValue("has_more") as JsonPrimitive).boolean && pages < 10)
        assertEquals(message.toString(), text.toString())
        assertEquals(1, after)
        assertEquals(0, offset)
        assertTrue(pages > 1)
    }

    @Test
    fun historyKeepsImageReferencesWithoutCopyingBinaryPayloadsIntoAgentContext() {
        val image = buildJsonObject { put("type", "image"); put("data", "DUMMY_BINARY_PAYLOAD") }
        val message = buildJsonObject { put("role", "user"); put("content", JsonArray(listOf(image))) }
        val page = SessionHistoryPages.page(listOf(message), 0, 0, 20).toString()
        assertFalse(page.contains("DUMMY_BINARY_PAYLOAD"))
        assertTrue(page.contains("View this image"))
    }

    @Test
    fun historyRedactsStructuredCredentialsAndSecretTyping() {
        val arguments = buildJsonObject { put("secret", true); put("text", "DUMMY_TYPED_PASSWORD"); put("api_key", "DUMMY_API_KEY") }
        val message = buildJsonObject { put("role", "assistant"); put("arguments", arguments) }
        val page = SessionHistoryPages.page(listOf(message), 0, 0, 20).toString()
        assertFalse(page.contains("DUMMY_TYPED_PASSWORD"))
        assertFalse(page.contains("DUMMY_API_KEY"))
        assertTrue(page.contains("[redacted]"))
    }
}
