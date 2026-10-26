package app.umbra.agent.ui.current

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import app.umbra.agent.container
import app.umbra.agent.ui.models.sessionEffortLabel
import app.umbra.agent.ui.porcelain.ModalBottomSheet
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.orchestrator.Block
import app.umbra.core.orchestrator.ChatItem
import app.umbra.core.orchestrator.SessionBus
import app.umbra.core.orchestrator.ToolRun
import app.umbra.core.session.Session
import app.umbra.core.session.SessionKind
import app.umbra.core.session.SessionState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ---- messages between sessions in a conversation ----

/** A message another session sent (a user turn with a bus header): shown as theirs, not the user's. False: not one. */
@Composable
fun SessionMessageCard(item: ChatItem.User): Boolean {
    val m = SessionBus.HEADER.find(item.text) ?: return false
    val who = m.groupValues[4]
    val kind = m.groupValues[1]
    val title = when {
        kind.startsWith("Reply") -> stringResource(R.string.ses_reply_from, who)
        kind.startsWith("Report") -> stringResource(R.string.ses_report_from, who)
        kind.startsWith("Subagent") -> if ("failed" in item.text.substringBefore('\n')) stringResource(R.string.ses_subagent_failed, who) else stringResource(R.string.ses_subagent_done, who)
        else -> stringResource(R.string.ses_from, who)
    }
    val body = item.text.removeRange(m.range).lines().filterNot { it.startsWith("(If an answer is wanted") || it.startsWith("(Read more with subagent_status") }.joinToString("\n").trim()
    BusCard(title, body, outgoing = false)
    return true
}

/** A tool call that wrote to another session: shown as the message it sent. False: not such a call. */
@Composable
fun OutgoingSessionCard(call: Block.ToolCall, run: ToolRun?): Boolean {
    fun arg(k: String) = (call.arguments[k] as? JsonPrimitive)?.contentOrNull
    val (title, body) = when (call.name) {
        "session_send" -> stringResource(R.string.ses_to, arg("to") ?: "?") to (arg("message") ?: "")
        "subagent_send" -> stringResource(R.string.ses_to, "#" + (arg("name") ?: "?").removePrefix("#")) to (arg("message") ?: "")
        "session_reply" -> stringResource(R.string.ses_reply_to, arg("message_id") ?: "?") to (arg("message") ?: "")
        "subagent_start" -> stringResource(R.string.ses_started, "#" + (arg("name") ?: "?"), listOfNotNull(arg("model"), arg("effort")?.let { "effort $it" }).joinToString(" · ").ifEmpty { "auto" }) to (arg("task") ?: "")
        "wait_until" -> {
            val target = arg("target") ?: arg("pid") ?: arg("pattern") ?: arg("command") ?: "?"
            val seconds = arg("timeout_s")?.toIntOrNull() ?: 0
            val duration = if (seconds >= 60 && seconds % 60 == 0) stringResource(R.string.ses_wait_minutes, seconds / 60) else stringResource(R.string.ses_wait_seconds, seconds)
            stringResource(R.string.ses_wait_until, target, duration) to ""
        }
        else -> return false
    }
    val failed = run?.isError == true
    BusCard(title, body + (if (failed) "\n\n" + (run?.result?.take(300) ?: "") else ""), outgoing = true, failed = failed)
    return true
}

@Composable
private fun BusCard(title: String, body: String, outgoing: Boolean, failed: Boolean = false) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(start = if (outgoing) 32.dp else 0.dp, end = if (outgoing) 0.dp else 32.dp)
            .clip(shape).background(if (outgoing) P.accent3.copy(alpha = 0.6f) else pc(0xFFF6F8F2)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PIcon(PIcons.swap, size = 14.dp, tint = if (failed) P.danger else P.accent2)
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (failed) P.danger else P.accent2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (body.isNotBlank()) Text(body.take(4_000), fontSize = 14.sp, lineHeight = 20.sp, color = P.ink)
    }
}

// ---- subagents of a session ----

