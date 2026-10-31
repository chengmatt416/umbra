package app.umbra.agent.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.agents.AgentProfile
import app.umbra.agent.agents.proactive.*
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.*
import app.umbra.agent.ui.shell.PageColumn
import app.umbra.agent.ui.shell.PorcelainPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BriefingData(val briefs: List<DailyBrief> = emptyList(), val cards: List<OpportunityCard> = emptyList(), val goals: List<AgentGoal> = emptyList())

/**
 * An agent's Briefing (reached from its row on the Agents overview and from its chat): what it has for you, not how it
 * is configured. What needs your decision comes first, each laid out as a decision you can make at a glance (why now,
 * what happens if you confirm, what it needs from you); then the latest brief (it unrolls), the goals, earlier briefs
 * and what was handled lately. Everything about how proactive it is lives in its settings ([ProactiveScreen]).
 */
@Composable
fun BriefingScreen(agentId: String, onBack: () -> Unit, onOpenSession: (String) -> Unit, onSettings: () -> Unit, focus: String = "brief") {
    val container = LocalContext.current.container
    val runtime = container.agents.proactive
    val profiles by container.agents.store.profiles.collectAsStateWithLifecycle()
    val p = profiles.find { it.id == agentId } ?: return
    val revision by runtime.store.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var replying by remember { mutableStateOf<OpportunityCard?>(null) }
    var asked by remember(agentId) { mutableStateOf(emptySet<String>()) }
    val data by produceState(BriefingData(), agentId, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching { BriefingData(runtime.store.briefs(agentId).sortedByDescending { it.generatedAtMs }, runtime.store.opportunities(agentId), runtime.store.goals(agentId)) }.getOrDefault(BriefingData())
        }
    }
    fun run(action: suspend () -> Unit) { scope.launch {
        try { withContext(Dispatchers.IO) { action() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "That did not work" }
    } }
    // Asking for a fix is a message from the user to the agent, in its chat: it prepares, the user still decides.
    fun ask(conflict: ConflictAlert) {
        val instruction = conflict.resolutionOptions.firstOrNull()?.payloadJson
            ?.let { runCatching { (app.umbra.core.model.UmbraJson.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject)?.get("instruction") }.getOrNull() }
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return
        run {
            container.agents.talk(agentId, instruction, via = "Briefing")
            withContext(Dispatchers.Main) { asked = asked + conflict.id; p.sessionId?.let(onOpenSession) }
        }
    }
    val today = java.time.LocalDate.now().toString()
    var openBrief by remember(agentId) { mutableStateOf<String?>(null) }
    LaunchedEffect(data.briefs) { if (openBrief == null && focus == "brief") openBrief = data.briefs.firstOrNull { it.date == today }?.id }

    val pending = data.cards.filter { it.state == OpportunityCard.State.PENDING }.sortedWith(compareByDescending<OpportunityCard> { it.kind == OpportunityCard.Kind.CONFLICT_ALERT }
        .thenByDescending { it.expiresAtMs?.let { at -> at - System.currentTimeMillis() < 6 * 3_600_000L } == true }.thenByDescending { it.urgency }.thenByDescending { it.createdAtMs })
    val latest = data.briefs.firstOrNull()
    // The latest brief's clashes wait here while that brief is today's (yesterday's evening one still counts before the morning's).
    val fresh = latest?.takeIf { it.date == today || it.date == java.time.LocalDate.now().minusDays(1).toString() && it.kind == DailyBrief.Kind.EVENING }
    // A clash an agent already turned into a suggestion (same calendar entries) is decided there, not twice.
    val covered = pending.filter { it.kind == OpportunityCard.Kind.CONFLICT_ALERT }.flatMap { it.evidenceRefs }.filter { ':' in it }.toSet()
    val clashes = fresh?.conflictsDetected.orEmpty().filter { it.id !in asked && it.sources.none { source -> source in covered } }

    PorcelainPage(stringResource(R.string.briefing_title), onBack, actions = {
        InkIconButton(PIcons.sliders, stringResource(R.string.proactive_settings_title), onSettings, Modifier, 40.dp, P.ink)
    }) {
        PageColumn {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AgentAvatar(p.avatar, p.name, 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = P.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(statusLine(p, pending.size + clashes.size), color = P.sub, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            error?.let { Callout(it, Tone.Danger) }

            // 1. What needs the user: the decisions, most pressing first.
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.briefing_decide_title), Modifier.semantics { heading() }, color = P.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.briefing_decide_note), color = P.sub, fontSize = 13.sp, lineHeight = 18.sp)
                }
                val waiting = pending.size + clashes.size
                if (waiting > 0) Box(Modifier.size(30.dp).clip(CircleShape).background(P.amber.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                    Text("$waiting", color = P.amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (pending.isEmpty() && clashes.isEmpty()) AllClear(p.name)
            clashes.forEachIndexed { i, conflict -> ConflictDecision(conflict, p.name, { ask(conflict) }, Modifier.inkEnter(i)) }
            pending.forEachIndexed { i, card ->
                DecisionCard(card, p.name, onConfirm = { replying = card },
                    onNotNow = { run { runtime.dismiss(agentId, card.id, false) } }, onLess = { run { runtime.dismiss(agentId, card.id, true) } },
                    modifier = Modifier.inkEnter(clashes.size + i))
            }

            // 2. The latest brief: the day at a glance, unrolling into the schedule, the work and the digest.
            SectionLabel(stringResource(if (latest?.date == today) R.string.briefing_today else R.string.briefing_latest))
            if (latest == null) ProactiveEmpty(PIcons.clock, stringResource(R.string.proactive_empty_briefs_title), stringResource(R.string.briefing_no_briefs))
            else ExpandableBrief(latest, null, openBrief == latest.id, { openBrief = if (it) latest.id else null },
                onSuggestion = { replying = it }, onOpenSession = onOpenSession, modifier = Modifier.inkEnter(),
                onConflict = ::ask, conflictsAbove = latest === fresh)

            // 3. Goals and how far along they are.
            val goals = data.goals.filter { it.state != GoalState.ARCHIVED }.sortedWith(compareBy<AgentGoal> { it.state == GoalState.COMPLETED }.thenByDescending { it.updatedAtMs })
            if (goals.isNotEmpty()) {
                SectionLabel(stringResource(R.string.proactive_goals))
                goals.forEach { goal -> GoalCard(goal, onPause = { run { runtime.pauseGoal(agentId, goal.id, goal.state != GoalState.PAUSED) } }, onOpenSession = onOpenSession) }
            }

            // 4. Earlier briefs, rolled up.
            val earlier = data.briefs.drop(1).take(13)
            if (earlier.isNotEmpty()) {
                SectionLabel(stringResource(R.string.briefing_earlier))
                earlier.forEach { brief ->
                    ExpandableBrief(brief, null, openBrief == brief.id, { openBrief = if (it) brief.id else null }, onSuggestion = { replying = it }, onOpenSession = onOpenSession)
                }
            }
            // 5. Recently handled suggestions, so adopting one leads to its result.
            val handled = data.cards.filter { it.state != OpportunityCard.State.PENDING }.sortedByDescending { it.updatedAtMs ?: it.createdAtMs }.take(5)
            if (handled.isNotEmpty()) {
                SectionLabel(stringResource(R.string.briefing_handled))
                handled.forEach { card ->
                    SuggestionCard(card, compact = true, onReview = {}, onDismiss = {}, onOpenResult = card.executionSessionId?.let { sid -> { onOpenSession(sid) } })
                }
            }
        }
    }
    replying?.let { card ->
        ConfirmSheet(card, p.name, onDismiss = { replying = null }) { payload, note ->
            val session = runtime.adopt(agentId, card.id, payload, note)
            withContext(Dispatchers.Main) { replying = null; onOpenSession(session.id) }
        }
    }
}

/** Under the agent's name: when the next brief comes and how much waits, said plainly (never its settings' numbers). */
@Composable
private fun statusLine(p: AgentProfile, waiting: Int): String {
    val minutes = listOfNotNull(p.morningBriefMinute, p.eveningBriefMinute).sorted()
    val next = if (p.proactivityLevel < 1 || minutes.isEmpty()) null else {
        val now = java.time.LocalTime.now()
        val nowMin = now.hour * 60 + now.minute
        minutes.firstOrNull { it > nowMin }?.let { it to false } ?: (minutes.first() to true)
    }
    val brief = when {
        p.proactivityLevel < 1 -> stringResource(R.string.briefing_status_off)
        next == null -> stringResource(R.string.briefing_status_no_briefs)
        else -> {
            val at = "%02d:%02d".format(next.first / 60, next.first % 60)
            stringResource(if (next.second) R.string.briefing_status_next_tomorrow else R.string.briefing_status_next, at)
        }
    }
    return if (waiting > 0) brief + " · " + androidx.compose.ui.res.pluralStringResource(R.plurals.briefing_waiting, waiting, waiting) else brief
}

/** Nothing to decide: a calm card that says so and that the agent keeps watching. */
@Composable
private fun AllClear(name: String) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
    Row(Modifier.fillMaxWidth().inkSurface(shape, CardStyle.Ceramic).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(P.good.copy(alpha = .14f)), contentAlignment = Alignment.Center) { PIcon(PIcons.check, size = 16.dp, tint = P.good) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.briefing_all_clear_title), color = P.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.briefing_all_clear_body, name), color = P.sub, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/** A goal and how far along it is; editing and authorization stay in the settings. */
