package app.umbra.core.orchestrator

import app.umbra.runtime.omp.UiRequest
import app.umbra.runtime.omp.UiResponse
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Questions the agent puts to the user while it works (`ask`, `select`, `input`, `confirm`, `editor`).
 * The UI shows [pending] and calls [answer]; the agent waits. A question nobody answers within its own
 * timeout (if it has one) or when the session ends resolves to "cancelled", which omp treats as no answer.
 */
class QuestionBroker : UserQuestions {
    data class Pending(val sessionId: String, val request: UiRequest, val askedAtMs: Long)

    private val waiters = ConcurrentHashMap<String, CompletableDeferred<UiResponse>>()
    private val mutablePending = MutableStateFlow<List<Pending>>(emptyList())

    /** Oldest first. */
    val pending: StateFlow<List<Pending>> = mutablePending.asStateFlow()

    override suspend fun ask(sessionId: String, request: UiRequest): UiResponse {
        val deferred = CompletableDeferred<UiResponse>()
        waiters[request.id] = deferred
        val entry = Pending(sessionId, request, System.currentTimeMillis())
        mutablePending.update { it + entry }
        try {
            val timeout = when (request) {
                is UiRequest.Select -> request.timeoutMs
                is UiRequest.Confirm -> request.timeoutMs
                is UiRequest.Input -> request.timeoutMs
                is UiRequest.Ask -> request.timeoutMs
                is UiRequest.Editor -> null
            }
            return if (timeout != null && timeout > 0) withTimeoutOrNull(timeout) { deferred.await() } ?: UiResponse.Cancelled(timedOut = true) else deferred.await()
        } finally {
            waiters.remove(request.id)
            mutablePending.update { list -> list.filterNot { it.request.id == request.id } }
        }
    }

    /** Returns false if the question is gone (already answered, timed out, or the session ended). */
    fun answer(requestId: String, response: UiResponse): Boolean = waiters[requestId]?.complete(response) ?: false

    fun cancelSession(sessionId: String) {
        mutablePending.value.filter { it.sessionId == sessionId }.forEach { waiters[it.request.id]?.complete(UiResponse.Cancelled()) }
    }
}
