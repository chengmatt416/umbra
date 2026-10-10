package app.umbra.agent.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PObject
import app.umbra.agent.ui.porcelain.PSetting
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.SerifTitle
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.core.control.LeaseState
import app.umbra.core.control.Principal
import app.umbra.core.orchestrator.terminal.TerminalState
import app.umbra.core.orchestrator.terminal.TerminalStatus
import app.umbra.runtime.linux.tmux.ScreenLine
import kotlinx.coroutines.launch

fun statusLabel(s: TerminalState): Pair<String, Tone> = when (s.status) {
    TerminalStatus.ATTACHED -> "Attached" to Tone.Good
    TerminalStatus.DETACHED -> "Detached · not verified" to Tone.Warn
    TerminalStatus.PANE_EXITED -> "Exited" + (s.exitStatus?.let { " ($it)" } ?: "") to Tone.Neutral
    TerminalStatus.SERVER_LOST -> "tmux server lost" to Tone.Danger
    TerminalStatus.HISTORY_GAP -> "History gap" to Tone.Warn
}

/**
 * Terminals (architecture spec §6.1): every terminal with its verified status, who holds its input, and the
 * controls for taking, handing over and returning it. [sessionId] limits the list to one session's terminals.
 */
@Composable
fun TerminalsScreen(sessionId: String? = null, cwd: String? = null, embedded: Boolean = false) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val all by container.terminals.terminals.collectAsStateWithLifecycle()
    val leases by container.inputLeases.all.collectAsStateWithLifecycle()
    val list = all.filter { sessionId == null || it.record.sessionId == sessionId }
    var open by remember(sessionId) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { container.terminals.reconcile() } }

    val focused = list.firstOrNull { it.record.id == open } ?: list.firstOrNull { it.status == TerminalStatus.ATTACHED }?.takeIf { embedded && open == null }
    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(horizontal = if (embedded) 0.dp else 18.dp),
    ) {
        if (!embedded) {
            Eyebrow("Runtime · tmux")
            SerifTitle("Terminals", Modifier.padding(top = 6.dp, bottom = 6.dp), size = 26)
        }
        error?.let { Callout(it, Tone.Danger); Gap(6.dp) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PButton("New terminal", {
                busy = true
                scope.launch {
                    try {
                        val dir = cwd ?: sessionId?.let { container.workspaces.sessionDirs(it) }?.let { container.workspaceLayout.toGuest(it.files) } ?: "/home/agent"
                        val t = container.terminals.open(if (sessionId != null) "shell" else "Linux shell", dir, group = "user", sessionId = sessionId)
                        open = t.id
                        error = null
                    } catch (e: Exception) {
                        error = "Could not open a terminal: ${e.message}"
                    } finally { busy = false }
                }
            }, tiny = true, icon = PIcons.plus, enabled = !busy)
            list.forEach { t ->
                val (label, tone) = statusLabel(t)
                PButton(t.record.title.take(18), { open = t.record.id }, style = if (t.record.id == focused?.record?.id) ButtonStyle.Primary else ButtonStyle.Alt, tiny = true)
                if (t.record.id == focused?.record?.id) Tag(label, tone)
            }
        }
        Gap(8.dp)
        if (focused == null) {
            if (list.isEmpty()) Subtle(if (sessionId != null) "No terminals for this session yet. A terminal is a tmux pane in the Linux environment: it keeps running when you leave this screen." else "No terminals yet.")
            else list.forEach { t ->
                val (label, tone) = statusLabel(t)
                PObject(t.record.title, "${t.record.group} · ${t.record.cwd}", PIcons.terminal, onClick = { open = t.record.id }, trailing = { Tag(label, tone) })
                Gap(6.dp)
            }
        } else {
            val t = focused
            val lease = leases.values.firstOrNull { it.resourceId == t.record.resourceId }
            val mine = lease?.owner == Principal.User && (lease.state == LeaseState.ACTIVE || lease.state == LeaseState.HANDOFF_PENDING)
            var details by remember(t.record.id) { mutableStateOf(false) }
            OwnershipBar(t, lease, onError = { error = it }, onDetails = { details = true })
            Gap(6.dp)
            TerminalPane(container.terminals, t.record.id, ownsInput = mine && t.status == TerminalStatus.ATTACHED, modifier = Modifier.weight(1f), onFenced = { error = "Not sent: $it" })
            if (details) TerminalDetailsSheet(t, onClose = { details = false })
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TerminalDetailsSheet(t: TerminalState, onClose: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose, containerColor = androidx.compose.ui.graphics.Color(0xFFEEF2EA)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Eyebrow("Terminal details")
            SerifTitle(t.record.title, Modifier.padding(top = 6.dp, bottom = 8.dp), size = 22)
            TerminalExtras(t)
        }
    }
}