/** The subagents a session started, side by side above its conversation; a tap opens one. */
@Composable
fun SubagentStrip(parent: Session, onOpenSession: (String) -> Unit) {
    val container = LocalContext.current.container
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val children = sessions.filter { it.spawnedBy == parent.id }.sortedBy { it.createdAtMs }
    if (children.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PIcon(PIcons.fork, size = 14.dp, tint = P.sub)
            Text(stringResource(R.string.ses_subagents, children.size), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = P.sub)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            children.forEach { c -> SubagentCard(c) { onOpenSession(c.id) } }
        }
    }
}

@Composable
private fun SubagentCard(s: Session, onOpen: () -> Unit) {
    val container = LocalContext.current.container
    val shape = RoundedCornerShape(16.dp)
    val done = s.plan.count { it.status == "done" }
    val step = s.plan.firstOrNull { it.status == "active" }?.text
    val needs = s.state == SessionState.WAITING_APPROVAL
    Column(
        Modifier.width(176.dp).clip(shape).background(pc(0xFFF6F8F2)).clickable(role = Role.Button, onClick = onOpen).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            app.umbra.agent.capsule.TaskCompanion(s, needs)
            Text(container.sessionBus.address(s), fontSize = 13.sp, fontFamily = P.mono, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            listOfNotNull(s.model?.modelId, s.effort?.let { sessionEffortLabel(it) }).joinToString(" · ").ifEmpty { stringResource(R.string.r_model_auto) },
            fontSize = 11.5.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                s.queuedAtMs != null && s.state == SessionState.CREATED -> stringResource(R.string.ses_queued)
                step != null -> step
                else -> app.umbra.agent.ui.sessions.stateText(s)
            },
            fontSize = 12.sp, color = if (needs) P.amber else P.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (s.plan.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(P.base2)) {
                Box(Modifier.fillMaxWidth(done.toFloat() / s.plan.size).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (needs) P.amber else P.accent2))
            }
        }
    }
}

/** On a subagent's page: whose subagent it is, a tap away. */
@Composable
fun ParentLink(session: Session, onOpenSession: (String) -> Unit) {
    val parentId = session.spawnedBy ?: return
    val container = LocalContext.current.container
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val parent = sessions.firstOrNull { it.id == parentId } ?: return
    Row(
        Modifier.padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(P.base2).clickable(role = Role.Button) { onOpenSession(parent.id) }.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PIcon(PIcons.fork, size = 13.dp, tint = P.sub)
        Text(stringResource(R.string.ses_subagent_of, container.sessionBus.address(parent)), fontSize = 12.sp, color = P.sub)
    }
}

// ---- every session and agent, as other sessions see them ----

/** The sessions and agents on this phone by handle, what each is doing; a tap opens one. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SessionsDirectorySheet(current: String?, onOpenSession: (String) -> Unit, onDismiss: () -> Unit) {
    val container = LocalContext.current.container
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val shown = sessions.filter { !it.state.isTerminal || it.kind == SessionKind.AGENT }.ifEmpty { sessions.take(10) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pc(0xFFFBFDF7)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ses_directory), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = P.ink)
                Text(stringResource(R.string.ses_directory_count, shown.size), fontSize = 12.sp, color = P.muted)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(pc(0xFFF6F8F2))) {
                shown.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onDismiss(); onOpenSession(s.id) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        app.umbra.agent.capsule.TaskCompanion(s, s.state == SessionState.WAITING_APPROVAL)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(container.sessionBus.address(s), fontSize = 14.sp, fontFamily = P.mono, color = P.ink, maxLines = 1)
                                Text(
                                    when {
                                        s.id == current -> stringResource(R.string.ses_kind_here)
                                        s.kind == SessionKind.AGENT -> stringResource(R.string.ses_kind_agent)
                                        s.kind == SessionKind.SUBAGENT -> stringResource(R.string.ses_kind_subagent)
                                        else -> stringResource(R.string.ses_kind_coding)
                                    },
                                    fontSize = 11.sp, color = P.muted,
                                )
                            }
                            Text(s.plan.firstOrNull { it.status == "active" }?.text ?: s.title, fontSize = 12.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        PIcon(PIcons.swap, size = 18.dp, tint = P.sub)
                    }
                }
            }
        }
    }
}
