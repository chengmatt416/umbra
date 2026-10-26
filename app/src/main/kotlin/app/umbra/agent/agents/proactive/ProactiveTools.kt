package app.umbra.agent.agents.proactive

import app.umbra.agent.AppContainer
import app.umbra.core.model.UmbraJson
import app.umbra.runtime.omp.*
import app.umbra.tools.HostSideTool
import java.time.ZoneId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/** Models propose state; enabling routines, accepting opportunities and approving skills are UI-only actions. */
class ProactiveTools(private val container: AppContainer) {
    private val runtime get() = container.agents.proactive
    private val store get() = runtime.store
    fun forAgent(agentId: String, sessionId: String): List<HostSideTool> = listOf(
        tool("goal_list", "List persistent goals and their milestones, acceptance criteria and evidence.", { enum("state_filter", GoalState.entries.map { it.name }) }) { a ->
            json(AgentGoal.serializer(), store.goals(agentId).filter { a.str("state_filter") == null || it.state.name == a.str("state_filter") })
        },
        tool("goal_create", "Create a durable goal: an outcome that takes several steps over days or weeks (a project, a habit, a plan), with at least two ordered milestones and acceptance criteria. Never make a goal of a single calendar event, appointment, reminder or errand; use tasks_write, a timer, the calendar or opportunity_propose for those. This does not grant permission for external actions.", {
            string("title", required = true); string("description", required = true); stringArray("acceptance_criteria", required = true)
            integer("priority"); integer("deadline_ms"); string("check_cron")
            objectArray("milestones", required = true) { string("id", required = true); string("title", required = true); stringArray("depends_on"); string("verification_rule") }
        }) { a ->
            val now = System.currentTimeMillis()
            a.str("check_cron")?.let { app.umbra.agent.agents.Cron(it) }
            if (!GoalScope.requested(container.sessionStore.get(sessionId)?.latestUserIntent))
                GoalScope.refusal(a.required("title"), a.required("description"), a.milestones(now).size, a.long("deadline_ms"), now, eventTitles(agentId, now))?.let { return@tool HostToolPayload.error(it) }
            val goal = AgentGoal(store.id("goal"), agentId, a.required("title"), a.required("description"), priority = a.int("priority") ?: 2,
                acceptanceCriteria = a.strings("acceptance_criteria"), milestones = a.milestones(now), linkedSessionIds = listOf(sessionId),
                checkCadenceCron = a.str("check_cron"), deadlineMs = a.long("deadline_ms"), createdAtMs = now)
            store.saveGoal(goal); runtime.requestGoalCheck(agentId); HostToolPayload.text("Created ${goal.id}. Its acceptance criteria and evidence remain required before completion.")
        },
        tool("goal_update", "Update goal state or one milestone. Completion requires finished dependencies, acceptance criteria and evidence references.", {
            string("goal_id", required = true); enum("state", GoalState.entries.map { it.name }); string("milestone_id")
            enum("milestone_status", listOf("pending", "active", "verifying", "done", "failed", "skipped")); string("deliverable_path"); string("note")
            stringArray("linked_sessions"); stringArray("linked_routines")
        }) { a ->
            val g = requireNotNull(store.goals(agentId).find { it.id == a.required("goal_id") })
            val milestone = a.str("milestone_id")
            require(milestone == null || g.milestones.any { it.id == milestone })
            val now = System.currentTimeMillis()
            val artifact = a.str("deliverable_path")
            val delegated = store.handoffs().filter { it.fromAgentId == agentId && it.status == "completed" }.mapNotNull { it.executionSessionId }.toSet()
            suspend fun evidence(reference: String) = GoalEvidence.requireVerified(reference, agentId, container.sessionStore.get(GoalEvidence.sessionId(reference)), delegated)
            artifact?.let { evidence(it) }
            val next = g.copy(state = a.str("state")?.let(GoalState::valueOf) ?: g.state,
                milestones = g.milestones.map { if (it.id == milestone) it.copy(status = a.str("milestone_status") ?: it.status, artifactPath = artifact ?: it.artifactPath, updatedAtMs = now) else it },
                deliverables = (g.deliverables + listOfNotNull(artifact)).distinct(),
                linkedSessionIds = (g.linkedSessionIds + a.strings("linked_sessions")).distinct(), linkedRoutineIds = (g.linkedRoutineIds + a.strings("linked_routines")).distinct(),
                updatedAtMs = now, lastProgressAtMs = if (a.str("milestone_status") == "done") now else g.lastProgressAtMs)
            GoalUpdates.requireModelUpdate(g, next)
            next.milestones.filter { it.status == "done" }.forEach { evidence(requireNotNull(it.artifactPath) { "Completed milestones need verified session evidence" }) }
            for (sid in a.strings("linked_sessions")) {
                val linked = container.sessionStore.get(sid)
                require(linked != null && (linked.agentId == agentId || sid in delegated)) { "Only owned or verified delegated sessions may be linked" }
            }
            require(a.strings("linked_routines").all { id -> store.routines(agentId).any { it.id == id } }) { "Only owned routines may be linked" }
            store.updateGoal(g, next)
            runtime.requestGoalCheck(agentId)
            a.str("note")?.let { store.writeNote(agentId, g.id, it, true) }
            HostToolPayload.text("Updated ${g.id}.")
        },
        tool("goal_replan", "Replace unfinished milestones after new evidence, failure or changed constraints; preserve completed work and append the reason to history.", {
            string("goal_id", required = true); string("reason", required = true); string("trigger")
            objectArray("milestones", required = true) { string("id", required = true); string("title", required = true); stringArray("depends_on"); string("verification_rule") }
        }) { a ->
            val g = requireNotNull(store.goals(agentId).find { it.id == a.required("goal_id") })
            val now = System.currentTimeMillis()
            val done = g.milestones.filter { it.status == "done" }
            val next = a.milestones(now)
            require(next.none { m -> done.any { it.id == m.id } }) { "Completed milestones cannot be rewritten" }
            runtime.replanGoal(g, g.copy(milestones = done + next, state = GoalUpdates.replannedState(g), updatedAtMs = now,
                replanHistory = g.replanHistory + ReplanRecord(now, a.str("trigger") ?: "new_evidence", a.required("reason"))))
            HostToolPayload.text("Replanned ${g.id}; completed milestones and prior evidence were preserved.")
        },
        tool("note_write", "Write or append a private research note. No external systems are modified.", { string("topic", required = true); string("content", required = true); boolean("append") }) { a ->
            store.writeNote(agentId, a.required("topic"), a.required("content"), a.bool("append") ?: false); HostToolPayload.text("Private note saved.")
        },
        tool("note_read", "Read a private research note by topic.", { string("topic", required = true) }) { a -> HostToolPayload.text(store.note(agentId, a.required("topic")).ifBlank { "No note with that topic." }) },
        tool("note_list", "List private research note topics.", {}) { HostToolPayload.text(store.notes(agentId).joinToString("\n").ifBlank { "No private notes." }) },
        tool("opportunity_list", "List your suggestions: the open ones the user has not acted on yet, and the ones they dismissed or adopted in the last two weeks. Check it before proposing, so one event or message never becomes two suggestions.", {
            boolean("include_settled")
        }) { a ->
            val now = System.currentTimeMillis()
            val cards = store.opportunities(agentId).filter {
                it.state == OpportunityCard.State.PENDING && (it.expiresAtMs == null || it.expiresAtMs > now) ||
                    a.bool("include_settled") != false && it.state != OpportunityCard.State.EXPIRED && now - it.createdAtMs < OpportunityDedup.SETTLED_WINDOW_MS
            }
            HostToolPayload.text(if (cards.isEmpty()) "No open suggestions." else buildJsonArray { cards.forEach { add(summary(it)) } }.toString())
        },
        tool("opportunity_update", "Change one of your open suggestions (id from opportunity_list) when there is news about the same thing: a new time, more evidence, a better next step. Omitted fields keep their value. The user sees the updated card, not a new one.", {
            string("id", required = true); string("title"); string("summary"); stringArray("evidence_refs"); number("urgency"); number("confidence")
            string("action_label"); enum("action_type", listOf("start_coding", "send_message_draft", "create_goal", "run_skill", "agent_turn"))
            raw("action_payload", buildJsonObject { put("type", "object"); put("additionalProperties", true) }); integer("expires_at_ms"); string("reason")
        }) { a ->
            val now = System.currentTimeMillis()
            val payload = a["action_payload"]?.let { it as? JsonObject ?: error("action_payload must be an object") }
            val card = store.updateOpportunity(agentId, a.required("id"), now) { old ->
                val step = PreparedNextStep(a.str("action_label") ?: old.preparedAction.label, a.str("action_type") ?: old.preparedAction.actionType,
                    payload?.toString() ?: old.preparedAction.payloadJson, old.preparedAction.secondaryOptions)
                old.copy(title = a.str("title") ?: old.title, summary = a.str("summary") ?: old.summary,
                    evidenceRefs = if (a["evidence_refs"] != null) a.strings("evidence_refs") else old.evidenceRefs,
                    urgency = a.double("urgency") ?: old.urgency, confidence = a.double("confidence") ?: old.confidence,
                    preparedAction = step, expiresAtMs = a.long("expires_at_ms") ?: old.expiresAtMs, sourceSessionId = sessionId, error = null)
            }
            goalSuggestionRefusal(card, now)?.let { return@tool HostToolPayload.error(it) }
            refreshDelivery(card)
            a.str("reason")?.let { store.writeNote(agentId, card.id, "Updated: $it", true) }
            HostToolPayload.text("Updated ${card.id}; the user sees the new version in place of the old one.")
        },
        tool("opportunity_withdraw", "Delete one of your open suggestions (id from opportunity_list) that no longer applies: the event passed or was cancelled, the user already did it, or it was a duplicate. It disappears from the Capsule, Today and the next brief.", {
            string("id", required = true); string("reason", required = true)
        }) { a ->
            val card = store.removeOpportunity(agentId, a.required("id"))
            store.writeNote(agentId, card.id, "Withdrawn \"${card.title}\": ${a.required("reason")}", true)
            HostToolPayload.text("Withdrew ${card.id}.")
        },
        tool("opportunity_propose", "Prepare a suggestion with evidence and a reviewable next step. The user must explicitly adopt it before any execution. One event, message or item is one suggestion: when you already have an open one about it (opportunity_list), use opportunity_update instead; a repeat is merged into it, and one the user dismissed or adopted lately is refused. Give evidence_refs that name the item (\"calendar:<event id>\", \"email:<id>\").", {
            enum("kind", OpportunityCard.Kind.entries.map { it.name }, required = true); string("title", required = true); string("summary", required = true)
            stringArray("evidence_refs", required = true); number("urgency", required = true); number("confidence", required = true)
            string("action_label", required = true); enum("action_type", listOf("start_coding", "send_message_draft", "create_goal", "run_skill", "agent_turn"), required = true)
            raw("action_payload", buildJsonObject { put("type", "object"); put("additionalProperties", true) }, required = true); integer("expires_at_ms")
        }) { a ->
            val payload = a["action_payload"] as? JsonObject ?: error("action_payload must be an object")
            val card = OpportunityCard(store.id("opp"), agentId, OpportunityCard.Kind.valueOf(a.required("kind")), a.required("title"), a.required("summary"), a.strings("evidence_refs"),
                a.double("urgency") ?: error("urgency is required"), a.double("confidence") ?: error("confidence is required"),
                PreparedNextStep(a.required("action_label"), a.required("action_type"), payload.toString()), createdAtMs = System.currentTimeMillis(), expiresAtMs = a.long("expires_at_ms"),
                sourceSessionId = sessionId)
            goalSuggestionRefusal(card, card.createdAtMs)?.let { return@tool HostToolPayload.error(it) }
            val running = container.agents.store.profiles.value.filter { it.running }.map { it.id }
            when (val outcome = store.proposeOpportunity(card, running, card.createdAtMs)) {
                is ProactiveStore.Proposal.Created -> {
                    runtime.deliver(requireNotNull(container.agents.store.get(agentId)), attention(card))
                    HostToolPayload.text("Proposed ${card.id}. It remains pending user adoption.")
                }
                is ProactiveStore.Proposal.Updated -> {
                    refreshDelivery(outcome.card)
                    HostToolPayload.text("You already had ${outcome.card.id} about this; it now carries these words instead of a second suggestion. Use opportunity_update for it from now on.")
                }
                is ProactiveStore.Proposal.Duplicate -> {
                    val c = outcome.card
                    val why = when {
                        c.agentId != agentId -> "Another agent's suggestion ${c.id} covers it (${c.state.name.lowercase()})"
                        !store.proposed(c) -> "Umbra already shows this (${c.id})"
                        c.state == OpportunityCard.State.DISMISSED -> "The user dismissed the same suggestion (${c.id}) recently; do not propose it again unless something important changed, and then say what"
                        c.state == OpportunityCard.State.ACCEPTED -> "The user already adopted the same suggestion (${c.id}); its task is ${c.executionSessionId ?: "starting"}"
                        else -> "The user is adopting the same suggestion (${c.id}) right now"
                    }
                    HostToolPayload.error("Not proposed: $why.")
                }
            }
        },
        tool("watcher_add", "Draft a cheap conditional watcher. It remains disabled until the user reviews its source and enables it. No cloud model is called for an unmatched probe.", {
            string("title", required = true); enum("probe_type", listOf("http", "guest", "notification"), required = true); string("target", required = true)
            string("selector_or_pattern", "CSS/JSON selector, or a case-insensitive RE2 notification-body regex (no lookarounds/backreferences)"); stringArray("argv"); string("working_dir")
            enum("condition", listOf("changed", "contains", "numeric", "laya"), required = true); string("value"); string("operator"); number("threshold")
            integer("interval_minutes"); string("on_match", required = true); boolean("trigger_once")
        }) { a ->
            val probe = when (a.required("probe_type")) {
                "http" -> ProbeSpec.HttpOrCss(a.required("target"), a.str("selector_or_pattern").orEmpty())
                "guest" -> ProbeSpec.GuestCheckCommand(a.strings("argv").also { ReadOnlyProbeCommands.command(it) }, a.str("working_dir"))
                "notification" -> ProbeSpec.NotificationPattern(a.str("target"), a.str("selector_or_pattern").orEmpty(), literal = false)
                else -> error("Unknown probe")
            }
            val condition = when (a.required("condition")) {
                "changed" -> ConditionSpec.Changed; "contains" -> ConditionSpec.Contains(a.required("value"))
                "numeric" -> ConditionSpec.NumericCompare(a.required("operator"), a.double("threshold") ?: error("threshold is required"))
                "laya" -> ConditionSpec.LayaVerify(a.required("value")); else -> error("Unknown condition")
            }
            val watcher = AgentWatcher(store.id("watch"), agentId, a.required("title"), probe, condition, a.int("interval_minutes") ?: 30, a.required("on_match"), triggerOnce = a.bool("trigger_once") ?: true)
            store.saveWatcher(watcher); container.agents.reschedule(); HostToolPayload.text("Drafted ${watcher.id}. Review and enable it in Proactivity → Watchers.")
        },
        tool("watcher_list", "List watchers, their current observations and errors.", {}) { json(AgentWatcher.serializer(), store.watchers(agentId)) },
        tool("watcher_remove", "Remove a watcher.", { string("id", required = true) }) { a -> store.removeWatcher(agentId, a.required("id")); HostToolPayload.text("Watcher removed.") },
        tool("brief_stage", "Stage a non-interrupting item for the next morning/evening brief.", { string("title", required = true); string("detail", required = true); string("source") }) { a ->
            store.stage(agentId, AttentionItem(store.id("briefitem"), a.required("title"), a.required("detail"), .3, .5, .8, a.str("source") ?: "research", System.currentTimeMillis()))
            HostToolPayload.text("Staged for the next brief.")
        },
        tool("routine_add", "Draft a durable cron, event or watcher routine with owner, scopes and run history. User review is required to enable it.", {
            string("title", required = true); enum("trigger", listOf("cron", "event", "watcher", "manual"), required = true)
            string("cron"); string("zone_id"); string("source"); string("text_contains"); string("sender_or_channel"); string("semantic_condition"); string("watcher_id")
            string("instruction", required = true); string("skill_id"); stringMap("skill_args"); stringArray("allowed_connectors"); integer("approval_ttl_minutes"); integer("minimum_interval_minutes")
        }) { a ->
            val now = System.currentTimeMillis()
            val trigger = when (a.required("trigger")) {
                "cron" -> RoutineTrigger.CronSchedule(a.required("cron").also { app.umbra.agent.agents.Cron(it) }, a.str("zone_id") ?: ZoneId.systemDefault().id)
                "event" -> RoutineTrigger.EventMatch(a.required("source"), a.str("sender_or_channel"), a.str("text_contains"), a.str("semantic_condition"))
                "watcher" -> RoutineTrigger.WatcherMatch(a.required("watcher_id")); else -> RoutineTrigger.Manual
            }
            if (trigger is RoutineTrigger.CronSchedule) ZoneId.of(trigger.zoneId)
            val routine = AgentRoutine(store.id("routine"), agentId, a.required("title"), trigger, skillId = a.str("skill_id"), instruction = a.required("instruction"),
                skillArgs = (a["skill_args"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty(), allowedConnectors = a.strings("allowed_connectors"),
                approvalTTLMinutes = a.int("approval_ttl_minutes") ?: 10, idempotencyWindowMinutes = a.int("minimum_interval_minutes") ?: 5, createdAtMs = now)
            store.saveRoutine(routine); HostToolPayload.text("Drafted ${routine.id}. Review the instruction and scopes in Proactivity → Routines before enabling it.")
        },
        tool("routine_list", "List durable routines and their last twenty run records.", {}) { json(AgentRoutine.serializer(), store.routines(agentId)) },
        tool("routine_remove", "Disable and remove a routine, pausing its unfinished runs. Existing runs remain in session history.", { string("id", required = true) }) { a ->
            val id = a.required("id")
            runtime.setRoutineEnabled(agentId, id, false)
            store.removeRoutine(agentId, id)
            HostToolPayload.text("Routine removed; unfinished runs were paused.")
        },
        tool("context_resolve", "Search only this agent's authorized memory, messages, notifications, email, calendar, contacts, tasks and documents. Documents are UTF-8 text or text extracted from DOCX/XLSX/PPTX/ODT/ODS/ODP in the user-selected Android folder only, with bounded traversal and excerpts. Macros, formulas and links are never executed; unsupported or oversized files are skipped. Return dated evidence; ambiguity is not authorization.", {
            string("query", required = true); stringArray("sources"); integer("time_window_days"); enum("entity_type", listOf("order", "person", "appointment", "document", "any"))
        }) { a -> json(ContextEvidence.serializer(), container.personalContext.resolve(agentId, a.required("query"), a.strings("sources").ifEmpty { listOf("memory", "messages", "notifications", "email", "calendar", "contacts", "tasks", "documents") }.toSet(), a.int("time_window_days") ?: 90, a.str("entity_type") ?: "any")) },
        tool("calendars_list", "List writable calendar IDs and names after the user connects calendar. Never guess a calendar ID.", {}) {
            HostToolPayload.text(buildJsonArray { container.personalContext.writableCalendars(agentId).forEach { (id, name) -> add(buildJsonObject { put("id", id); put("name", name) }) } }.toString())
        },
        tool("calendar_write", "Propose a calendar event for user review. Supply calendar_id from calendars_list only to create it directly with explicit WRITE_EVENTS permission and the normal approval gate. No invitations or recurring events; use calendar_update or calendar_delete for an existing event. Inspect ambiguous writes before retrying.", {
            string("title", required = true); integer("start_ms", required = true); integer("end_ms", required = true); string("zone_id", required = true)
            string("location"); string("description"); integer("calendar_id")
        }) { a ->
            val draft = CalendarDraft(store.id("event"), sessionId, a.required("title"), a.long("start_ms") ?: error("start_ms required"), a.long("end_ms") ?: error("end_ms required"),
                a.required("zone_id"), a.str("location").orEmpty(), a.str("description").orEmpty(), System.currentTimeMillis())
            HostToolPayload.text(container.personalContext.writeCalendar(agentId, draft, a.long("calendar_id")))
        },
        tool("calendar_update", "Change one existing event (event_id from calendar_read) with WRITE_EVENTS permission and the approval gate; omitted fields keep their current value. Recurring events and events with other guests are refused: the user changes those. Read the event back afterwards.", {
            integer("event_id", required = true); string("title"); integer("start_ms"); integer("end_ms"); string("zone_id"); string("location"); string("description")
        }) { a ->
            val id = a.long("event_id") ?: error("event_id required")
            val old = requireNotNull(container.personalContext.eventForUpdate(id)) { "No such event. Read the calendar again." }
            val draft = CalendarDraft(store.id("event"), sessionId, a.str("title") ?: old.title, a.long("start_ms") ?: old.startMs, a.long("end_ms") ?: old.endMs,
                a.str("zone_id") ?: old.zoneId ?: java.time.ZoneId.systemDefault().id, a.str("location") ?: old.location, a.str("description") ?: old.description, System.currentTimeMillis())
            HostToolPayload.text(container.personalContext.changeCalendarEvent(agentId, sessionId, id, draft))
        },
        tool("calendar_delete", "Delete one existing event (event_id from calendar_read) with WRITE_EVENTS permission and the approval gate. Recurring events and events with other guests are refused. Read the calendar back afterwards.", {
            integer("event_id", required = true)
        }) { a -> HostToolPayload.text(container.personalContext.changeCalendarEvent(agentId, sessionId, a.long("event_id") ?: error("event_id required"), null)) },
        tool("calendar_read", "Read upcoming events only when this agent has calendar access and Android permission.", { integer("days") }) { a ->
            val now = System.currentTimeMillis(); json(CalendarItem.serializer(), container.personalContext.calendar(agentId, now, now + (a.int("days") ?: 7).coerceIn(1, 366) * 86_400_000L))
        },
        tool("contacts_read", "Resolve names and email addresses only when contacts access is enabled for this agent.", { string("query", required = true) }) { a -> json(ContextEvidence.serializer(), container.personalContext.contacts(agentId, a.required("query"))) },
        tool("email_read", "Search authorized email evidence. The OAuth token stays in native storage.", { string("query", required = true); integer("days") }) { a -> json(ContextEvidence.serializer(), container.personalContext.resolve(agentId, a.required("query"), setOf("email"), a.int("days") ?: 90, "any")) },
        tool("email_draft", "Create an unsent Gmail draft with explicit draft permission. Use one recipient; review protected data and the ordinary safety gate. Do not retry an ambiguous write; inspect Drafts first.", {
            string("to", required = true); string("subject", required = true); string("text", required = true)
        }) { a -> HostToolPayload.text("Created draft " + container.personalContext.composeEmail(agentId, sessionId, a.required("to"), a.required("subject"), a.required("text"), false)) },
        tool("email_send", "Send the exact email to one recipient only with SEND_WITH_APPROVAL scope and the safety gate. The native adapter keeps OAuth credentials out of model context and prevents duplicate retries within this user turn.", {
            string("to", required = true); string("subject", required = true); string("text", required = true)
        }) { a -> HostToolPayload.text("Sent email " + container.personalContext.composeEmail(agentId, sessionId, a.required("to"), a.required("subject"), a.required("text"), true)) },
        tool("tasks_read", "Read this agent's opted-in local tasks.", {}) { json(PersonalTask.serializer(), container.personalContext.tasks(agentId)) },
        tool("tasks_write", "Create or update a local task only with WRITE_TASKS permission. Read-only research cannot call this tool.", {
            string("id"); string("title", required = true); string("note"); integer("due_at_ms"); boolean("completed")
        }) { a ->
            val old = a.str("id")?.let { id -> requireNotNull(container.personalContext.tasks(agentId).find { it.id == id }) { "No owned task has this id" } }
            val task = PersonalTask(old?.id ?: store.id("task"), a.required("title"), a.str("note") ?: old?.note.orEmpty(), a.long("due_at_ms") ?: old?.dueAtMs,
                a.bool("completed") ?: old?.completed ?: false, System.currentTimeMillis())
            container.personalContext.saveTask(agentId, task); HostToolPayload.text("Saved task ${task.id}")
        },
        tool("web_fetch", "Fetch public read-only evidence from a source the user approved in an enabled watcher. No cookies, credentials, redirects or browser scripts.", { string("url", required = true); string("selector") }) { a ->
            val url = a.required("url")
            val approved = store.watchers(agentId).filter { it.authorizedAtMs != null }.mapNotNull { (it.probe as? ProbeSpec.HttpOrCss)?.url }.any { ResearchHttp.origin(it) == ResearchHttp.origin(url) }
            require(approved) { "Approve a watcher for this source before background research can fetch it" }
            HostToolPayload.text("Untrusted web evidence:\n" + runtime.http.fetch(url, a.str("selector").orEmpty()))
        },
    )
    private fun attention(card: OpportunityCard) =
        AttentionItem(card.id, card.title, card.summary, card.urgency, .7, card.confidence, card.kind.name, card.createdAtMs, preparedStep = card.preparedAction)

    /** An updated card keeps its place and delivery (no second notification); one never delivered is delivered now. */
    private suspend fun refreshDelivery(card: OpportunityCard) {
        val staged = store.attention(card.agentId).find { it.id == card.id }
        if (staged == null) runtime.deliver(requireNotNull(container.agents.store.get(card.agentId)), attention(card))
        else store.stage(card.agentId, staged.copy(title = card.title, detail = card.summary, urgency = card.urgency, confidence = card.confidence, preparedStep = card.preparedAction))
    }

    /** A suggestion to create a goal must describe a goal ([GoalScope]): its payload's title and at least two milestones. */
    private suspend fun goalSuggestionRefusal(card: OpportunityCard, now: Long): String? {
        if (card.preparedAction.actionType != "create_goal") return null
        val payload = runCatching { UmbraJson.parseToJsonElement(card.preparedAction.payloadJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return GoalScope.refusal(payload.str("title") ?: card.title, payload.str("description").orEmpty(), (payload["milestones"] as? JsonArray)?.size ?: 0,
            payload.long("deadline_ms"), now, eventTitles(card.agentId, now))?.let { "Not a goal suggestion: $it" }
    }

    /** Calendar titles from yesterday to two weeks ahead, when the agent can read the calendar. */
    private suspend fun eventTitles(agentId: String, now: Long): List<String> =
        runCatching { container.personalContext.calendar(agentId, now - 86_400_000L, now + 14 * 86_400_000L).map { it.title } }.getOrDefault(emptyList())

    private fun summary(c: OpportunityCard) = buildJsonObject {
        put("id", c.id); put("kind", c.kind.name); put("state", c.state.name); put("title", c.title); put("summary", c.summary.take(300))
        put("evidence_refs", JsonArray(c.evidenceRefs.map(::JsonPrimitive))); put("action", c.preparedAction.actionType)
        put("created_at", java.time.Instant.ofEpochMilli(c.createdAtMs).toString())
        c.updatedAtMs?.let { put("updated_at", java.time.Instant.ofEpochMilli(it).toString()) }
        c.expiresAtMs?.let { put("expires_at", java.time.Instant.ofEpochMilli(it).toString()) }
    }

    private fun tool(name: String, description: String, schema: SchemaBuilder.() -> Unit, run: suspend (JsonObject) -> HostToolPayload) =
        HostSideTool(HostToolDefinition(name, description, objectSchema(schema))) { call, _ -> run(call.arguments) }
    private fun <T> json(serializer: KSerializer<T>, list: List<T>) = HostToolPayload.text(UmbraJson.encodeToString(ListSerializer(serializer), list))
    private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    private fun JsonObject.required(name: String) = requireNotNull(str(name)) { "$name is required" }.also { require(it.length <= 20_000) }
    private fun JsonObject.int(name: String) = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(name: String) = (this[name] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.double(name: String) = (this[name] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.strings(name: String) = (this[name] as? JsonArray).orEmpty().map { it.jsonPrimitive.content.also { s -> require(s.length <= 2000) } }.also { require(it.size <= 100) }
    private fun JsonObject.milestones(now: Long) = (this["milestones"] as? JsonArray).orEmpty().map { raw ->
        val a = raw as? JsonObject ?: error("Each milestone must be an object")
        GoalMilestone(a.required("id"), a.required("title"), dependsOn = a.strings("depends_on"), verificationRule = a.str("verification_rule"), updatedAtMs = now)
    }.also { require(it.isNotEmpty()) }
}
