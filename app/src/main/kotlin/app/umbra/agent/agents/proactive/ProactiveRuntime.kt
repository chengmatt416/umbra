package app.umbra.agent.agents.proactive

import android.app.NotificationManager
import android.media.AudioManager
import app.umbra.agent.AppContainer
import app.umbra.agent.agents.AgentProfile
import app.umbra.agent.agents.AgentRuntime
import app.umbra.agent.agents.Cron
import app.umbra.agent.agents.Incoming
import app.umbra.agent.agents.MemoryCategory
import app.umbra.core.orchestrator.SessionSpec
import app.umbra.core.orchestrator.CompletionEvidence
import app.umbra.core.session.ProactiveTurnMode
import app.umbra.core.session.Session
import app.umbra.core.session.SessionKind
import app.umbra.core.session.SessionState
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Background signals create separate immutable discovery sessions; only user-reviewed routines execute. */
class ProactiveRuntime(private val container: AppContainer, private val agents: AgentRuntime) {
    val store = ProactiveStore(File(container.context.filesDir, "agents"))
    val http = ResearchHttp()
    private val tickLock = Mutex()
    private val adoptionLock = Mutex()
    private val briefs = DailyBriefEngine(store)
    private val immersiveApps = ImmersiveApps { packageName -> container.context.packageManager.getApplicationInfo(packageName, 0).category }
    private val watcherEngine = WatcherEngine(store, ::probe, { question, evidence ->
        val answer = container.decisions.judge(question, "Untrusted probe evidence:\n$evidence")
        if (!answer.usedFallback && answer.trusted && answer.confidence >= .8) answer.value else null
    }, { watcher, value ->
        val p = requireNotNull(agents.store.get(watcher.agentId))
        val routines = store.routines(p.id).filter { it.enabled && (it.trigger as? RoutineTrigger.WatcherMatch)?.watcherId == watcher.id }
        if (routines.isEmpty()) discovery(p, "[Watcher ${watcher.id}] ${watcher.onMatchInstruction}\nUntrusted observation:\n${value.take(6000)}", watcher)
        else routines.forEach { runRoutine(p, it, "watcher:${watcher.id}:${watcher.lastHash}", watcher) }
    })

