package app.umbra.agent.capsule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.agents.AgentProfile
import app.umbra.agent.agents.AgentRuntime
import app.umbra.agent.ui.agents.AgentAvatar
import app.umbra.agent.ui.porcelain.IconSpec
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.core.orchestrator.ChatItem
import app.umbra.core.orchestrator.ConversationState
import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.safety.ApprovalRequest
import app.umbra.core.safety.approveGesture
import app.umbra.core.safety.ApprovalSurface
import app.umbra.core.safety.ApproveGesture

/**
 * The default agent in the Capsule's card (Figma 11.6): its face and state, the last thing it said, and what it asks
 * the user to approve, answered right here. The field below talks to it.
 */
@Composable
internal fun AgentBlock(capsule: Capsule, container: AppContainer, agent: AgentProfile?, approvals: List<ApprovalRequest>) {
    if (agent == null) {
        Text(stringResource(R.string.ag_capsule_first), fontSize = 13.5.sp, lineHeight = 19.sp, color = Small)
        return
    }
    val conversation by produceState(ConversationState(), agent.sessionId) {
        val sid = agent.sessionId ?: return@produceState
        val flow = runCatching { container.supervisor.conversation(sid) }.getOrNull() ?: return@produceState
        flow.collect { value = it }
    }
    val activity by container.agents.activity.collectAsState()
    val last = conversation.items.lastOrNull { it is ChatItem.Assistant && it.text.isNotBlank() && !it.text.trim().startsWith(AgentRuntime.QUIET) }?.let { (it as ChatItem.Assistant).text.trim() }
    val working = conversation.session?.isStreaming == true || activity[agent.id] != null
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AgentAvatar(agent.avatar, agent.name, 34.dp)
            Column(Modifier.weight(1f)) {
                Text(agent.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Label, maxLines = 1)
                Text(
                    stringResource(R.string.ag_capsule_default) + " · " + stringResource(if (working) R.string.ag_status_working else if (agent.running) R.string.ag_state_on else R.string.ag_state_off),
                    fontSize = 12.sp, color = Small, maxLines = 1,
                )
            }
            RoundButton(PIcons.external, stringResource(R.string.cap_open), size = 40.dp) { capsule.open("agent/${agent.id}", null) }
        }
        if (last != null) Text(
            last, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Line.copy(alpha = 0.7f)).clickable { capsule.open("agent/${agent.id}", null) }.padding(horizontal = 12.dp, vertical = 9.dp),
            fontSize = 14.sp, lineHeight = 20.sp, color = RowText, maxLines = 5, overflow = TextOverflow.Ellipsis,
        )
        approvals.firstOrNull { it.sessionId == agent.sessionId }?.let { request ->
            Text(request.description, fontSize = 14.sp, color = Attention, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (approveGesture(request, ApprovalSurface.CAPSULE) == ApproveGesture.ONE_TAP) {
                    DarkButton(stringResource(R.string.appr_approve_once), null, primary = true) { container.approvals.resolve(request.id, ApprovalDecision.Approved()) }
                } else {
                    DarkButton(stringResource(R.string.appr_review_inbox), null, primary = true) { capsule.open(null, request.sessionId) }
                }
                DarkButton(stringResource(R.string.ag_edit), null) {
                    container.approvals.resolve(request.id, ApprovalDecision.Denied("the user wants to change it; their words follow"))
                    container.ui.saveDraft(capsule.context.getString(R.string.ag_edit_prefix) + " ")
                    capsule.focusCard(true)
                }
                DarkButton(stringResource(R.string.ag_dont), null) { container.approvals.resolve(request.id, ApprovalDecision.Denied("not now, from the Capsule")) }
            }
        }
    }
}

/** Under the field: select on the screen, switch the default agent, or make the words a coding session instead. */
@Composable
internal fun AgentChips(capsule: Capsule, container: AppContainer) {
    val draft by container.ui.draft.collectAsState()
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(PIcons.target, stringResource(R.string.ag_chip_select)) { capsule.startSelection() }
        if (container.agents.profiles().count { it.running } > 1) Chip(PIcons.swap, stringResource(R.string.ag_chip_switch)) { container.agents.cycleDefault() }
        Chip(PIcons.terminal, stringResource(R.string.ag_chip_coding)) {
            if (draft.isNotBlank()) capsule.send(draft.trim(), container.ui.homeProject.value, target = null)
            else capsule.open("new-coding", null)
        }
    }
}

@Composable
private fun Chip(icon: IconSpec, text: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Line).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp).heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PIcon(icon, size = 14.dp, tint = Small)
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Label, maxLines = 1)
    }
}
