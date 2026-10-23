package app.umbra.core.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResultSummariesTest {
    private fun answer(text: String, streaming: Boolean = false) = ChatItem.Assistant("a", 0, listOf(Block.Text(text)), "p", "m", 0, 0, 0.0, "stop", null, streaming)
    private val user = ChatItem.User("u", 0, "task", emptyList(), false, false)

    @Test
    fun summaryUsesTheCurrentTurnsFinalAnswerAndSurvivesWithoutALiveProcess() {
        assertEquals("Implemented and tested.", ResultSummaries.from(ConversationState(items = listOf(user, answer("Working"), answer("Implemented and tested.")))))
        assertNull(ResultSummaries.from(ConversationState(items = listOf(answer("Old result"), user))))
        assertNull(ResultSummaries.from(ConversationState(items = listOf(user, answer("Still typing", streaming = true)))))
        assertEquals(ResultSummaries.LIMIT, ResultSummaries.from(ConversationState(items = listOf(user, answer("x".repeat(5_000)))))!!.length)
    }
}
