package app.umbra.tools

import app.umbra.core.decision.DecisionRouter
import app.umbra.core.model.Clock
import app.umbra.core.model.action.ActionRequest
import app.umbra.core.model.action.ActionResult
import app.umbra.core.model.action.ActionStatus
import app.umbra.core.model.action.Attempt
import app.umbra.core.model.action.Environment
import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.action.Expectation
import app.umbra.core.model.action.Operation
import app.umbra.core.model.action.Privilege
import app.umbra.core.model.action.VerificationResult
import app.umbra.core.model.capability.CapabilitySnapshot
import app.umbra.core.model.observation.UiSnapshot
import app.umbra.core.safety.ActionContext
import app.umbra.core.safety.SafetyGate
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.BackendRegistry
import app.umbra.platform.api.SnapshotOptions
import app.umbra.platform.api.UiBackend
import app.umbra.platform.logs.ActionJournal
import app.umbra.platform.logs.LogMonitor
import app.umbra.platform.vision.ImageCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * The single path every operation takes (spec §16, §17): normalise, look at the screen, resolve the
 * target, ask the safety gate, take the display lease, run the best strategy with fallbacks, verify
 * the effect, and return a structured result. It never throws for a failed action: whatever went
 * wrong comes back as an [ActionResult] the agent can reason about.
 */
