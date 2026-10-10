package app.umbra.runtime.omp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class PromptStatus { COMPLETED, ABORTED, ERROR }

data class PromptError(
    val message: String,
    val provider: String? = null,
    val model: String? = null,
    val httpStatus: Int? = null,
    val retryable: Boolean = false,
)

/** Terminal outcome of one accepted `prompt` / `abort_and_prompt`. */
data class PromptResult(
    val id: String,
    val agentInvoked: Boolean,
    val status: PromptStatus,
    val error: PromptError?,
    /** Nothing can wake the session again; otherwise a [OmpEvent.SessionSettled] follows. */
    val sessionSettled: Boolean,
)

/** Handle for an admitted prompt; [result] completes when the agent yields. */
class PromptHandle(val id: String, val result: kotlinx.coroutines.Deferred<PromptResult>)

enum class StreamingBehavior(val wire: String) {
    /** Interrupt the running turn with this message. */
    STEER("steer"),

    /** Deliver after the running turn ends. */
    FOLLOW_UP("followUp"),
}

/** What the orchestrator and the UI observe. Unknown frames arrive as [Other]; nothing is dropped. */
sealed interface OmpEvent {
    data object AgentStart : OmpEvent

    data class AgentEnd(val isTerminal: Boolean?, val yielded: Boolean?, val messageCount: Int?, val raw: JsonObject) : OmpEvent

    data class TurnStart(val raw: JsonObject) : OmpEvent

    data class TurnEnd(val raw: JsonObject) : OmpEvent

    data class MessageStart(val messageId: String?, val role: String?, val raw: JsonObject) : OmpEvent

    /** Streaming assistant output; [textDelta] is set for plain text deltas. */
    data class MessageUpdate(val messageId: String?, val kind: String?, val textDelta: String?, val raw: JsonObject) : OmpEvent

    data class MessageEnd(val messageId: String?, val role: String?, val text: String, val raw: JsonObject) : OmpEvent

    data class ToolStart(val toolCallId: String, val toolName: String, val args: JsonElement?, val intent: String?) : OmpEvent

    data class ToolUpdate(val toolCallId: String, val toolName: String, val partial: JsonElement?) : OmpEvent

    data class ToolEnd(val toolCallId: String, val toolName: String, val isError: Boolean, val result: JsonElement?) : OmpEvent

    data class QueueUpdate(val steering: List<String>, val followUp: List<String>) : OmpEvent

    data class Notice(val level: String, val message: String, val source: String?) : OmpEvent

    data class Prompt(val result: PromptResult) : OmpEvent

    data object SessionSettled : OmpEvent

    /** `subagent_lifecycle`, `subagent_progress` or `subagent_event`. */
    data class Subagent(val kind: String, val payload: JsonObject) : OmpEvent

    data class Ui(val notification: UiNotification) : OmpEvent

    data class ExtensionError(val extensionPath: String?, val event: String?, val error: String) : OmpEvent

    /** A frame that could not be parsed or decoded, or one omp had to drop. The session continues. */
    data class FrameError(val message: String) : OmpEvent

    /** The process ended or the transport broke. Always the last event. */
    data class Closed(val exitCode: Int?, val stderrTail: List<String>) : OmpEvent

    data class Other(val type: String, val raw: JsonObject) : OmpEvent

    /** What a slash command printed (`/session info`, `/mcp list`, …). */
    data class CommandOutput(val text: String) : OmpEvent

    /** A command renamed the session. */
    data class SessionInfo(val sessionId: String?, val title: String?) : OmpEvent

    /** A command changed the model or the thinking level. */
    data class ConfigChanged(val raw: JsonObject) : OmpEvent

    /** The slash commands available now (skills, extensions and MCP prompts come and go). */
    data class CommandsChanged(val commands: List<OmpSlashCommand>) : OmpEvent

    data class CompactionStarted(val reason: String?, val action: String?) : OmpEvent

    data class CompactionEnded(val aborted: Boolean, val willRetry: Boolean, val summary: String?, val error: String?) : OmpEvent

    /** The provider failed and omp is retrying ([attempt] of [maxAttempts], after [delayMs]). */
    data class RetryStarted(val attempt: Int, val maxAttempts: Int, val delayMs: Long, val error: String) : OmpEvent

    data class RetryEnded(val success: Boolean, val finalError: String?) : OmpEvent

    /** The model, the thinking level, the goal or the todos changed: a client reads the state again. */
    data class StateChanged(val what: String, val raw: JsonObject) : OmpEvent

