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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.ui.porcelain.PEmpty
import androidx.compose.foundation.layout.widthIn
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

@Composable
fun statusLabel(s: TerminalState): Pair<String, Tone> = when (s.status) {
    TerminalStatus.ATTACHED -> stringResource(R.string.term_attached) to Tone.Good
    TerminalStatus.DETACHED -> stringResource(R.string.term_detached) to Tone.Warn
    TerminalStatus.PANE_EXITED -> stringResource(R.string.term_exited) + (s.exitStatus?.let { " ($it)" } ?: "") to Tone.Neutral
    TerminalStatus.SERVER_LOST -> stringResource(R.string.term_server_lost) to Tone.Danger
    TerminalStatus.HISTORY_GAP -> stringResource(R.string.term_history_gap) to Tone.Warn
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
    // While the keyboard is up the terminal gets the room: the title, the terminal chips and the notes fold away.
    @OptIn(ExperimentalLayoutApi::class)
    val typing = WindowInsets.isImeVisible && focused != null
    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(horizontal = if (embedded) 0.dp else 18.dp),
    ) {
        val context = LocalContext.current
        val resources = androidx.compose.ui.platform.LocalResources.current
        val newTerminal: () -> Unit = {
            busy = true
            scope.launch {
                try {
                    val dir = cwd ?: sessionId?.let { container.workspaces.sessionDirs(it) }?.let { container.workspaceLayout.toGuest(it.files) } ?: "/home/agent"
                    val t = container.terminals.open(if (sessionId != null) "shell" else "Linux shell", dir, group = "user", sessionId = sessionId)
                    open = t.id
                    error = null
                } catch (e: Exception) {
                    error = resources.getString(R.string.term_open_failed, e.message ?: "")
                } finally { busy = false }
            }
        }
        if (list.isEmpty() && !embedded) {
            PEmpty(
                stringResource(R.string.term_empty_title), stringResource(R.string.term_empty_body), icon = PIcons.terminal,
                action = { PButton(stringResource(R.string.term_new), newTerminal, icon = PIcons.plus, enabled = !busy) },
            )
            return@Column
        }
        if (!embedded && !typing) {
            Eyebrow(stringResource(R.string.term_eyebrow))
            SerifTitle(stringResource(R.string.dest_terminals), Modifier.padding(top = 6.dp, bottom = 6.dp), size = 26)
        }
        error?.let { Callout(it, Tone.Danger); Gap(6.dp) }
        if (!typing) app.umbra.agent.ui.porcelain.FadingRow(Modifier.fillMaxWidth()) {
            PButton(stringResource(R.string.term_new), newTerminal, tiny = true, icon = PIcons.plus, enabled = !busy)
            list.forEach { t ->
                val (label, tone) = statusLabel(t)
                // The whole title, ellipsized by width rather than cut at a fixed character count (L-01).
                PButton(t.record.title, { open = t.record.id }, Modifier.widthIn(max = 168.dp), style = if (t.record.id == focused?.record?.id) ButtonStyle.Primary else ButtonStyle.Alt, tiny = true)
                if (t.record.id == focused?.record?.id) Tag(label, tone)
            }
        }
        Gap(8.dp)
        if (focused == null) {
            if (list.isEmpty()) Subtle(stringResource(R.string.term_session_empty))
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
            OwnershipBar(t, lease, onError = { error = it }, onDetails = { details = true }, compact = typing)
            Gap(6.dp)
            TerminalPane(container.terminals, t.record.id, ownsInput = mine && t.status == TerminalStatus.ATTACHED, modifier = Modifier.weight(1f), onFenced = { error = resources.getString(R.string.term_not_sent, it) })
            if (details) TerminalDetailsSheet(t, onClose = { details = false })
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TerminalDetailsSheet(t: TerminalState, onClose: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose, containerColor = androidx.compose.ui.graphics.Color(0xFFEEF2EA)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Eyebrow(stringResource(R.string.term_details_eyebrow))
            SerifTitle(t.record.title, Modifier.padding(top = 6.dp, bottom = 8.dp), size = 22)
            TerminalExtras(t)
        }
    }
}

