package app.umbra.agent.ui.agents

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.agents.proactive.*
import app.umbra.agent.container
import app.umbra.agent.ui.MarkdownText
import app.umbra.agent.ui.porcelain.*
import app.umbra.agent.ui.shell.*
import app.umbra.core.model.UmbraJson
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ProactivePageData(
    val goals: List<AgentGoal> = emptyList(), val opportunities: List<OpportunityCard> = emptyList(),
    val briefs: List<DailyBrief> = emptyList(), val watchers: List<AgentWatcher> = emptyList(),
    val routines: List<AgentRoutine> = emptyList(), val skills: List<UmbraSkill> = emptyList(),
    val grants: List<PersonalConnectorGrant> = emptyList(), val error: String? = null,
    val calendarDrafts: List<CalendarDraft> = emptyList(),
)
private data class JsonReview(val title: String, val content: String, val instruction: OpportunityInput? = null, val save: suspend (String) -> Unit)

/**
 * An agent's proactive settings: how much it does on its own, when its briefs arrive, its goals' authorization,
 * watchers, routines, skills, sources, rules, teams and templates. What it has for you (briefs, suggestions, goal
 * progress) is on its Briefing page instead. Power users may edit the complete typed configuration before authorizing it.
 */
@Composable
fun ProactiveScreen(agentId: String, onBack: () -> Unit, onOpenSession: (String) -> Unit, initialTab: Int = 2) {
    val context = LocalContext.current
    val container = context.container
    val runtime = container.agents.proactive
    val profiles by container.agents.store.profiles.collectAsStateWithLifecycle()
    val p = profiles.find { it.id == agentId } ?: return
    val revision by runtime.store.revision.collectAsStateWithLifecycle()
    val skillRevision by container.skills.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Settings sections, in strip order: brief schedule, goals, watchers, routines, skills, sources, rules, teams, templates.
    val visible = listOf(2, 0, 3, 4, 5, 6, 7, 8, 9)
    var tab by remember(agentId, initialTab) { mutableIntStateOf(initialTab.takeIf { it in visible } ?: 2) }
    var error by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<JsonReview?>(null) }
    var emailSetup by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf<String?>(null) }
    var pendingGrant by remember { mutableStateOf<PersonalConnectorGrant?>(null) }
    val documentsUnavailable = stringResource(R.string.proactive_documents_unavailable)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val grant = pendingGrant
        pendingGrant = null
        if (grant != null && grants.values.all { it }) scope.launch(Dispatchers.IO) { runtime.store.saveGrant(agentId, grant) }
        else error = "Permission was not granted. This source remains disconnected."
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            try { withContext(Dispatchers.IO) {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runtime.store.saveGrant(agentId, PersonalConnectorGrant("drive_files", enabled = true, endpoint = uri.toString(), authorizedAtMs = System.currentTimeMillis()))
            } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: documentsUnavailable }
        }
    }
    val data by produceState(ProactivePageData(), agentId, revision, skillRevision) {
        value = withContext(Dispatchers.IO) {
            try { ProactivePageData(runtime.store.goals(agentId), runtime.store.opportunities(agentId), runtime.store.briefs(agentId),
                runtime.store.watchers(agentId), runtime.store.routines(agentId), container.skills.list().filter { it.ownerAgentId == null || it.ownerAgentId == agentId }, runtime.store.grants(agentId), calendarDrafts = runtime.store.calendarDrafts(agentId)) }
            catch (e: Exception) { ProactivePageData(error = e.message ?: "Saved proactive state could not be read") }
        }
    }
    fun run(action: suspend () -> Unit) { scope.launch {
        try { withContext(Dispatchers.IO) { action() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "The change could not be saved" }
    } }
    PorcelainPage(stringResource(R.string.proactive_settings_title), onBack) {
        PageColumn {
            RowGroup {
                Segmented((0..4).map { it to "L$it" }, p.proactivityLevel, { level -> run { container.agents.store.update(agentId) { it.copy(proactivityLevel = level) }; container.agents.reschedule() } }, fill = true)
                ProactiveLevelDescription(p.proactivityLevel, Modifier.padding(16.dp))
            }
            val counts = listOf(data.goals.count { it.state != GoalState.ARCHIVED && it.state != GoalState.COMPLETED }, data.opportunities.count { it.state == OpportunityCard.State.PENDING },
                data.briefs.size, data.watchers.count { it.enabled }, data.routines.count { it.enabled }, data.skills.size, null, null, null, null)
            val labels = listOf(R.string.proactive_goals, R.string.proactive_suggestions, R.string.proactive_brief_schedule_tab, R.string.proactive_watchers,
                R.string.proactive_routines, R.string.proactive_skills, R.string.proactive_context, R.string.proactive_rules, R.string.proactive_groups, R.string.proactive_templates)
            ProactiveTabs(visible.map { i -> stringResource(labels[i]) to counts[i].takeIf { i != 2 } }, visible.indexOf(tab).coerceAtLeast(0)) { tab = visible[it] }
            data.error?.let { Text(it, color = P.danger) }
            // Sections slide along the same axis as the tab strip; content keeps its own state per section.
            androidx.compose.animation.AnimatedContent(tab, transitionSpec = {
                val forward = targetState > initialState
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) + androidx.compose.animation.slideInHorizontally(Motion.page) { w -> if (forward) w / 8 else -w / 8 }) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90))
            }, label = "proactive-tab") { shown -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (shown) {
                0 -> {
                    if (data.goals.isEmpty()) ProactiveEmpty(PIcons.pin, stringResource(R.string.proactive_empty_goals_title), stringResource(R.string.proactive_add_goal_hint))
                    data.goals.sortedByDescending { it.updatedAtMs }.forEachIndexed { index, goal ->
                        val done = goal.milestones.count { it.status == "done" }
                        Box(Modifier.inkEnter(index)) { ProactiveCard(goal.title, goalStateLabel(goal.state) + " · " + stringResource(R.string.proactive_goal_progress, done, goal.milestones.size)) {
                            ProgressLine(done, goal.milestones.size, color = if (goal.state == GoalState.BLOCKED) P.danger else P.ink)
                            MarkdownText(goal.description)
                            if (goal.milestones.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { goal.milestones.forEach { MilestoneLine(it.status, it.title) } }
                            if (goal.acceptanceCriteria.isNotEmpty()) {
                                SectionLabel(stringResource(R.string.proactive_goal_done_when))
                                goal.acceptanceCriteria.forEach { Text("• $it", color = P.sub, fontSize = 13.sp) }
                            }
                            goal.replanHistory.lastOrNull()?.let { SectionLabel(stringResource(R.string.proactive_goal_replanned)); Text(it.summary, color = P.sub, fontSize = 13.sp) }
                            if (p.proactivityLevel >= 4) SwitchRow(null, stringResource(R.string.proactive_goal_authorize), stringResource(R.string.proactive_goal_scope), checked = goal.autoAdvance) { enabled -> run {
                                runtime.authorizeGoal(agentId, goal.id, enabled)
                            } }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PButton(stringResource(R.string.action_edit), { review = JsonReview(goal.title, UmbraJson.encodeToString(AgentGoal.serializer(), goal)) { json ->
                                    runtime.store.updateGoal(goal, UmbraJson.decodeFromString(AgentGoal.serializer(), json).copy(id = goal.id, agentId = agentId, updatedAtMs = System.currentTimeMillis()))
                                    runtime.requestGoalCheck(agentId)
                                } }, style = ButtonStyle.Ghost)
                                PButton(if (goal.state == GoalState.PAUSED) stringResource(R.string.action_resume) else stringResource(R.string.action_pause), { run { runtime.pauseGoal(agentId, goal.id, goal.state != GoalState.PAUSED) } }, style = ButtonStyle.Ghost)
                            }
                            goal.linkedSessionIds.forEach { sid -> PButton(stringResource(R.string.proactive_open_result), { onOpenSession(sid) }, style = ButtonStyle.Ghost, tiny = true) }
                        } }
                    }
                }
                2 -> {
                    RowGroup {
                        for ((morning, minute) in listOf(true to p.morningBriefMinute, false to p.eveningBriefMinute)) {
                            val label = stringResource(if (morning) R.string.proactive_morning else R.string.proactive_evening)
                            val time = minute?.let { "%02d:%02d".format(it / 60, it % 60) }
                            fun saveMinute(value: Int?) { run {
                                container.agents.store.update(agentId) { if (morning) it.copy(morningBriefMinute = value) else it.copy(eveningBriefMinute = value) }
                                container.agents.reschedule()
                            } }
                            SwitchRow(PIcons.clock, label, time, minute != null) { on -> saveMinute(if (on) if (morning) 480 else 1080 else null) }
                            if (minute != null) NavRow(PIcons.clock, stringResource(R.string.proactive_brief_time, label), value = time) {
                                android.app.TimePickerDialog(context, { _, hour, selectedMinute -> saveMinute(hour * 60 + selectedMinute) },
                                    minute / 60, minute % 60, android.text.format.DateFormat.is24HourFormat(context)).show()
                            }
                        }
                    }
                    Text(stringResource(R.string.proactive_brief_schedule_hint), color = P.sub, fontSize = 13.sp)
                }
                3 -> {
                    Text(stringResource(R.string.proactive_watcher_hint), color = P.sub)
                    data.watchers.forEach { watcher ->
                        ProactiveCard(watcher.title, stringResource(R.string.proactive_watcher_every, watcher.intervalMinutes) + " · " + conditionText(watcher.condition)) {
                            Text(when (val probe = watcher.probe) { is ProbeSpec.HttpOrCss -> probe.url + "\n" + probe.selectorOrJsonPath; is ProbeSpec.GuestCheckCommand -> probe.argv.joinToString(" "); is ProbeSpec.NotificationPattern -> stringResource(if (probe.literal) R.string.proactive_notification_literal else R.string.proactive_notification_regex, probe.packageName ?: stringResource(R.string.proactive_notification_all_apps), probe.regex) }, color = P.ink)
                            Text(watcher.onMatchInstruction, color = P.sub)
                            SwitchRow(null, stringResource(R.string.proactive_enabled), checked = watcher.enabled) { enabled -> run {
                                runtime.store.saveWatcher(watcher.copy(enabled = enabled, authorizedAtMs = if (enabled) System.currentTimeMillis() else null))
                                container.agents.reschedule()
                            } }
                            watcher.lastValue?.let { Text(it, color = P.sub, fontSize = 12.sp) }
                            watcher.error?.let { Text(it, color = P.danger) }
                            PButton(stringResource(R.string.action_edit), { review = JsonReview(watcher.title, UmbraJson.encodeToString(AgentWatcher.serializer(), watcher)) { json ->
                                runtime.store.saveWatcher(UmbraJson.decodeFromString(AgentWatcher.serializer(), json).copy(id = watcher.id, agentId = agentId, enabled = false, authorizedAtMs = null))
                            } }, style = ButtonStyle.Ghost)
                        }
                    }
                }
                4 -> {
                    Text(stringResource(R.string.proactive_routine_hint), color = P.sub)
                    data.routines.forEach { routine ->
                        ProactiveCard(routine.title, triggerText(routine.trigger)) {
                            MarkdownText(routine.instruction)
                            Text(stringResource(R.string.proactive_routine_scope, routine.allowedConnectors.joinToString().ifEmpty { "—" }, routine.approvalTTLMinutes), color = P.sub, fontSize = 13.sp)
                            SwitchRow(null, stringResource(R.string.proactive_authorized), checked = routine.enabled) { enabled -> run {
                                runtime.setRoutineEnabled(agentId, routine.id, enabled)
                            } }
                            if (routine.enabled) PButton(stringResource(R.string.proactive_run), { run { runtime.runRoutine(p, routine, "manual:${System.currentTimeMillis()}") } }, style = ButtonStyle.Ghost)
                            PButton(stringResource(R.string.action_edit), { review = JsonReview(routine.title, UmbraJson.encodeToString(AgentRoutine.serializer(), routine)) { json ->
                                val edited = UmbraJson.decodeFromString(AgentRoutine.serializer(), json).copy(id = routine.id, ownerAgentId = agentId, enabled = false, authorizedAtMs = null, recentRuns = routine.recentRuns, nextRunAtMs = null)
                                runtime.setRoutineEnabled(agentId, routine.id, false)
                                runtime.store.saveRoutine(edited)
                            } }, style = ButtonStyle.Ghost)
                            routine.recentRuns.asReversed().forEach { record ->
                                Text("${record.status} · ${Instant.ofEpochMilli(record.startedAtMs)}\n${record.summary.ifBlank { record.error.orEmpty() }}", color = P.sub, fontSize = 12.sp)
                                record.sessionId?.let { sid -> PButton(stringResource(R.string.proactive_open_result), { onOpenSession(sid) }, style = ButtonStyle.Ghost) }
                            }
                        }
                    }
                }
                5 -> {
                    TeachPanel(agentId) { error = it }
                    data.skills.forEach { skill ->
                    ProactiveCard(skill.name, "v${skill.version} · ${skill.status}") {
                        Text(skill.description, color = P.ink)
                        Text(skill.approvalBoundary, color = P.amber)
                        Text(skill.verificationChecks.joinToString("\n"), color = P.sub)
                        skill.synthesizedScript?.let { source ->
                            val caps = skill.scriptCapabilities
                            Text(when {
                                caps == null -> stringResource(R.string.proactive_script_transform)
                                caps.network -> stringResource(R.string.proactive_script_network, skill.scriptLanguage.orEmpty(), caps.allowedDomains.joinToString())
                                else -> stringResource(R.string.proactive_script_offline, skill.scriptLanguage.orEmpty())
                            }, color = if (caps?.network == true) P.amber else P.sub, fontSize = 12.sp)
                            Text(source, Modifier.fillMaxWidth().background(P.code, RoundedCornerShape(10.dp)).horizontalScroll(rememberScrollState()).padding(12.dp),
                                color = P.codeText, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 17.sp)
                            skill.testCases.forEach { case -> Text("${case.input} → ${case.expected ?: case.expectedKeys.joinToString(prefix = "{", postfix = "…}")}", color = P.sub, fontSize = 12.sp) }
                            PButton(stringResource(R.string.proactive_test_skill), { run { container.synthesizedTools.test(skill.id) } }, enabled = skill.status != UmbraSkill.Status.TESTING, style = ButtonStyle.Ghost)
                        }
                        if (skill.synthesizedScript == null) PButton(stringResource(R.string.proactive_test_skill), {
                            review = JsonReview(skill.name, buildString {
                                append("{"); append(skill.parameters.filter { it.required || it.defaultValue != null }.joinToString(",") { p -> "\"${p.name}\":${kotlinx.serialization.json.JsonPrimitive(p.defaultValue.orEmpty())}" }); append("}")
                            }) { json ->
                                val args = (UmbraJson.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject).mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content }
                                val task = container.skillWorkflows.test(skill.id, args)
                                withContext(Dispatchers.Main) { onOpenSession(task.id) }
                            }
                        }, enabled = skill.status != UmbraSkill.Status.TESTING, style = ButtonStyle.Ghost)
                        PButton(stringResource(R.string.action_edit), { scope.launch {
                            val text = withContext(Dispatchers.IO) { container.skills.instructions(skill.id) }
                            review = JsonReview(skill.name, text) { replacement -> container.skills.propose(skill, replacement) }
                        } }, style = ButtonStyle.Ghost)
                        skill.pendingPatch?.let { patch -> PButton(stringResource(R.string.proactive_review_patch), { review = JsonReview(skill.name, patch) { text ->
                            container.skills.proposePatch(skill.id, text); container.skills.applyReviewedPatch(skill.id)
                        } }, style = ButtonStyle.Ghost) }
                        PButton(stringResource(R.string.proactive_enable_skill), { run { container.skills.approve(skill.id) } }, enabled = skill.testedVersion == skill.version, style = ButtonStyle.Ghost)
                        if (skill.status == UmbraSkill.Status.READY) PButton(stringResource(R.string.proactive_disable), { run { container.skills.disable(skill.id) } }, style = ButtonStyle.Ghost)
                    }
                }
                }
                6 -> {
                    Text(stringResource(R.string.proactive_context_hint), color = P.sub)
                    listOf("calendar", "contacts", "email", "tasks", "drive_files").forEach { connector ->
                        val grant = data.grants.find { it.connectorId == connector }
                        RowGroup {
                            SwitchRow(null, if (connector == "drive_files") stringResource(R.string.proactive_documents_title) else connector, checked = grant?.enabled == true) { enabled ->
                                if (!enabled) disconnect = connector
                                else if (connector == "email") emailSetup = true
                                else if (connector == "drive_files") documents.launch(null)
                                else if (connector == "tasks") run { runtime.store.saveGrant(agentId, PersonalConnectorGrant(connector, enabled = true, authorizedAtMs = System.currentTimeMillis())) }
                                else {
                                    pendingGrant = PersonalConnectorGrant(connector, enabled = true, authorizedAtMs = System.currentTimeMillis())
                                    permissions.launch(arrayOf(if (connector == "calendar") Manifest.permission.READ_CALENDAR else Manifest.permission.READ_CONTACTS))
                                }
                            }
                            if (connector == "drive_files") {
                                Text(stringResource(R.string.proactive_documents_body), Modifier.padding(16.dp), color = P.sub, fontSize = 13.sp)
                                if (grant?.enabled == true) PButton(stringResource(R.string.proactive_documents_choose), { documents.launch(null) }, style = ButtonStyle.Ghost)
                            }
                            if (grant?.enabled == true && connector in setOf("email", "tasks", "calendar")) {
                                val scopes = if (connector == "email") listOf(ConnectorScope.READ_ONLY to stringResource(R.string.proactive_scope_read), ConnectorScope.DRAFT_ONLY to stringResource(R.string.proactive_scope_draft), ConnectorScope.SEND_WITH_APPROVAL to stringResource(R.string.proactive_scope_send))
                                    else if (connector == "calendar") listOf(ConnectorScope.READ_ONLY to stringResource(R.string.proactive_scope_read), ConnectorScope.PROPOSE_EVENT to stringResource(R.string.proactive_scope_propose_event), ConnectorScope.WRITE_EVENTS to stringResource(R.string.proactive_scope_write_events))
                                    else listOf(ConnectorScope.READ_ONLY to stringResource(R.string.proactive_scope_read), ConnectorScope.WRITE_TASKS to stringResource(R.string.proactive_scope_tasks))
                                Segmented(scopes, grant.scope, { choice ->
                                    if (connector == "calendar" && choice == ConnectorScope.WRITE_EVENTS) {
                                        pendingGrant = grant.copy(scope = choice, authorizedAtMs = System.currentTimeMillis())
                                        permissions.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                                    } else run { runtime.store.saveGrant(agentId, grant.copy(scope = choice, authorizedAtMs = System.currentTimeMillis())) }
                                }, fill = true)
                            }
                            if (connector == "calendar" && grant?.enabled == true) data.calendarDrafts.forEach { draft ->
                                Text(draft.review(), color = P.sub)
                                PButton(stringResource(R.string.proactive_calendar_review), { run {
                                    withContext(Dispatchers.Main) { context.startActivity(android.content.Intent(android.content.Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI).apply {
                                        putExtra(android.provider.CalendarContract.Events.TITLE, draft.title)
                                        putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, draft.startMs)
                                        putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, draft.endMs)
                                        putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, draft.location)
                                        putExtra(android.provider.CalendarContract.Events.DESCRIPTION, draft.description)
                                    }) }
                                } }, style = ButtonStyle.Ghost)
                                PButton(stringResource(R.string.proactive_calendar_dismiss), { run { runtime.store.removeCalendarDraft(agentId, draft.id) } }, style = ButtonStyle.Ghost)
                            }
                        }
                    }
                }
                7 -> CustomRulesPanel(agentId) { error = it }
                8 -> TeamPanel(agentId, onOpenSession)
                9 -> TemplatePanel(agentId)
            }
            } }
        }
    }
    review?.let { item -> JsonReviewDialog(item, onDismiss = { review = null }, onSave = { text ->
        run { item.save(text); withContext(Dispatchers.Main) { review = null } }
    }) }
    if (emailSetup) EmailSecretDialog(onDismiss = { emailSetup = false }, onSave = { token -> run {
        val name = "EMAIL_${agentId.uppercase()}".take(64)
        container.secrets.put(name, token)
        runtime.store.saveGrant(agentId, PersonalConnectorGrant("email", enabled = true, secretName = name, authorizedAtMs = System.currentTimeMillis()))
        withContext(Dispatchers.Main) { emailSetup = false }
    } })
    disconnect?.let { connector ->
        var purge by remember(connector) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { disconnect = null }, title = { Text(stringResource(R.string.proactive_disconnect, if (connector == "drive_files") stringResource(R.string.proactive_documents_title) else connector)) },
            text = { SwitchRow(null, stringResource(R.string.proactive_forget_source), checked = purge) { purge = it } },
            confirmButton = { PButton(stringResource(R.string.action_confirm), { run {
                container.personalContext.revoke(agentId, connector, purge)
                withContext(Dispatchers.Main) { disconnect = null }
            } }) }, dismissButton = { PButton(stringResource(R.string.action_cancel), { disconnect = null }, style = ButtonStyle.Ghost) })
    }
    error?.let { text -> AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.proactive_action_failed)) }, text = { Text(text) },
        confirmButton = { PButton(stringResource(R.string.action_close), { error = null }) }) }
}

