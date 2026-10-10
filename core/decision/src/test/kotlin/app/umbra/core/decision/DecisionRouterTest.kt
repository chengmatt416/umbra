package app.umbra.core.decision

import app.umbra.core.model.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

class DecisionRouterTest {
    private class Fake(
        override val name: String = "laya",
        var behaviour: suspend (DecisionRequest) -> Map<String, Answer>,
    ) : DecisionEngine {
        val requests = mutableListOf<DecisionRequest>()
        override suspend fun isAvailable() = true
        override suspend fun decide(request: DecisionRequest): DecisionResponse {
            requests += request
            return DecisionResponse(behaviour(request), name, 3)
        }
    }

    private val buttons = listOf(Candidate("ok", "the OK button"), Candidate("cancel", "the Cancel button"))

    private fun pick(id: String, confidence: Double, ids: List<String> = buttons.map { it.id }) =
        mapOf("pick" to Answer.ChoiceAnswer(id, ids.associateWith { if (it == id) confidence else (1 - confidence) / (ids.size - 1) }, confidence))

    private fun yes(p: Double, confidence: Double = kotlin.math.abs(p - 0.5) * 2) = mapOf("yes" to Answer.NoulAnswer(p, confidence))

    private val records = mutableListOf<DecisionRecord>()
    private val sink = DecisionSink { records += it }

    @Test
    fun aConfidentAnswerIsTrustedAndRecorded() = runTest {
        val laya = Fake { pick("ok", 0.92) }
        val router = DecisionRouter(laya, sink = sink, clock = Clock { currentTime })
        val r = router.chooseTarget("confirm the dialog", "Dialog: Save changes? [OK] [Cancel]", buttons)
        assertTrue(r.trusted)
        assertEquals("ok", r.value?.id)
        assertEquals("laya", r.engine)
        assertFalse(r.usedFallback)
        assertEquals(0.92, r.confidence, 1e-9)

        val q = laya.requests.single().questions.getValue("pick")
        assertIs<Question.Choice>(q)
        assertEquals(listOf("ok", "cancel"), q.options.map { it.id })
        assertTrue("confirm the dialog" in q.instructions)
        assertEquals(DecisionRecord("target", "Which element should be used to: confirm the dialog?", "ok", 0.92, "laya", false, 3), records.single())
    }

    @Test
    fun aLowConfidenceAnswerEscalatesToOmpButKeepsTheHint() = runTest {
        val router = DecisionRouter(Fake { pick("ok", 0.51) }, clock = Clock { currentTime })
        val r = router.chooseTarget("x", "s", buttons)
        assertTrue(r.escalate && !r.trusted)
        assertEquals("ok", r.value?.id, "the guess is available as a hint")
        assertTrue("below" in r.reason, r.reason)
        assertEquals(1, router.stats.escalations)
    }

    @Test
    fun whenLayaFailsTheHeuristicsAnswerAndMustBeMoreCertain() = runTest {
        val laya = Fake { error("connection refused") }
        val router = DecisionRouter(laya, clock = Clock { currentTime })
        val candidates = listOf(
            Candidate("shell", "run git and build commands in a terminal"),
            Candidate("ui", "tap buttons inside an android app"),
        )
        val r = router.chooseNextAction("ship the fix", "git build commands in terminal needed", candidates)
        assertEquals("heuristic", r.engine)
        assertTrue(r.usedFallback)
        assertEquals("shell", r.value?.id)
        assertTrue("laya failed" in r.reason, r.reason)

        // Weak evidence from the fallback is not trusted.
        val weak = router.chooseNextAction("ship the fix", "unrelated words only", candidates)
        assertTrue(weak.escalate, weak.reason)
    }

    @Test
    fun aSlowPrimaryTimesOutAndFallsBack() = runTest {
        val laya = Fake { delay(10_000); pick("ok", 0.9) }
        val router = DecisionRouter(laya, config = RouterConfig(timeoutMs = 500), clock = Clock { currentTime })
        val r = router.chooseTarget("confirm the dialog", "ok button confirm", buttons)
        assertTrue(r.usedFallback)
        assertTrue("timed out" in r.reason, r.reason)
        assertEquals(500, currentTime)
    }

