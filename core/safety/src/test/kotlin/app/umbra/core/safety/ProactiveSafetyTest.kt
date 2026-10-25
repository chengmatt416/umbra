package app.umbra.core.safety

import app.umbra.core.model.action.*
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ProactiveSafetyTest {
    @Test fun fileInspectionBlocksProtectedValuesAcrossReadBoundariesAndBoundsWork() {
        val secret = "原生保護值-qa-1357"
        val guard = OutboundDlpGuard(4) { listOf(secret) }
        val text = "a".repeat(16_380) + secret + " more text"
        val error = assertFailsWith<IllegalArgumentException> { guard.requireSafe(text.reader(), 100_000) }
        assertFalse(error.message.orEmpty().contains(secret))
        assertFailsWith<IllegalArgumentException> { guard.requireSafe("safe text".reader(), 3) }
        guard.requireSafe("A synthetic report without protected data.".reader(), 1000)
        assertFailsWith<IllegalArgumentException> { guard.requireSafe(("a".repeat(16_380) + "verification code: 123456").reader(), 100_000) }
    }

    @Test fun fileInspectionSnapshotsKnownValuesOnceAndRejectsUnsupportedLengths() {
        var reads = 0
        OutboundDlpGuard(4) { reads++; emptyList() }.requireSafe("safe content ".repeat(10_000).reader(), 200_000)
        assertEquals(1, reads)
        assertFailsWith<IllegalArgumentException> { OutboundDlpGuard(4) { listOf("x".repeat(65_537)) }.requireSafe("safe".reader(), 1000) }
    }
    @Test fun nativeHandoffProtectsShortCodesAndRefreshesKnownValues() {
        val values = mutableListOf<String>()
        val guard = OutboundDlpGuard(4) { values }
        assertEquals("Code 1357", guard.redact("Code 1357"))
        values += "1357"
        assertFailsWith<IllegalArgumentException> { guard.requireSafe("Code 1357") }
        assertFalse(guard.redact("Code 1357").contains("1357"))
        values.clear()
        assertEquals("Code 1357", guard.redact("Code 1357"))
    }
    @Test fun outboundCredentialsAreBlockedWithoutPersistingTheSecret() = runTest {
        val entries = mutableListOf<ApprovalLedgerEntry>()
        val secret = "opaque-fixture-secret-value"
        val broker = ApprovalBroker(ledger = ApprovalLedger { entries += it })
        val gate = SafetyGate(RiskClassifier(), { ApprovalPolicy(ApprovalMode.AUTONOMOUS) }, broker,
            dlp = OutboundDlpGuard { listOf(secret) })
        val result = gate.check(ActionRequest("x", Operation.TypeText(secret), sessionId = "s"))
        assertIs<SafetyGate.Outcome.Blocked>(result)
        assertEquals(RiskLevel.CRITICAL, result.assessment.level)
        assertTrue(broker.pending.value.isEmpty())
        assertFalse(entries.toString().contains(secret))
        assertTrue(OutboundDlpGuard().matches("Pay with 4111 1111 1111 1111").isNotEmpty())
        assertTrue(OutboundDlpGuard().matches("verification code: 123456").isNotEmpty())
        assertTrue(OutboundDlpGuard().matches("Order 1234 costs 15.00").isEmpty())
    }
    @Test fun strictestRuleWinsAndCriticalActionsCannotBeAutomaticallyApproved() = runTest {
        val rules = mutableListOf(CustomActionRule("auto", scope = RuleScope(), disposition = CustomActionRule.Disposition.AUTO_EXECUTE, description = "Low risk automatic"))
        val broker = ApprovalBroker()
        val gate = SafetyGate(RiskClassifier(), { ApprovalPolicy(ApprovalMode.AUTONOMOUS) }, broker, customRules = { rules })
        val request = ActionRequest("x", Operation.TypeText("ordinary text"), sessionId = "s")
        assertIs<SafetyGate.Outcome.Proceed>(gate.check(request))
        rules += CustomActionRule("ask", scope = RuleScope(), disposition = CustomActionRule.Disposition.ALWAYS_ASK, description = "Always review")
        val waiting = async { gate.check(request) }; runCurrent()
        broker.resolve(broker.pending.value.single().id, ApprovalDecision.Denied())
        assertIs<SafetyGate.Outcome.Blocked>(waiting.await())
        rules += CustomActionRule("handoff", scope = RuleScope(), disposition = CustomActionRule.Disposition.HANDOFF_TO_USER, description = "Human only")
        assertEquals(ActionStatus.NEEDS_USER, assertIs<SafetyGate.Outcome.Blocked>(gate.check(request)).result.status)
        rules.removeAll { it.id != "auto" }
        val critical = async { gate.check(request, additionalAssessment = RiskAssessment.of(RiskFinding(RiskCategory.PURCHASE, RiskLevel.CRITICAL, "Checkout"))) }
        runCurrent(); assertEquals(1, broker.pending.value.size)
        broker.resolve(broker.pending.value.single().id, ApprovalDecision.Denied()); critical.await()
    }
    @Test fun scopedRecipientRulesDoNotMatchUnknownAmountsOrLookalikeDomains() {
        val request = ActionRequest("x", Operation.TypeText("draft"))
        val rule = CustomActionRule("r", scope = RuleScope(recipientOrDomain = "*.example.com", maxAmountUsd = 10.0), disposition = CustomActionRule.Disposition.PRE_APPROVED_SCOPE, description = "Scoped")
        assertFalse(rule.matches(request, RiskAssessment.NONE, ActionContext(recipientOrDomain = "evil-example.com", amountUsd = 5.0)))
        assertFalse(rule.matches(request, RiskAssessment.NONE, ActionContext(recipientOrDomain = "a.example.com")))
        assertTrue(rule.matches(request, RiskAssessment.NONE, ActionContext(recipientOrDomain = "a.example.com", amountUsd = 5.0)))
    }
}
