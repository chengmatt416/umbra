package app.umbra.tools

import app.umbra.core.decision.DecisionRouter
import app.umbra.core.model.Clock
import app.umbra.core.model.action.ActionError
import app.umbra.core.model.action.ActionRequest
import app.umbra.core.model.action.ActionStatus
import app.umbra.core.model.action.Attempt
import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.action.Environment
import app.umbra.core.model.action.ActionResult
import app.umbra.core.model.action.FailurePolicy
import app.umbra.core.model.observation.UiSnapshot
import app.umbra.core.safety.ActionContext
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.BackendRegistry
import app.umbra.platform.api.UiBackend
import app.umbra.platform.logs.LogMonitor
import app.umbra.platform.vision.ImageCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

/** An image the agent should see (a screenshot), already encoded and small enough for the RPC frame. */
class ImagePayload(val bytes: ByteArray, val mime: String, val description: String? = null) {
    override fun toString() = "ImagePayload($mime, ${bytes.size}B${description?.let { ", $it" } ?: ""})"
}

/** What the pipeline returns: the structured result plus images for the model. */
class ExecutionResult(val result: ActionResult, val images: List<ImagePayload> = emptyList())

/** Supplies the per-session part of the risk context: workspace roots, protected paths, trusted packages. */
fun interface ContextProvider {
    fun base(sessionId: String?): ActionContext

    companion object {
        val Default = ContextProvider { ActionContext() }
    }
}

data class PipelineConfig(
    /** How long a UI action waits for another session to give up a display before failing. */
    val leaseWaitMs: Long = 5_000,
    /** How long `launch_app` waits for the app to reach the foreground; a phone that is busy or hot can take many seconds to start a large app. */
    val launchForegroundTimeoutMs: Long = 20_000,
    /** After a launch reaches the foreground, how long to wait for the app to draw something. */
    val launchContentTimeoutMs: Long = 8_000,
    /** Pause between a scroll and the next look, so the list has stopped moving. */
    val scrollSettleMs: Long = 350,
    /** Hard limit on image payload size; omp's RPC frames are 1 MiB, base64 adds a third. */
    val maxImageBytes: Int = 600_000,
    val maxScreenshotEdge: Int = 1280,
    val jpegQuality: Int = 78,
    /** Text rows of an observation shown to the agent. */
    val defaultMaxNodes: Int = 250,
    val gridStepPx: Int = 200,
    val contextProvider: ContextProvider = ContextProvider.Default,
    /**
     * Turns a path as the agent knows it from the Linux environment (`/workspace/...`) into the phone's path for the
     * same file, for tools that act on the phone's own file system: installing an APK built in Linux, copying a
     * file the agent wrote. Paths outside the shared workspace come back unchanged.
     */
    val pathMapper: (String) -> String = { it },
)

/** Everything the executors share. */
internal class PipelineEnv(
    val backends: BackendRegistry,
    val clock: Clock,
    val codec: ImageCodec?,
    val logs: LogMonitor?,
    val decisions: DecisionRouter?,
    val verifier: Verifier,
    val leases: DisplayLeaseManager,
    val config: PipelineConfig,
    val targeting: ElementTargeting,
)

/** One executor's result, before verification and rendering. */
internal class OpOutcome(
    val status: ActionStatus,
    val summary: String,
    val data: JsonElement? = null,
    val images: List<ImagePayload> = emptyList(),
    val error: ActionError? = null,
    val backend: String? = null,
) {
    val isOk: Boolean get() = status == ActionStatus.OK

    companion object {
        fun ok(summary: String, data: JsonElement? = null, backend: String? = null, images: List<ImagePayload> = emptyList()) =
            OpOutcome(ActionStatus.OK, summary, data, images, backend = backend)

        fun fail(code: ErrorCode, message: String, retryable: Boolean = false, status: ActionStatus = ActionStatus.FAILED, data: JsonElement? = null, images: List<ImagePayload> = emptyList()) =
            OpOutcome(status, message, data, images, ActionError(code, message, retryable))

        fun unsupported(message: String) = fail(ErrorCode.UNSUPPORTED, message, status = ActionStatus.UNSUPPORTED)

        fun unavailable(message: String) = fail(ErrorCode.BACKEND_UNAVAILABLE, message)
    }
}

