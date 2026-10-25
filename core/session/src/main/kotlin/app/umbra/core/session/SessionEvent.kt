package app.umbra.core.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Timeline entries (spec §24: tool calls, tool results, observations, approvals, failures and
 * recoveries). Streaming deltas are UI-only and are not persisted; messages are stored when complete.
 */
@Serializable
sealed interface SessionEvent {
    @Serializable @SerialName("user_message")
    data class UserMessage(val text: String, val imageCount: Int = 0, val source: String = "user") : SessionEvent

    @Serializable @SerialName("agent_message")
    data class AgentMessage(val text: String, val messageId: String? = null, val agentId: String? = null) : SessionEvent

    @Serializable @SerialName("tool_call")
    data class ToolCall(
        val callId: String,
        val tool: String,
        /** omp (built-in) or host (Android / GUI / privileged). */
        val source: String,
        /** Arguments with secrets already redacted. */
        val args: JsonElement? = null,
        val agentId: String? = null,
        /** Why the agent made the call, in its own words, when it said. */
        val intent: String? = null,
    ) : SessionEvent

    @Serializable @SerialName("tool_result")
    data class ToolResult(
        val callId: String,
        val tool: String,
        /** ok | failed | denied | timeout | needs_user | unsupported | cancelled */
        val status: String,
        val summary: String,
        val backend: String? = null,
        val durationMs: Long = 0,
        val data: JsonElement? = null,
    ) : SessionEvent

    @Serializable @SerialName("observation")
    data class Observation(
        val summary: String,
        val displayId: Int? = null,
        val screenshotPath: String? = null,
        val screenshotSha256: String? = null,
        val screenshotSizeBytes: Long? = null,
        val screenshotMimeType: String? = null,
    ) : SessionEvent

    @Serializable @SerialName("approval")
    data class Approval(
        val requestId: String,
        /** requested | approved | denied | timed_out | cancelled | auto_allowed | auto_denied */
        val kind: String,
        val summary: String,
        val level: String = "none",
        val categories: List<String> = emptyList(),
    ) : SessionEvent

    @Serializable @SerialName("plan")
    data class PlanUpdated(val steps: List<PlanStep>) : SessionEvent

    @Serializable @SerialName("subagent")
    data class Subagent(val subagentId: String, val kind: String, val summary: String? = null) : SessionEvent

    @Serializable @SerialName("state")
    data class StateChanged(val from: SessionState, val to: SessionState, val reason: String? = null) : SessionEvent

    @Serializable @SerialName("failure")
    data class Failure(val subsystem: String, val message: String, val recoverable: Boolean = true) : SessionEvent

    @Serializable @SerialName("recovery")
    data class Recovery(val subsystem: String, val action: String, val success: Boolean, val detail: String? = null) : SessionEvent

    @Serializable @SerialName("decision")
    data class Decision(
        val question: String,
        val answer: String,
        val confidence: Double? = null,
        val engine: String? = null,
    ) : SessionEvent

    @Serializable @SerialName("app_event")
    data class AppEvent(
        /** crash | anr | native_crash | warning */
        val kind: String,
        val packageName: String?,
        val summary: String,
        val actionId: String? = null,
    ) : SessionEvent

    @Serializable @SerialName("notice")
    data class Notice(val level: String, val message: String, val source: String? = null) : SessionEvent
}

/** One persisted line of `events.jsonl`. `seq` comes first so it can be read without full parsing. */
@Serializable
data class EventRecord(
    val seq: Long,
    val ts: Long,
    val event: SessionEvent,
)
