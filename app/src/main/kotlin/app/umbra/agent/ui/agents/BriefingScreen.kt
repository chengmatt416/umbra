package app.umbra.agent.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
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
 * is configured. Today's brief first (it unrolls), then the suggestions waiting for you, then its goals, then earlier
 * briefs. Everything about how proactive it is lives in its settings ([ProactiveScreen]).
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
    val today = java.time.LocalDate.now().toString()
    var openBrief by remember(agentId) { mutableStateOf<String?>(null) }
    LaunchedEffect(data.briefs) { if (openBrief == null && focus == "brief") openBrief = data.briefs.firstOrNull { it.date == today }?.id }

    PorcelainPage(stringResource(R.string.briefing_title), onBack, actions = {
        InkIconButton(PIcons.sliders, stringResource(R.string.proactive_settings_title), onSettings, Modifier, 40.dp, P.ink)
    }) {
        PageColumn {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AgentAvatar(p.avatar, p.name, 44.dp)
                Column {
                    Text(p.name, color = P.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.briefing_sub, p.proactivityLevel), color = P.sub, fontSize = 13.sp)
                }
            }
            error?.let { Callout(it, Tone.Danger) }

            val latest = data.briefs.firstOrNull()
            if (latest == null) ProactiveEmpty(PIcons.clock, stringResource(R.string.proactive_empty_briefs_title), stringResource(R.string.briefing_no_briefs))
            else ExpandableBrief(latest, null, openBrief == latest.id, { openBrief = if (it) latest.id else null },
                onSuggestion = { replying = it }, onOpenSession = onOpenSession, modifier = Modifier.inkEnter())

            val pending = data.cards.filter { it.state == OpportunityCard.State.PENDING }.sortedWith(compareByDescending<OpportunityCard> { it.urgency }.thenByDescending { it.createdAtMs })
            SectionLabel(stringResource(R.string.briefing_suggestions) + if (pending.isNotEmpty()) " · ${pending.size}" else "")
            if (pending.isEmpty()) Text(stringResource(R.string.proactive_no_suggestions), color = P.sub, fontSize = 13.sp)
            pending.forEachIndexed { i, card ->
                SuggestionCard(card, modifier = Modifier.inkEnter(i + 1), onReview = { replying = card },
                    onDismiss = { run { runtime.dismiss(agentId, card.id, false) } }, onLess = { run { runtime.dismiss(agentId, card.id, true) } })
            }

            val goals = data.goals.filter { it.state != GoalState.ARCHIVED }.sortedWith(compareBy<AgentGoal> { it.state == GoalState.COMPLETED }.thenByDescending { it.updatedAtMs })
            if (goals.isNotEmpty()) {
                SectionLabel(stringResource(R.string.proactive_goals))
                goals.forEach { goal -> GoalCard(goal, onPause = { run { runtime.pauseGoal(agentId, goal.id, goal.state != GoalState.PAUSED) } }, onOpenSession = onOpenSession) }
            }

            val earlier = data.briefs.drop(1).take(13)
            if (earlier.isNotEmpty()) {
                SectionLabel(stringResource(R.string.briefing_earlier))
                earlier.forEach { brief ->
                    ExpandableBrief(brief, null, openBrief == brief.id, { openBrief = if (it) brief.id else null }, onSuggestion = { replying = it }, onOpenSession = onOpenSession)
                }
            }
            // Recently handled suggestions, so adopting one leads to its result.
            data.cards.filter { it.state != OpportunityCard.State.PENDING }.sortedByDescending { it.createdAtMs }.take(5).forEach { card ->
                SuggestionCard(card, compact = true, onReview = {}, onDismiss = {}, onOpenResult = card.executionSessionId?.let { sid -> { onOpenSession(sid) } })
            }
        }
    }
    replying?.let { card ->
        SuggestionReplySheet(card, p.name, onDismiss = { replying = null }) { payload, note ->
            val session = runtime.adopt(agentId, card.id, payload, note)
            withContext(Dispatchers.Main) { replying = null; onOpenSession(session.id) }
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

/**
 * Answering a suggestion: what the agent prepared, readable, and one plain message field. A message draft is the
 * message itself (edit it and it is sent for review as edited); anything else gets an empty field for what you want
 * to add or change, in your own words. No configuration text is ever put in the field.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SuggestionReplySheet(card: OpportunityCard, agentName: String, onDismiss: () -> Unit, onSend: suspend (payload: String?, note: String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val draft = remember(card.id) { OpportunityInput.from(card.preparedAction)?.takeIf { it.message } }
    var text by remember(card.id) { mutableStateOf(draft?.text.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = P.base) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(card.title, color = P.ink, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
            if (draft == null) PreparedPreview(card.preparedAction)
            else draft.context.forEach { (field, value) -> Text(stringResource(R.string.card_to, value).takeIf { field in setOf("to", "recipient", "chat") } ?: "$field: $value", color = P.sub, fontSize = 13.sp) }
            PField(text, { text = it; error = null }, Modifier.fillMaxWidth().heightIn(min = 96.dp),
                label = stringResource(if (draft != null) R.string.proactive_message_draft else R.string.suggestion_reply_label),
                placeholder = if (draft == null) stringResource(R.string.suggestion_reply_hint, agentName) else null)
            Text(stringResource(if (draft != null) R.string.proactive_message_draft_note else R.string.suggestion_reply_note), color = P.sub, fontSize = 12.sp)
            error?.let { Callout(it, Tone.Danger) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PButton(card.preparedAction.label, {
                    busy = true
                    scope.launch {
                        try {
                            if (draft != null) onSend(draft.payload(text), null) else onSend(null, text.takeIf { it.isNotBlank() })
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "That did not work" }
                        finally { busy = false }
                    }
                }, Modifier.weight(1f), enabled = !busy && (draft == null || text.isNotBlank()) && text.length <= 4_000, icon = PIcons.send)
                PButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Ghost)
            }
        }
    }
}
