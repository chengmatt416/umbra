package app.umbra.tools

import app.umbra.core.decision.*
import app.umbra.core.model.Point
import app.umbra.core.model.Rect
import app.umbra.core.model.Clock
import app.umbra.core.model.action.GlobalAction
import app.umbra.core.model.capability.UiBackendKind
import app.umbra.core.model.observation.*
import app.umbra.core.safety.*
import app.umbra.platform.api.*
import app.umbra.runtime.omp.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.*

class ScreenPlanTest {
    private class Screen(private val extra: List<UiNode> = emptyList()) : UiBackend {
        var stage = 0
        var calls = 0
        var progresses = true
        override val name = "fixture-screen"
        override val kind = UiBackendKind.ACCESSIBILITY
        override val features = setOf(UiFeature.GESTURES)
        override suspend fun isAvailable() = true
        override suspend fun snapshot(options: SnapshotOptions) = UiSnapshot(listOf(UiTree(0, 1, "fixture.app", isActive = true,
            nodes = listOf(UiNode("1:0", 0, text = "Next", contentDescription = "Stage $stage", bounds = Rect(0, 0, 100, 100),
                flags = setOf(NodeFlag.VISIBLE, NodeFlag.ENABLED, NodeFlag.CLICKABLE))) + extra)))
        override suspend fun tap(displayId: Int?, point: Point) { calls++; if (progresses) stage++ }
        override suspend fun performAction(node: UiNode, action: NodeAction, args: NodeActionArgs) = false
        override suspend fun longPress(displayId: Int?, point: Point, durationMs: Long) = error("unused")
        override suspend fun swipe(displayId: Int?, from: Point, to: Point, durationMs: Long) = error("unused")
        override suspend fun drag(displayId: Int?, from: Point, to: Point, holdMs: Long, durationMs: Long) = error("unused")
        override suspend fun key(displayId: Int?, key: String) = error("unused")
        override suspend fun typeText(displayId: Int?, text: String) = error("unused")
        override suspend fun global(action: GlobalAction) = false
    }

    private class Laya(private val screen: Screen) : DecisionEngine {
        var confidence = .99
        var onChoice: () -> Unit = {}
        var pick = "0"
        val offered = mutableListOf<List<String>>()
        var calls = 0
        override val name = "fixture-laya"
        override suspend fun isAvailable() = true
        override suspend fun decide(request: DecisionRequest): DecisionResponse {
            calls++
            val answers = request.questions.mapValues { (_, q) ->
                when (q) {
                    is Question.Noul -> {
                        val stage = Regex("Stage (\\d+)").find(q.instructions)!!.groupValues[1].toInt()
                        Answer.NoulAnswer(if (screen.stage >= stage) .99 else .01, confidence)
                    }
                    is Question.Choice -> {
                        onChoice(); offered += q.options.map { it.description }
                        Answer.ChoiceAnswer(pick, mapOf(pick to .99), confidence)
                    }
                    else -> error("unused")
                }
            }
            return DecisionResponse(answers, name, 0)
        }
    }

    private fun TestScope.host(screen: Screen, laya: Laya?, epoch: () -> Long = { 0L }): UmbraToolHost {
        val pipeline = ActionPipeline(StaticBackends(androidUi = listOf(screen)), SafetyGate(RiskClassifier(), { ApprovalPolicy() }, ApprovalBroker()),
            inputEpoch = { _, _, _ -> epoch() }, clock = Clock { testScheduler.currentTime })
        return UmbraToolHost(pipeline, "fixture", DecisionRouter(laya))
    }

    private fun call(budget: Int = 24, tool: String = "android_tap") = HostToolCall("call", "plan", ScreenPlanTool.NAME,
        Json.parseToJsonElement("""{"max_actions":$budget,"steps":[
          {"task":"First page","done_when":"Stage 1","actions":[{"label":"Next page","tool":"$tool","args":{"label":"Next"}}]},
          {"task":"Second page","done_when":"Stage 2","actions":[{"label":"Next page","tool":"$tool","args":{"label":"Next"}}]}
        ]}""").jsonObject)
    private val progress = HostToolProgress { }

    @Test
    fun layaRunsSequentialSubtasksAndVerifiesEachBeforeAdvancing() = runTest {
        val screen = Screen()
        val result = host(screen, Laya(screen)).handle(call(), progress)
        assertFalse(result.isError, result.content.toString())
        assertEquals("completed", result.details?.jsonObject?.get("status")?.jsonPrimitive?.content)
        assertEquals(2, result.details?.jsonObject?.get("completed_subtasks")?.jsonPrimitive?.int)
        assertEquals(2, screen.calls)
    }