@Composable
private fun OwnershipBar(t: TerminalState, lease: app.umbra.core.control.InputLease?, onError: (String) -> Unit, onDetails: () -> Unit, compact: Boolean = false) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val closeFailed = stringResource(R.string.term_close_failed)
    val owner = lease?.owner
    app.umbra.agent.ui.porcelain.FadingRow(Modifier.fillMaxWidth()) {
        Kicker(stringResource(R.string.term_input))
        Tag(
            when {
                lease == null || owner == null -> stringResource(R.string.term_nobody)
                lease.state == LeaseState.UNCERTAIN -> stringResource(R.string.term_uncertain)
                lease.state == LeaseState.HANDOFF_PENDING -> stringResource(R.string.term_handover_asked, owner.toString())
                owner == Principal.User -> stringResource(R.string.term_you)
                else -> owner.toString()
            } + (lease?.let { " · gen ${it.generation}" } ?: ""),
            if (owner == Principal.User) Tone.Good else if (lease?.state == LeaseState.UNCERTAIN) Tone.Danger else Tone.Warn,
        )
        // Two ways to get input, named by what happens; returning it is offered only while you hold it (X-07).
        if (owner != Principal.User && t.status == TerminalStatus.ATTACHED) {
            PButton(stringResource(R.string.term_take_over), { container.terminals.takeControl(t.record.id) }, tiny = true)
            if (owner != null && lease.state == LeaseState.ACTIVE) PButton(stringResource(R.string.term_ask_handover), { container.terminals.requestHandoff(t.record.id) }, style = ButtonStyle.Alt, tiny = true)
        }
        if (owner == Principal.User && t.record.sessionId != null && t.record.purpose != "omp-tui") PButton(stringResource(R.string.term_return), { container.terminals.returnControl(t.record.id) }, style = ButtonStyle.Alt, tiny = true)
        PButton(stringResource(R.string.term_details), onDetails, style = ButtonStyle.Ghost, tiny = true)
        PButton(stringResource(R.string.term_close), { scope.launch { runCatching { container.terminals.close(t.record.id) }.onFailure { onError(it.message ?: closeFailed) } } }, style = ButtonStyle.Ghost, tiny = true)
    }
    if (!compact) when {
        owner == Principal.User -> Subtle(stringResource(R.string.term_you_hold), Modifier.padding(top = 4.dp))
        owner != null && t.status == TerminalStatus.ATTACHED -> Subtle(stringResource(R.string.term_choice_note), Modifier.padding(top = 4.dp))
    }
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
            PSetting(stringResource(R.string.term_size), stringResource(R.string.term_size_d, t.cols, t.rows)) {
                Segmented(listOf("owner" to stringResource(R.string.term_owner), "fixed" to stringResource(R.string.term_fixed)), t.record.sizeMode, { container.terminals.setSizeMode(t.record.id, it) })
            }
            PSetting(stringResource(R.string.term_where), stringResource(R.string.term_where_line, t.record.group, t.record.windowId, t.record.paneId, t.record.serverEpoch.substringBefore(':')), divider = false) {}
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PButton(if (history == null) stringResource(R.string.term_history) else stringResource(R.string.term_hide_history), {
                    if (history != null) history = null else scope.launch { history = runCatching { container.terminals.scrollback(t.record.id) }.getOrDefault(emptyList()) }
                }, style = ButtonStyle.Alt, tiny = true)
            }
            history?.let { Gap(6.dp); ScrollbackView(it) }
            Gap(8.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(stringResource(R.string.term_search_log), fontSize = 13.sp) })
                Box(Modifier.padding(start = 6.dp)) {
                    PButton(stringResource(R.string.term_search), { scope.launch { found = container.terminals.searchLog(t.record.id, query) } }, style = ButtonStyle.Alt, tiny = true, enabled = query.isNotBlank())
                }
            }
            found.take(50).forEach { Text(it, fontFamily = P.mono, fontSize = 11.sp, color = P.sub, maxLines = 2) }
        }
    }
}
