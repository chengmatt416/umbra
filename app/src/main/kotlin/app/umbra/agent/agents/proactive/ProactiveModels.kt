package app.umbra.agent.agents.proactive

import kotlinx.serialization.Serializable

@Serializable enum class GoalState { IN_PROGRESS, SCHEDULED, WAITING_USER, BLOCKED, PAUSED, COMPLETED, ARCHIVED }
@Serializable enum class GoalCategory { PROJECT, PERSONAL_LIFE, RESEARCH, MAINTENANCE }
@Serializable data class GoalMilestone(
    val id: String, val title: String, val status: String = "pending", val assignedTo: String? = null,
    val dependsOn: List<String> = emptyList(), val verificationRule: String? = null,
    val artifactPath: String? = null, val updatedAtMs: Long,
)
@Serializable data class ReplanRecord(val atMs: Long, val trigger: String, val summary: String)
@Serializable data class AgentGoal(
    val id: String, val agentId: String, val title: String, val description: String,
    val category: GoalCategory = GoalCategory.PROJECT, val state: GoalState = GoalState.IN_PROGRESS,
    val priority: Int = 2, val acceptanceCriteria: List<String> = emptyList(),
    val milestones: List<GoalMilestone> = emptyList(), val linkedSessionIds: List<String> = emptyList(),
    val linkedRoutineIds: List<String> = emptyList(), val deliverables: List<String> = emptyList(),
    val replanHistory: List<ReplanRecord> = emptyList(), val checkCadenceCron: String? = null,
    val deadlineMs: Long? = null, val stalledAfterHours: Int = 48, val createdAtMs: Long,
    val updatedAtMs: Long = createdAtMs, val lastProgressAtMs: Long = createdAtMs,
    val autoAdvance: Boolean = false, val authorizedAtMs: Long? = null,
    val lastCheckedAtMs: Long? = null,
)

@Serializable data class PreparedNextStep(
    val label: String, val actionType: String, val payloadJson: String,
    val secondaryOptions: List<String> = emptyList(),
)

/**
 * What a person needs to decide, in their own language and in plain sentences (never ids, JSON, field names or scores):
 * [why] it comes up now, the [steps] that happen if they confirm, what it [needs] from them, what happens [ifIgnored],
 * and the [sources] it rests on ("Calendar · Dentist, Thu 15:00"). The agent writes it with the suggestion; Umbra writes
 * it for what it finds itself (a calendar conflict).
 */
@Serializable data class CardExplanation(
    val why: String = "", val steps: List<String> = emptyList(), val needs: String = "",
    val ifIgnored: String = "", val sources: List<String> = emptyList(),
) {
    fun isEmpty() = why.isBlank() && steps.isEmpty() && needs.isBlank() && ifIgnored.isBlank() && sources.isEmpty()
}
@Serializable data class OpportunityCard(
    val id: String, val agentId: String, val kind: Kind, val title: String, val summary: String,
    val evidenceRefs: List<String>, val urgency: Double, val confidence: Double,
    val preparedAction: PreparedNextStep, val state: State = State.PENDING,
    val createdAtMs: Long, val expiresAtMs: Long? = null,
    val executionSessionId: String? = null, val error: String? = null,
    /** The agent run that last proposed or updated it; its closing words are not delivered as a second item. */
    val sourceSessionId: String? = null, val updatedAtMs: Long? = null,
    /** The plain-language account the card shows before the user decides (null on cards from before it existed). */
    val explain: CardExplanation? = null,
) {
    @Serializable enum class Kind { TASK_DISCOVERY, CONFLICT_ALERT, FOLLOW_UP, PRICE_OR_MONITOR, GOAL_SUGGESTION }
    @Serializable enum class State { PENDING, ACCEPTING, ACCEPTED, DISMISSED, EXPIRED }
}

@Serializable sealed interface ProbeSpec {
    @Serializable data class HttpOrCss(val url: String, val selectorOrJsonPath: String = "", val headers: Map<String, String> = emptyMap()) : ProbeSpec
    @Serializable data class GuestCheckCommand(val argv: List<String>, val workingDir: String? = null) : ProbeSpec
    @Serializable data class NotificationPattern(val packageName: String?, val regex: String, val literal: Boolean = true) : ProbeSpec
}
@Serializable sealed interface ConditionSpec {
    @Serializable data object Changed : ConditionSpec
    @Serializable data class Contains(val substring: String, val ignoreCase: Boolean = true) : ConditionSpec
    @Serializable data class NumericCompare(val op: String, val threshold: Double) : ConditionSpec
    @Serializable data class LayaVerify(val question: String) : ConditionSpec
}
@Serializable data class AgentWatcher(
    val id: String, val agentId: String, val title: String, val probe: ProbeSpec, val condition: ConditionSpec,
    val intervalMinutes: Int = 30, val onMatchInstruction: String, val lastCheckedMs: Long = 0,
    val lastValue: String? = null, val lastHash: String? = null, val triggerOnce: Boolean = true,
    val enabled: Boolean = false, val lastMatched: Boolean = false, val error: String? = null,
    val authorizedAtMs: Long? = null,
)