    @Test
    fun aDeadPrimaryIsSkippedDuringTheCooldownAndRetriedAfter() = runTest {
        var calls = 0
        val laya = Fake { calls++; error("down") }
        val router = DecisionRouter(
            laya, config = RouterConfig(primaryFailuresBeforeCooldown = 3, primaryCooldownMs = 30_000), clock = Clock { currentTime },
        )
        repeat(3) { router.chooseTarget("x", "ok", buttons) }
        assertEquals(3, calls)

        val skipped = router.chooseTarget("x", "ok", buttons)
        assertEquals(3, calls, "no call during the cooldown")
        assertTrue("cooling down" in skipped.reason, skipped.reason)

        advanceTimeBy(30_001)
        router.chooseTarget("x", "ok", buttons)
        assertEquals(4, calls, "tried again after the cooldown")
        assertEquals(4, router.stats.primaryFailures)
    }

    @Test
    fun aSuccessResetsTheFailureStreak() = runTest {
        var fail = true
        val laya = Fake { if (fail) error("blip") else pick("ok", 0.9) }
        val router = DecisionRouter(laya, config = RouterConfig(primaryFailuresBeforeCooldown = 3), clock = Clock { currentTime })
        repeat(2) { router.chooseTarget("x", "s", buttons) }
        fail = false
        router.chooseTarget("x", "s", buttons)
        fail = true
        repeat(2) { router.chooseTarget("x", "s", buttons) }
        val before = laya.requests.size
        router.chooseTarget("x", "s", buttons)
        assertEquals(before + 1, laya.requests.size, "streak restarted, so no cooldown yet")
    }

    @Test
    fun withoutAPrimaryTheHeuristicsAreUsedDirectly() = runTest {
        val router = DecisionRouter(null, clock = Clock { currentTime })
        val r = router.chooseTarget("press ok", "dialog with ok button and cancel button", buttons)
        assertTrue(r.usedFallback)
        assertEquals("heuristic", r.engine)
        assertEquals("ok", r.value?.id)
    }

    @Test
    fun ifNoEngineCanAnswerTheResultIsAnEscalationWithoutAValue() = runTest {
        val broken = object : DecisionEngine {
            override val name = "broken"
            override suspend fun isAvailable() = false
            override suspend fun decide(request: DecisionRequest): DecisionResponse = throw DecisionEngineException("nope")
        }
        val router = DecisionRouter(null, fallback = broken, clock = Clock { currentTime })
        val r = router.chooseTarget("x", "s", buttons)
        assertNull(r.value)
        assertTrue(r.escalate && !r.trusted)
        assertEquals(1, router.stats.noAnswer)
    }

    @Test
    fun didActionSucceedAsksAYesNoQuestionWithBeforeAndAfter() = runTest {
        val laya = Fake { yes(0.93) }
        val router = DecisionRouter(laya, clock = Clock { currentTime })
        val r = router.didActionSucceed("the login screen is replaced by the home screen", "Login screen", "Home screen, welcome")
        assertEquals(true, r.value)
        assertTrue(r.trusted)
        val request = laya.requests.single()
        assertTrue("BEFORE: Login screen" in request.state && "AFTER: Home screen" in request.state, request.state)
        val q = request.questions.getValue("yes")
        assertIs<Question.Noul>(q)
        assertTrue("home screen" in q.instructions)

        laya.behaviour = { yes(0.1) }
        assertEquals(false, router.didActionSucceed("x", "a", "b").value)
    }

