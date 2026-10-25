package app.umbra.core.safety

import app.umbra.core.model.action.*
import app.umbra.core.model.observation.NodeFlag
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class CheckoutGuardTest {
    @Test fun bindingCheckoutIsCriticalInEveryApprovalModeAndNeverOffersRemember() = runTest {
        for (mode in ApprovalMode.entries) {
            val broker = ApprovalBroker()
            val gate = SafetyGate(RiskClassifier(), { ApprovalPolicy(mode) }, broker)
            val context = ActionContext(foregroundPackage = "example.shop", targetNode = button("Place Order"), screenTexts = listOf("Item A", "Total", "USD 24.50", "Free returns within 30 days"))
            val result = async { gate.check(request(Operation.Tap), context) }
            runCurrent()
            val approval = broker.pending.value.single()
            assertEquals(RiskLevel.CRITICAL, approval.assessment.level)
            assertTrue(approval.rememberOptions.isEmpty())
            assertEquals("example.shop", approval.details["checkout_service"])
            assertTrue(approval.details["checkout_total"].orEmpty().contains("USD 24.50"))
            assertTrue(approval.details["checkout_policy"].orEmpty().contains("returns"))
            broker.resolve(approval.id, ApprovalDecision.Denied())
            assertIs<SafetyGate.Outcome.Blocked>(result.await())
        }
    }
    @Test fun cardEntryAndPaymentVerificationAlwaysHandOffWithoutAnApprovalBypass() = runTest {
        var handoffs = 0
        val broker = ApprovalBroker()
        val gate = SafetyGate(RiskClassifier(), { ApprovalPolicy(ApprovalMode.AUTONOMOUS) }, broker, onHandoff = { _, _ -> handoffs++ })
        val card = ActionContext(targetNode = button("Card number", NodeFlag.EDITABLE))
        assertEquals(ActionStatus.NEEDS_USER, assertIs<SafetyGate.Outcome.Blocked>(gate.check(request(Operation.TypeText("ordinary placeholder")), card)).result.status)
        val verify = ActionContext(targetNode = button("Pay Now"), screenTexts = listOf("Complete 3D Secure verification"))
        assertEquals(ActionStatus.NEEDS_USER, assertIs<SafetyGate.Outcome.Blocked>(gate.check(request(Operation.Tap), verify)).result.status)
        assertEquals(2, handoffs)
        assertTrue(broker.pending.value.isEmpty())
    }
    @Test fun longOutboundContentRemainsCompleteOnTheApprovalSurface() = runTest {
        val text = "Reviewed message content. ".repeat(200)
        val broker = ApprovalBroker()
        val gate = SafetyGate(RiskClassifier(), { ApprovalPolicy() }, broker)
        val result = async { gate.check(request(Operation.TypeText(text)), ActionContext(outboundText = text), additionalAssessment = RiskAssessment.of(RiskFinding(RiskCategory.SENDS_MESSAGE, RiskLevel.HIGH, "Sends an email"))) }
        runCurrent()
        assertEquals(text, broker.pending.value.single().details["outbound_payload"])
        broker.resolve(broker.pending.value.single().id, ApprovalDecision.Denied())
        result.await()
    }
}
