package app.umbra.agent.data

import app.umbra.agent.telegram.telegramMessageParts
import app.umbra.core.safety.ApprovalRequest
import app.umbra.core.safety.ApprovalSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalPresentationTest {
    @Test
    fun fullCommandAndInputRemainVisibleAcrossTelegramMessages() {
        val command = "echo " + "x".repeat(4_100) + " END_OF_COMMAND"
        val request = ApprovalRequest("r", "s", ApprovalSource.HOST_TOOL, "Review", command.take(300),
            details = mapOf("command" to command, "stdin" to "dummy input", "cwd" to "/fixture", "environment_keys" to "DUMMY_KEY"), createdAtMs = 0, timeoutMs = 1000)
        val text = request.description + "\n" + approvalExecutionDetails(request)
        assertTrue(text.contains(command))
        assertTrue(text.contains("dummy input"))
        assertEquals(text, telegramMessageParts(text).joinToString(""))
    }

    @Test
    fun chunkBoundariesNeverSplitAnEmoji() {
        val text = "a".repeat(3999) + "😀" + "b".repeat(4100)
        val parts = telegramMessageParts(text)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 4000 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
}
