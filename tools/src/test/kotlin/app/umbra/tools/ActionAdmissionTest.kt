package app.umbra.tools

import app.umbra.core.model.action.ActionRequest
import app.umbra.core.model.action.ActionStatus
import app.umbra.core.model.action.Operation
import app.umbra.core.safety.*
import app.umbra.platform.api.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ActionAdmissionTest {
    @Test
    fun lockdownDuringIndependentReviewStopsANonUiActionBeforeDispatch() = runTest {
        var calls = 0
        var locked = false
        val review = CompletableDeferred<RiskAssessment>()
        val shell = object : ShellBackend {
            override val name = "dummy"
            override val identity = ShellIdentity.APP
            override suspend fun isAvailable() = true
            override suspend fun exec(request: ShellRequest): ShellResult { calls++; return ShellResult(0, "dummy") }
            override suspend fun execBytes(request: ShellRequest) = BinaryShellResult(0, byteArrayOf())
            override fun stream(request: ShellRequest) = emptyFlow<ShellEvent>()
        }
        val gate = SafetyGate(RiskClassifier(), { ApprovalPolicy() }, ApprovalBroker(), securityCheck = ActionSecurityCheck { _, _ -> review.await() })
        val pipeline = ActionPipeline(StaticBackends(shells = listOf(shell)), gate, inputGate = { _, _, _ -> if (locked) "Locked by owner" else null })
        val action = async { pipeline.execute(ActionRequest("dummy", Operation.Shell("echo fixture"))) }
        runCurrent()
        locked = true
        review.complete(RiskAssessment.NONE)
        assertEquals(ActionStatus.NEEDS_USER, action.await().result.status)
        assertEquals(0, calls, "No executor may receive work after lockdown during review")
    }
}