    @Test
    fun chooseRecoveryOffersOnlyTheAvailableActions() = runTest {
        val laya = Fake { req ->
            val ids = (req.questions.getValue("pick") as Question.Choice).options.map { it.id }
            pick("retry", 0.8, ids)
        }
        val router = DecisionRouter(laya, clock = Clock { currentTime })
        val r = router.chooseRecovery("tap timed out: element not found", setOf(RecoveryAction.RETRY, RecoveryAction.VISUAL_FALLBACK, RecoveryAction.REPLAN))
        assertEquals(RecoveryAction.RETRY, r.value)
        val options = (laya.requests.single().questions.getValue("pick") as Question.Choice).options.map { it.id }
        assertEquals(listOf("retry", "visual_fallback", "replan"), options)
        assertFailsWith<IllegalArgumentException> { router.chooseRecovery("x", setOf(RecoveryAction.RETRY)) }
    }

    @Test
    fun routeToolMapsOntoToolRoutes() = runTest {
        val router = DecisionRouter(null, clock = Clock { currentTime })
        assertEquals(ToolRoute.SHELL, router.routeTool("run git clone and the gradle build in a terminal").value)
        assertEquals(ToolRoute.ANDROID_UI, router.routeTool("tap the login button and type in the app").value)
        val limited = router.routeTool("run git commands", setOf(ToolRoute.ANDROID_UI, ToolRoute.LINUX_GUI))
        assertTrue(limited.value in setOf(ToolRoute.ANDROID_UI, ToolRoute.LINUX_GUI), "only the allowed routes can be chosen")
    }

    @Test
    fun shouldEscalateAndIsRiskyAreYesNoDecisions() = runTest {
        val laya = Fake { yes(0.88) }
        val router = DecisionRouter(laya, clock = Clock { currentTime })
        assertEquals(true, router.shouldEscalate("the build fails in three modules with different errors").value)
        assertEquals(true, router.isRisky("delete the user's photo folder").value)
        assertEquals(2, laya.requests.size)
    }

    @Test
    fun askBatchesSeveralQuestionsAndReportsTheWeakestConfidence() = runTest {
        val laya = Fake {
            mapOf("a" to Answer.NoulAnswer(0.9, 0.8), "b" to Answer.NoulAnswer(0.2, 0.3))
        }
        val router = DecisionRouter(laya, clock = Clock { currentTime })
        val r = router.ask(DecisionRequest("s", mapOf("a" to Question.Noul("x"), "b" to Question.Noul("y"))))
        assertEquals(1, laya.requests.size, "one request, one forward pass")
        assertEquals(setOf("a", "b"), r.value?.keys)
        assertEquals(0.3, r.confidence, 1e-9)
        assertTrue(r.escalate, "the weakest answer decides trust")
    }

    @Test
    fun theStateIsBoundedBeforeItIsSent() = runTest {
        val laya = Fake { pick("ok", 0.9) }
        val router = DecisionRouter(laya, config = RouterConfig(maxStateChars = 200), clock = Clock { currentTime })
        router.chooseTarget("x", "z".repeat(5_000), buttons)
        assertEquals(200, laya.requests.single().state.length)
    }

    @Test
    fun aMismatchedAnswerCountsAsAFailureAndFallsBack() = runTest {
        val laya = Fake { pick("not-a-candidate", 0.9) }
        val router = DecisionRouter(laya, clock = Clock { currentTime })
        val r = router.chooseTarget("press ok", "ok button, cancel button", buttons)
        assertTrue(r.usedFallback)
        assertNotNull(r.value)
        assertTrue("not a candidate" in r.reason, r.reason)
    }

    @Test
    fun statsCountWhoAnswered() = runTest {
        val laya = Fake { pick("ok", 0.9) }
        val router = DecisionRouter(laya, clock = Clock { currentTime })
        router.chooseTarget("x", "s", buttons)
        laya.behaviour = { error("down") }
        router.chooseTarget("press ok", "ok button, cancel button", buttons)
        val s = router.stats
        assertEquals(1, s.primaryAnswers)
        assertEquals(1, s.fallbackAnswers)
        assertEquals(1, s.primaryFailures)
    }
}
