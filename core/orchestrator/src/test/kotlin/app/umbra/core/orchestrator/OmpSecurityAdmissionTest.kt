package app.umbra.core.orchestrator

import app.umbra.core.safety.*
import app.umbra.runtime.omp.UiRequest
import app.umbra.runtime.omp.UiResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class OmpSecurityAdmissionTest {
    @Test
    fun lockdownDuringIndependentReviewDoesNotApproveACodingTool() = runTest {
        var locked = false
        val review = CompletableDeferred<RiskAssessment>()
        val gate = SafetyGate(RiskClassifier(), { ApprovalPolicy() }, ApprovalBroker(), securityCheck = ActionSecurityCheck { _, _ -> review.await() })
        val bridge = OmpApprovalBridge("fixture", gate, { ActionContext() }, UserQuestions { _, _ -> error("Unexpected user question") }, fence = { if (locked) "Locked" else null })
        val pending = async { bridge.handle(UiRequest.Select("dummy", "Allow tool: bash\nCommand: echo fixture", listOf("Approve", "Deny"))) }
        runCurrent()
        locked = true
        review.complete(RiskAssessment.NONE)
        assertEquals(UiResponse.Value("Deny"), pending.await())
    }
}
