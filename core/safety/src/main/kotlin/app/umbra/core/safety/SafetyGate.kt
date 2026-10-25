package app.umbra.core.safety

import app.umbra.core.model.Clock
import app.umbra.core.model.action.ActionRequest
import app.umbra.core.model.action.ActionResult
import app.umbra.core.model.action.ActionStatus
import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.action.Operation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The single checkpoint every host-tool call passes before it executes: classify, apply policy,
 * possibly ask the user, remember approvals per session, and turn refusals into structured results
 * the agent can reason about.
 */
class SafetyGate(
    private val classifier: RiskClassifier,
    private val policy: () -> ApprovalPolicy,
    private val broker: ApprovalBroker,
    private val clock: Clock = Clock.System,
    private val approvalTimeoutMs: Long = 5 * 60_000,
    private val securityCheck: ActionSecurityCheck? = null,
    private val commandRules: () -> List<AllowRule> = { emptyList() },
    private val saveCommandRule: (AllowRule) -> Unit = {},
    private val dlp: OutboundDlpGuard = OutboundDlpGuard(),
    private val customRules: suspend (sessionId: String) -> List<CustomActionRule> = { emptyList() },
    private val originalIntent: suspend (sessionId: String) -> String? = { null },
    private val onHandoff: (ActionRequest, ActionContext) -> Unit = { _, _ -> },
) {
    sealed interface Outcome {
        data class Proceed(val assessment: RiskAssessment, val how: String) : Outcome
        data class Blocked(val result: ActionResult, val assessment: RiskAssessment) : Outcome
    }

    private val rulesBySession = ConcurrentHashMap<String, CopyOnWriteArrayList<AllowRule>>()
    private val counter = AtomicLong()

    fun rulesFor(sessionId: String): List<AllowRule> = rulesBySession[sessionId]?.toList() ?: emptyList()

    fun restoreRules(sessionId: String, rules: List<AllowRule>) {
        rulesBySession[sessionId] = CopyOnWriteArrayList(rules)
    }

    fun revokeRules(sessionId: String) {
        rulesBySession.remove(sessionId)
    }

    suspend fun check(
        request: ActionRequest,
        ctx: ActionContext = ActionContext(),
        source: ApprovalSource = ApprovalSource.HOST_TOOL,
        additionalAssessment: RiskAssessment = RiskAssessment.NONE,
        additionalRules: List<AllowRule> = emptyList(),
    ): Outcome {
        val sessionId = request.sessionId ?: "-"
        val effectiveContext = ctx.copy(userIntent = originalIntent(sessionId) ?: ctx.userIntent)
        val baseline = classifier.assess(request, effectiveContext) + additionalAssessment + dlp.assess(request.operation, effectiveContext)
        // A hard denial is final. The independent model only adds findings, never removes them.
        val assessment = if (policy().decide(request, baseline) is PolicyDecision.Deny) baseline
            else baseline + (securityCheck?.assess(request, effectiveContext) ?: RiskAssessment.NONE)
        // Read settings after inference, so a running task observes a policy change immediately.
        val decision = policy().decide(request, assessment, rulesFor(sessionId) + commandRules() + additionalRules, effectiveContext.foregroundPackage, effectiveContext.cwd)
        if (decision !is PolicyDecision.Deny) {
            if (RiskCategory.FINANCIAL_TRANSACTION in assessment.categories && CheckoutGuard.requiresUser(effectiveContext)) {
                onHandoff(request, effectiveContext)
                broker.recordAutomatic(toApproval(request, assessment, effectiveContext, source), false)
                return Outcome.Blocked(ActionResult.failure(request.id, ErrorCode.PERMISSION_DENIED, "Complete payment, card entry or verification yourself. The agent has handed control to you.", status = ActionStatus.NEEDS_USER), assessment)
            }
            val rule = customRules(sessionId).filter { it.matches(request, assessment, effectiveContext) }.maxByOrNull { it.disposition.ordinal }
            if (rule?.disposition == CustomActionRule.Disposition.HANDOFF_TO_USER) {
                onHandoff(request, effectiveContext)
                broker.recordAutomatic(toApproval(request, assessment, effectiveContext, source), false)
                return Outcome.Blocked(ActionResult.failure(request.id, ErrorCode.PERMISSION_DENIED, "User takeover required by rule: ${rule.description}", status = ActionStatus.NEEDS_USER), assessment)
            }
            if (rule?.disposition == CustomActionRule.Disposition.ALWAYS_ASK)
                return ask(request, assessment, effectiveContext, PolicyDecision.Ask(assessment, rule.description), source)
            val hardReview = assessment.level >= RiskLevel.CRITICAL || assessment.findings.any { it.level >= RiskLevel.HIGH && it.category in setOf(
                RiskCategory.CREDENTIAL_SUBMISSION, RiskCategory.ACCOUNT_SECURITY_CHANGE, RiskCategory.FINANCIAL_TRANSACTION, RiskCategory.PURCHASE) }
            if (rule != null && !hardReview && rule.disposition in setOf(CustomActionRule.Disposition.AUTO_EXECUTE, CustomActionRule.Disposition.PRE_APPROVED_SCOPE)) {
                broker.recordAutomatic(toApproval(request, assessment, effectiveContext, source), true)
                return Outcome.Proceed(assessment, "allowed by custom rule ${rule.id}")
            }
        }
        return when (decision) {
            PolicyDecision.Allow -> Outcome.Proceed(assessment, "allowed")
            is PolicyDecision.AllowByRule -> {
                broker.recordAutomatic(toApproval(request, assessment, effectiveContext, source), allowed = true)
                Outcome.Proceed(assessment, "allowed by remembered approval ${decision.rule.id}")
            }
            is PolicyDecision.Deny -> {
                broker.recordAutomatic(toApproval(request, assessment, effectiveContext, source).let {
                    if (RiskCategory.SECRET_EXFILTRATION in assessment.categories) it.copy(description = "Blocked outbound protected data", details = mapOf("risk" to assessment.describe())) else it
                }, allowed = false)
                Outcome.Blocked(
                    ActionResult.failure(
                        id = request.id,
                        code = ErrorCode.DENIED_BY_POLICY,
                        message = "Not performed: ${decision.reason} Choose a different approach.",
                        status = ActionStatus.DENIED,
                    ),
                    assessment,
                )
            }
            is PolicyDecision.Ask -> ask(request, assessment, effectiveContext, decision, source)
        }
    }

    private suspend fun ask(
        request: ActionRequest,
        assessment: RiskAssessment,
        ctx: ActionContext,
        decision: PolicyDecision.Ask,
        source: ApprovalSource,
    ): Outcome {
        val approval = toApproval(request, assessment, ctx, source)
        return when (val answer = broker.request(approval)) {
            is ApprovalDecision.Approved -> {
                answer.remember?.takeIf { it in approval.rememberOptions }?.let { remember(request, assessment, ctx, it) }
                Outcome.Proceed(assessment, "approved by the user")
            }
            is ApprovalDecision.Denied -> Outcome.Blocked(
                ActionResult.failure(
                    id = request.id,
                    code = ErrorCode.DENIED_BY_USER,
                    message = "The user denied this action${answer.reason?.let { " (\"$it\")" } ?: ""}: ${decision.reason}. " +
                        "Do not retry it; choose another approach or ask the user what they want.",
                    status = ActionStatus.DENIED,
                ),
                assessment,
            )
            ApprovalDecision.TimedOut -> Outcome.Blocked(
                ActionResult.failure(
                    id = request.id,
                    code = ErrorCode.DENIED_BY_USER,
                    message = "No approval was given in time, so the action was not performed: ${decision.reason}. " +
                        "Tell the user what is waiting for approval, or pick a safer alternative.",
                    status = ActionStatus.NEEDS_USER,
                ),
                assessment,
            )
            ApprovalDecision.Cancelled -> Outcome.Blocked(
                ActionResult.failure(
                    id = request.id,
                    code = ErrorCode.CANCELLED,
                    message = "The approval request was cancelled because the session stopped.",
                    status = ActionStatus.CANCELLED,
                ),
                assessment,
            )
        }
    }

    private fun remember(request: ActionRequest, assessment: RiskAssessment, ctx: ActionContext, scope: RememberScope) {
        val sessionId = request.sessionId ?: return
        val categories = assessment.findings.filter { it.level < RiskLevel.CRITICAL }.map { it.category }.toSet()
        if (categories.isEmpty()) return
        if (assessment.level >= RiskLevel.CRITICAL) return
        val shellCommand = CommandPrefixes.offered(request.operation)
        if (scope == RememberScope.THIS_COMMAND_FOREVER) {
            if (shellCommand == null || request.requires == app.umbra.core.model.action.Privilege.ROOT) return
            saveCommandRule(AllowRule(
                id = "prefix-${clock.nowMs()}-${counter.incrementAndGet()}", sessionId = "*", categories = categories,
                operation = "shell", commandPrefix = shellCommand,
                workingDirectory = (request.operation as Operation.Shell).cwd, contextDirectory = ctx.cwd, literalCommand = true,
            ))
            return
        }
        if (scope == RememberScope.THIS_COMMAND_THIS_SESSION && shellCommand == null) return
        if (scope == RememberScope.THIS_APP_THIS_SESSION && ctx.foregroundPackage == null) return
        val rule = AllowRule(
            id = "rule-${counter.incrementAndGet()}",
            sessionId = sessionId,
            categories = categories,
            maxLevel = RiskLevel.HIGH,
            app = if (scope == RememberScope.THIS_APP_THIS_SESSION) ctx.foregroundPackage else null,
            operation = if (scope == RememberScope.THIS_APP_THIS_SESSION || scope == RememberScope.THIS_COMMAND_THIS_SESSION) request.operation.name else null,
            commandPrefix = if (scope == RememberScope.THIS_COMMAND_THIS_SESSION) shellCommand else null,
        )
        rulesBySession.getOrPut(sessionId) { CopyOnWriteArrayList() }.add(rule)
    }

    private fun toApproval(request: ActionRequest, assessment: RiskAssessment, ctx: ActionContext, source: ApprovalSource = ApprovalSource.HOST_TOOL) = ApprovalRequest(
        id = "apr-${request.id}-${clock.nowMs()}",
        sessionId = request.sessionId ?: "-",
        source = source,
        title = "Approve ${request.operation.name}?",
        description = ctx.actionDescription?.take(1_000) ?: describe(request),
        assessment = assessment,
        toolName = request.operation.name,
        operation = request.operation.name,
        foregroundPackage = ctx.foregroundPackage,
        details = buildMap {
            request.rationale?.let { put("why", it) }
            ctx.foregroundPackage?.let { put("app", it) }
            ctx.targetNode?.label?.let { put("element", it) }
            put("risk", assessment.describe())
            ctx.outboundText?.let { put("outbound_payload", it) }
            if (RiskCategory.FINANCIAL_TRANSACTION in assessment.categories) {
                putAll(CheckoutGuard.details(ctx))
                put("checkout_resource", if (request.target.environment == app.umbra.core.model.action.Environment.LINUX_GUI) "gui:0" else "display:${request.target.display ?: 0}")
            }
            (request.operation as? Operation.Shell)?.let { shell ->
                put("command", shell.command)
                (shell.cwd ?: ctx.cwd)?.let { put("cwd", it) }
                shell.stdin?.let { put("stdin", it) }
                if (shell.env.isNotEmpty()) put("environment_keys", shell.env.keys.sorted().joinToString(", "))
            }
            CommandPrefixes.offered(request.operation)?.let { put("command_prefix", it) }
        },
        createdAtMs = clock.nowMs(),
        timeoutMs = approvalTimeoutMs,
        rememberOptions = if (assessment.level >= RiskLevel.CRITICAL) emptyList() else when {
            CommandPrefixes.offered(request.operation) != null && request.requires != app.umbra.core.model.action.Privilege.ROOT -> listOf(RememberScope.THIS_COMMAND_FOREVER)
            request.operation !is Operation.Shell && ctx.foregroundPackage != null -> listOf(RememberScope.THIS_APP_THIS_SESSION, RememberScope.THIS_CATEGORY_THIS_SESSION)
            else -> emptyList()
        },
    )

    /** One-line, secret-free description of what will run. */
    internal fun describe(request: ActionRequest): String = when (val op = request.operation) {
        is Operation.Shell -> (if (op.asRoot || request.requires == app.umbra.core.model.action.Privilege.ROOT) "As root: " else "Run: ") + op.command.take(300)
        is Operation.LaunchCommand -> "Launch: ${op.command} ${op.args.joinToString(" ")}".take(300)
        is Operation.TypeText -> if (op.secret) "Type a secret value" else "Type \"${op.text.take(60)}\""
        is Operation.Packages -> "Packages: ${op.action.name.lowercase()} ${op.packageName ?: op.apkPath ?: ""}".trim()
        is Operation.File -> "File: ${op.action.name.lowercase()} ${op.path}"
        is Operation.Process -> "Process: ${op.action.name.lowercase()} ${op.pid ?: op.name_ ?: ""}".trim()
        else -> op.name
    }
}
