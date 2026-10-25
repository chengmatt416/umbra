package app.umbra.agent.agents.proactive

import app.umbra.agent.AppContainer
import app.umbra.agent.agents.AgentDeliveryRouter
import app.umbra.core.model.UmbraJson
import app.umbra.core.orchestrator.CompletionEvidence
import app.umbra.core.session.*
import app.umbra.runtime.omp.*
import app.umbra.tools.HostSideTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** All routing counters and parent links are host-owned; a model cannot invent a fresh user root. */
class Teams(private val container: AppContainer) {
    private val store get() = container.agents.proactive.store
    private val lock = Mutex()
    private val router = AgentDeliveryRouter(container.laya.engine) { container.laya.state.value == app.umbra.agent.data.LayaSidecar.State.RUNNING }

    fun create(name: String, members: List<String>, coordinator: String): AgentGroup {
        require(members.all { container.agents.store.get(it) != null })
        return AgentGroup(store.id("group"), name.trim(), members, coordinator, System.currentTimeMillis()).also(store::saveGroup)
    }

    suspend fun sendUser(groupId: String, text: String): String = lock.withLock {
        require(text.isNotBlank() && text.length <= 20_000)
        val group = requireNotNull(store.groups().find { it.id == groupId })
        require(store.groupTurns().none { it.groupId == groupId && it.status in setOf("active", "summarizing", "paused") }) { "Finish or stop the current group turn before sending another" }
        val coordinator = requireNotNull(container.agents.store.get(group.coordinatorAgentId)).also { check(it.running) }
        val root = container.supervisor.create(text, title = group.name, model = coordinator.model, effort = coordinator.effort, kind = SessionKind.AGENT, agentId = coordinator.id)
        val id = store.id("discussion")
        val targets = select(group, id, text)
        store.appendGroup(GroupMessage(id, group.id, null, text, System.currentTimeMillis(), id, targetAgentIds = targets, sessionId = root.id))
        store.saveGroupTurn(GroupTurn(id, group.id, root.id, targets))
        advance(requireNotNull(store.groupTurns().find { it.id == id }))
        root.id
    }

    private suspend fun select(group: AgentGroup, key: String, text: String): List<String> {
        val eligible = group.memberAgentIds.mapNotNull { container.agents.store.get(it)?.takeIf { p -> p.running } }
        val mentioned = mentioned(text, eligible.map { it.id to it.name })
        if (mentioned.isNotEmpty()) return mentioned.take(2)
        val selected = router.recipients(key, text, eligible.map { AgentDeliveryRouter.Recipient(it.id, it.name, container.agents.store.soul(it.id)) })
        // The general router preserves subscriptions on uncertainty. A group instead has one accountable fallback.
        return if (selected.isEmpty() || selected.size > 2) listOf(group.coordinatorAgentId) else selected.toList().take(2)
    }

