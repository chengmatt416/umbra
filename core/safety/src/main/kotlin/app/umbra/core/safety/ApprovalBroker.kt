package app.umbra.core.safety

import app.umbra.core.model.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
enum class ApprovalSource { HOST_TOOL, OMP }

/** What a "remember" approval covers. */
@Serializable
enum class RememberScope {
    THIS_COMMAND_FOREVER,
    /** The same risk categories, in the same foreground app, for the rest of the session. */
    THIS_APP_THIS_SESSION,

    /** The same risk categories for shell commands with the same first word, this session. */
    THIS_COMMAND_THIS_SESSION,

    /** The same risk categories, anywhere, this session. */
    THIS_CATEGORY_THIS_SESSION,
}

@Serializable
data class ApprovalRequest(
    val id: String,
    val sessionId: String,
    val source: ApprovalSource,
    val title: String,
    /** Plain-language description of exactly what will happen. */
    val description: String,
    val assessment: RiskAssessment = RiskAssessment.NONE,
    val toolName: String? = null,
    val operation: String? = null,
    val foregroundPackage: String? = null,
    val details: Map<String, String> = emptyMap(),
    val createdAtMs: Long,
    val timeoutMs: Long,
    val rememberOptions: List<RememberScope> = emptyList(),
)

sealed interface ApprovalDecision {
    data class Approved(val remember: RememberScope? = null) : ApprovalDecision
    data class Denied(val reason: String? = null) : ApprovalDecision
    data object TimedOut : ApprovalDecision
    data object Cancelled : ApprovalDecision

    val isApproved: Boolean get() = this is Approved
}

@Serializable
data class ApprovalLedgerEntry(
    val timestampMs: Long,
    val requestId: String,
    val sessionId: String,
    /** requested | approved | denied | timed_out | cancelled | auto_allowed | auto_denied */
    val kind: String,
    val source: ApprovalSource,
    val summary: String,
    val categories: List<RiskCategory> = emptyList(),
    val level: RiskLevel = RiskLevel.NONE,
    val remember: RememberScope? = null,
)

/** Append-only history of approval requests and decisions (spec §32 "review approvals"). */
fun interface ApprovalLedger {
    suspend fun record(entry: ApprovalLedgerEntry)

    companion object {
        val None = ApprovalLedger { }
    }
}

/**
 * Hands approval requests to whoever shows the UI (activity, overlay, notification) and suspends the
 * caller until somebody decides, the timeout passes (fail closed), or the session is cancelled.
 */
class ApprovalBroker(
    private val clock: Clock = Clock.System,
    private val ledger: ApprovalLedger = ApprovalLedger.None,
) {
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<ApprovalDecision>>()
    private val requests = ConcurrentHashMap<String, ApprovalRequest>()
    private val sessionTimeouts = ConcurrentHashMap<String, Long>()
    fun setSessionTimeout(sessionId: String, timeoutMs: Long?) {
        if (timeoutMs == null) sessionTimeouts.remove(sessionId)
        else { require(timeoutMs in 1..600_000L); sessionTimeouts[sessionId] = timeoutMs }
    }
    private val _pending = MutableStateFlow<List<ApprovalRequest>>(emptyList())

    /** Oldest first. Observed by the approval UI, the overlay and the notification. */
    val pending: StateFlow<List<ApprovalRequest>> = _pending.asStateFlow()

    suspend fun request(input: ApprovalRequest): ApprovalDecision {
        val request = sessionTimeouts[input.sessionId]?.let { input.copy(timeoutMs = minOf(input.timeoutMs, it)) } ?: input
        require(request.timeoutMs > 0)
        val deferred = CompletableDeferred<ApprovalDecision>()
        waiters[request.id] = deferred
        requests[request.id] = request
        _pending.update { it + request }
        record(request, "requested", null)
        val decision = try {
            val remaining = request.timeoutMs - (clock.nowMs() - request.createdAtMs).coerceAtLeast(0)
            if (remaining <= 0) ApprovalDecision.TimedOut
            else withTimeoutOrNull(remaining) { deferred.await() } ?: ApprovalDecision.TimedOut
        } catch (e: CancellationException) {
            // The caller was cancelled (session stopped); still leave a trace in the ledger.
            withContext(NonCancellable) { record(request, "cancelled", null) }
            throw e
        } finally {
            waiters.remove(request.id)
            requests.remove(request.id)
            _pending.update { list -> list.filterNot { it.id == request.id } }
        }
        record(request, kindOf(decision), (decision as? ApprovalDecision.Approved)?.remember)
        return decision
    }

    /** Called by the UI. Returns false if the request is gone (already decided, timed out or cancelled). */
    fun resolve(id: String, decision: ApprovalDecision): Boolean {
        val request = requests[id] ?: return false
        if (clock.nowMs() - request.createdAtMs >= request.timeoutMs) {
            waiters[id]?.complete(ApprovalDecision.TimedOut)
            return false
        }
        if (decision is ApprovalDecision.Approved && decision.remember != null && decision.remember !in request.rememberOptions) return false
        return waiters[id]?.complete(decision) ?: false
    }

    fun cancelSession(sessionId: String) {
        requests.values.filter { it.sessionId == sessionId }.forEach { waiters[it.id]?.complete(ApprovalDecision.Cancelled) }
    }

    fun cancelAll() {
        waiters.values.forEach { it.complete(ApprovalDecision.Cancelled) }
    }

    /** Records an automatic decision (rule or policy) so the ledger shows everything that was allowed. */
    suspend fun recordAutomatic(request: ApprovalRequest, allowed: Boolean) {
        record(request, if (allowed) "auto_allowed" else "auto_denied", null)
    }

    private suspend fun record(request: ApprovalRequest, kind: String, remember: RememberScope?) {
        ledger.record(
            ApprovalLedgerEntry(
                timestampMs = clock.nowMs(),
                requestId = request.id,
                sessionId = request.sessionId,
                kind = kind,
                source = request.source,
                summary = request.description,
                categories = request.assessment.categories.toList(),
                level = request.assessment.level,
                remember = remember,
            ),
        )
    }

    private fun kindOf(decision: ApprovalDecision) = when (decision) {
        is ApprovalDecision.Approved -> "approved"
        is ApprovalDecision.Denied -> "denied"
        ApprovalDecision.TimedOut -> "timed_out"
        ApprovalDecision.Cancelled -> "cancelled"
    }
}
