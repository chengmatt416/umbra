package app.umbra.agent.ui.agents

import app.umbra.agent.ui.porcelain.inkItem
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.agents.AgentPair
import app.umbra.agent.agents.AgentProfile
import app.umbra.agent.agents.Templates
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.pc
import app.umbra.core.orchestrator.ChatItem
import app.umbra.core.orchestrator.ConversationState

/** A template's name as the user reads it. */
@Composable
fun templateName(template: String): String = stringResource(
    when (template) {
        Templates.ASSISTANT -> R.string.ag_tpl_assistant
        Templates.INBOX -> R.string.ag_tpl_inbox
        Templates.WORK -> R.string.ag_tpl_work
        else -> R.string.ag_tpl_blank
    },
)

/**
 * Home's Agents tab (Figma 10.1): one row per agent like a Telegram chat list (face, name and role, last line, time,
 * unread count, next wake), then the conversations agents had with each other.
 */
internal fun LazyListScope.agentRows(profiles: List<AgentProfile>, pairs: List<AgentPair>, today: AgentsToday, onOpen: (String) -> Unit, onNew: () -> Unit, onProactive: (String, Int) -> Unit, onOpenSession: (String) -> Unit = {}) {
    if (profiles.isEmpty()) {
        item(key = "agents-empty") { NoAgents(onNew) }
        return
    }
    if (today.brief != null || today.suggestions.isNotEmpty()) item(key = "agents-today") { androidx.compose.foundation.layout.Box(inkItem()) { AgentsTodayDeck(today, onProactive, onOpenSession) } }
    items(profiles, key = { "agent-" + it.id }) { p -> androidx.compose.foundation.layout.Box(inkItem()) { AgentRow(p, today.goals[p.id], today.pending[p.id] ?: 0, { onOpen(p.id) }, { tab -> onProactive(p.id, tab) }) } }
    val known = profiles.associateBy { it.id }
    val shown = pairs.filter { it.a in known && it.b in known }.take(5)
    items(shown, key = { "pair-" + it.a + it.b }) { pair -> androidx.compose.foundation.layout.Box(inkItem()) { PairRow(pair, known.getValue(pair.a), known.getValue(pair.b)) { onOpen(pair.fromId) } } }
}

@Composable
private fun NoAgents(onNew: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 32.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
            listOf("a1", "a6", "a11").forEach { AgentAvatar(it, "", 52.dp) }
        }
        Text(stringResource(R.string.ag_empty_title), fontFamily = P.serif, fontSize = 24.sp, color = P.ink, textAlign = TextAlign.Center, style = androidx.compose.ui.text.TextStyle(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading))
        Text(stringResource(R.string.ag_empty_body), fontSize = 14.sp, lineHeight = 21.sp, color = P.sub, textAlign = TextAlign.Center)
        PButton(stringResource(R.string.ag_new), onNew, icon = PIcons.plus)
    }
}

@Composable
private fun AgentRow(p: AgentProfile, goal: app.umbra.agent.agents.proactive.AgentGoal?, suggestions: Int, onClick: () -> Unit, onProactive: (Int) -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val conversation by produceState(ConversationState(), p.sessionId) {
        val sid = p.sessionId ?: return@produceState
        val flow = runCatching { container.supervisor.conversation(sid) }.getOrNull() ?: return@produceState
        flow.collect { value = it }
    }
    val (last, unread) = lastLine(conversation, p.lastReadMs)
    val lastAt = conversation.items.lastOrNull { it is ChatItem.Assistant || it is ChatItem.User }?.timestamp
    val status = agentStatus(p, conversation)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AgentAvatar(p.avatar, p.name, 52.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name + " · " + templateName(p.template), Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (lastAt != null) Text(shortTime(context, lastAt), fontSize = 12.sp, color = P.muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(last.ifEmpty { stringResource(R.string.ag_row_quiet) }, Modifier.weight(1f), fontSize = 14.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (unread > 0) Box(Modifier.padding(start = 8.dp).sizeIn(minWidth = 20.dp, minHeight = 20.dp).clip(CircleShape).background(P.accent2).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    Text("$unread", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = P.base)
                }
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PIcon(if (p.running) PIcons.clock else PIcons.pause, size = 12.dp, tint = P.muted)
                if (status.working) app.umbra.agent.ui.porcelain.ThinkingOrb(status.label, state = status.orb, paused = status.paused)
                Text(status.label, fontSize = 12.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            goal?.let { Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onProactive(0) }) { AgentGoalLine(it) } }
            // The agent's Briefing: its brief, the suggestions waiting (counted here) and its goals.
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PButton(stringResource(R.string.briefing_title) + if (suggestions > 0) " · $suggestions" else "", { onProactive(2) }, tiny = true,
                    style = if (suggestions > 0) app.umbra.agent.ui.porcelain.ButtonStyle.Primary else app.umbra.agent.ui.porcelain.ButtonStyle.Ghost, icon = PIcons.clock)
            }
        }
    }
}

@Composable
private fun PairRow(pair: AgentPair, a: AgentProfile, b: AgentProfile, onClick: () -> Unit) {
    val context = LocalContext.current
    val from = if (pair.fromId == a.id) a else b
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(pc(0xFFDDE5D9)), contentAlignment = Alignment.Center) { PIcon(PIcons.swap, size = 22.dp, tint = P.accent2) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${a.name} ↔ ${b.name}", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortTime(context, pair.atMs), fontSize = 12.sp, color = P.muted)
            }
            Text("${from.name}: ${pair.text}", fontSize = 14.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PIcon(PIcons.message, size = 12.dp, tint = P.muted)
                Text(stringResource(R.string.ag_pair_sub), fontSize = 12.sp, color = P.muted, maxLines = 1)
            }
        }
    }
}

/** 14:02 today, "Yesterday", or the date. */
internal fun shortTime(context: android.content.Context, at: Long): String = when {
    DateUtils.isToday(at) -> DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME)
    DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.r_yesterday)
    else -> DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
}