    @Test
    fun unavailableOrUncertainLayaNeverDispatchesAnAction() = runTest {
        for (available in listOf(false, true)) {
            val screen = Screen()
            val laya = if (available) Laya(screen).apply { confidence = .5 } else null
            assertTrue(host(screen, laya).handle(call(), progress).isError)
            assertEquals(0, screen.calls)
        }
    }

    @Test
    fun controlChangesWhileLayaDecidesInvalidateTheEntirePlan() = runTest {
        val screen = Screen()
        var epoch = 0L
        val laya = Laya(screen).apply { onChoice = { epoch += 2 } }
        assertTrue(host(screen, laya) { epoch }.handle(call(), progress).isError)
        assertEquals(0, screen.calls)
    }

    @Test
    fun budgetExhaustionReportsOnlyVerifiedSubtasks() = runTest {
        val screen = Screen()
        val result = host(screen, Laya(screen)).handle(call(budget = 1), progress)
        assertTrue(result.isError)
        assertEquals(1, screen.calls)
        assertEquals(1, result.details?.jsonObject?.get("completed_subtasks")?.jsonPrimitive?.int)
    }

    @Test
    fun stalledPlansStopAfterTwoUnchangedActionsAndCannotRunShellCommands() = runTest {
        val screen = Screen().apply { progresses = false }
        val laya = Laya(screen)
        val host = host(screen, laya)
        assertTrue(host.handle(call(), progress).isError)
        assertEquals(2, screen.calls)
        val decisions = laya.calls
        assertTrue(host.handle(call(tool = "android_shell"), progress).isError)
        assertEquals(decisions, laya.calls, "Invalid plans are rejected before inference or execution")
        assertEquals(2, screen.calls)
    }

    private fun derivedCall(stepExtras: String) = HostToolCall("call", "plan", ScreenPlanTool.NAME,
        Json.parseToJsonElement("""{"steps":[{"task":"First page","done_when":"Stage 1",$stepExtras}]}""").jsonObject)

    @Test
    fun layaChoosesAmongTheElementsOnScreenWhenTheStepAllowsIt() = runTest {
        val screen = Screen()
        val laya = Laya(screen)
        val result = host(screen, laya).handle(derivedCall(""""allow":["tap","back"]"""), progress)
        assertFalse(result.isError, result.content.toString())
        assertEquals(1, screen.calls)
        assertEquals(listOf("Tap \"Next\" (element)", "Press Back", "Stop and ask the main model to replan; no permitted action safely completes the subtask"), laya.offered.single())
    }

    @Test
    fun layaTypesOnlyLiteralInputsAndNeverIntoPasswordFields() = runTest {
        val field = { id: String, label: String, password: Boolean -> UiNode(id, 0, text = label, className = "android.widget.EditText", bounds = Rect(0, 200, 100, 300),
            flags = setOfNotNull(NodeFlag.VISIBLE, NodeFlag.ENABLED, NodeFlag.EDITABLE, NodeFlag.PASSWORD.takeIf { password })) }
        val screen = Screen(listOf(field("1:1", "Search", false), field("1:2", "Password", true)))
        val laya = Laya(screen).apply { pick = "stop" }
        val result = host(screen, laya).handle(derivedCall(""""allow":["type"],"inputs":[{"label":"the city","text":"Taipei"}]"""), progress)
        assertTrue(result.isError)
        assertEquals(0, screen.calls)
        val offered = laya.offered.single()
        assertEquals(listOf("Type the city into \"Search\" (field)"), offered.dropLast(1))
    }

    @Test
    fun inputsWithoutTypingAndUnknownOperationsAreRejectedBeforeInference() = runTest {
        val screen = Screen()
        val laya = Laya(screen)
        val host = host(screen, laya)
        assertTrue(host.handle(derivedCall(""""allow":["tap"],"inputs":[{"label":"x","text":"y"}]"""), progress).isError)
        assertTrue(host.handle(derivedCall(""""allow":["shell"]"""), progress).isError)
        assertTrue(host.handle(derivedCall(""""allow":[]"""), progress).isError)
        assertEquals(0, laya.calls)
        assertEquals(0, screen.calls)
    }
}