class ActionPipeline(
    private val backends: BackendRegistry,
    private val safety: SafetyGate,
    decisions: DecisionRouter? = null,
    val leases: DisplayLeaseManager = DisplayLeaseManager(),
    val journal: ActionJournal = ActionJournal(),
    logs: LogMonitor? = null,
    private val capabilities: () -> CapabilitySnapshot = { CapabilitySnapshot() },
    codec: ImageCodec? = null,
    private val clock: Clock = Clock.System,
    private val config: PipelineConfig = PipelineConfig(),
    /**
     * Asked before any side-effecting operation: a non-null answer fences it (emergency input lockdown, or the user
     * holding the screen the operation would act on). This is the writer-side check of INV-006.
     */
    private val inputGate: suspend (request: ActionRequest, displayId: Int?, touchesDisplay: Boolean) -> String? = { _, _, _ -> null },
) {
    private val verifier = Verifier(backends, logs, clock, codec)
    private val env = PipelineEnv(backends, clock, codec, logs, decisions, verifier, leases, config, ElementTargeting(decisions))
    private val ui = UiExecutor(env)
    private val observation = ObservationExecutor(env)
    private val system = SystemExecutor(env)
    private val displays = DisplayExecutor(env)
    private val gui = GuiExecutor(env)
    private val screen = ScreenGuard(env)

    suspend fun execute(request: ActionRequest, state: SessionToolState = SessionToolState(request.sessionId)): ExecutionResult {
        val started = clock.nowMs()
        val attempts = mutableListOf<Attempt>()
        val op = request.operation
        val displayId = effectiveDisplay(request, state)
        journal.begin(request.id, request.sessionId, op.name, displayId, request.target.app)
        try {
            if (request.target.environment == Environment.LINUX_CLI) {
                return fail(request, started, attempts, OpOutcome.unsupported("Command-line Linux work runs through the agent's own bash tool, not through this one."))
            }
            checkPrivilege(request)?.let { return fail(request, started, attempts, it) }
            if (OperationTraits.hasSideEffects(op)) {
                inputGate(request, displayId, OperationTraits.mutatesUi(op))?.let { why ->
                    return fail(request, started, attempts, OpOutcome.fail(ErrorCode.LEASE_UNAVAILABLE, why, status = ActionStatus.NEEDS_USER))
                }
            }
            if (request.target.environment == Environment.ANDROID && displayId == 0 && touchesScreen(op)) {
                screen.ensureUsable()?.let { problem ->
                    return fail(request, started, attempts, OpOutcome.fail(ErrorCode.NOT_ACTIONABLE, problem, status = ActionStatus.NEEDS_USER))
                }
            }

            var round = 0
            var approvedSignature: String? = null
            while (true) {
                // ---- look and resolve ----
                val look = if (needsSnapshot(op)) takeSnapshot(request.target.environment, displayId) else Look(null, null, null)
                val snapshot = look.snapshot
                val primary = look.backend
                val resolved = if (OperationTraits.targetsUi(op)) {
                    env.targeting.resolve(request.target, op, snapshot, state, displayId, request.rationale, look.error)
                } else {
                    Resolved.Unspecified
                }
                if (resolved is Resolved.Failed) {
                    val e = resolved.error
                    if ((e.code == ErrorCode.NOT_FOUND || e.code == ErrorCode.STALE_ELEMENT) && e.retryable && round < request.failure.retries) {
                        round++
                        delay(request.failure.retryDelayMs)
                        continue
                    }
                    return fail(request, started, attempts, OpOutcome(ActionStatus.FAILED, e.message, error = e))
                }

                // ---- safety ----
                val context = buildContext(request, snapshot, resolved, displayId)
                if (OperationTraits.mutatesUi(op) && context.foregroundPackage != request.target.app) {
                    inputGate(request.copy(target = request.target.copy(app = context.foregroundPackage)), displayId, true)?.let { why ->
                        return fail(request, started, attempts, OpOutcome.fail(ErrorCode.LEASE_UNAVAILABLE, why, status = ActionStatus.NEEDS_USER))
                    }
                }
                val signature = approvalSignature(request, resolved)
                if (signature != approvedSignature) {
                    when (val gate = safety.check(request, context)) {
                        is SafetyGate.Outcome.Blocked -> return ExecutionResult(
                            gate.result.copy(attempts = attempts, durationMs = clock.nowMs() - started),
                        )
                        is SafetyGate.Outcome.Proceed -> approvedSignature = signature
                    }
                }

                // ---- lease ----
                if (OperationTraits.mutatesUi(op) && request.target.environment != Environment.LINUX_GUI) {
                    val session = request.sessionId ?: "-"
                    try {
                        leases.acquire(session, displayId ?: 0, config.leaseWaitMs)
                    } catch (e: LeaseUnavailableException) {
                        return fail(request, started, attempts, OpOutcome.fail(ErrorCode.LEASE_UNAVAILABLE, e.message ?: "display is in use", retryable = true))
                    }
                }

                // ---- baseline, execute, verify ----
                // Approval and display admission can wait: the user may have taken the app back meanwhile.
                if (OperationTraits.mutatesUi(op)) {
                    inputGate(request.copy(target = request.target.copy(app = context.foregroundPackage)), displayId, true)?.let { why ->
                        return fail(request, started, attempts, OpOutcome.fail(ErrorCode.LEASE_UNAVAILABLE, why, status = ActionStatus.NEEDS_USER))
                    }
                }
                val spec = request.verify
                val baseline = if (spec.expect is Expectation.None || op is Operation.WaitFor || op is Operation.Verify) null else baselineFor(spec.expect, request.target.environment, displayId, snapshot)
                val c = OpContext(request, state, displayId, snapshot, primary, look.error, resolved, started, attempts)
                var outcome = run(c)
                var verification: VerificationResult? = null

                if (outcome.isOk && spec.expect !is Expectation.None && op !is Operation.WaitFor && op !is Operation.Verify) {
                    val vctx = VerifyContext(request.target.environment, displayId, baseline, started)
                    verification = verifier.verify(spec, vctx)
                    var reruns = 0
                    // The strategy "worked" but the effect is missing: for an operation that is safe to repeat,
                    // try the next strategy (a gesture where the node click did nothing). Only when the first
                    // try changed nothing at all: if the screen did change, repeating a tap at the same place
                    // would land on whatever is there now.
                    while (!verification!!.passed && spec.required && OperationTraits.idempotent(op) && reruns < MAX_STRATEGY_RERUNS) {
                        val last = attempts.lastOrNull { it.status == ActionStatus.OK } ?: break
                        val untouched = baseline != null && !verifier.check(Expectation.UiChanged, vctx).passed
                        if (!untouched) {
                            val note = if (baseline == null) "could not tell whether the screen changed, so it was not repeated" else "the screen did change, so the action had some effect and was not repeated"
                            verification = verification.copy(
                                summary = verification.summary + "; " + note,
                                checks = verification.checks + app.umbra.core.model.action.CheckResult("repeat", false, note),
                            )
                            break
                        }
                        c.excluded += "${last.strategy}@${last.backend}"
                        val again = run(c)
                        reruns++
                        if (!again.isOk) { break }
                        outcome = again
                        verification = verifier.verify(spec, vctx)
                    }
                }

                if (outcome.error?.code == ErrorCode.STALE_ELEMENT && round < request.failure.retries) {
                    round++
                    attempts.clear()
                    delay(request.failure.retryDelayMs)
                    continue
                }
                return finalResult(request, started, attempts, outcome, verification, spec.required)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendException) {
            return fail(request, started, attempts, OpOutcome.fail(e.code, e.message ?: "backend failed", e.retryable))
        } catch (e: Exception) {
            return fail(request, started, attempts, OpOutcome.fail(ErrorCode.INTERNAL, "Internal error while running ${op.name}: ${e.message ?: e::class.simpleName}"))
        } finally {
            journal.end(request.id)
        }
    }

    // ---- stages ----

    private fun effectiveDisplay(request: ActionRequest, state: SessionToolState): Int? = when {
        request.target.display != null -> request.target.display
        request.target.environment == Environment.ANDROID && !OperationTraits.isSystemOnly(request.operation) -> state.activeDisplay
        else -> null
    }

    private fun needsSnapshot(op: Operation): Boolean =
        OperationTraits.targetsUi(op) || op is Operation.Observe || op is Operation.Screenshot || op is Operation.Find || op is Operation.Scroll

    private class Look(val snapshot: UiSnapshot?, val backend: UiBackend?, val error: String?)

    /** Reads the screen with the first UI backend that works, and keeps the reasons the others did not. */
    private suspend fun takeSnapshot(environment: Environment, displayId: Int?): Look {
        val backends = backends.ui(environment)
        if (backends.isEmpty()) return Look(null, null, "no UI backend is configured for ${environment.name.lowercase()}")
        val problems = mutableListOf<String>()
        for (ui in backends) {
            try {
                if (!ui.isAvailable()) { problems += "${ui.name} is not connected"; continue }
                return Look(ui.snapshot(SnapshotOptions(displayId = displayId)), ui, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problems += "${ui.name}: ${e.message ?: e::class.simpleName}"
            }
        }
        return Look(null, null, problems.joinToString("; "))
    }

    private suspend fun baselineFor(expect: Expectation, environment: Environment, displayId: Int?, snapshot: UiSnapshot?): Baseline? {
        val needsThumb = containsScreenChange(expect)
        if (snapshot != null && !needsThumb) {
            val (sig, count) = uiSignature(snapshot, displayId)
            return Baseline(sig, count)
        }
        return verifier.baseline(expect, environment, displayId)
    }

    private fun containsScreenChange(e: Expectation): Boolean = when (e) {
        is Expectation.ScreenChanged -> true
        is Expectation.AllOf -> e.items.any(::containsScreenChange)
        is Expectation.AnyOf -> e.items.any(::containsScreenChange)
        is Expectation.Not -> containsScreenChange(e.item)
        else -> false
    }

    private fun checkPrivilege(request: ActionRequest): OpOutcome? {
        val op = request.operation
        val needs = if (op is Operation.Shell && op.asRoot) Privilege.ROOT else request.requires
        if (needs == Privilege.NONE) return null
        val snapshot = capabilities()
        if (snapshot.capabilities.isEmpty()) return null // not probed yet: backend availability decides
        if (snapshot.canProvide(needs)) return null
        val what = when (needs) {
            Privilege.ROOT -> "Root access"
            Privilege.SHELL -> "Shizuku or root (shell access)"
            Privilege.ACCESSIBILITY -> "The accessibility service or UiAutomation"
            Privilege.NONE -> ""
        }
        val fix = snapshot[app.umbra.core.model.capability.CapabilityId.ROOT_PERMISSION]?.userAction.takeIf { needs == Privilege.ROOT }
        return OpOutcome.fail(
            ErrorCode.PERMISSION_DENIED,
            "$what is not available on this device, so ${op.name} cannot run. ${fix ?: "Ask the user to enable it in the app's capability screen."}",
        )
    }

    private fun buildContext(request: ActionRequest, snapshot: UiSnapshot?, resolved: Resolved, displayId: Int?): ActionContext {
        val base = config.contextProvider.base(request.sessionId)
        val window = snapshot?.activeWindow(displayId)
        val trees = snapshot?.windows?.filter { displayId == null || it.displayId == displayId }.orEmpty()
        return base.copy(
            foregroundPackage = window?.packageName ?: request.target.app ?: base.foregroundPackage,
            targetNode = (resolved as? Resolved.Node)?.node,
            screenTexts = trees.flatMap { it.visibleTexts() }.take(80),
            passwordFieldVisible = trees.any { t -> t.nodes.any { it.isPassword && it.isVisible } },
        )
    }

    private fun approvalSignature(request: ActionRequest, resolved: Resolved): String =
        request.operation.name + "|" + ((resolved as? Resolved.Node)?.let { "${it.node.id}:${it.node.label}" } ?: "")

    private suspend fun run(c: OpContext): OpOutcome {
        val request = c.request
        // Waiting and verifying are bounded by their own deadline plus one read of the screen.
        val slack = if (request.operation is Operation.WaitFor || request.operation is Operation.Verify) Verifier.ROUND_GRACE_MS + 5_000L else 4_000L
        return try {
            withTimeout(request.timeoutMs + slack) {
                when (val op = request.operation) {
                    is Operation.Observe, is Operation.Screenshot, is Operation.Find, is Operation.WaitFor, is Operation.Verify -> observation.execute(c)
                    is Operation.Shell, is Operation.Packages, is Operation.SysInfo, is Operation.Logs, is Operation.File, is Operation.Process -> system.execute(c)
                    is Operation.Display -> displays.execute(c, op)
                    is Operation.Window, is Operation.LaunchCommand -> gui.execute(c)
                    else -> ui.execute(c)
                }
            }
        } catch (e: TimeoutCancellationException) {
            OpOutcome.fail(ErrorCode.TIMEOUT, "${request.operation.name} did not finish within ${request.timeoutMs}ms.", retryable = true, status = ActionStatus.TIMEOUT)
        } catch (e: BackendException) {
            OpOutcome.fail(e.code, e.message ?: "backend failed", e.retryable)
        }
    }

    // ---- results ----

    /** Operations that need the main screen to be on and unlocked: reading it, touching it, opening apps on it. */
    private fun touchesScreen(op: Operation): Boolean =
        needsSnapshot(op) || OperationTraits.targetsUi(op) || op is Operation.Screenshot || op is Operation.LaunchApp || op is Operation.SwitchApp

    private fun fail(request: ActionRequest, started: Long, attempts: List<Attempt>, outcome: OpOutcome): ExecutionResult =
        ExecutionResult(
            ActionResult(
                id = request.id,
                status = outcome.status.takeIf { it != ActionStatus.OK } ?: ActionStatus.FAILED,
                summary = outcome.summary,
                data = outcome.data,
                attempts = attempts.toList(),
                error = outcome.error,
                durationMs = clock.nowMs() - started,
                backend = outcome.backend,
            ),
            outcome.images,
        )

    private fun finalResult(
        request: ActionRequest,
        started: Long,
        attempts: List<Attempt>,
        outcome: OpOutcome,
        verification: VerificationResult?,
        verificationRequired: Boolean,
    ): ExecutionResult {
        val tookMs = clock.nowMs() - started
        if (!outcome.isOk) {
            return ExecutionResult(
                ActionResult(request.id, outcome.status, outcome.summary, outcome.data, verification, attempts.toList(), outcome.error, tookMs, outcome.backend),
                outcome.images,
            )
        }
        if (verification != null && !verification.passed && verificationRequired) {
            val via = attempts.filter { it.status == ActionStatus.OK }.joinToString(" then ") { "${it.strategy} via ${it.backend}" }
            val message = "${outcome.summary} (${via.ifEmpty { "done" }}), but the expected effect was not observed: ${verification.summary}"
            return ExecutionResult(
                ActionResult(
                    request.id, ActionStatus.FAILED, message, outcome.data, verification, attempts.toList(),
                    app.umbra.core.model.action.ActionError(ErrorCode.VERIFICATION_FAILED, message, retryable = true), tookMs, outcome.backend,
                ),
                outcome.images,
            )
        }
        return ExecutionResult(
            ActionResult(request.id, ActionStatus.OK, outcome.summary, outcome.data, verification, attempts.toList(), null, tookMs, outcome.backend),
            outcome.images,
        )
    }

    private companion object {
        const val MAX_STRATEGY_RERUNS = 2
    }
}