/** Per-call state handed to the executors. */
internal class OpContext(
    val request: ActionRequest,
    val state: SessionToolState,
    val displayId: Int?,
    val snapshot: UiSnapshot?,
    /** The UI backend [snapshot] came from. Node actions only work on this one: it owns the live nodes. */
    val primaryUi: UiBackend?,
    /** Why there is no [snapshot]: no UI backend, or what each one reported. Null when there is one. */
    val snapshotError: String?,
    val resolved: Resolved,
    val startedAtMs: Long,
    val attempts: MutableList<Attempt>,
    /** `strategy@backend` keys already tried whose effect could not be verified; they are skipped on a re-run. */
    val excluded: MutableSet<String> = mutableSetOf(),
) {
    val environment: Environment get() = request.target.environment
    val policy: FailurePolicy get() = request.failure
}

/** One way of carrying out an operation, tried in order until one works. */
internal class Strategy(val name: String, val backend: String, val run: suspend () -> StrategyOutcome)

internal sealed interface StrategyOutcome {
    data class Done(val detail: String? = null) : StrategyOutcome

    /** The strategy ran and the target did not accept it (a node refused CLICK): try the next one. */
    data class Refused(val reason: String) : StrategyOutcome
}

internal class StrategyRun(val succeeded: Boolean, val backend: String?, val detail: String?, val error: ActionError?)

/**
 * Tries [strategies] in order, retrying transient failures of one strategy before moving on, and
 * records every try as an [Attempt] (spec §17 "recovery trail"). A stale element ends the run at
 * once: no strategy can fix a target that moved, only a fresh look can.
 */
internal suspend fun runStrategies(env: PipelineEnv, c: OpContext, strategies: List<Strategy>): StrategyRun {
    var lastError: ActionError? = null
    for (s in strategies) {
        if ("${s.name}@${s.backend}" in c.excluded) continue
        var tries = 0
        while (true) {
            val t0 = env.clock.nowMs()
            val failure: ActionError? = try {
                when (val r = s.run()) {
                    is StrategyOutcome.Done -> {
                        c.attempts += Attempt(s.name, s.backend, ActionStatus.OK, r.detail, env.clock.nowMs() - t0)
                        return StrategyRun(true, s.backend, r.detail, null)
                    }
                    is StrategyOutcome.Refused -> {
                        c.attempts += Attempt(s.name, s.backend, ActionStatus.FAILED, r.reason, env.clock.nowMs() - t0)
                        ActionError(ErrorCode.NOT_ACTIONABLE, r.reason, retryable = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendException) {
                c.attempts += Attempt(s.name, s.backend, ActionStatus.FAILED, "${e.code.name.lowercase()}: ${e.message}", env.clock.nowMs() - t0)
                ActionError(e.code, e.message ?: e.code.name, e.retryable)
            } catch (e: Exception) {
                c.attempts += Attempt(s.name, s.backend, ActionStatus.FAILED, "${e::class.simpleName}: ${e.message}", env.clock.nowMs() - t0)
                ActionError(ErrorCode.INTERNAL, "${s.name} failed: ${e.message ?: e::class.simpleName}", retryable = false)
            }
            lastError = failure
            if (failure!!.code == ErrorCode.STALE_ELEMENT) return StrategyRun(false, s.backend, null, failure)
            if (failure.retryable && tries < c.policy.retries) {
                tries++
                delay(c.policy.retryDelayMs)
                continue
            }
            break
        }
    }
    return StrategyRun(false, null, null, lastError ?: ActionError(ErrorCode.UNSUPPORTED, "No strategy is available for this operation on this device."))
}

/** Runs [block] and returns null on failure, but never swallows cancellation (unlike `runCatching`). */
internal suspend inline fun <T> tryOrNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
