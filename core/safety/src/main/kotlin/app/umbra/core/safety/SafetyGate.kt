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

    suspend fun check(request: ActionRequest, ctx: ActionContext = ActionContext(), source: ApprovalSource = ApprovalSource.HOST_TOOL): Outcome {
        val assessment = classifier.assess(request, ctx)
        val sessionId = request.sessionId ?: "-"
        val decision = policy().decide(request, assessment, rulesFor(sessionId), ctx.foregroundPackage)
        return when (decision) {
            PolicyDecision.Allow -> Outcome.Proceed(assessment, "allowed")
            is PolicyDecision.AllowByRule -> {
                broker.recordAutomatic(toApproval(request, assessment, ctx, source), allowed = true)
                Outcome.Proceed(assessment, "allowed by remembered approval ${decision.rule.id}")
            }
            is PolicyDecision.Deny -> {
                broker.recordAutomatic(toApproval(request, assessment, ctx, source), allowed = false)
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
            is PolicyDecision.Ask -> ask(request, assessment, ctx, decision, source)
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
                answer.remember?.let { remember(request, assessment, ctx, it) }
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
        val shellCommand = (request.operation as? Operation.Shell)?.command?.trim()?.substringBefore(' ')
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
        description = describe(request),
        assessment = assessment,
        toolName = request.operation.name,
        operation = request.operation.name,
        foregroundPackage = ctx.foregroundPackage,
        details = buildMap {
            request.rationale?.let { put("why", it) }
            ctx.foregroundPackage?.let { put("app", it) }
            ctx.targetNode?.label?.let { put("element", it) }
            put("risk", assessment.describe())
        },
        createdAtMs = clock.nowMs(),
        timeoutMs = approvalTimeoutMs,
        rememberOptions = listOf(
            RememberScope.THIS_APP_THIS_SESSION,
            RememberScope.THIS_COMMAND_THIS_SESSION,
            RememberScope.THIS_CATEGORY_THIS_SESSION,
        ),
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
