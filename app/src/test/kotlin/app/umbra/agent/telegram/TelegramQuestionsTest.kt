package app.umbra.agent.telegram

import app.umbra.runtime.omp.*
import kotlin.test.*
import kotlinx.serialization.json.*

class TelegramQuestionsTest {
    private fun keyboard(request: UiRequest, index: Int = 0, selected: Set<Int> = emptySet()) =
        telegramQuestionKeyboard(request, index, "tq:opaque", ::telegramTestText, selected)
    private fun labels(markup: JsonObject) = telegramTestButtons(markup).map { it.getValue("text").jsonPrimitive.content }
    private val questions = listOf(
        UiRequest.AskQuestion("first", "Pick one", "First", listOf(UiRequest.AskOption("A", "Alpha"), UiRequest.AskOption("B")), false, 0),
        UiRequest.AskQuestion("second", "Pick several", "Second", listOf(UiRequest.AskOption("C"), UiRequest.AskOption("D")), true, null),
        UiRequest.AskQuestion("third", "Your words", null, emptyList(), false, null),
    )

    @Test fun everyRequestKindOffersSkipAndOnlyItsOwnControls() {
        val requests = listOf<UiRequest>(
            UiRequest.Select("select", "Choose", listOf("One", "Two")),
            UiRequest.Confirm("confirm", "Continue?", "Details"),
            UiRequest.Input("input", "Name", "Type here"),
            UiRequest.Editor("editor", "Edit", "Initial words"),
            UiRequest.Ask("ask", questions),
        )
        requests.forEach { request ->
            val markup = keyboard(request)
            assertTrue("Skip" in labels(markup))
            telegramTestButtons(markup).forEach { assertTrue(it.getValue("callback_data").jsonPrimitive.content.toByteArray().size <= 64) }
        }
        assertEquals(listOf("One", "Two", "Skip"), labels(keyboard(requests[0])))
        assertEquals(listOf("Yes", "No", "Skip"), labels(keyboard(requests[1])))
        assertEquals(listOf("Skip"), labels(keyboard(requests[2])))
        assertEquals(listOf("Skip"), labels(keyboard(requests[3])))
        assertEquals(listOf("A", "B", "✏️ Type an answer", "Skip"), labels(keyboard(requests[4])))
        assertEquals(listOf("C", "✓ D", "Done", "✏️ Type an answer", "Skip"), labels(keyboard(requests[4], 1, setOf(1))))
        assertEquals(listOf("✏️ Type an answer", "Skip"), labels(keyboard(requests[4], 2)))
        assertEquals(listOf(false, false, true, true, false), requests.map { telegramQuestionNeedsReply(it) })
        assertTrue(telegramQuestionNeedsReply(requests[4], 2))
        assertEquals(true, telegramForceReply()["force_reply"]?.jsonPrimitive?.boolean)
        assertEquals(true, telegramForceReply()["selective"]?.jsonPrimitive?.boolean)
    }

    @Test fun askAnswersKeepRequestOrderAndWaitForEveryQuestion() {
        val answers = TelegramAskAnswers(UiRequest.Ask("ask", questions))
        assertTrue(answers.custom(2, "Custom words"))
        assertNull(answers.response())
        assertTrue(answers.choose(1, 1))
        assertTrue(answers.choose(1, 0))
        assertNull(answers.response(), "Multi choice needs Done")
        assertTrue(answers.done(1))
        assertTrue(answers.choose(0, 1))
        assertEquals(UiResponse.Answers(listOf(
            AskAnswer("first", listOf("B")), AskAnswer("second", listOf("C", "D")), AskAnswer("third", emptyList(), "Custom words"),
        )), answers.response())
    }

    @Test fun togglesInvalidateDoneAndCustomInputReplacesTheSelection() {
        val request = UiRequest.Ask("ask", listOf(questions[1]))
        val answers = TelegramAskAnswers(request)
        assertFalse(answers.done(0))
        assertTrue(answers.choose(0, 0))
        assertTrue(answers.done(0))
        assertEquals(listOf("C"), answers.response()?.answers?.single()?.selectedOptions)
        assertTrue(answers.choose(0, 0))
        assertNull(answers.response())
        assertFalse(answers.done(0))
        assertTrue(answers.custom(0, "Another option"))
        assertEquals(AskAnswer("second", emptyList(), "Another option"), answers.response()?.answers?.single())
        assertFalse(answers.choose(4, 0))
        assertFalse(answers.choose(0, 99))
        assertFalse(answers.custom(-1, "No"))
    }
}
