package app.umbra.agent.ui.agents

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.agents.AgentProfile
import app.umbra.agent.agents.proactive.AgentGoal
import app.umbra.agent.agents.proactive.DailyBrief
import app.umbra.agent.agents.proactive.GoalState
import app.umbra.agent.agents.proactive.OpportunityCard
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Home's Agents tab shows above the chat list (spec §11): today's brief and the two suggestions that matter most. */
internal data class AgentsToday(
    val brief: Pair<AgentProfile, DailyBrief>? = null,
    val suggestions: List<Pair<AgentProfile, OpportunityCard>> = emptyList(),
    val goals: Map<String, AgentGoal> = emptyMap(),
    val pending: Map<String, Int> = emptyMap(),
)

@Composable
internal fun rememberAgentsToday(profiles: List<AgentProfile>): AgentsToday {
    val store = LocalContext.current.container.agents.proactive.store
    val revision by store.revision.collectAsStateWithLifecycle()
    val today by produceState(AgentsToday(), profiles, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val date = java.time.LocalDate.now().toString()
                val brief = profiles.flatMap { p -> store.briefs(p.id).filter { it.date == date }.map { p to it } }.maxByOrNull { it.second.generatedAtMs }
                val pending = profiles.associate { p -> p.id to store.opportunities(p.id).filter { it.state == OpportunityCard.State.PENDING } }
                val suggestions = profiles.filter { it.running }.flatMap { p -> pending[p.id].orEmpty().map { p to it } }
                    .sortedWith(compareByDescending<Pair<AgentProfile, OpportunityCard>> { it.second.urgency }.thenByDescending { it.second.createdAtMs }).take(2)
                val goals = profiles.mapNotNull { p ->
                    store.goals(p.id).filter { it.state in setOf(GoalState.IN_PROGRESS, GoalState.WAITING_USER, GoalState.BLOCKED, GoalState.SCHEDULED) }
                        .maxWithOrNull(compareBy<AgentGoal> { it.priority }.thenBy { it.updatedAtMs })?.let { p.id to it }
                }.toMap()
                AgentsToday(brief, suggestions, goals, pending.mapValues { it.value.size })
            }.getOrDefault(AgentsToday())
        }
    }
    return today
}

@Composable
internal fun AgentsTodayDeck(today: AgentsToday, onProactive: (String, Int) -> Unit, onOpenSession: (String) -> Unit = {}) {
    if (today.brief == null && today.suggestions.isEmpty()) return
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).animateContentSize(Motion.settle()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Today's brief unrolls right here (tap or pull it down); the whole Briefing is one tap further.
        var open by rememberSaveable { mutableStateOf(false) }
        var replying by remember { mutableStateOf<Pair<AgentProfile, OpportunityCard>?>(null) }
        today.brief?.let { (agent, brief) ->
            ExpandableBrief(brief, agent.name, open, { open = it }, modifier = Modifier.inkEnter(),
                onSuggestion = { card -> replying = agent to card }, onOpenSession = onOpenSession,
                footer = { PButton(stringResource(R.string.briefing_open), { onProactive(agent.id, 2) }, style = ButtonStyle.Alt, icon = PIcons.arrowRight, tiny = true) })
        }
        today.suggestions.forEachIndexed { index, (agent, card) ->
            SuggestionCard(card, agent.name, compact = true, modifier = Modifier.inkEnter(index + 1), onReview = { replying = agent to card },
                onDismiss = { scope.launch { runCatching { withContext(Dispatchers.IO) { container.agents.proactive.dismiss(agent.id, card.id, false) } } } })
        }
        replying?.let { (agent, card) ->
            SuggestionReplySheet(card, agent.name, onDismiss = { replying = null }) { payload, note ->
                container.agents.proactive.adopt(agent.id, card.id, payload, note)
                withContext(Dispatchers.Main) { replying = null; onProactive(agent.id, 1) }
            }
        }
    }
}

/** The goal an agent is working on, one line under its row. */
@Composable
internal fun AgentGoalLine(goal: AgentGoal) {
    val done = goal.milestones.count { it.status == "done" }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PIcon(PIcons.pin, size = 12.dp, tint = if (goal.state == GoalState.WAITING_USER || goal.state == GoalState.BLOCKED) P.amber else P.muted)
        Text(goal.title, Modifier.weight(1f, fill = false), fontSize = 12.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (goal.milestones.isNotEmpty()) ProgressLine(done, goal.milestones.size, Modifier.width(44.dp))
    }
}
