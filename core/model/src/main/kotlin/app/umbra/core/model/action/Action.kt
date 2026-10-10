package app.umbra.core.model.action

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** What the executor may try, in order, when the preferred strategy fails (spec §17). */
@Serializable
enum class Fallback {
    @SerialName("alternate_tool") ALTERNATE_TOOL,
    @SerialName("visual") VISUAL,
    @SerialName("shell") SHELL,
    @SerialName("privileged") PRIVILEGED,
}

@Serializable
enum class OnExhausted {
    /** Return the failure to OMP so it can re-plan. */
    @SerialName("replan") REPLAN,
    @SerialName("ask_user") ASK_USER,
    @SerialName("abort") ABORT,
}

/** Retry/fallback behaviour (spec §16 "failure behavior", §17). */
@Serializable
data class FailurePolicy(
    val retries: Int = 1,
    val retryDelayMs: Long = 250,
    val fallbacks: List<Fallback> = listOf(Fallback.ALTERNATE_TOOL, Fallback.VISUAL, Fallback.SHELL),
    val onExhausted: OnExhausted = OnExhausted.REPLAN,
)

/**
 * The common action abstraction (spec §16). Every host tool call is normalised to this before
 * safety classification, leasing, execution and verification.
 */
@Serializable
data class ActionRequest(
    /** Correlation id; equals the OMP tool-call id when the request came from a host tool call. */
    val id: String,
    val operation: Operation,
    val target: Target = Target(),
    val requires: Privilege = Privilege.NONE,
    val verify: Verification = Verification(),
    val timeoutMs: Long = 10_000,
    val failure: FailurePolicy = FailurePolicy(),
    /** Why the agent wants this; shown in approval prompts. */
    val rationale: String? = null,
    val sessionId: String? = null,
)

@Serializable
enum class ActionStatus {
    @SerialName("ok") OK,
    @SerialName("failed") FAILED,
    @SerialName("denied") DENIED,
    @SerialName("timeout") TIMEOUT,
    @SerialName("needs_user") NEEDS_USER,
    @SerialName("unsupported") UNSUPPORTED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
enum class ErrorCode {
    @SerialName("not_found") NOT_FOUND,
    @SerialName("ambiguous") AMBIGUOUS,
    @SerialName("stale_element") STALE_ELEMENT,
    @SerialName("not_actionable") NOT_ACTIONABLE,
    @SerialName("timeout") TIMEOUT,
    @SerialName("permission_denied") PERMISSION_DENIED,
    @SerialName("unsupported") UNSUPPORTED,
    @SerialName("backend_unavailable") BACKEND_UNAVAILABLE,
    @SerialName("verification_failed") VERIFICATION_FAILED,
    @SerialName("denied_by_user") DENIED_BY_USER,
    @SerialName("denied_by_policy") DENIED_BY_POLICY,
    @SerialName("lease_unavailable") LEASE_UNAVAILABLE,
    @SerialName("invalid_arguments") INVALID_ARGUMENTS,
    /** The command ran but exited non-zero. Its output is still returned. */
    @SerialName("command_failed") COMMAND_FAILED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("internal") INTERNAL,
}

@Serializable
data class ActionError(
    val code: ErrorCode,
    val message: String,
    /** True when retrying (possibly after re-observing) has a realistic chance of working. */
    val retryable: Boolean = false,
)

/** One strategy tried while executing a request (spec §17 recovery trail). */
@Serializable
data class Attempt(
    val strategy: String,
    val backend: String,
    val status: ActionStatus,
    val detail: String? = null,
    val durationMs: Long = 0,
)

/** Structured result consumed by OMP (as tool-result text + details) and by Laya/the router. */
@Serializable
data class ActionResult(
    val id: String,
    val status: ActionStatus,
    /** Compact, LLM-readable one-paragraph summary. Never contains secrets. */
    val summary: String,
    /** Operation-specific payload (tree, shell output, display list, ...). */
    val data: JsonElement? = null,
    val verification: VerificationResult? = null,
    val attempts: List<Attempt> = emptyList(),
    val error: ActionError? = null,
    val durationMs: Long = 0,
    /** Which backend finally executed it (accessibility, ui_automation, root_shell, ...). */
    val backend: String? = null,
) {
    val isOk: Boolean get() = status == ActionStatus.OK

    companion object {
        fun failure(
            id: String,
            code: ErrorCode,
            message: String,
            status: ActionStatus = ActionStatus.FAILED,
            retryable: Boolean = false,
            attempts: List<Attempt> = emptyList(),
            durationMs: Long = 0,
        ) = ActionResult(
            id = id,
            status = status,
            summary = message,
            error = ActionError(code, message, retryable),
            attempts = attempts,
            durationMs = durationMs,
        )
    }
}