    private suspend fun advance(turn: GroupTurn) {
        if (turn.status !in setOf("active", "summarizing") || container.emergency.lockdownReason() != null) return
        if (turn.activeSessionId != null) return
        val group = requireNotNull(store.groups().find { it.id == turn.groupId })
        val messages = store.groupMessages(group.id).filter { it.rootMessageId == turn.id }
        val final = turn.hop >= 3 || turn.pendingAgentIds.isEmpty()
        val target = if (final) group.coordinatorAgentId else turn.pendingAgentIds.first()
        val profile = requireNotNull(container.agents.store.get(target))
        if (!profile.running) { store.saveGroupTurn(turn.copy(status = "paused", error = "${profile.name} is stopped")); return }
        val hop = if (final) 4 else turn.hop + 1
        val prefix = "[GroupTurn ${turn.id} $hop]"
        val task = prefix + "\nGroup: ${group.name}. Your role: ${profile.name}.\n" +
            messages.joinToString("\n\n") { "${it.fromAgentId?.let { id -> container.agents.store.get(id)?.name } ?: "User"}: ${it.text}" }.take(40_000) +
            if (final) "\nGive the coordinator's final summary, unresolved questions and evidence. Return the floor to the user. Do not request another group turn." else
                "\nAnswer the user's request from your role. Use group_send only to request up to two named members for the next turn. The host enforces four hops, ending in a coordinator summary."
        val session = if (final) {
            val root = requireNotNull(container.sessionStore.get(turn.rootSessionId))
            require(root.state == SessionState.CREATED)
            container.sessionStore.update(root.id) { it.copy(request = task, attachments = it.attachments + (GROUP_KEY to JsonPrimitive(turn.id))) }!!
        } else container.sessionStore.list().find { it.request.startsWith(prefix) } ?: container.supervisor.create(task, title = "${group.name} · ${profile.name}", model = profile.model, effort = profile.effort,
            kind = SessionKind.AGENT, agentId = profile.id, spawnedBy = turn.rootSessionId).also { child ->
                container.sessionStore.update(child.id) { it.copy(attachments = it.attachments + (GROUP_KEY to JsonPrimitive(turn.id))) }
            }
        store.saveGroupTurn(turn.copy(hop = hop, activeSessionId = session.id, status = if (final) "summarizing" else "active",
            pendingAgentIds = if (final) emptyList() else turn.pendingAgentIds.drop(1), error = null))
        try { if (session.state == SessionState.CREATED && session.queuedAtMs == null) container.supervisor.enqueue(session.id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { store.saveGroupTurn(requireNotNull(store.groupTurns().find { it.id == turn.id }).copy(status = "paused", error = e.message?.take(500))) }
    }

    suspend fun onCompleted(session: Session, text: String): Boolean = lock.withLock { settleCompleted(session, text) }

    private suspend fun settleCompleted(finished: Session, text: String): Boolean {
        val session = container.sessionStore.get(finished.id) ?: return false
        val groupId = (session.attachments[GROUP_KEY] as? JsonPrimitive)?.content
        if (!session.state.isTerminal) return groupId != null || store.handoffs().any { it.executionSessionId == session.id }
        val settledText = if (session.updatedAtMs == finished.updatedAtMs) text else session.resultSummary.orEmpty()
        if (groupId != null) {
            val turn = store.groupTurns().find { it.id == groupId } ?: return true
            if (turn.activeSessionId != session.id || turn.status !in setOf("active", "summarizing")) return true
            if (awaitingDelegates(session)) return true
            val messageId = "${turn.id}:${turn.hop}"
            val requested = (session.attachments[TARGETS_KEY] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            if (store.groupMessages(turn.groupId).none { it.id == messageId }) store.appendGroup(GroupMessage(messageId, turn.groupId, session.agentId,
                settledText.ifBlank { session.lastError ?: "No answer was produced" }.take(20_000), System.currentTimeMillis(), turn.id, turn.hop, requested, session.id))
            container.supervisor.releaseIfIdle(session.id, 0)
            if (turn.status == "summarizing") store.saveGroupTurn(turn.copy(status = if (session.state == SessionState.COMPLETED) "completed" else "failed", activeSessionId = null))
            else {
                val next = turn.copy(activeSessionId = null, pendingAgentIds = (turn.pendingAgentIds + requested).distinct().take(2))
                store.saveGroupTurn(next); advance(next)
            }
            return true
        }
        val handoff = store.handoffs().find { it.executionSessionId == session.id && it.status in setOf("pending", "running", "reported") } ?: return false
        if (awaitingDelegates(session)) return true
        val verified = verified(session) && session.state == SessionState.COMPLETED
        val complete = handoff.copy(status = if (verified) "completed" else "failed", summary = (handoff.summary ?: settledText).take(6000),
            deliverablePath = "session:${session.id}#verification")
        store.saveHandoff(complete)
        if (verified && handoff.goalId != null && handoff.milestoneId != null) {
            store.goals(handoff.fromAgentId).find { it.id == handoff.goalId }?.let { g ->
                val milestone = g.milestones.find { it.id == handoff.milestoneId && it.assignedTo == "#${session.id}" }
                if (milestone != null) store.updateGoal(g, g.copy(milestones = g.milestones.map { if (it.id == milestone.id) it.copy(status = "done", artifactPath = complete.deliverablePath, updatedAtMs = System.currentTimeMillis()) else it },
                    deliverables = (g.deliverables + requireNotNull(complete.deliverablePath)).distinct(), lastProgressAtMs = System.currentTimeMillis()))
            }
        }
        container.supervisor.releaseIfIdle(session.id, 0)
        handoff.sourceSessionId?.let { source ->
            val parent = container.sessionStore.get(source)
            if (parent != null && parent.state != SessionState.STOPPED && container.agents.store.get(handoff.fromAgentId)?.running == true) {
                container.sessionStore.update(source) { it.copy(attachments = it.attachments + ("child.verification.${session.id}" to buildJsonObject {
                    put("sessionId", session.id); put("atMs", session.updatedAtMs); put("verified", verified)
                })) }
                container.supervisor.send(source, "[Structured handoff ${handoff.id} ${complete.status}]\n${complete.summary}\nEvidence: ${complete.deliverablePath}", messageSource = "report")
            }
        }
        return true
    }

    private suspend fun awaitingDelegates(session: Session): Boolean {
        if (session.state != SessionState.COMPLETED) return false
        val current = container.sessionStore.get(session.id) ?: return true
        if (!current.state.isTerminal) return true
        if (!CompletionEvidence.awaitingDelegates(current, container.sessionStore.list(SessionFilter(limit = Int.MAX_VALUE)))) return false
        container.supervisor.releaseIfIdle(session.id, 0)
        return true
    }

    suspend fun reconcile() = lock.withLock {
        for (turn in store.groupTurns().filter { it.status in setOf("active", "summarizing") }) {
            val active = turn.activeSessionId?.let { container.sessionStore.get(it) }
            if (active == null && turn.activeSessionId != null) store.saveGroupTurn(turn.copy(status = "failed", error = "The active task is missing; review before starting another discussion."))
            else if (active == null) advance(turn)
            else if (active.state.isTerminal) settleCompleted(active, active.resultSummary.orEmpty())
            else if (active.state == SessionState.PAUSED) store.saveGroupTurn(turn.copy(status = "paused", error = "The app restarted or the user paused this discussion. Resume its linked task to continue."))
            else if (active.state == SessionState.CREATED && active.queuedAtMs == null) container.supervisor.enqueue(active.id)
        }
        for (handoff in store.handoffs().filter { it.status in setOf("pending", "running", "reported") }) {
            val session = handoff.executionSessionId?.let { container.sessionStore.get(it) }
            if (handoff.deadlineMs?.let { it <= System.currentTimeMillis() } == true) {
                session?.let { if (it.queuedAtMs != null) container.supervisor.dequeue(it.id) else if (it.state.isActive) container.supervisor.pause(it.id, "The handoff deadline expired") }
                store.saveHandoff(handoff.copy(status = "expired", summary = "The handoff deadline expired; no automatic retry was made."))
            } else if (session == null) store.saveHandoff(handoff.copy(status = "failed", summary = "The delegated task is missing; review before retrying."))
            else if (session.state.isTerminal) settleCompleted(session, session.resultSummary.orEmpty())
            else if (session.state == SessionState.CREATED && session.queuedAtMs == null && container.agents.store.get(handoff.toAgentId)?.running == true) container.supervisor.enqueue(session.id)
        }
    }

    suspend fun resume(groupId: String) = lock.withLock {
        val turn = requireNotNull(store.groupTurns().lastOrNull { it.groupId == groupId && it.status == "paused" })
        val active = turn.activeSessionId?.let { container.sessionStore.get(it) }
        val next = turn.copy(status = if (turn.hop == 4) "summarizing" else "active", error = null)
        store.saveGroupTurn(next)
        if (active?.state == SessionState.PAUSED) container.supervisor.resume(active.id)
        else if (active?.state == SessionState.CREATED && active.queuedAtMs == null) container.supervisor.enqueue(active.id)
        else if (active == null) advance(next.copy(activeSessionId = null))
        else if (active.state.isTerminal) settleCompleted(active, active.resultSummary.orEmpty())
    }

    suspend fun stop(groupId: String) = lock.withLock {
        store.groupTurns().filter { it.groupId == groupId && it.status !in setOf("completed", "failed") }.forEach { turn ->
            store.saveGroupTurn(turn.copy(status = "failed", error = "Stopped by the user"))
            val sessions = container.sessionStore.list(SessionFilter(limit = Int.MAX_VALUE))
            val selected = mutableSetOf(turn.rootSessionId)
            repeat(32) { sessions.filter { it.spawnedBy in selected }.forEach { selected += it.id } }
            sessions.filter { it.id in selected }.forEach { s ->
                if (s.queuedAtMs != null) container.supervisor.dequeue(s.id) else if (!s.state.isTerminal) container.supervisor.stop(s.id)
            }
        }
    }

    fun forSession(agentId: String, sessionId: String) = listOf(
        HostSideTool(HostToolDefinition("group_read", "Read only groups this agent belongs to, including persisted messages and linked results.", objectSchema { string("group_id") })) { call, _ ->
            val groups = store.groups().filter { agentId in it.memberAgentIds && ((call.arguments["group_id"] as? JsonPrimitive)?.content?.let { id -> it.id == id } != false) }
            HostToolPayload.text(buildJsonArray { groups.forEach { g -> add(buildJsonObject { put("group", UmbraJson.encodeToJsonElement(AgentGroup.serializer(), g)); put("messages", JsonArray(store.groupMessages(g.id).takeLast(50).map { UmbraJson.encodeToJsonElement(GroupMessage.serializer(), it) })) }) } }.toString())
        },
        HostSideTool(HostToolDefinition("group_send", "Request up to two group members for the next turn of this discussion. Four host-counted hops end with the coordinator; this tool cannot start a new user discussion.", objectSchema { stringArray("target_agent_ids", required = true) })) { call, _ ->
            val session = requireNotNull(container.sessionStore.get(sessionId))
            val turnId = (session.attachments[GROUP_KEY] as? JsonPrimitive)?.content ?: error("This session is not a group turn")
            val turn = requireNotNull(store.groupTurns().find { it.id == turnId })
            val group = requireNotNull(store.groups().find { it.id == turn.groupId })
            require(turn.activeSessionId == sessionId && turn.status == "active" && turn.hop < 3 && agentId in group.memberAgentIds)
            val targets = (call.arguments["target_agent_ids"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.distinct()
            require(targets.size in 1..2 && targets.all { it in group.memberAgentIds && it != agentId })
            container.sessionStore.update(sessionId) { it.copy(attachments = it.attachments + (TARGETS_KEY to JsonArray(targets.map(::JsonPrimitive)))) }
            HostToolPayload.text("The next turn is requested. Finish this answer; the host routes it within the remaining hop budget.")
        },
        HostSideTool(HostToolDefinition("handoff_delegate", "Delegate a structured, scoped task to a different specialist. Parent task/agent budgets, routine scopes and the four-hop limit remain in force. No new authority is granted.", objectSchema {
            string("to_agent_id", required = true); string("instruction", required = true); string("expected_deliverable", required = true)
            string("goal_id"); string("milestone_id"); integer("deadline_ms"); stringArray("context_refs")
        })) { call, _ -> lock.withLock {
            val source = requireNotNull(container.sessionStore.get(sessionId))
            require(source.agentId == agentId && source.turnMode == ProactiveTurnMode.AUTHORIZED_EXECUTION)
            require(source.attachments[GROUP_KEY] == null) { "Use group_send inside a bounded group discussion" }
            val to = requireNotNull(container.agents.store.get(call.arguments.required("to_agent_id"))).also { require(it.running && it.id != agentId) }
            var ancestor: Session? = source
            var inherited: AgentHandoff? = null
            val seen = mutableSetOf<String>()
            while (ancestor != null && seen.add(ancestor.id) && seen.size <= 32) {
                val current = ancestor
                inherited = store.handoffs().find { it.executionSessionId == current.id }
                if (inherited != null) break
                ancestor = current.spawnedBy?.let { container.sessionStore.get(it) }
            }
            val hop = (inherited?.hop ?: 0) + 1
            require(hop <= 4) { "The four-hop delegation limit was reached; report back to the user" }
            val id = store.id("handoff")
            val deadline = (call.arguments["deadline_ms"] as? JsonPrimitive)?.longOrNull
            require(deadline == null || deadline > System.currentTimeMillis())
            val h = AgentHandoff(id, agentId, to.id, call.arguments.required("instruction"), (call.arguments["goal_id"] as? JsonPrimitive)?.content,
                (call.arguments["milestone_id"] as? JsonPrimitive)?.content, call.arguments.required("expected_deliverable"), deadline,
                (call.arguments["context_refs"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.take(20), System.currentTimeMillis(), hop = hop,
                rootId = inherited?.rootId ?: source.id, sourceSessionId = sessionId)
            require(h.instruction.length <= 20_000 && h.expectedDeliverable.length <= 4000)
            val goal = h.goalId?.let { goalId -> requireNotNull(store.goals(agentId).find { it.id == goalId }) }
            require(goal?.state !in setOf(GoalState.PAUSED, GoalState.COMPLETED, GoalState.ARCHIVED)) { "Resume or reopen the goal in the app before delegating work" }
            if (h.milestoneId != null) {
                val milestone = requireNotNull(goal?.milestones?.find { it.id == h.milestoneId && it.status == "pending" }) { "Delegate only a pending milestone of an owned goal" }
                require(milestone.dependsOn.all { dep -> goal.milestones.find { it.id == dep }?.status == "done" }) { "Complete milestone dependencies first" }
            }
            val task = container.supervisor.create("[Handoff $id]\n${h.instruction}\nExpected deliverable: ${h.expectedDeliverable}\nDeadline: ${h.deadlineMs}\nExplicitly shared context references: ${h.contextRefs.joinToString()}\nUse handoff_complete to report, and record independent verification before ending.", title = "${to.name} · delegated task", model = to.model, effort = to.effort,
                kind = SessionKind.AGENT, agentId = to.id, spawnedBy = source.id, connectorScope = source.connectorScope, approvalTtlMs = source.approvalTtlMs ?: 600_000)
            try {
                if (goal != null && h.milestoneId != null) goal.let { g ->
                    store.updateGoal(g, g.copy(milestones = g.milestones.map { if (it.id == h.milestoneId) it.copy(status = "active", assignedTo = "#${task.id}") else it }, linkedSessionIds = (g.linkedSessionIds + task.id).distinct()))
                }
                store.saveHandoff(h.copy(status = "running", executionSessionId = task.id))
            } catch (e: Exception) {
                container.supervisor.stop(task.id, "The handoff was not committed")
                throw e
            }
            container.sessionBus.announceHandoff(source.id, task.id, "${h.expectedDeliverable}\nHandoff: $id", hop)
            container.supervisor.enqueue(task.id)
            HostToolPayload.text("Delegated $id to ${to.name}. Task ${task.id} shares the parent's usage limits and may be queued until this turn yields.")
        } },
        HostSideTool(HostToolDefinition("handoff_complete", "Report the assigned handoff. The host checks the final recorded verification receipt before completing a milestone or notifying the coordinator.", objectSchema { string("handoff_id", required = true); string("summary", required = true) })) { call, _ ->
            val h = requireNotNull(store.handoffs().find { it.id == call.arguments.required("handoff_id") && it.executionSessionId == sessionId && it.toAgentId == agentId && it.status in setOf("running", "reported") })
            store.saveHandoff(h.copy(status = "reported", summary = call.arguments.required("summary").take(6000)))
            HostToolPayload.text("Report recorded. Finish the turn so the host can check its verification receipt.")
        },
    )
    companion object {
        private const val GROUP_KEY = "team.group.turn"
        private const val TARGETS_KEY = "team.group.targets"
        fun mentioned(text: String, members: List<Pair<String, String>>): List<String> = members.filter { (id, name) ->
            listOf(id, name).any { token -> Regex("(?iu)(?<![\\p{L}\\p{N}_])@${Regex.escape(token)}(?![\\p{L}\\p{N}_])").containsMatchIn(text) }
        }.map { it.first }.distinct()
        private fun verified(s: Session) = ((s.attachments[CompletionEvidence.KEY] as? JsonObject)?.get("verification") as? JsonObject)?.get("verified") == JsonPrimitive(true)
        private fun JsonObject.required(name: String) = (this[name] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: error("$name is required")
    }
}
