package app.umbra.runtime.omp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A question omp puts to the host user (`extension_ui_request`). */
sealed interface UiRequest {
    val id: String

    /** Also how tool approvals arrive: options are `Approve` / `Deny`. */
    data class Select(
        override val id: String,
        val title: String,
        val options: List<String>,
        val optionDescriptions: List<String?> = emptyList(),
        val timeoutMs: Long? = null,
    ) : UiRequest

    data class Confirm(override val id: String, val title: String, val message: String, val timeoutMs: Long? = null) : UiRequest
    data class Input(override val id: String, val title: String, val placeholder: String? = null, val timeoutMs: Long? = null) : UiRequest
    data class Editor(override val id: String, val title: String, val prefill: String? = null) : UiRequest

    data class AskOption(val label: String, val description: String? = null)
    data class AskQuestion(
        val id: String,
        val question: String,
        val header: String?,
        val options: List<AskOption>,
        val multi: Boolean,
        val recommended: Int?,
    )

    data class Ask(override val id: String, val questions: List<AskQuestion>, val timeoutMs: Long? = null) : UiRequest
}

data class AskAnswer(val id: String, val selectedOptions: List<String>, val customInput: String? = null)

/** The host's reply. [Cancelled] resolves select/input to nothing and confirm to false. */
sealed interface UiResponse {
    data class Value(val value: String) : UiResponse
    data class Confirmed(val confirmed: Boolean) : UiResponse
    data class Answers(val answers: List<AskAnswer>) : UiResponse
    data class Cancelled(val timedOut: Boolean = false) : UiResponse
}

/** Answers dialogs. The default fails closed: it cancels everything. */
fun interface UiRequestHandler {
    /** Cancelling the calling coroutine means omp closed the dialog (`cancel` request). */
    suspend fun handle(request: UiRequest): UiResponse

    companion object {
        val DenyAll = UiRequestHandler { UiResponse.Cancelled() }
    }
}

/** Fire-and-forget presentation frames (`notify`, `setStatus`, `open_url`, ...). */
data class UiNotification(val method: String, val payload: JsonObject) {
    val message: String? get() = (payload["message"] as? JsonPrimitive)?.content
    val url: String? get() = (payload["url"] as? JsonPrimitive)?.content
}
