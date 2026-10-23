package app.umbra.core.safety

import app.umbra.core.model.Clock
import app.umbra.core.model.action.ActionRequest
import app.umbra.core.model.action.Privilege
import app.umbra.core.model.action.Operation
import kotlinx.serialization.Serializable

/**
 * How much the user wants to be asked (spec §28). Whatever the mode, a spec §28 category at HIGH or
 * above always needs approval, and CRITICAL findings can never be pre-approved by a remembered rule.
 */
@Serializable
enum class ApprovalMode(val askFrom: RiskLevel) {
    /** Ask for anything at MEDIUM or above. */
    CAUTIOUS(RiskLevel.MEDIUM),

    /** Default: ask at HIGH or above. */
    BALANCED(RiskLevel.HIGH),

    /** Ask only at CRITICAL, plus the always-ask spec categories. */
    AUTONOMOUS(RiskLevel.CRITICAL),
}

/**
 * A remembered approval ("allow this for the rest of the session"). It covers only the listed
 * categories, up to [maxLevel] (never CRITICAL), and only where the optional restrictions match.
 */
@Serializable
data class AllowRule(
    val id: String,
    val sessionId: String,
    val categories: Set<RiskCategory>,
    val maxLevel: RiskLevel = RiskLevel.HIGH,
    /** Restrict to a foreground package (UI actions). */
    val app: String? = null,
    /** Restrict to an operation name such as `tap` or `shell`. */
    val operation: String? = null,
    /** Restrict to shell commands starting with this text. */
    val commandPrefix: String? = null,
    val expiresAtMs: Long? = null,
    val workingDirectory: String? = null,
    val contextDirectory: String? = null,
    val literalCommand: Boolean = false,
) {
    fun matches(request: ActionRequest, foregroundPackage: String?, now: Long, contextDirectory: String? = null): Boolean {
        if (expiresAtMs != null && now >= expiresAtMs) return false
        if (sessionId != "*" && request.sessionId != sessionId) return false
        if (app != null && app != foregroundPackage) return false
        if (operation != null && operation != request.operation.name) return false
        if (commandPrefix != null) {
            val command = (request.operation as? Operation.Shell)?.command ?: return false
            if (!CommandPrefixes.matches(command, commandPrefix)) return false
            if (literalCommand) {
                val shell = request.operation as Operation.Shell
                if (CommandPrefixes.offered(shell) == null || shell.cwd != workingDirectory || contextDirectory != this.contextDirectory || request.requires == Privilege.ROOT) return false
            }
        }
        return true
    }

    fun covers(finding: RiskFinding): Boolean =
        finding.category in categories && finding.level <= maxLevel && finding.level < RiskLevel.CRITICAL
}

sealed interface PolicyDecision {
    data object Allow : PolicyDecision
    data class AllowByRule(val rule: AllowRule) : PolicyDecision
    data class Ask(val assessment: RiskAssessment, val reason: String) : PolicyDecision
    data class Deny(val assessment: RiskAssessment, val reason: String) : PolicyDecision
}

class ApprovalPolicy(
    val mode: ApprovalMode = ApprovalMode.BALANCED,
    /** Categories the user has chosen to refuse outright (revocable). */
    val denyCategories: Set<RiskCategory> = emptySet(),
    private val clock: Clock = Clock.System,
) {
    fun decide(
        request: ActionRequest,
        assessment: RiskAssessment,
        rules: List<AllowRule> = emptyList(),
        foregroundPackage: String? = null,
        workingDirectory: String? = null,
    ): PolicyDecision {
        if (assessment.isSelfProtectionViolation) {
            return PolicyDecision.Deny(assessment, "This action targets the agent's own app, approvals or data and is never allowed.")
        }
        val denied = assessment.findings.firstOrNull { it.category in denyCategories }
        if (denied != null) {
            return PolicyDecision.Deny(assessment, "Blocked by your settings: ${denied.category.title}. ${denied.reason}")
        }

        val needing = assessment.findings.filter { needsApproval(it) }
        if (needing.isEmpty()) return PolicyDecision.Allow

        val now = clock.nowMs()
        val applicable = rules.filter { it.matches(request, foregroundPackage, now, workingDirectory) }
        val covering = needing.map { f -> applicable.firstOrNull { it.covers(f) } }
        if (covering.all { it != null }) return PolicyDecision.AllowByRule(covering.first()!!)

        val top = needing.maxBy { it.level }
        return PolicyDecision.Ask(assessment, "${top.category.title} (${top.level.name.lowercase()}): ${top.reason}")
    }

    fun needsApproval(finding: RiskFinding): Boolean =
        finding.category != RiskCategory.SELF_PROTECTION &&
            ((finding.category.inSpec && finding.level >= RiskLevel.HIGH) || finding.level >= mode.askFrom)

    fun withMode(newMode: ApprovalMode) = ApprovalPolicy(newMode, denyCategories, clock)
}
