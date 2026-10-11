package app.umbra.core.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class SessionState {
    @SerialName("created") CREATED,
    @SerialName("running") RUNNING,
    @SerialName("paused") PAUSED,
    @SerialName("waiting_approval") WAITING_APPROVAL,
    @SerialName("stopped") STOPPED,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED;

    val isTerminal: Boolean get() = this == STOPPED || this == COMPLETED || this == FAILED
    val isActive: Boolean get() = this == RUNNING || this == WAITING_APPROVAL
}

@Serializable
data class PlanStep(
    val id: String,
    val text: String,
    /** pending | active | done | failed | skipped */
    val status: String = "pending",
)

@Serializable
data class SubagentBrief(
    val id: String,
    val role: String? = null,
    /** running | finished | aborted | failed */
    val state: String = "running",
    val summary: String? = null,
)

@Serializable
data class ProcessRef(
    val pid: Int,
    val name: String,
    val environment: String? = null,
    val startedAtMs: Long? = null,
)

@Serializable
data class ModelRef(
    val provider: String,
    val modelId: String,
    val role: String? = null,
)

/**
 * A persistent autonomous task (spec §24). It keeps everything needed to pause, resume, restart,
 * duplicate and review it: the request, goal, plan, workspace, OMP's own session file, and what the
 * agent currently holds (apps, displays, terminals, processes, subagents).
 */
@Serializable
data class Session(
    val id: String,
    val title: String,
    val state: SessionState = SessionState.CREATED,
    val createdAtMs: Long,
    val updatedAtMs: Long = createdAtMs,
    /** The user's original request, verbatim. */
    val request: String,
    val goal: String? = null,
    val plan: List<PlanStep> = emptyList(),
    val workspaceId: String? = null,
    /** Path (inside the guest) of OMP's session file, used to re-open the conversation. */
    val ompSessionFile: String? = null,
    val model: ModelRef? = null,
    val activeApps: List<String> = emptyList(),
    val displays: List<Int> = emptyList(),
    val terminals: List<String> = emptyList(),
    val processes: List<ProcessRef> = emptyList(),
    val subagents: List<SubagentBrief> = emptyList(),
    /** Set when this session was created by duplicating another. */
    val parentSessionId: String? = null,
    val failureCount: Int = 0,
    val lastError: String? = null,
    /** approval mode name in effect (cautious | balanced | autonomous). */
    val approvalMode: String? = null,
    /** Free-form per-subsystem data (for example remembered approval rules), keyed by name. */
    val attachments: Map<String, JsonElement> = emptyMap(),
    /** The one project that owns this session; null for an independent session (architecture spec §2.1). */
    val projectId: String? = null,
    /** Projects whose context this session may read; linking is not moving (§2.1). */
    val linkedContextIds: List<String> = emptyList(),
    /**
     * Set while the session waits for admission (architecture spec AC-022): a logical agent with no process yet,
     * started in fair order when the phone, its project's budget and the shared services let one more run.
     */
    val queuedAtMs: Long? = null,
)

@Serializable
data class SessionFilter(
    val states: Set<SessionState>? = null,
    /** Case-insensitive match against title and request. */
    val query: String? = null,
    val limit: Int = 200,
)