@Composable
private fun GoalCard(goal: AgentGoal, onPause: () -> Unit, onOpenSession: (String) -> Unit) {
    val done = goal.milestones.count { it.status == "done" }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().inkSurface(shape, CardStyle.Ceramic).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(goal.title, Modifier.weight(1f), color = P.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            StatusChip(stringResource(R.string.proactive_goal_progress, done, goal.milestones.size), if (goal.state == GoalState.COMPLETED) P.good else P.sub)
        }
        ProgressLine(done, goal.milestones.size, color = if (goal.state == GoalState.BLOCKED) P.danger else P.ink)
        if (goal.milestones.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { goal.milestones.forEach { MilestoneLine(it.status, it.title) } }
        goal.replanHistory.lastOrNull()?.let { Text(it.summary, color = P.sub, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (goal.state != GoalState.COMPLETED) PButton(stringResource(if (goal.state == GoalState.PAUSED) R.string.action_resume else R.string.action_pause), onPause, tiny = true, style = ButtonStyle.Ghost)
            goal.linkedSessionIds.lastOrNull()?.let { sid -> PButton(stringResource(R.string.proactive_open_result), { onOpenSession(sid) }, tiny = true, style = ButtonStyle.Ghost) }
        }
    }
}

/** Home's way into the same confirmation as the Briefing ([ConfirmSheet]). */
@Composable
internal fun SuggestionReplySheet(card: OpportunityCard, agentName: String, onDismiss: () -> Unit, onSend: suspend (payload: String?, note: String?) -> Unit) =
    ConfirmSheet(card, agentName, onDismiss, onSend)