    companion object {
        internal fun from(frame: JsonObject): OmpEvent {
            val type = frame.str("type") ?: return Other("", frame)
            return when (type) {
                "agent_start" -> AgentStart
                "agent_end" -> AgentEnd(frame.bool("isTerminal"), frame.bool("yielded"), frame.int("messageCount"), frame)
                "turn_start" -> TurnStart(frame)
                "turn_end" -> TurnEnd(frame)
                "message_start" -> MessageStart(frame.str("messageId"), frame.obj("message")?.str("role"), frame)
                "message_update" -> {
                    val inner = frame.obj("assistantMessageEvent")
                    val kind = inner?.str("type")
                    MessageUpdate(frame.str("messageId"), kind, if (kind == "text_delta") inner.str("delta") else null, frame)
                }
                "message_end" -> {
                    val message = frame.obj("message")
                    MessageEnd(frame.str("messageId"), message?.str("role"), message?.let(::messageText).orEmpty(), frame)
                }
                "tool_execution_start" -> ToolStart(
                    frame.str("toolCallId").orEmpty(), frame.str("toolName").orEmpty(), frame["args"], frame.str("intent"),
                )
                "tool_execution_update" -> ToolUpdate(frame.str("toolCallId").orEmpty(), frame.str("toolName").orEmpty(), frame["partialResult"])
                "tool_execution_end" -> ToolEnd(
                    frame.str("toolCallId").orEmpty(), frame.str("toolName").orEmpty(), frame.bool("isError") ?: false, frame["result"],
                )
                "queue_update" -> QueueUpdate(frame.strList("steering"), frame.strList("followUp"))
                "notice" -> Notice(frame.str("level") ?: "info", frame.str("message").orEmpty(), frame.str("source"))
                "prompt_result" -> Prompt(promptResult(frame))
                "session_settled" -> SessionSettled
                "subagent_lifecycle", "subagent_progress", "subagent_event" ->
                    Subagent(type.removePrefix("subagent_"), frame.obj("payload") ?: JsonObject(emptyMap()))
                "extension_error" -> ExtensionError(frame.str("extensionPath"), frame.str("event"), frame.str("error").orEmpty())
                "rpc_frame_error" -> FrameError(frame.str("error") ?: "frame dropped (${frame.str("originalType")})")
                "command_output" -> CommandOutput(frame.str("text").orEmpty())
                "session_info_update" -> SessionInfo(frame.str("sessionId"), frame.str("title"))
                "config_update" -> ConfigChanged(frame)
                "available_commands_update" -> CommandsChanged(frame.arr("commands")?.mapNotNull { (it as? JsonObject)?.let(OmpSlashCommand::from) }.orEmpty())
                "auto_compaction_start" -> CompactionStarted(frame.str("reason"), frame.str("action"))
                "auto_compaction_end" -> CompactionEnded(
                    frame.bool("aborted") ?: false, frame.bool("willRetry") ?: false,
                    frame.obj("result")?.let { it.str("shortSummary") ?: it.str("summary") }, frame.str("errorMessage"),
                )
                "auto_retry_start" -> RetryStarted(frame.int("attempt") ?: 1, frame.int("maxAttempts") ?: 1, frame.long("delayMs") ?: 0, frame.str("errorMessage").orEmpty())
                "auto_retry_end" -> RetryEnded(frame.bool("success") ?: false, frame.str("finalError"))
                "model_changed", "thinking_level_changed", "goal_updated", "todo_reminder", "todo_auto_clear", "config_warnings_changed" -> StateChanged(type, frame)
                else -> Other(type, frame)
            }
        }

        internal fun promptResult(frame: JsonObject): PromptResult = PromptResult(
            id = frame.str("id").orEmpty(),
            agentInvoked = frame.bool("agentInvoked") ?: true,
            status = when (frame.str("status")) {
                "aborted" -> PromptStatus.ABORTED
                "error" -> PromptStatus.ERROR
                else -> PromptStatus.COMPLETED
            },
            error = frame.obj("error")?.let {
                PromptError(
                    it.str("message").orEmpty(), it.str("provider"), it.str("model"), it.int("httpStatus"), it.bool("retryable") ?: false,
                )
            },
            sessionSettled = frame.bool("sessionSettled") ?: false,
        )

        /** Plain text of an agent message: a string, or the text blocks of a content array. */
        fun messageText(message: JsonObject): String {
            return when (val content = message["content"]) {
                is JsonPrimitive -> content.content
                is JsonArray -> content.mapNotNull { block ->
                    (block as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
                }.joinToString("")
                else -> ""
            }
        }
    }
}

/**
 * How [OmpClient] turns an unsolicited frame into an event, for code that holds frames without a client (tests, a
 * replayed log). Dialog requests that need an answer are not events and come back as [OmpEvent.Other].
 */
object OmpEventParsing {
    fun parse(frame: JsonObject): OmpEvent = when (frame.str("type")) {
        "prompt_result" -> OmpEvent.Prompt(OmpEvent.promptResult(frame))
        "extension_ui_request" -> when (val method = frame.str("method")) {
            "select", "confirm", "input", "editor", "ask", "cancel" -> OmpEvent.Other("extension_ui_request", frame)
            else -> OmpEvent.Ui(UiNotification(method.orEmpty(), frame))
        }
        else -> OmpEvent.from(frame)
    }
}
