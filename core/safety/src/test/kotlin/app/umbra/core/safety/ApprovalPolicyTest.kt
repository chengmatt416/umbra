package app.umbra.core.safety

import app.umbra.core.model.Clock
import app.umbra.core.model.action.Operation
import app.umbra.core.safety.RiskCategory.DESTRUCTIVE_SYSTEM
import app.umbra.core.safety.RiskCategory.FACTORY_RESET
import app.umbra.core.safety.RiskCategory.IMPORTANT_DATA_DELETION
import app.umbra.core.safety.RiskCategory.NETWORK_CHANGE
import app.umbra.core.safety.RiskCategory.PURCHASE
import app.umbra.core.safety.RiskCategory.SELF_PROTECTION
import app.umbra.core.safety.RiskCategory.SENSITIVE_DATA_EXPOSURE
import app.umbra.core.safety.RiskCategory.SYSTEM_CONFIGURATION
import app.umbra.core.safety.RiskLevel.CRITICAL
import app.umbra.core.safety.RiskLevel.HIGH
import app.umbra.core.safety.RiskLevel.LOW
import app.umbra.core.safety.RiskLevel.MEDIUM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApprovalPolicyTest {
    private val req = request(Operation.Shell("x"))
    private fun a(vararg f: RiskFinding) = RiskAssessment(f.toList())
    private fun f(c: RiskCategory, l: RiskLevel) = RiskFinding(c, l, "because")
    private fun policy(mode: ApprovalMode, now: Long = 0) = ApprovalPolicy(mode, clock = Clock { now })

    @Test
    fun thresholdsFollowTheMode() {
        val medium = a(f(SYSTEM_CONFIGURATION, MEDIUM))
        val high = a(f(SENSITIVE_DATA_EXPOSURE, HIGH))
        val critical = a(f(NETWORK_CHANGE, CRITICAL))

        assertIs<PolicyDecision.Ask>(policy(ApprovalMode.CAUTIOUS).decide(req, medium))
        assertIs<PolicyDecision.Allow>(policy(ApprovalMode.BALANCED).decide(req, medium))
        assertIs<PolicyDecision.Allow>(policy(ApprovalMode.AUTONOMOUS).decide(req, medium))

        assertIs<PolicyDecision.Ask>(policy(ApprovalMode.CAUTIOUS).decide(req, high))
        assertIs<PolicyDecision.Ask>(policy(ApprovalMode.BALANCED).decide(req, high))
        assertIs<PolicyDecision.Allow>(policy(ApprovalMode.AUTONOMOUS).decide(req, high), "non-spec HIGH is allowed when autonomous")

        for (mode in ApprovalMode.entries) assertIs<PolicyDecision.Ask>(policy(mode).decide(req, critical), "$mode")
    }

    @Test
    fun specCategoriesAlwaysAskAtHighEvenWhenAutonomous() {
        for (category in RiskCategory.entries.filter { it.inSpec }) {
            val decision = policy(ApprovalMode.AUTONOMOUS).decide(req, a(f(category, HIGH)))
            assertIs<PolicyDecision.Ask>(decision, "$category must always ask")
        }
    }

    @Test
    fun specCategoriesBelowHighFollowTheMode() {
        assertIs<PolicyDecision.Allow>(policy(ApprovalMode.BALANCED).decide(req, a(f(IMPORTANT_DATA_DELETION, MEDIUM))))
        assertIs<PolicyDecision.Ask>(policy(ApprovalMode.CAUTIOUS).decide(req, a(f(IMPORTANT_DATA_DELETION, MEDIUM))))
    }

    @Test
    fun noFindingsIsAlwaysAllowed() {
        for (mode in ApprovalMode.entries) assertIs<PolicyDecision.Allow>(policy(mode).decide(req, RiskAssessment.NONE))
    }

    @Test
    fun selfProtectionIsDeniedNotAsked() {
        for (mode in ApprovalMode.entries) {
            val d = policy(mode).decide(req, a(f(SELF_PROTECTION, CRITICAL)))
            assertIs<PolicyDecision.Deny>(d, "$mode")
        }
    }

    @Test
    fun userDenyListBlocksCategories() {
        val p = ApprovalPolicy(ApprovalMode.AUTONOMOUS, denyCategories = setOf(PURCHASE))
        assertIs<PolicyDecision.Deny>(p.decide(req, a(f(PURCHASE, HIGH))))
        assertIs<PolicyDecision.Ask>(p.decide(req, a(f(FACTORY_RESET, CRITICAL))))
    }

    @Test
    fun rememberedRulesCoverMatchingFindingsUpToHigh() {
        val rule = AllowRule("r1", "s1", setOf(SENSITIVE_DATA_EXPOSURE), app = "com.example.bank")
        val high = a(f(SENSITIVE_DATA_EXPOSURE, HIGH))
        val p = policy(ApprovalMode.BALANCED)

        assertIs<PolicyDecision.AllowByRule>(p.decide(req, high, listOf(rule), foregroundPackage = "com.example.bank"))
        assertIs<PolicyDecision.Ask>(p.decide(req, high, listOf(rule), foregroundPackage = "com.other"), "wrong app")
        assertIs<PolicyDecision.Ask>(p.decide(req, a(f(PURCHASE, HIGH)), listOf(rule), "com.example.bank"), "different category")
    }

    @Test
    fun criticalFindingsAreNeverCoveredByRules() {
        val rule = AllowRule("r1", "s1", setOf(DESTRUCTIVE_SYSTEM), maxLevel = CRITICAL)
        assertIs<PolicyDecision.Ask>(policy(ApprovalMode.BALANCED).decide(req, a(f(DESTRUCTIVE_SYSTEM, CRITICAL)), listOf(rule)))
    }

    @Test
    fun aRuleMustCoverEveryFindingThatNeedsApproval() {
        val rule = AllowRule("r1", "s1", setOf(SENSITIVE_DATA_EXPOSURE))
        val both = a(f(SENSITIVE_DATA_EXPOSURE, HIGH), f(PURCHASE, HIGH))
        assertIs<PolicyDecision.Ask>(policy(ApprovalMode.BALANCED).decide(req, both, listOf(rule)))
    }

    @Test
    fun rulesExpireAndAreScopedToTheirSession() {
        val rule = AllowRule("r1", "s1", setOf(SENSITIVE_DATA_EXPOSURE), expiresAtMs = 1_000)
        val high = a(f(SENSITIVE_DATA_EXPOSURE, HIGH))
        assertIs<PolicyDecision.AllowByRule>(policy(ApprovalMode.BALANCED, now = 999).decide(req, high, listOf(rule)))
        assertIs<PolicyDecision.Ask>(policy(ApprovalMode.BALANCED, now = 1_000).decide(req, high, listOf(rule)))
        val other = request(Operation.Shell("x"), session = "s2")
        assertIs<PolicyDecision.Ask>(policy(ApprovalMode.BALANCED).decide(other, high, listOf(rule)))
    }

    @Test
    fun commandPrefixRulesOnlyMatchThatShellCommand() {
        val rule = AllowRule("r1", "s1", setOf(SENSITIVE_DATA_EXPOSURE), operation = "shell", commandPrefix = "cat /data/data/com.example.crashy")
        val high = a(f(SENSITIVE_DATA_EXPOSURE, HIGH))
        val match = request(Operation.Shell("cat /data/data/com.example.crashy/files/log"))
        val other = request(Operation.Shell("cat /data/data/com.whatsapp/x"))
        assertIs<PolicyDecision.AllowByRule>(policy(ApprovalMode.BALANCED).decide(match, high, listOf(rule)))
        assertIs<PolicyDecision.Ask>(policy(ApprovalMode.BALANCED).decide(other, high, listOf(rule)))
    }

    @Test
    fun askCarriesAReadableReason() {
        val d = policy(ApprovalMode.BALANCED).decide(req, a(f(FACTORY_RESET, CRITICAL)))
        assertIs<PolicyDecision.Ask>(d)
        assertTrue("Factory reset" in d.reason, d.reason)
    }

    @Test
    fun withModeKeepsTheDenyList() {
        val p = ApprovalPolicy(ApprovalMode.CAUTIOUS, denyCategories = setOf(PURCHASE)).withMode(ApprovalMode.AUTONOMOUS)
        assertEquals(ApprovalMode.AUTONOMOUS, p.mode)
        assertEquals(setOf(PURCHASE), p.denyCategories)
        assertIs<PolicyDecision.Allow>(p.decide(req, a(f(SYSTEM_CONFIGURATION, LOW))))
    }
}
