package app.umbra.agent.ui.current

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.capsule.TaskCompanion
import app.umbra.agent.container
import app.umbra.agent.ui.displays.rememberCapture
import app.umbra.agent.ui.porcelain.AlertDialog
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.pc
import app.umbra.core.control.LeaseState
import app.umbra.core.control.Principal
import app.umbra.core.model.action.Environment
import app.umbra.core.orchestrator.Block
import app.umbra.core.orchestrator.ChatItem
import app.umbra.core.orchestrator.ConversationState
import app.umbra.core.session.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A screen the task is driving: a background Android display, or the Linux desktop. */
sealed interface LiveTarget {
    val resource: String
    data class Android(val displayId: Int) : LiveTarget { override val resource get() = "display:$displayId" }
    data object Linux : LiveTarget { override val resource get() = "gui:0" }
}

/**
 * Which screen the task works on right now: the Linux desktop while its last tool was a desktop one (`gui_*`), a
 * background display while it holds that display's lease. The phone's own screen is not shown: you are on it.
 */
@Composable
fun rememberLiveTarget(session: Session, conversation: ConversationState): LiveTarget? {
    val container = LocalContext.current.container
    val lastTool = (conversation.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.blocks?.filterIsInstance<Block.ToolCall>()?.lastOrNull()?.name
    val target by produceState<LiveTarget?>(null, session.id, conversation.working, lastTool) {
        while (true) {
            value = when {
                !conversation.working -> null
                lastTool?.startsWith("gui_") == true -> LiveTarget.Linux
                else -> container.leases.leases().firstOrNull { it.sessionId == session.id && it.displayId != 0 }?.let { LiveTarget.Android(it.displayId) }
            }
            if (!conversation.working) break
            delay(1_500)
        }
    }
    return target
}

/** The screen as the task works on it, in the conversation: live, a tap enlarges it, Take over stops the agent's input. */
@Composable
fun LiveDisplayCard(session: Session, target: LiveTarget, onExpand: () -> Unit) {
    val container = LocalContext.current.container
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(target) { while (true) { delay(1_000); tick++ } }
    val image = rememberCapture(container, if (target is LiveTarget.Linux) Environment.LINUX_GUI else Environment.ANDROID, (target as? LiveTarget.Android)?.displayId, tick)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(pc(0xFFF6F8F2)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LiveHeader(session, target)
        image?.let {
            Image(
                it, stringResource(R.string.r_screens_title),
                Modifier.fillMaxWidth().heightIn(max = 260.dp).aspectRatio(it.width.toFloat() / it.height.coerceAtLeast(1)).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onExpand),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun LiveHeader(session: Session, target: LiveTarget) {
    val container = LocalContext.current.container
    val leases by container.inputLeases.all.collectAsStateWithLifecycle()
    val userHolds = leases.values.any { it.resourceId == target.resource && it.state == LeaseState.ACTIVE && it.owner == Principal.User }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TaskCompanion(session, false, size = 18.dp)
        Text(
            stringResource(R.string.r_task_live, stringResource(if (target is LiveTarget.Linux) R.string.r_task_live_linux else R.string.r_task_live_phone)),
            Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = P.ink,
        )
        if (userHolds) PButton(stringResource(R.string.r_task_give_back), { container.returnControl(target.resource, session.id) }, style = ButtonStyle.Alt, tiny = true)
        else PButton(stringResource(R.string.r_task_take_over), { container.takeOver(target.resource, session.id) }, style = ButtonStyle.Alt, tiny = true, icon = PIcons.hand)
    }
}

/** The same screen, larger, over the conversation; closing returns to where it was. */
@Composable
fun LiveDisplayDialog(session: Session, target: LiveTarget, onDismiss: () -> Unit) {
    val container = LocalContext.current.container
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(target) { while (true) { delay(800); tick++ } }
    val image = rememberCapture(container, if (target is LiveTarget.Linux) Environment.LINUX_GUI else Environment.ANDROID, (target as? LiveTarget.Android)?.displayId, tick)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveHeader(session, target)
                image?.let { Image(it, stringResource(R.string.r_screens_title), Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height.coerceAtLeast(1)).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit) }
            }
        },
        confirmButton = { PButton(stringResource(R.string.action_close), onDismiss, style = ButtonStyle.Ghost) },
        dismissButton = { PButton(stringResource(R.string.cap_pause), { scope.launch { container.supervisor.pause(session.id) } }, style = ButtonStyle.Alt, icon = PIcons.pause) },
    )
}

/** The task folder's changes (Power and Dev): files with lines added and removed, and the way to commit them. */
@Composable
fun ChangesView(session: Session, onCommit: () -> Unit) {
    val container = LocalContext.current.container
    var tick by remember { mutableIntStateOf(0) }
    val dir = session.workspaceId?.let { container.workspaceLayout.guestProjectDir(it) } ?: "${container.workspaceLayout.guestSessionDir(session.id)}/files"
    val result by produceState<Pair<Boolean, List<Triple<String, String, String>>>?>(null, dir, tick) {
        value = runCatching {
            val rt = container.guest.runtime
            val inside = rt.exec(listOf("git", "-C", dir, "rev-parse", "--is-inside-work-tree"), timeoutMs = 20_000).ok
            if (!inside) return@runCatching false to emptyList()
            val numstat = rt.exec(listOf("git", "-C", dir, "diff", "--numstat", "HEAD"), timeoutMs = 30_000).stdout
            val untracked = rt.exec(listOf("git", "-C", dir, "ls-files", "--others", "--exclude-standard"), timeoutMs = 30_000).stdout
            val changed = numstat.lines().mapNotNull { l -> l.split('\t').takeIf { it.size >= 3 }?.let { Triple(it[2], it[0], it[1]) } } +
                untracked.lines().filter { it.isNotBlank() }.map { Triple(it, "new", "") }
            true to changed
        }.getOrNull()
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val r = result
        when {
            r == null -> Unit
            !r.first -> Text(stringResource(R.string.r_task_no_repo), fontSize = 14.sp, color = P.sub)
            r.second.isEmpty() -> Text(stringResource(R.string.r_task_no_changes), fontSize = 14.sp, color = P.sub)
            else -> {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(pc(0xFFF6F8F2)).padding(vertical = 6.dp)) {
                    r.second.forEach { (path, added, removed) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(path, Modifier.weight(1f), fontSize = 12.5.sp, fontFamily = P.mono, color = P.ink, maxLines = 1)
                            Text(if (added == "new") "new" else "+$added", fontSize = 12.sp, fontFamily = P.mono, color = P.good)
                            if (removed.isNotEmpty()) Text("−$removed", fontSize = 12.sp, fontFamily = P.mono, color = P.danger)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton(stringResource(R.string.r_model_refresh), { tick++ }, Modifier.weight(1f), style = ButtonStyle.Ghost)
                    PButton(stringResource(R.string.dest_commit), onCommit, Modifier.weight(1f), icon = PIcons.git)
                }
            }
        }
    }
}