@Serializable enum class DeliveryTier { SILENT_LOG, BRIEF_QUEUE, CAPSULE_AMBIENT, STANDARD_NOTIFY, URGENT_BREAKTHROUGH }
@Serializable data class AttentionItem(
    val id: String, val title: String, val detail: String, val urgency: Double,
    val importance: Double, val confidence: Double, val source: String,
    val createdAtMs: Long, val tier: DeliveryTier = DeliveryTier.BRIEF_QUEUE,
    val linkedSessionId: String? = null, val preparedStep: PreparedNextStep? = null,
)
@Serializable data class ConflictAlert(
    val id: String, val severity: String, val description: String, val sources: List<String>,
    val resolutionOptions: List<PreparedNextStep> = emptyList(),
    /** A short headline ("Dentist and Team sync overlap") and the plain account; [description] stays the one-line text. */
    val title: String = "", val explain: CardExplanation? = null,
)
@Serializable data class BriefItem(val title: String, val detail: String, val linkedSessionId: String? = null,
    val linkedGoalId: String? = null, val preparedStep: PreparedNextStep? = null)
/** Stable states keep stored briefs independent of session enum names. */
@Serializable data class BriefTask(
    val title: String, val state: String, val detail: String = "", val linkedSessionId: String? = null,
    val updatedAtMs: Long = 0,
)
@Serializable data class DailyBrief(
    val id: String, val date: String, val kind: Kind, val headline: String,
    val scheduleHighlights: List<BriefItem> = emptyList(), val conflictsDetected: List<ConflictAlert> = emptyList(),
    val overnightCompleted: List<BriefItem> = emptyList(), val attentionNeeded: List<OpportunityCard> = emptyList(),
    val lowPriorityDigest: String = "", val generatedAtMs: Long, val tasks: List<BriefTask> = emptyList(),
) { @Serializable enum class Kind { MORNING, EVENING } }

@Serializable enum class ExecutionTarget { CONNECTOR_API, HEADLESS_BROWSER, ANDROID_VISUAL, LINUX_CLI, REMOTE_NODE, SANDBOX_TRANSFORM }
@Serializable data class SkillParam(val name: String, val type: String = "string", val description: String,
    val required: Boolean = true, val defaultValue: String? = null)
@Serializable data class UmbraSkill(
    val id: String, val name: String, val description: String, val ownerAgentId: String? = null,
    val executionTarget: ExecutionTarget, val parameters: List<SkillParam> = emptyList(),
    val verificationChecks: List<String> = emptyList(), val approvalBoundary: String,
    val idempotencyRule: String? = null, val version: Int = 1, val createdFrom: String = "manual",
    val successCount: Int = 0, val failureCount: Int = 0, val updatedAtMs: Long,
    val status: Status = Status.DRAFT, val testedVersion: Int? = null, val approvedVersion: Int? = null,
    val pendingPatch: String? = null, val testSessionId: String? = null,
    val synthesizedScript: String? = null, val scriptLanguage: String? = null,
    val codeReviewSha256: String? = null, val testCases: List<TransformTestCase> = emptyList(),
    /** Declared reach of a synthesized guest script ([GuestScriptPolicy]); null for transforms and workflows. */
    val scriptCapabilities: ScriptCapabilities? = null,
) {
    @Serializable enum class Status { DRAFT, TESTING, READY, DISABLED }
    val isGuestScript get() = executionTarget == ExecutionTarget.LINUX_CLI && synthesizedScript != null
}
/** A case passes when the output equals [expected] (when given) and, for an object, has every [expectedKeys] entry. */
@Serializable data class TransformTestCase(val input: kotlinx.serialization.json.JsonElement, val expected: kotlinx.serialization.json.JsonElement? = null,
    val expectedKeys: List<String> = emptyList())
@Serializable data class ScriptCapabilities(val network: Boolean = false, val allowedDomains: List<String> = emptyList())

