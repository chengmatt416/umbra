package app.umbra.core.decision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HeuristicDecisionEngineTest {
    private val engine = HeuristicDecisionEngine()

    private val screenStates = listOf(
        Option("crash", "the app crashed, threw an exception or stopped responding"),
        Option("loading", "the screen shows a spinner or progress and is still loading"),
        Option("done", "everything looks normal and the task finished successfully"),
    )

    private fun choose(state: String, options: List<Option> = screenStates): Answer.ChoiceAnswer = runBlocking {
        val r = engine.decide(DecisionRequest(state, mapOf("q" to Question.Choice(options, "what is the screen state?"))))
        assertIs<Answer.ChoiceAnswer>(r.answers.getValue("q"))
    }

    private fun yesNo(statement: String, state: String): Answer.NoulAnswer = runBlocking {
        val r = engine.decide(DecisionRequest(state, mapOf("q" to Question.Noul(statement))))
        assertIs<Answer.NoulAnswer>(r.answers.getValue("q"))
    }

    @Test
    fun picksTheOptionWithTheBestEvidenceAndIsConfidentWhenItIsClear() {
        val a = choose("The app crashed with a fatal exception and stopped responding")
        assertEquals("crash", a.choice)
        assertTrue(a.confidence >= 0.6, "confidence ${a.confidence}")
        assertEquals(1.0, a.probabilities.values.sum(), 1e-9)

        assertEquals("loading", choose("Please wait, loading with a progress spinner").choice)
        assertEquals("done", choose("Saved successfully, the task is completed and all is ready").choice)
    }

    @Test
    fun synonymsFoldOntoConcepts() {
        assertEquals("crash", choose("ANR: application not responding, the app froze").choice)
        assertEquals("done", choose("Upload complete").choice)
    }

    @Test
    fun withNoEvidenceConfidenceStaysNearChance() {
        val a = choose("hello world nothing relevant here")
        assertTrue(a.confidence < 0.5, "confidence ${a.confidence} must not look like a real answer")
        assertEquals(1.0 / 3, a.probabilities.getValue("crash"), 1e-9)
    }

    @Test
    fun aNearTieLowersConfidence() {
        val clear = choose("The app crashed with a fatal exception")
        val tie = choose("crashed but also loading with a spinner and progress")
        assertTrue(tie.confidence < clear.confidence, "tie ${tie.confidence} vs clear ${clear.confidence}")
    }

    @Test
    fun optionIdsThemselvesCountAsEvidenceWhenTheyAppearAsWholeWords() {
        val options = listOf(Option("billing", "refunds and invoices"), Option("tech", "bugs and outages"))
        assertEquals("billing", choose("customer wants help with billing", options).choice)
        assertEquals("tech", choose("the checkout page has bugs", options).choice)
    }

    @Test
    fun cjkTextIsUnderstood() {
        val options = listOf(
            Option("crash", "應用程式崩潰或當機"),
            Option("done", "操作成功完成"),
        )
        assertEquals("crash", choose("應用程式崩潰了", options).choice)
        assertEquals("done", choose("儲存成功", options).choice)
    }

    @Test
    fun yesNoFollowsOverlapAndIsConfidentWhenTheEvidenceIsClear() {
        val yes = yesNo("The login succeeded", "Welcome back, you are signed in. Saved.")
        assertTrue(yes.probability > 0.8 && yes.confidence > 0.6, yes.toString())

        val no = yesNo("The login succeeded", "Error: invalid password, access denied")
        assertTrue(no.probability < 0.5, no.toString())
    }

    @Test
    fun aNegatedStatementInvertsTheEvidence() {
        val a = yesNo("There is no error", "Error: invalid password, access denied")
        assertTrue(a.probability < 0.2, a.toString())
        val b = yesNo("There is no error", "Welcome back, everything is fine and saved")
        assertTrue(b.probability > 0.8, b.toString())
    }

    @Test
    fun aNegationInTheStateCancelsTheMatchedToken() {
        val a = yesNo("The app crashed", "no crash detected, the session continues")
        assertTrue(a.probability < 0.2, a.toString())
    }

    @Test
    fun anEmptyStatementIsNotAnAnswer() {
        val a = yesNo("the is of", "anything at all")
        assertEquals(0.5, a.probability, 1e-9)
        assertEquals(0.0, a.confidence, 1e-9)
    }

    @Test
    fun scoresPlaceTheStateOnTheScale() {
        val levels = listOf(
            Option("low", "minor cosmetic issue, no urgency"),
            Option("medium", "degraded service for some users"),
            Option("high", "critical outage, customers are down, urgent"),
        )
        val r = runBlocking { engine.decide(DecisionRequest("urgent critical outage, customers down", mapOf("q" to Question.Score(levels, "how urgent?")))) }
        val a = assertIs<Answer.ScoreAnswer>(r.answers.getValue("q"))
        assertTrue(a.score > 1.5, "expected near 2 (high) but was ${a.score}")
        assertEquals(3, a.distribution.size)
    }

    @Test
    fun severalQuestionsAreAnsweredInOneCall() = runBlocking<Unit> {
        val r = engine.decide(
            DecisionRequest(
                "The app crashed with a fatal exception",
                mapOf(
                    "state" to Question.Choice(screenStates, "state?"),
                    "bad" to Question.Noul("The app crashed"),
                ),
            ),
        )
        assertEquals(setOf("state", "bad"), r.answers.keys)
        assertEquals("heuristic", r.engine)
    }

    @Test
    fun answersAreDeterministic() {
        val s = "The app crashed with a fatal exception and stopped responding"
        assertEquals(choose(s), choose(s))
    }

    @Test
    fun invalidQuestionsAreRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> { Question.Choice(listOf(Option("a", "x")), "?") }
        assertFailsWith<IllegalArgumentException> { Question.Choice(listOf(Option("a", "x"), Option("a", "y")), "?") }
        assertFailsWith<IllegalArgumentException> { Question.Score(listOf(Option("a", "x")), "?") }
        assertFailsWith<IllegalArgumentException> { DecisionRequest("s", emptyMap()) }
    }

    @Test
    fun isAlwaysAvailable() = runBlocking<Unit> { assertTrue(engine.isAvailable()) }
}