@Composable private fun conditionText(c: ConditionSpec) = when (c) {
    ConditionSpec.Changed -> stringResource(R.string.cond_changed)
    is ConditionSpec.Contains -> stringResource(R.string.cond_contains, c.substring)
    is ConditionSpec.NumericCompare -> stringResource(R.string.cond_numeric, c.op.replace("<=", "≤").replace(">=", "≥"), java.text.NumberFormat.getNumberInstance().format(c.threshold))
    is ConditionSpec.LayaVerify -> stringResource(R.string.cond_laya, c.question)
}
@Composable private fun triggerText(t: RoutineTrigger) = when (t) {
    is RoutineTrigger.CronSchedule -> CronText.describe(t.cron, java.util.Locale.getDefault().language == "zh") ?: stringResource(R.string.trigger_cron, t.cron)
    is RoutineTrigger.EventMatch -> stringResource(R.string.trigger_event, t.source)
    is RoutineTrigger.WatcherMatch -> stringResource(R.string.trigger_watcher)
    RoutineTrigger.Manual -> stringResource(R.string.trigger_manual)
}
@Composable private fun goalStateLabel(state: GoalState) = stringResource(when (state) {
    GoalState.IN_PROGRESS -> R.string.goal_state_in_progress; GoalState.SCHEDULED -> R.string.goal_state_scheduled
    GoalState.WAITING_USER -> R.string.goal_state_waiting; GoalState.BLOCKED -> R.string.goal_state_blocked
    GoalState.PAUSED -> R.string.goal_state_paused; GoalState.COMPLETED -> R.string.goal_state_completed; GoalState.ARCHIVED -> R.string.goal_state_archived
})
@Composable private fun opportunityKindLabel(kind: OpportunityCard.Kind) = stringResource(when (kind) {
    OpportunityCard.Kind.TASK_DISCOVERY -> R.string.opp_kind_task; OpportunityCard.Kind.CONFLICT_ALERT -> R.string.opp_kind_conflict
    OpportunityCard.Kind.FOLLOW_UP -> R.string.opp_kind_follow_up; OpportunityCard.Kind.PRICE_OR_MONITOR -> R.string.opp_kind_monitor
    OpportunityCard.Kind.GOAL_SUGGESTION -> R.string.opp_kind_goal
})
@Composable private fun ProactiveCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) = RowGroup {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = P.ink, fontSize = 17.sp)
        Text(subtitle, color = P.sub, fontSize = 12.sp)
        content()
    }
}
@Composable private fun JsonReviewDialog(item: JsonReview, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(item) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(item.instruction?.text ?: item.content)) }
    val tooLong = item.instruction != null && text.text.length > 20_000
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.imePadding(), title = { Text(item.title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item.instruction?.context?.forEach { (field, value) -> Text("$field: $value", color = P.sub, fontSize = 13.sp) }
            androidx.compose.material3.OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(max = 320.dp),
            label = item.instruction?.let { input -> { Text(stringResource(if (input.message) R.string.proactive_message_draft else R.string.proactive_instruction)) } }, minLines = 3, maxLines = 10,
            isError = tooLong, supportingText = item.instruction?.let { { Text("${text.text.length}/20000") } },
            textStyle = androidx.compose.ui.text.TextStyle(color = P.ink, fontFamily = if (item.instruction == null) P.mono else P.sans, fontSize = 14.sp))
            if (item.instruction?.message == true) Text(stringResource(R.string.proactive_message_draft_note), color = P.sub, fontSize = 12.sp)
        } },
        confirmButton = { PButton(stringResource(R.string.action_confirm), { onSave(item.instruction?.payload(text.text) ?: text.text) }, enabled = text.text.isNotBlank() && !tooLong) },
        dismissButton = { PButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Ghost) })
}
@Composable private fun EmailSecretDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var token by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.proactive_email_title)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.proactive_email_body), color = P.sub)
                PField(token, { token = it }, label = "OAuth access token", visualTransformation = PasswordVisualTransformation(), singleLine = true)
            }
        }, confirmButton = { PButton(stringResource(R.string.action_confirm), { onSave(token) }, enabled = token.isNotBlank()) },
        dismissButton = { PButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Ghost) })
}