@Serializable sealed interface RoutineTrigger {
    @Serializable data class CronSchedule(val cron: String, val zoneId: String) : RoutineTrigger
    @Serializable data class EventMatch(val source: String, val senderOrChannelFilter: String? = null,
        val textContains: String? = null, val semanticCondition: String? = null) : RoutineTrigger
    @Serializable data class WatcherMatch(val watcherId: String) : RoutineTrigger
    @Serializable data object Manual : RoutineTrigger
}
@Serializable data class RoutineRunRecord(
    val runId: String, val startedAtMs: Long, val finishedAtMs: Long? = null, val status: String = "running",
    val summary: String = "", val deliverablePath: String? = null, val error: String? = null,
    val sessionId: String? = null, val idempotencyKey: String, val authorizedAtMs: Long? = null,
)
@Serializable data class AgentRoutine(
    val id: String, val ownerAgentId: String, val title: String, val trigger: RoutineTrigger,
    val skillId: String? = null, val instruction: String, val skillArgs: Map<String, String> = emptyMap(),
    val allowedConnectors: List<String> = emptyList(), val approvalTTLMinutes: Int = 10,
    val idempotencyWindowMinutes: Int = 5, val enabled: Boolean = false,
    val createdAtMs: Long, val lastRunAtMs: Long? = null, val recentRuns: List<RoutineRunRecord> = emptyList(),
    val authorizedAtMs: Long? = null, val nextRunAtMs: Long? = null,
) {
    fun authorizes(runId: String) = enabled && authorizedAtMs != null && recentRuns.any { it.runId == runId && it.authorizedAtMs == authorizedAtMs }
    companion object { const val MAX_ROUTINES_PER_AGENT = 50; const val MAX_RUN_HISTORY = 20; const val MIN_INTERVAL_MINUTES = 5 }
}

@Serializable data class AgentGroup(
    val id: String, val name: String, val memberAgentIds: List<String>, val coordinatorAgentId: String,
    val createdAtMs: Long, val avatar: String = "g1", val lastMessageAtMs: Long = createdAtMs,
)
@Serializable data class GroupMessage(
    val id: String, val groupId: String, val fromAgentId: String?, val text: String, val atMs: Long,
    val rootMessageId: String, val hop: Int = 0, val targetAgentIds: List<String> = emptyList(),
    val sessionId: String? = null,
)
@Serializable data class GroupTurn(
    val id: String, val groupId: String, val rootSessionId: String, val pendingAgentIds: List<String>,
    val hop: Int = 0, val activeSessionId: String? = null, val status: String = "active", val error: String? = null,
)
@Serializable data class AgentHandoff(
    val id: String, val fromAgentId: String, val toAgentId: String, val instruction: String,
    val goalId: String?, val milestoneId: String?, val expectedDeliverable: String,
    val deadlineMs: Long?, val contextRefs: List<String>, val createdAtMs: Long,
    val status: String = "pending", val summary: String? = null, val deliverablePath: String? = null,
    val hop: Int = 0, val rootId: String? = null,
    val sourceSessionId: String? = null, val executionSessionId: String? = null,
)
@Serializable data class AgentTemplateBundle(
    val version: Int = 1, val name: String, val avatar: String, val template: String, val soul: String,
    val requiredConnectors: List<String>, val skills: List<SkillTemplate> = emptyList(),
    val routines: List<AgentRoutine> = emptyList(),
)
@Serializable data class SkillTemplate(val skill: UmbraSkill, val instructions: String)

@Serializable data class ArtifactItem(val kind: Kind, val title: String, val path: String, val mimeType: String,
    val previewTextOrThumbPath: String? = null) {
    @Serializable enum class Kind { DOCUMENT, SPREADSHEET, SLIDES, CODE_PATCH, VISUAL_PROOF, WEB_PROTOTYPE, DATA_CHART }
}
@Serializable data class VerificationReceipt(val verified: Boolean, val checksRun: List<String>,
    val visualProofSheetPath: String? = null, val residualRisks: List<String> = emptyList())
@Serializable data class DeliverableBundle(val sessionId: String, val summary: String, val artifacts: List<ArtifactItem>,
    val verification: VerificationReceipt, val gitSummary: String? = null)

@Serializable enum class ConnectorScope { READ_ONLY, PROPOSE_EVENT, WRITE_EVENTS, DRAFT_ONLY, SEND_WITH_APPROVAL, WRITE_TASKS, WORKSPACE_COPY, DIRECT_EDIT }
@Serializable data class PersonalConnectorGrant(val connectorId: String, val scope: ConnectorScope = ConnectorScope.READ_ONLY,
    val enabled: Boolean = false, val endpoint: String? = null, val secretName: String? = null,
    val authorizedAtMs: Long? = null)
@Serializable data class ContextEvidence(val source: String, val id: String, val text: String, val atMs: Long,
    val entityType: String = "any", val score: Double = 0.0)
/**
 * One calendar entry. [calendar] and [account] name where it lives, [organizer] who sent it, [uid] its iCalendar UID
 * (one meeting on two accounts usually keeps it): what tells the conflict judge that two overlapping entries are one.
 */
@Serializable data class CalendarItem(val id: String, val title: String, val startMs: Long, val endMs: Long,
    val location: String = "", val attendees: List<String> = emptyList(), val source: String = "calendar",
    val travelMinutes: Int? = null, val declaredWeekday: Int? = null,
    val calendar: String = "", val account: String = "", val organizer: String = "", val allDay: Boolean = false,
    val free: Boolean = false, val declined: Boolean = false, val uid: String = "")
@Serializable data class PersonalTask(val id: String, val title: String, val note: String = "", val dueAtMs: Long? = null,
    val completed: Boolean = false, val updatedAtMs: Long)