    suspend fun checkAuthority(session: Session, nativeHandoff: Boolean = false) {
        container.credentialHandoff.fence(session.id, nativeHandoff)?.let { error(it) }
        val groupTurns = store.groupTurns()
        val handoffs = store.handoffs()
        session.agentId?.let { id -> check(agents.store.get(id)?.running == true) { "The owning agent is stopped. Start it before resuming its work." } }
        val groupId = (session.attachments["team.group.turn"] as? JsonPrimitive)?.content
        if (groupId != null) {
            val turn = groupTurns.find { it.id == groupId }
            check(turn != null && turn.activeSessionId == session.id && turn.status in (if (nativeHandoff) setOf("active", "summarizing", "paused") else setOf("active", "summarizing"))) { "This group turn is paused, stopped or no longer owns the floor" }
        } else check(groupTurns.none { it.rootSessionId == session.id && it.status in setOf("active", "paused", "summarizing") }) { "This task is reserved for the coordinator's final group summary" }
        var current: Session? = session
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current.id) && seen.size <= 32) {
            val task = current
            task.agentId?.let { id -> check(agents.store.get(id)?.running == true) { "An owning agent in this task's ancestry was stopped or removed" } }
            val enclosingGroup = (task.attachments["team.group.turn"] as? JsonPrimitive)?.content?.let { id -> groupTurns.find { it.id == id } }
                ?: groupTurns.find { it.rootSessionId == task.id }
            check(enclosingGroup?.status !in (if (nativeHandoff) setOf("failed") else setOf("paused", "failed"))) { "The enclosing group discussion was paused or stopped" }
            val parent = task.spawnedBy?.let { requireNotNull(container.sessionStore.get(it)) { "The parent task was removed" } }
            task.routineRunId?.takeIf { it != parent?.routineRunId }?.let { runId ->
                val routine = task.agentId?.let { id -> store.routines(id).find { r -> r.recentRuns.any { it.runId == runId } } }
                check(routine?.authorizes(runId) == true && task.connectorScope?.toSet() == routine.allowedConnectors.toSet()) { "The routine was disabled, removed or reconfigured. Review it before starting a new run." }
            }
            val ownerId = task.agentId
            val handoff = handoffs.find { it.executionSessionId == task.id }
            if (handoff != null || task.request.startsWith("[Handoff ")) {
                val goal = handoff?.goalId?.let { id -> store.goals(handoff.fromAgentId).find { it.id == id } }
                check(HandoffAuthority.allows(handoff, task, System.currentTimeMillis(), goal)) { "The handoff expired, ended or its goal is no longer authorized" }
            }
            task.attachments[WatcherAuthority.KEY]?.let { raw ->
                val stamp = raw as? JsonObject ?: error("Watcher authority is invalid")
                val watcherId = stamp["id"]?.jsonPrimitive?.contentOrNull
                val watcher = ownerId?.let { id -> store.watchers(id).find { it.id == watcherId } }
                check(WatcherAuthority.allows(watcher, stamp)) { "The watcher was removed, revoked or reconfigured" }
            }
            if (task.request.startsWith("[GoalRun ") && ownerId != null) {
                check(store.goals(ownerId).any { GoalAuthority.allows(it, task) }) { "The goal was paused, replanned or its execution permission was revoked" }
            }
            current = parent
        }
        check(current == null) { "The task ancestry could not be verified" }
    }

    suspend fun setRoutineEnabled(agentId: String, id: String, enabled: Boolean) {
        val r = requireNotNull(store.routines(agentId).find { it.id == id })
        if (enabled) r.skillId?.let { require(container.skills.get(it)?.status == UmbraSkill.Status.READY) { "Enable the tested skill first" } }
        store.saveRoutine(r.copy(enabled = enabled, authorizedAtMs = if (enabled) System.currentTimeMillis() else null, nextRunAtMs = null))
        if (!enabled) cancelRuns(r.recentRuns.mapNotNull { it.sessionId }.toSet(), "The routine was disabled")
        agents.reschedule()
    }

    suspend fun pauseGoal(agentId: String, id: String, paused: Boolean) {
        val (goal, _) = store.changeGoal(agentId, id) { it.copy(state = if (paused) GoalState.PAUSED else GoalState.IN_PROGRESS) }
        val active = goal.milestones.filter { it.status in setOf("active", "verifying") }.mapNotNull { it.assignedTo?.takeIf { ref -> ref.startsWith("#") }?.removePrefix("#") }.toSet()
        if (paused) cancelRuns(active, "The goal was paused")
        else for (sid in active) if (container.sessionStore.get(sid)?.state == SessionState.PAUSED) container.supervisor.resume(sid)
        requestGoalCheck(agentId)
    }
    suspend fun authorizeGoal(agentId: String, id: String, enabled: Boolean) {
        val (goal, _) = store.changeGoal(agentId, id) { current ->
            val active = current.milestones.filter { it.status in setOf("active", "verifying") }
            current.copy(autoAdvance = enabled, authorizedAtMs = if (enabled) System.currentTimeMillis() else null,
                milestones = if (enabled) current.milestones.map { if (it in active) it.copy(status = "pending", assignedTo = null) else it } else current.milestones,
                state = if (enabled && active.isNotEmpty() && current.state == GoalState.WAITING_USER) GoalState.IN_PROGRESS else current.state, lastCheckedAtMs = null)
        }
        val active = goal.milestones.filter { it.status in setOf("active", "verifying") }
        cancelRuns(active.mapNotNull { it.assignedTo?.takeIf { ref -> ref.startsWith("#") }?.removePrefix("#") }.toSet(), "Goal execution permission changed; review the current state before starting a new run")
        requestGoalCheck(agentId)
    }

    fun replanGoal(previous: AgentGoal, next: AgentGoal) {
        GoalUpdates.requireModelUpdate(previous, next)
        store.updateGoal(previous, next)
        val roots = previous.milestones.filter { it.status in setOf("active", "verifying") }.mapNotNull { it.assignedTo?.takeIf { ref -> ref.startsWith("#") }?.removePrefix("#") }.toSet()
        container.scope.launch {
            try { cancelRuns(roots, "The goal was replanned; the old milestone is no longer authorized") }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { runCatching { store.writeNote(previous.agentId, previous.id, "An old milestone could not be paused after replanning. Its previous goal authority is no longer valid; review its linked task before continuing.", true) } }
            requestGoalCheck(previous.agentId)
        }
    }

    fun requestGoalCheck(agentId: String) {
        container.scope.launch(Dispatchers.IO) {
            try { tickLock.withLock {
                if (container.emergency.lockdownReason() != null || container.credentialHandoff.fence("", false) != null) return@withLock
                agents.store.get(agentId)?.takeIf { it.running && it.proactivityLevel >= 3 }?.let { checkGoals(it, System.currentTimeMillis(), forced = true) }
            } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("ProactiveRuntime", "Goal reconciliation could not finish", e) }
            finally { agents.reschedule() }
        }
    }

    private suspend fun cancelRuns(roots: Set<String>, reason: String) {
        val all = container.sessionStore.list(app.umbra.core.session.SessionFilter(limit = Int.MAX_VALUE))
        val selected = roots.toMutableSet()
        repeat(32) { all.filter { it.spawnedBy in selected }.forEach { selected += it.id } }
        all.filter { it.id in selected }.forEach { task ->
            if (task.queuedAtMs != null) container.supervisor.dequeue(task.id)
            else if (task.state.isActive) container.supervisor.pause(task.id, reason)
        }
    }

    private suspend fun reconcile(p: AgentProfile) {
        val sessions = container.sessionStore.list(app.umbra.core.session.SessionFilter(limit = Int.MAX_VALUE))
        for (routine in store.routines(p.id)) for (run in routine.recentRuns.filter { it.finishedAtMs == null }) {
            val session = run.sessionId?.let { id -> sessions.find { it.id == id } } ?: sessions.find { it.routineRunId == run.runId }
            if (session == null) {
                store.updateRun(p.id, routine.id, run.copy(status = "failed", finishedAtMs = System.currentTimeMillis(), error = "Interrupted before a task was created; review before retrying."))
            } else {
                if (run.sessionId == null) store.updateRun(p.id, routine.id, run.copy(sessionId = session.id))
                if (session.state.isTerminal) settleRoutine(p.id, routine.id, run.copy(sessionId = session.id), session)
            }
        }
        adoptionLock.withLock {
            val current = container.sessionStore.list()
            for (card in store.opportunities(p.id).filter { it.state == OpportunityCard.State.ACCEPTING || it.state == OpportunityCard.State.ACCEPTED && it.executionSessionId == null }) {
                val task = current.find { it.agentId == p.id && it.request.startsWith("[User adopted opportunity ${card.id}]") }
                store.saveOpportunity(if (task != null) card.copy(state = OpportunityCard.State.ACCEPTED, executionSessionId = task.id) else card.copy(state = OpportunityCard.State.PENDING, error = "Interrupted before a task was created; review and adopt again."))
            }
        }
    }

    private suspend fun settleRoutine(agentId: String, routineId: String, run: RoutineRunRecord, session: Session) {
        if (CompletionEvidence.awaitingDelegates(session, container.sessionStore.list(app.umbra.core.session.SessionFilter(limit = Int.MAX_VALUE)))) return
        val expired = container.sessionStore.tail(session.id, 1000).any { (it.event as? app.umbra.core.session.SessionEvent.Approval)?.kind == "timed_out" }
        store.updateRun(agentId, routineId, run.copy(finishedAtMs = System.currentTimeMillis(), status = if (expired) "approval_expired" else if (session.state == SessionState.COMPLETED) "succeeded" else "failed",
            summary = session.resultSummary.orEmpty().take(2000), error = session.lastError))
    }

    suspend fun discovery(p: AgentProfile, instruction: String, watcher: AgentWatcher? = null): Session {
        check(agents.store.get(p.id)?.let { it.running && it.proactivityLevel >= 1 } == true)
        return submitBackground(p, instruction, "${p.name} · research", SessionSpec(kind = SessionKind.AGENT, agentId = p.id, spawnedBy = p.sessionId,
            turnMode = ProactiveTurnMode.READ_ONLY_DISCOVERY), watcher)
    }

    private suspend fun submitBackground(p: AgentProfile, instruction: String, title: String, spec: SessionSpec, watcher: AgentWatcher?): Session {
        if (watcher == null) return container.supervisor.submit(instruction, title = title, model = p.model, effort = p.effort, spec = spec)
        val stamp = WatcherAuthority.stamp(watcher)
        check(WatcherAuthority.allows(store.watchers(p.id).find { it.id == watcher.id }, stamp)) { "The watcher was revoked before dispatch" }
        val session = container.supervisor.create(instruction, title = title, model = p.model, effort = p.effort,
            kind = spec.kind, agentId = spec.agentId, spawnedBy = spec.spawnedBy, turnMode = spec.turnMode,
            routineRunId = spec.routineRunId, connectorScope = spec.connectorScope, approvalTtlMs = spec.approvalTtlMs)
        try {
            val recorded = requireNotNull(container.sessionStore.update(session.id) { it.copy(attachments = it.attachments + (WatcherAuthority.KEY to stamp)) })
            checkAuthority(recorded)
            return container.supervisor.enqueue(session.id)
        } catch (e: Exception) {
            container.supervisor.stop(session.id, "The watcher dispatch did not finish")
            throw e
        }
    }

    suspend fun tick(now: Long = System.currentTimeMillis()) = tickLock.withLock {
        if (container.emergency.lockdownReason() != null || container.credentialHandoff.fence("", false) != null) return@withLock
        container.teams.reconcile()
        for (p in agents.store.profiles.value.filter { it.running && it.proactivityLevel >= 1 }) {
            reconcile(p)
            if (p.proactivityLevel >= 3) checkGoals(p, now)
            if (p.proactivityLevel >= 2) watcherEngine.tick(p.id, now)
            for (routine in store.routines(p.id).filter { it.enabled && it.trigger is RoutineTrigger.CronSchedule }) {
                val cron = routine.trigger as RoutineTrigger.CronSchedule
                val zone = ZoneId.of(cron.zoneId)
                if (routine.nextRunAtMs == null) store.saveRoutine(routine.copy(nextRunAtMs = Cron(cron.cron).next(now, zone)))
                else if (routine.nextRunAtMs <= now) {
                    val scheduled = routine.nextRunAtMs
                    // Advance before dispatch; missed intervals coalesce into one durable run.
                    store.saveRoutine(routine.copy(nextRunAtMs = Cron(cron.cron).next(now, zone)))
                    runRoutine(p, requireNotNull(store.routines(p.id).find { it.id == routine.id }), "cron:$scheduled")
                }
            }
            val events = container.personalContext.calendar(p.id, now, now + 7 * 86_400_000L)
            for ((kind, minute) in listOf(DailyBrief.Kind.MORNING to p.morningBriefMinute, DailyBrief.Kind.EVENING to p.eveningBriefMinute)) {
                if (minute == null) continue
                val at = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
                if (at.hour * 60 + at.minute < minute) continue
                val id = "brief_${at.toLocalDate()}_${kind.name.lowercase()}"
                if (store.briefs(p.id).any { it.id == id }) continue
                val complete = container.sessionStore.list().filter { it.agentId == p.id && it.state == SessionState.COMPLETED && now - it.updatedAtMs < 86_400_000 }
                    .map { BriefItem(it.title, it.resultSummary.orEmpty(), linkedSessionId = it.id) }
                val brief = briefs.generate(p.id, kind, now, events, complete)
                if (brief.attentionNeeded.isNotEmpty() || brief.scheduleHighlights.isNotEmpty() || brief.overnightCompleted.isNotEmpty() || brief.lowPriorityDigest.isNotBlank())
                    deliver(p, AttentionItem(id, if (kind == DailyBrief.Kind.MORNING) "Morning brief" else "Evening wrap-up", brief.headline, .6, .9, .95, "brief", now),
                        deliveryDetail = BriefText.format(brief))
            }
            if (p.proactivityLevel < 3) continue
            val staged = store.attention(p.id).map { it.id }.toSet()
            for (goal in store.goals(p.id).filter { it.state == GoalState.IN_PROGRESS && now - it.lastProgressAtMs > it.stalledAfterHours * 3_600_000L }) {
                val key = "stalled:${goal.id}:${goal.lastProgressAtMs}"
                if (key in staged) continue
                store.stage(p.id, AttentionItem(key, "Stalled goal: ${goal.title}", goal.description.take(500), .4, .7, .9, "goal", now))
                discovery(p, "[Stalled Goal: ${goal.id}] Investigate why ${goal.title} has stalled. Read authorized sources and propose a next step. Do not execute changes.")
            }
            for (event in events.filter { it.startMs > now && it.startMs - now <= p.prepareMeetingMinutes.coerceIn(5, 120) * 60_000L }) {
                val key = "meeting:${event.id}:${event.startMs}"
                if (key in staged) continue
                store.stage(p.id, AttentionItem(key, "Preparing ${event.title}", event.location, .7, .7, .9, "calendar", now))
                discovery(p, "Prepare a concise pre-meeting dossier for ${event.title}, starting ${Instant.ofEpochMilli(event.startMs)}. Attendees: ${event.attendees.joinToString()}. Search authorized memory, messages, email and workspace context. Write a private note with prior decisions, open questions and next steps; propose any useful action for user review.")
            }
        }
    }

    suspend fun onIncoming(p: AgentProfile, item: Incoming): Boolean {
        if (container.emergency.lockdownReason() != null) return false
        if (p.proactivityLevel < 2) return false
        var dispatched = false
        for (r in store.routines(p.id).filter { it.enabled }) {
            val trigger = r.trigger as? RoutineTrigger.EventMatch ?: continue
            if (!(trigger.source == item.source || trigger.source == item.sourceLabel)) continue
            if (trigger.senderOrChannelFilter?.let { it != item.from && it != item.chat } == true) continue
            if (trigger.textContains?.let { !item.text.contains(it, true) } == true) continue
            if (trigger.semanticCondition != null) {
                val verdict = container.decisions.judge(trigger.semanticCondition, "Untrusted incoming item:\n${item.line()}")
                if (verdict.usedFallback || !verdict.trusted || verdict.confidence < .8 || verdict.value != true) continue
            }
            runRoutine(p, r, "event:${item.key}")
            dispatched = true
        }
        return dispatched
    }

    suspend fun runRoutine(p: AgentProfile, r: AgentRoutine, key: String, watcher: AgentWatcher? = null): Session? {
        check(agents.store.get(p.id)?.let { it.running && it.proactivityLevel >= 1 } == true)
        val run = store.claimRun(p.id, r.id, key, System.currentTimeMillis()) ?: return null
        return try {
            val skillText = r.skillId?.let { id -> container.skills.instructionsForRun(id, r.skillArgs) }.orEmpty()
            val session = submitBackground(p, "[Routine ${r.id}, run ${run.runId}]\n${r.instruction}\n$skillText", "${p.name} · ${r.title}",
                SessionSpec(kind = SessionKind.AGENT, agentId = p.id, spawnedBy = p.sessionId, routineRunId = run.runId, connectorScope = r.allowedConnectors, approvalTtlMs = r.approvalTTLMinutes * 60_000L), watcher)
            val latest = store.routines(p.id).find { it.id == r.id }?.recentRuns?.find { it.runId == run.runId } ?: run
            store.updateRun(p.id, r.id, latest.copy(sessionId = session.id))
            session
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            store.updateRun(p.id, r.id, run.copy(finishedAtMs = System.currentTimeMillis(), status = "failed", error = e.message?.take(500)))
            throw e
        }
    }

    private suspend fun checkGoals(p: AgentProfile, now: Long, forced: Boolean = false) {
        val due = store.goals(p.id).filter { GoalSchedule.nextCheck(it)?.let { at -> forced || at <= now } == true }
            .sortedByDescending { GoalSchedule.effectivePriority(it, now) }
        for (goal in due) {
            try {
                if (p.proactivityLevel >= 4 && goal.autoAdvance && goal.authorizedAtMs != null) advanceGoals(p, now, goal.id)
                val current = store.goals(p.id).find { it.id == goal.id } ?: continue
                if (!GoalSchedule.tracks(current)) continue
                current.deadlineMs?.takeIf { it - now <= 86_400_000L }?.let { deadline ->
                    val overdue = deadline <= now
                    val key = "deadline:${current.id}:$deadline:$overdue"
                    val card = store.opportunities(p.id).find { it.id == key } ?: OpportunityCard(key, p.id, OpportunityCard.Kind.GOAL_SUGGESTION,
                        "${if (overdue) "Overdue goal" else "Goal due soon"}: ${current.title}",
                        "Review progress, remaining acceptance criteria and the deadline (${Instant.ofEpochMilli(deadline)}). This reminder does not change the plan or execution permission.",
                        listOf("goal:${current.id}"), if (overdue) .85 else .6, .95,
                        PreparedNextStep("Review goal", "agent_turn", buildJsonObject { put("instruction", "Review goal ${current.id}, its deadline, unfinished milestones and linked receipts. Propose any plan changes for review.") }.toString()), createdAtMs = now)
                    if (store.opportunities(p.id).none { it.id == key }) store.saveOpportunity(card)
                    if (card.state == OpportunityCard.State.PENDING && store.attention(p.id).none { it.id == key })
                        deliver(p, AttentionItem(key, card.title, card.summary, card.urgency, .8, card.confidence, "goal", now, preparedStep = card.preparedAction))
                }
                store.updateGoal(current, current.copy(lastCheckedAtMs = now))
            } catch (_: GoalChangedException) { }
        }
    }

    private suspend fun advanceGoals(p: AgentProfile, now: Long, goalId: String) {
        val sessions = container.sessionStore.list(app.umbra.core.session.SessionFilter(limit = Int.MAX_VALUE))
        for (original in store.goals(p.id).filter { it.id == goalId && it.autoAdvance && it.authorizedAtMs != null && it.state in setOf(GoalState.IN_PROGRESS, GoalState.WAITING_USER) }) {
            var goal = original
            try {
                val active = goal.milestones.firstOrNull { it.status in setOf("active", "verifying") }
                if (active != null) {
                    val reference = active.assignedTo.orEmpty()
                    val session = if (reference.startsWith("dispatch:")) sessions.find { it.agentId == p.id && it.request.startsWith("[GoalRun ${reference.removePrefix("dispatch:")}]") }
                        else sessions.find { it.id == reference.removePrefix("#") }
                    if (session == null && reference.startsWith("dispatch:")) { dispatchGoalStep(p, goal, active, reference.removePrefix("dispatch:")); continue }
                    if (session == null) {
                        store.updateGoal(goal, goal.copy(state = GoalState.BLOCKED, updatedAtMs = now, replanHistory = goal.replanHistory + ReplanRecord(now, "step_failed", "The milestone session is missing; review before retrying.")))
                        continue
                    }
                    if (reference.startsWith("dispatch:")) goal = store.updateGoal(goal, goal.copy(milestones = goal.milestones.map { if (it.id == active.id) it.copy(assignedTo = "#${session.id}") else it }, linkedSessionIds = (goal.linkedSessionIds + session.id).distinct()))
                    if (session.state == SessionState.CREATED && session.queuedAtMs == null) {
                        if (reference.startsWith("dispatch:") && session.attachments["goal.authorization"] == null)
                            container.sessionStore.update(session.id) { it.copy(attachments = it.attachments + ("goal.authorization" to JsonPrimitive(goal.authorizedAtMs))) }
                        checkAuthority(requireNotNull(container.sessionStore.get(session.id)))
                        container.supervisor.enqueue(session.id)
                        continue
                    }
                    val verified = ((session.attachments[app.umbra.core.orchestrator.CompletionEvidence.KEY] as? JsonObject)?.get("verification") as? JsonObject)?.get("verified") == JsonPrimitive(true)
                    if (session.state == SessionState.COMPLETED &&
                        (app.umbra.core.orchestrator.CompletionEvidence.awaitingDelegateReport(session) ||
                            app.umbra.core.orchestrator.CompletionEvidence.pendingDelegates(session.id, sessions).isNotEmpty() || !verified && now - session.updatedAtMs < 60_000L)) continue
                    when {
                        session.state == SessionState.COMPLETED && verified -> {
                            val milestones = goal.milestones.map { if (it.id == active.id) it.copy(status = "done", artifactPath = "session:${session.id}#verification", updatedAtMs = now) else it }
                            val allDone = milestones.all { it.status == "done" }
                            goal = store.updateGoal(goal, goal.copy(milestones = milestones, state = if (allDone) GoalState.WAITING_USER else GoalState.IN_PROGRESS, lastProgressAtMs = now, updatedAtMs = now,
                                linkedSessionIds = (goal.linkedSessionIds + session.id).distinct(), deliverables = (goal.deliverables + "session:${session.id}").distinct()))
                            if (allDone) {
                                val card = OpportunityCard(store.id("opp"), p.id, OpportunityCard.Kind.GOAL_SUGGESTION, "Review completed milestones: ${goal.title}",
                                    "All milestone sessions recorded successful checks. Review the goal acceptance criteria before marking the goal complete.", listOf("goal:${goal.id}"), .6, .9,
                                    PreparedNextStep("Review acceptance criteria", "agent_turn", buildJsonObject { put("instruction", "Review goal ${goal.id}: ${goal.acceptanceCriteria.joinToString("; ")}. Read all linked receipts and check every criterion before marking it complete.") }.toString()), createdAtMs = now)
                                store.saveOpportunity(card)
                                deliver(p, AttentionItem(card.id, card.title, card.summary, card.urgency, .7, card.confidence, "goal", now, preparedStep = card.preparedAction))
                                continue
                            }
                        }
                        session.state.isTerminal -> {
                            store.updateGoal(goal, goal.copy(state = GoalState.BLOCKED, updatedAtMs = now,
                                milestones = goal.milestones.map { if (it.id == active.id) it.copy(status = "failed", updatedAtMs = now) else it },
                                replanHistory = goal.replanHistory + ReplanRecord(now, "step_failed", "${session.title}: ${session.lastError ?: "No successful verification receipt was recorded"}")))
                            continue
                        }
                        else -> {
                            if ((session.state == SessionState.PAUSED || session.state == SessionState.WAITING_APPROVAL) && goal.state != GoalState.WAITING_USER)
                                store.updateGoal(goal, goal.copy(state = GoalState.WAITING_USER, updatedAtMs = now))
                            continue
                        }
                    }
                }
                if (goal.state != GoalState.IN_PROGRESS) continue
                val next = goal.milestones.firstOrNull { m -> m.status == "pending" && m.dependsOn.all { dep -> goal.milestones.find { it.id == dep }?.status == "done" } } ?: continue
                val key = store.id("goalrun")
                val claimed = next.copy(status = "active", assignedTo = "dispatch:$key", updatedAtMs = now)
                val claimedGoal = store.updateGoal(goal, goal.copy(milestones = goal.milestones.map { if (it.id == next.id) claimed else it }, updatedAtMs = now))
                dispatchGoalStep(p, claimedGoal, claimed, key)
            } catch (_: GoalChangedException) {
                // A newer user/model update wins. Its event or the next scheduled check reconciles it.
            }
        }
    }

    private suspend fun dispatchGoalStep(p: AgentProfile, goal: AgentGoal, milestone: GoalMilestone, key: String) {
        var recorded = goal
        var createdId: String? = null
        try {
            val task = container.supervisor.create("[GoalRun $key]\nUser-authorized goal: ${goal.title}\nGoal ID: ${goal.id}\n${goal.description}\n" +
                "Priority: ${goal.priority} of 4. Deadline: ${goal.deadlineMs?.let { Instant.ofEpochMilli(it) } ?: "none"}.\n" +
                "Current milestone: ${milestone.title}\nVerification: ${milestone.verificationRule ?: "Run independent checks and record evidence"}\n" +
                "Acceptance criteria: ${goal.acceptanceCriteria.joinToString("; ")}\nWork only on this milestone. Existing approvals, connector scopes and usage limits remain in force. Delegate file/build work with coding_start and wait for its checked report. Use goal_list to inspect other milestones and goal_replan if evidence or constraints require a revised plan. Publish concrete artifacts and verification evidence before ending.",
                title = "${goal.title} · ${milestone.title}", model = p.model, effort = p.effort,
                kind = SessionKind.AGENT, agentId = p.id, spawnedBy = p.sessionId)
            createdId = task.id
            container.sessionStore.update(task.id) { it.copy(attachments = it.attachments + ("goal.authorization" to JsonPrimitive(goal.authorizedAtMs))) }
            recorded = store.updateGoal(goal, goal.copy(milestones = goal.milestones.map { if (it.id == milestone.id) it.copy(assignedTo = "#${task.id}") else it }, linkedSessionIds = (goal.linkedSessionIds + task.id).distinct()))
            checkAuthority(requireNotNull(container.sessionStore.get(task.id)))
            container.supervisor.enqueue(task.id)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            createdId?.let { container.supervisor.stop(it, "Goal dispatch did not finish; review before retrying") }
            if (e !is GoalChangedException) try {
                store.updateGoal(recorded, recorded.copy(state = GoalState.BLOCKED, replanHistory = recorded.replanHistory + ReplanRecord(System.currentTimeMillis(), "step_failed", e.message?.take(500) ?: "Goal step could not start")))
            } catch (_: GoalChangedException) { }
        }
    }

    /** [note]: what the user typed when adopting (their words win over the prepared step where they differ). */
    suspend fun adopt(agentId: String, opportunityId: String, editedPayload: String? = null, note: String? = null): Session = adoptionLock.withLock {
        val p = requireNotNull(agents.store.get(agentId))
        val previous = requireNotNull(store.opportunities(agentId).find { it.id == opportunityId })
        previous.executionSessionId?.let { return@withLock requireNotNull(container.sessionStore.get(it)) }
        val payload = app.umbra.core.model.UmbraJson.parseToJsonElement(editedPayload ?: previous.preparedAction.payloadJson) as? JsonObject ?: error("Action payload must be an object")
        val direct = (payload["instruction"] ?: payload["task"] ?: payload["text"])?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val instruction = when (previous.preparedAction.actionType) {
            "create_goal" -> "Create the user-reviewed goal from this data using goal_create. Do not authorize autonomous execution.\n$payload"
            "run_skill" -> {
                val id = payload["skill_id"]?.jsonPrimitive?.content ?: error("skill_id is required")
                val skill = requireNotNull(container.skills.get(id))
                require(skill.ownerAgentId == null || skill.ownerAgentId == agentId)
                if (skill.executionTarget == ExecutionTarget.SANDBOX_TRANSFORM) {
                    container.skills.transformForRun(id)
                    SynthesizedTools.validateParameters(skill, payload["input"] ?: error("input is required"))
                    "Run the approved pure JSON transform with tool_run. Data: $payload"
                } else if (skill.isGuestScript) {
                    container.skills.scriptForRun(id)
                    SynthesizedTools.validateParameters(skill, payload["input"] ?: error("input is required"))
                    "Run the approved synthesized script with script_run. Data: $payload"
                } else container.skills.instructionsForRun(id, (payload["args"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty())
            }
            "send_message_draft" -> "Prepare this reviewed message draft. Do not send until the ordinary recipient-specific approval is satisfied. Data:\n$payload"
            else -> direct ?: error("The prepared step needs an instruction to review")
        }
        check(p.running) { "Start the owning agent before adopting this suggestion" }
        val card = store.claimOpportunity(agentId, opportunityId, System.currentTimeMillis())
        val kind = if (card.preparedAction.actionType == "start_coding") SessionKind.CODING else SessionKind.AGENT
        val message = note?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= 4_000) { "Keep the message under 4000 characters" } }
        val withNote = if (message == null) instruction else "$instruction\n\nThe user's message about this suggestion (follow it where it differs from the prepared step):\n$message"
        val task = container.supervisor.create("[User adopted opportunity $opportunityId]\n$withNote", title = card.title, model = p.model, effort = p.effort, kind = kind,
            agentId = p.id, spawnedBy = p.sessionId)
        store.saveOpportunity(card.copy(state = OpportunityCard.State.ACCEPTED, executionSessionId = task.id))
        // A failed admission remains attached to the same visible task, never an ambiguous duplicate action.
        try { container.supervisor.enqueue(task.id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { store.saveOpportunity(card.copy(state = OpportunityCard.State.ACCEPTED, executionSessionId = task.id, error = e.message?.take(500))) }
        task
    }

    fun dismiss(agentId: String, id: String, lessLikeThis: Boolean) {
        val card = store.dismissOpportunity(agentId, id)
        if (lessLikeThis) agents.store.memory(agentId).add("Reduce suggestions of type ${card.kind}: ${card.title}", "feedback:${card.kind}", category = MemoryCategory.FEEDBACK_RULE, tags = listOf(card.kind.name))
    }

    suspend fun onCompleted(p: AgentProfile, session: Session, text: String): Boolean {
        if (session.request.startsWith("[GoalRun ")) container.supervisor.releaseIfIdle(session.id, 0)
        try { if (container.teams.onCompleted(session, text)) return true }
        finally { requestGoalCheck(p.id) }
        if (CompletionEvidence.awaitingDelegates(session, container.sessionStore.list(app.umbra.core.session.SessionFilter(limit = Int.MAX_VALUE)))) return true
        session.routineRunId?.let { id ->
            store.routines(p.id).forEach { r -> r.recentRuns.find { it.runId == id }?.let { run ->
                settleRoutine(p.id, r.id, run, session)
            } }
        }
        if (session.turnMode != ProactiveTurnMode.READ_ONLY_DISCOVERY && session.routineRunId == null) return false
        if (text.isNotBlank() && !text.startsWith(AgentRuntime.QUIET)) deliver(p, AttentionItem("session:${session.id}", session.title, text.take(2000), .5, .7, .8, "research", System.currentTimeMillis(), linkedSessionId = session.id))
        return true
    }

    suspend fun deliver(p: AgentProfile, item: AttentionItem, deliveryDetail: String = item.detail) {
        if (agents.store.get(p.id)?.running != true) { store.stage(p.id, item.copy(tier = DeliveryTier.SILENT_LOG)); return }
        val audio = container.context.getSystemService(AudioManager::class.java)
        val notifications = container.context.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()
        val meeting = container.personalContext.calendar(p.id, now - 12 * 3_600_000L, now + 3_600_000L).any { it.startMs <= now && it.endMs > now }
        val context = InterruptionContext(inCallOrMeeting = meeting || audio.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION),
            immersiveOrTyping = audio.isMusicActive || immersiveApps.active(app.umbra.platform.android.UmbraAccessibilityService.foregroundPackage.value,
                app.umbra.platform.android.UmbraAccessibilityService.connected.value) ||
                now - app.umbra.platform.android.UmbraAccessibilityService.lastTextInputAtMs.get() in 0..5000,
            quietOrDnd = agents.inQuietHours(p) || notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
        val feedback = agents.store.memory(p.id).entries().any { it.category == MemoryCategory.FEEDBACK_RULE && (item.source in it.tags || it.source == "feedback:${item.source}") }
        val tier = AttentionRanker.tier(item.urgency, item.importance, item.confidence, context, if (feedback) .4 else 1.0)
        store.stage(p.id, item.copy(tier = tier))
        if (tier == DeliveryTier.STANDARD_NOTIFY || tier == DeliveryTier.URGENT_BREAKTHROUGH) agents.notify(p, "${item.title}\n$deliveryDetail", tier == DeliveryTier.URGENT_BREAKTHROUGH, suggestionId = item.id.takeIf { item.preparedStep != null })
    }

    fun nextDue(p: AgentProfile, now: Long): Long? {
        if (p.proactivityLevel < 1) return null
        val candidates = mutableListOf<Long>()
        if (p.proactivityLevel >= 3) store.goals(p.id).mapNotNullTo(candidates) { GoalSchedule.nextCheck(it) }
        store.routines(p.id).filter { it.enabled }.mapNotNullTo(candidates) { it.nextRunAtMs }
        if (p.proactivityLevel >= 2) store.watchers(p.id).filter { it.enabled }.mapTo(candidates) { it.lastCheckedMs + it.intervalMinutes * 60_000L }
        for ((kind, minute) in listOf(DailyBrief.Kind.MORNING to p.morningBriefMinute, DailyBrief.Kind.EVENING to p.eveningBriefMinute)) {
            if (minute == null) continue
            val date = LocalDate.now()
            val done = store.briefs(p.id).any { it.id == "brief_${date}_${kind.name.lowercase()}" }
            candidates += (if (done) date.plusDays(1) else date).atStartOfDay(ZoneId.systemDefault()).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
        }
        return candidates.minOrNull()?.coerceAtLeast(now + 5000)
    }

    private suspend fun probe(spec: ProbeSpec): String = when (spec) {
        is ProbeSpec.HttpOrCss -> http.fetch(spec.url, spec.selectorOrJsonPath, spec.headers)
        is ProbeSpec.GuestCheckCommand -> {
            val cwd = spec.workingDir ?: "/workspace"
            require((cwd == "/workspace" || cwd.startsWith("/workspace/")) && cwd.split('/').none { it in setOf(".", "..") })
            val result = container.guest.runtime.exec(ReadOnlyProbeCommands.command(spec.argv), cwd = cwd,
                env = mapOf("GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_OPTIONAL_LOCKS" to "0"), timeoutMs = 10_000, maxOutputBytes = 64_000)
            check(result.ok && !result.truncated) { "Read-only guest probe failed or exceeded its output limit" }
            result.stdout
        }
        is ProbeSpec.NotificationPattern -> withContext(Dispatchers.IO) {
            val pattern = NotificationPatterns.compile(spec)
            container.notificationReader.records(System.currentTimeMillis() - 86_400_000L).asSequence()
                .filter { spec.packageName == null || spec.packageName == it.packageName }.take(200)
                .filter { NotificationPatterns.matches(spec, pattern, it.text) }.take(50)
                .joinToString("\n") { "${it.title.take(500)}: ${it.text.take(8000)}" }.take(64_000)
        }
    }
}