@Composable
private fun OwnershipBar(t: TerminalState, lease: app.umbra.core.control.InputLease?, onError: (String) -> Unit, onDetails: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val owner = lease?.owner
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Kicker("Input")
        Tag(
            when {
                lease == null || owner == null -> "nobody"
                lease.state == LeaseState.UNCERTAIN -> "uncertain"
                lease.state == LeaseState.HANDOFF_PENDING -> "$owner · handover asked"
                else -> owner.toString()
            } + (lease?.let { " · gen ${it.generation}" } ?: ""),
            if (owner == Principal.User) Tone.Good else if (lease?.state == LeaseState.UNCERTAIN) Tone.Danger else Tone.Warn,
        )
        if (owner != Principal.User && t.status == TerminalStatus.ATTACHED) {
            PButton("Take control", { container.terminals.takeControl(t.record.id) }, tiny = true)
            if (owner != null && lease.state == LeaseState.ACTIVE) PButton("Ask to hand over", { container.terminals.requestHandoff(t.record.id) }, style = ButtonStyle.Alt, tiny = true)
        }
        if (owner == Principal.User && t.record.sessionId != null && t.record.purpose != "omp-tui") PButton("Return to agent", { container.terminals.returnControl(t.record.id) }, style = ButtonStyle.Alt, tiny = true)
        PButton("Details", onDetails, style = ButtonStyle.Ghost, tiny = true)
        PButton("Close", { scope.launch { runCatching { container.terminals.close(t.record.id) }.onFailure { onError(it.message ?: "close failed") } } }, style = ButtonStyle.Ghost, tiny = true)
    }
    if (owner != null && owner != Principal.User) Subtle("Read-only: $owner holds input. Taking control stops new input from the agent; it does not stop a command that is already running.", Modifier.padding(top = 4.dp))
}

/** Size authority, history and the saved log of one terminal. */
@Composable
private fun TerminalExtras(t: TerminalState) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var history by remember(t.record.id) { mutableStateOf<List<ScreenLine>?>(null) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        PCard(style = CardStyle.Soft) {
            PSetting("Size", "The view that holds input sets the size; without one the pane keeps a stable ${t.cols}×${t.rows}.") {
                Segmented(listOf("owner" to "Owner", "fixed" to "Fixed"), t.record.sizeMode, { container.terminals.setSizeMode(t.record.id, it) })
            }
            PSetting("Where", "group ${t.record.group} · ${t.record.windowId} ${t.record.paneId} · epoch ${t.record.serverEpoch.substringBefore(':')}", divider = false) {}
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PButton(if (history == null) "History" else "Hide history", {
                    if (history != null) history = null else scope.launch { history = runCatching { container.terminals.scrollback(t.record.id) }.getOrDefault(emptyList()) }
                }, style = ButtonStyle.Alt, tiny = true)
            }
            history?.let { Gap(6.dp); ScrollbackView(it) }
            Gap(8.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search the saved log", fontSize = 13.sp) })
                Box(Modifier.padding(start = 6.dp)) {
                    PButton("Search", { scope.launch { found = container.terminals.searchLog(t.record.id, query) } }, style = ButtonStyle.Alt, tiny = true, enabled = query.isNotBlank())
                }
            }
            found.take(50).forEach { Text(it, fontFamily = P.mono, fontSize = 11.sp, color = P.sub, maxLines = 2) }
        }
    }
}
