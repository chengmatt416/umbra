package app.umbra.agent.agents.proactive

import app.umbra.core.model.UmbraJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/** App-owned, atomic state. Corrupt state is reported rather than silently resetting authorization. */
class ProactiveStore(private val root: File) {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    fun id(prefix: String): String = prefix + UUID.randomUUID().toString().replace("-", "")

    private fun path(agentId: String?, name: String): File {
        require(agentId == null || agentId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid agent id" }
        val base = if (agentId == null) root else File(root, agentId)
        require(!Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(base.toPath())) { "Linked state directories are not allowed" }
        return File(base, name).also { require(!Files.isSymbolicLink(it.toPath())) { "Linked state files are not allowed" } }
    }
    private fun <T> read(agentId: String?, name: String, serializer: KSerializer<T>): List<T> {
        val f = path(agentId, name)
        if (!f.exists()) return emptyList()
        require(f.length() <= 4_000_000) { "$name is too large" }
        return UmbraJson.decodeFromString(ListSerializer(serializer), f.readText())
    }
    private fun writeText(file: File, text: String) {
        require(text.length <= 4_000_000) { "State is too large" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4_000_000) { "State is too large" }
        file.parentFile!!.mkdirs()
        val temp = Files.createTempFile(requireNotNull(file.parentFile).toPath(), ".proactive-", ".tmp")
        try {
            Files.write(temp, bytes)
            Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            changes.value++
        } finally { Files.deleteIfExists(temp) }
    }
    private fun <T> write(agentId: String?, name: String, serializer: KSerializer<T>, values: List<T>) =
        writeText(path(agentId, name), UmbraJson.encodeToString(ListSerializer(serializer), values))

    @Synchronized fun goals(agentId: String) = read(agentId, "goals.json", AgentGoal.serializer())
    @Synchronized fun saveGoal(goal: AgentGoal): AgentGoal {
        val all = goals(goal.agentId)
        require(all.none { it.id == goal.id }) { "This goal already exists; reload it before editing" }
        require(all.size < 200) { "At most 200 goals per agent" }
        return writeGoal(goal)
    }
    @Synchronized fun updateGoal(expected: AgentGoal, next: AgentGoal): AgentGoal {
        require(next.id == expected.id && next.agentId == expected.agentId) { "Goal identity cannot change" }
        if (goals(expected.agentId).find { it.id == expected.id } != expected) throw GoalChangedException()
        return writeGoal(next)
    }
    /** Native controls apply to the latest state; background/model edits still require a snapshot. */
    @Synchronized fun changeGoal(agentId: String, id: String, transform: (AgentGoal) -> AgentGoal): Pair<AgentGoal, AgentGoal> {
        val current = requireNotNull(goals(agentId).find { it.id == id })
        return current to updateGoal(current, transform(current))
    }
    private fun writeGoal(goal: AgentGoal): AgentGoal {
        require(!goal.autoAdvance || goal.authorizedAtMs != null) { "Authorize goal execution before enabling autonomous advancement" }
        require(goal.title.isNotBlank() && goal.title.length <= 200 && goal.description.length <= 20_000)
        goal.checkCadenceCron?.let { app.umbra.agent.agents.Cron(it).next(goal.createdAtMs) }
        require(goal.priority in 1..4 && goal.stalledAfterHours in 1..8760)
        require(goal.milestones.size <= 100 && goal.milestones.map { it.id }.distinct().size == goal.milestones.size)
        val byId = goal.milestones.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in visited) return
            require(visiting.add(id)) { "Milestone dependencies contain a cycle" }
            val m = requireNotNull(byId[id]) { "Unknown milestone dependency" }
            require(m.title.isNotBlank() && m.status in setOf("pending", "active", "verifying", "done", "failed", "skipped"))
            if (m.status == "done") require(m.dependsOn.all { byId[it]?.status == "done" }) { "Complete dependencies first" }
            m.dependsOn.forEach(::visit)
            visiting.remove(id)
            visited += id
        }
        byId.keys.forEach(::visit)
        if (goal.state == GoalState.COMPLETED) {
            require(goal.acceptanceCriteria.isNotEmpty() && goal.milestones.isNotEmpty() && goal.milestones.all { it.status == "done" }) { "All milestones and acceptance criteria must be complete" }
            require(goal.deliverables.isNotEmpty() || goal.milestones.all { !it.artifactPath.isNullOrBlank() }) { "Completion needs evidence references" }
        }
        val saved = goal.copy(replanHistory = goal.replanHistory.takeLast(100))
        write(goal.agentId, "goals.json", AgentGoal.serializer(), goals(goal.agentId).filterNot { it.id == goal.id } + saved)
        return saved
    }
    @Synchronized fun opportunities(agentId: String) = read(agentId, "opportunities.json", OpportunityCard.serializer())
    @Synchronized fun saveOpportunity(card: OpportunityCard) {
        require(card.urgency.isFinite() && card.urgency in 0.0..1.0 && card.confidence.isFinite() && card.confidence in 0.0..1.0)
        require(card.title.isNotBlank() && card.summary.length <= 12_000 && card.preparedAction.payloadJson.length <= 20_000)
        require(card.preparedAction.actionType in setOf("start_coding", "send_message_draft", "create_goal", "run_skill", "agent_turn"))
        write(card.agentId, "opportunities.json", OpportunityCard.serializer(), (opportunities(card.agentId).filterNot { it.id == card.id } + card).takeLast(200))
    }
    /** What [proposeOpportunity] did: saved [card] as new, or found it repeats [card] (open, or settled by the user). */
    sealed interface Proposal { val card: OpportunityCard
        data class Created(override val card: OpportunityCard) : Proposal
        data class Updated(override val card: OpportunityCard) : Proposal
        data class Duplicate(override val card: OpportunityCard) : Proposal
    }
    /**
     * Saves [card] unless it repeats an earlier suggestion ([OpportunityDedup]): the agent's own open card about the same
     * thing takes the new words instead; another agent's open card, or one the user dismissed or adopted lately, stops it.
     */
    @Synchronized fun proposeOpportunity(card: OpportunityCard, otherAgents: List<String>, now: Long): Proposal {
        val own = opportunities(card.agentId)
        OpportunityDedup.match(card, own, now)?.let { earlier ->
            if (earlier.state != OpportunityCard.State.PENDING || !proposed(earlier)) return Proposal.Duplicate(earlier)
            val merged = earlier.copy(kind = card.kind, title = card.title, summary = card.summary,
                evidenceRefs = (earlier.evidenceRefs + card.evidenceRefs).distinct().take(100), urgency = card.urgency, confidence = card.confidence,
                preparedAction = card.preparedAction, expiresAtMs = card.expiresAtMs ?: earlier.expiresAtMs, sourceSessionId = card.sourceSessionId, updatedAtMs = now)
            saveOpportunity(merged)
            return Proposal.Updated(merged)
        }
        val others = otherAgents.filter { it != card.agentId }.flatMap { id -> runCatching { opportunities(id) }.getOrDefault(emptyList()) }
        OpportunityDedup.match(card, others, now)?.let { return Proposal.Duplicate(it) }
        saveOpportunity(card)
        return Proposal.Created(card)
    }
    /**
     * Folds open cards that are the same suggestion ([OpportunityDedup.same]) into the newest one, from before proposals
     * were matched. Returns how many were removed.
     */
    @Synchronized fun collapseDuplicates(agentId: String): Int {
        val all = opportunities(agentId)
        val open = all.filter { it.state == OpportunityCard.State.PENDING && proposed(it) }.sortedByDescending { it.updatedAtMs ?: it.createdAtMs }
        val kept = mutableListOf<OpportunityCard>()
        val gone = mutableSetOf<String>()
        for (card in open) {
            val i = kept.indexOfFirst { OpportunityDedup.same(it, card) }
            if (i < 0) { kept += card; continue }
            kept[i] = kept[i].copy(evidenceRefs = (kept[i].evidenceRefs + card.evidenceRefs).distinct().take(100), createdAtMs = minOf(kept[i].createdAtMs, card.createdAtMs))
            gone += card.id
        }
        if (gone.isEmpty()) return 0
        val merged = kept.associateBy { it.id }
        write(agentId, "opportunities.json", OpportunityCard.serializer(), all.filterNot { it.id in gone }.map { merged[it.id] ?: it })
        if (attention(agentId).any { it.id in gone }) write(agentId, "attention.json", AttentionItem.serializer(), attention(agentId).filterNot { it.id in gone })
        return gone.size
    }
    /** An agent's own proposal ("opp…"), not one Umbra keeps by itself (a goal's deadline), which only Umbra changes. */
    fun proposed(card: OpportunityCard) = card.id.startsWith("opp")
    /** Changes the agent's own pending card (the user has not acted on it yet). */
    @Synchronized fun updateOpportunity(agentId: String, id: String, now: Long, transform: (OpportunityCard) -> OpportunityCard): OpportunityCard {
        val card = requireNotNull(opportunities(agentId).find { it.id == id }) { "No suggestion $id. List them with opportunity_list." }
        require(proposed(card)) { "Umbra keeps $id up to date by itself" }
        require(card.state == OpportunityCard.State.PENDING) { "The user already ${if (card.state == OpportunityCard.State.DISMISSED) "dismissed" else "acted on"} this suggestion; it cannot change now" }
        val next = transform(card).copy(id = card.id, agentId = card.agentId, state = card.state, createdAtMs = card.createdAtMs, updatedAtMs = now)
        saveOpportunity(next)
        return next
    }
    /** Deletes the agent's own pending card and its delivery, so it leaves the Capsule, Today and the brief. */
    @Synchronized fun removeOpportunity(agentId: String, id: String): OpportunityCard {
        val card = requireNotNull(opportunities(agentId).find { it.id == id }) { "No suggestion $id. List them with opportunity_list." }
        require(proposed(card)) { "Umbra keeps $id up to date by itself" }
        require(card.state == OpportunityCard.State.PENDING) { "The user already ${if (card.state == OpportunityCard.State.DISMISSED) "dismissed" else "acted on"} this suggestion; it cannot be withdrawn" }
        write(agentId, "opportunities.json", OpportunityCard.serializer(), opportunities(agentId).filterNot { it.id == id })
        if (attention(agentId).any { it.id == id }) write(agentId, "attention.json", AttentionItem.serializer(), attention(agentId).filterNot { it.id == id })
        return card
    }
    @Synchronized fun claimOpportunity(agentId: String, id: String, now: Long): OpportunityCard {
        val card = requireNotNull(opportunities(agentId).find { it.id == id })
        require(card.state == OpportunityCard.State.PENDING) { "This suggestion has already been handled" }
        if (card.expiresAtMs?.let { it <= now } == true) {
            saveOpportunity(card.copy(state = OpportunityCard.State.EXPIRED)); error("This suggestion has expired")
        }
        return card.copy(state = OpportunityCard.State.ACCEPTING).also(::saveOpportunity)
    }
    @Synchronized fun dismissOpportunity(agentId: String, id: String): OpportunityCard {
        val card = requireNotNull(opportunities(agentId).find { it.id == id })
        require(card.state == OpportunityCard.State.PENDING) { "This suggestion has already been handled" }
        return card.copy(state = OpportunityCard.State.DISMISSED).also(::saveOpportunity)
    }
    @Synchronized fun watchers(agentId: String) = read(agentId, "watchers.json", AgentWatcher.serializer())
    @Synchronized fun saveWatcher(w: AgentWatcher) {
        (w.probe as? ProbeSpec.NotificationPattern)?.let(NotificationPatterns::compile)
        require(!w.enabled || w.authorizedAtMs != null) { "A user must authorize a watcher before it runs" }
        require(w.intervalMinutes in 5..1440 && w.title.isNotBlank() && w.onMatchInstruction.length <= 8000)
        val all = watchers(w.agentId)
        require(all.any { it.id == w.id } || all.size < 50) { "At most 50 watchers per agent" }
        write(w.agentId, "watchers.json", AgentWatcher.serializer(), all.filterNot { it.id == w.id } + w)
    }
    @Synchronized fun updateWatcher(expected: AgentWatcher, next: AgentWatcher): Boolean {
        require(next.id == expected.id && next.agentId == expected.agentId)
        if (watchers(expected.agentId).find { it.id == expected.id } != expected) return false
        saveWatcher(next)
        return true
    }
    @Synchronized fun removeWatcher(agentId: String, id: String) = write(agentId, "watchers.json", AgentWatcher.serializer(), watchers(agentId).filterNot { it.id == id })
    @Synchronized fun routines(agentId: String) = read(agentId, "routines.json", AgentRoutine.serializer())
    @Synchronized fun saveRoutine(r: AgentRoutine) {
        val all = routines(r.ownerAgentId)
        RoutineDefinitions.validate(r, all.find { it.id == r.id }?.trigger)
        require(all.any { it.id == r.id } || all.size < 50) { "At most 50 routines per agent" }
        write(r.ownerAgentId, "routines.json", AgentRoutine.serializer(), all.filterNot { it.id == r.id } + r.copy(recentRuns = r.recentRuns.takeLast(20)))
    }
    @Synchronized fun removeRoutine(agentId: String, id: String) = write(agentId, "routines.json", AgentRoutine.serializer(), routines(agentId).filterNot { it.id == id })
    @Synchronized fun claimRun(agentId: String, id: String, key: String, now: Long): RoutineRunRecord? {
        val routine = requireNotNull(routines(agentId).find { it.id == id })
        require(routine.enabled && routine.authorizedAtMs != null)
        if (routine.recentRuns.any { it.idempotencyKey == key } || routine.lastRunAtMs?.let { now - it < routine.idempotencyWindowMinutes * 60_000L } == true) return null
        val run = RoutineRunRecord(this.id("run"), now, idempotencyKey = key, authorizedAtMs = routine.authorizedAtMs)
        saveRoutine(routine.copy(lastRunAtMs = now, recentRuns = routine.recentRuns + run))
        return run
    }
    @Synchronized fun updateRun(agentId: String, routineId: String, run: RoutineRunRecord) {
        val routine = requireNotNull(routines(agentId).find { it.id == routineId })
        require(routine.recentRuns.any { it.runId == run.runId })
        saveRoutine(routine.copy(recentRuns = routine.recentRuns.map { if (it.runId == run.runId) run else it }))
    }
    @Synchronized fun attention(agentId: String) = read(agentId, "attention.json", AttentionItem.serializer())
    @Synchronized fun stage(agentId: String, item: AttentionItem) = write(agentId, "attention.json", AttentionItem.serializer(), (attention(agentId).filterNot { it.id == item.id } + item).takeLast(200))
    @Synchronized fun briefs(agentId: String) = read(agentId, "briefs.json", DailyBrief.serializer())
    @Synchronized fun saveBrief(agentId: String, brief: DailyBrief) = write(agentId, "briefs.json", DailyBrief.serializer(), (briefs(agentId).filterNot { it.id == brief.id } + brief).takeLast(30))
    @Synchronized fun note(agentId: String, topic: String): String {
        require(topic.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Use a plain note topic" }
        return path(agentId, "notes/$topic.md").also { require(!Files.isSymbolicLink(requireNotNull(it.parentFile).toPath())) }.takeIf { it.exists() }?.let { require(it.length() <= 100_000); it.readText() }.orEmpty()
    }
    @Synchronized fun writeNote(agentId: String, topic: String, content: String, append: Boolean) {
        val before = note(agentId, topic)
        val text = if (append) before + "\n" + content else content
        require(text.length <= 100_000) { "A note is limited to 100 KB" }
        writeText(path(agentId, "notes/$topic.md"), text)
    }
    @Synchronized fun notes(agentId: String): List<String> = path(agentId, "notes").listFiles().orEmpty().filter { it.extension == "md" && !Files.isSymbolicLink(it.toPath()) }.map { it.nameWithoutExtension }.sorted()
    @Synchronized fun groups() = read(null, "groups.json", AgentGroup.serializer())
    @Synchronized fun saveGroup(group: AgentGroup) {
        require(group.memberAgentIds.distinct().size in 2..6 && group.memberAgentIds.size == group.memberAgentIds.distinct().size && group.coordinatorAgentId in group.memberAgentIds)
        require(group.name.isNotBlank())
        write(null, "groups.json", AgentGroup.serializer(), groups().filterNot { it.id == group.id } + group)
    }
    @Synchronized fun groupMessages(groupId: String) = read(null, "group-messages.json", GroupMessage.serializer()).filter { it.groupId == groupId }
    @Synchronized fun removeGroup(id: String) {
        require(groupTurns().none { it.groupId == id && it.status in setOf("active", "summarizing", "paused") }) { "Stop the group discussion before removing it" }
        write(null, "groups.json", AgentGroup.serializer(), groups().filterNot { it.id == id })
        write(null, "group-messages.json", GroupMessage.serializer(), read(null, "group-messages.json", GroupMessage.serializer()).filterNot { it.groupId == id })
        write(null, "group-turns.json", GroupTurn.serializer(), groupTurns().filterNot { it.groupId == id })
    }
    @Synchronized fun appendGroup(message: GroupMessage) {
        val group = requireNotNull(groups().find { it.id == message.groupId })
        require(message.hop in 0..4 && message.text.length <= 20_000 && (message.fromAgentId == null || message.fromAgentId in group.memberAgentIds))
        require(message.targetAgentIds.all { it in group.memberAgentIds })
        val all = read(null, "group-messages.json", GroupMessage.serializer())
        require(all.none { it.id == message.id })
        write(null, "group-messages.json", GroupMessage.serializer(), (all + message).takeLast(1000))
        saveGroup(group.copy(lastMessageAtMs = message.atMs))
    }
    @Synchronized fun handoffs() = read(null, "handoffs.json", AgentHandoff.serializer())
    @Synchronized fun groupTurns() = read(null, "group-turns.json", GroupTurn.serializer())
    @Synchronized fun saveGroupTurn(turn: GroupTurn) {
        val group = requireNotNull(groups().find { it.id == turn.groupId })
        require(turn.hop in 0..4 && turn.pendingAgentIds.size <= 2 && turn.pendingAgentIds.all { it in group.memberAgentIds })
        require(turn.status in setOf("active", "summarizing", "completed", "paused", "failed"))
        val previous = groupTurns().find { it.id == turn.id }
        require(previous == null || previous.groupId == turn.groupId && previous.rootSessionId == turn.rootSessionId && turn.hop >= previous.hop)
        write(null, "group-turns.json", GroupTurn.serializer(), (groupTurns().filterNot { it.id == turn.id } + turn).takeLast(200))
    }
    @Synchronized fun saveHandoff(h: AgentHandoff) {
        require(h.hop in 0..4 && h.fromAgentId != h.toAgentId && h.expectedDeliverable.isNotBlank())
        write(null, "handoffs.json", AgentHandoff.serializer(), (handoffs().filterNot { it.id == h.id } + h).takeLast(500))
    }
    @Synchronized fun grants(agentId: String) = read(agentId, "personal-connectors.json", PersonalConnectorGrant.serializer())
    @Synchronized fun tasks(agentId: String) = read(agentId, "tasks.json", PersonalTask.serializer())
    @Synchronized fun calendarDrafts(agentId: String) = read(agentId, "calendar-drafts.json", CalendarDraft.serializer())
    @Synchronized fun saveCalendarDraft(agentId: String, draft: CalendarDraft) {
        draft.validate()
        write(agentId, "calendar-drafts.json", CalendarDraft.serializer(), (calendarDrafts(agentId).filterNot { it.id == draft.id } + draft).takeLast(50))
    }
    @Synchronized fun removeCalendarDraft(agentId: String, id: String) = write(agentId, "calendar-drafts.json", CalendarDraft.serializer(), calendarDrafts(agentId).filterNot { it.id == id })
    @Synchronized fun saveTask(agentId: String, task: PersonalTask) {
        require(task.title.isNotBlank() && task.title.length <= 300 && task.note.length <= 10_000)
        val all = tasks(agentId)
        require(all.any { it.id == task.id } || all.size < 1000) { "At most 1000 local tasks per agent" }
        write(agentId, "tasks.json", PersonalTask.serializer(), all.filterNot { it.id == task.id } + task)
    }
    @Synchronized fun actionRules(agentId: String?): List<app.umbra.core.safety.CustomActionRule> =
        read(null, "action-rules.json", app.umbra.core.safety.CustomActionRule.serializer()) +
            if (agentId == null) emptyList() else read(agentId, "action-rules.json", app.umbra.core.safety.CustomActionRule.serializer())
    @Synchronized fun saveActionRules(agentId: String?, rules: List<app.umbra.core.safety.CustomActionRule>) {
        require(rules.size <= 100 && rules.all { rule -> rule.agentId == agentId && rule.description.isNotBlank() && (rule.scope.maxAmountUsd?.let { it.isFinite() && it >= 0 } != false) })
        write(agentId, "action-rules.json", app.umbra.core.safety.CustomActionRule.serializer(), rules)
    }
    @Synchronized fun saveGrant(agentId: String, grant: PersonalConnectorGrant) {
        require(grant.connectorId in setOf("calendar", "email", "contacts", "tasks", "drive_files"))
        require(!grant.enabled || grant.authorizedAtMs != null)
        if (grant.connectorId == "drive_files" && grant.enabled) {
            require(grant.scope == ConnectorScope.READ_ONLY) { "Document context is read-only" }
            val endpoint = requireNotNull(grant.endpoint)
            val uri = java.net.URI(endpoint)
            require(endpoint.length <= 4096 && uri.scheme == "content" && !uri.authority.isNullOrBlank() &&
                uri.rawPath?.startsWith("/tree/") == true && uri.query == null && uri.fragment == null) { "Choose a documents folder in the Android picker" }
        }
        write(agentId, "personal-connectors.json", PersonalConnectorGrant.serializer(), grants(agentId).filterNot { it.connectorId == grant.connectorId } + grant)
    }
}

class GoalChangedException : IllegalStateException("The goal changed while this update was being prepared. Reload it before trying again.")
