package app.umbra.agent.ui.runtime

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.AppContainer
import app.umbra.agent.container
import app.umbra.agent.data.GuestManager
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.FactGrid
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.Headline
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PObject
import app.umbra.agent.ui.porcelain.PSetting
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.orchestrator.terminal.TerminalStatus
import app.umbra.runtime.linux.LinuxMode
import kotlinx.coroutines.delay

/** A runner's state (spec §14): only what a probe just confirmed. Detached is not dead. */
enum class RunnerState(val label: String, val tone: Tone) {
    CONNECTED("Connected", Tone.Good),
    DETACHED("Detached", Tone.Warn),
    RECOVERING("Recovering", Tone.Warn),
    UNAVAILABLE("Unavailable", Tone.Neutral),
}

data class RunnerRow(val name: String, val detail: String, val state: RunnerState, val icon: app.umbra.agent.ui.porcelain.IconSpec)

/** Asks every runner the app depends on how it is, now. */
suspend fun probeRunners(c: AppContainer): List<RunnerRow> {
    val rows = ArrayList<RunnerRow>()
    val a11y = runCatching { c.accessibility.isAvailable() }.getOrDefault(false)
    rows += RunnerRow("Android control", if (a11y) "Accessibility service connected" else "Accessibility service not connected", if (a11y) RunnerState.CONNECTED else RunnerState.UNAVAILABLE, PIcons.phone)
    val helper = runCatching { c.helper.client.isUp() }.getOrDefault(false)
    rows += RunnerRow("Privileged helper", if (helper) "Shell-level helper answering" else "Not running", if (helper) RunnerState.CONNECTED else RunnerState.UNAVAILABLE, PIcons.key)
    val rootHelper = runCatching { c.helper.rootClient.isUp() }.getOrDefault(false)
    rows += RunnerRow("Root helper", if (rootHelper) "Root helper answering" else "Not running", if (rootHelper) RunnerState.CONNECTED else RunnerState.UNAVAILABLE, PIcons.shield)
    val g = c.guest.state.value
    val mode = if (c.guest.mode == LinuxMode.PROOT) "proot, no root" else "chroot"
    rows += when (g) {
        GuestManager.State.Ready -> RunnerRow("Linux environment", "Debian, $mode, mounted", RunnerState.CONNECTED, PIcons.terminal)
        GuestManager.State.Installed -> RunnerRow("Linux environment", "Installed, not mounted yet", RunnerState.DETACHED, PIcons.terminal)
        is GuestManager.State.Installing, is GuestManager.State.Downloading -> RunnerRow("Linux environment", "Installing", RunnerState.RECOVERING, PIcons.terminal)
        else -> RunnerRow("Linux environment", g.toString(), RunnerState.UNAVAILABLE, PIcons.terminal)
    }
    val terms = c.terminals.terminals.value
    terms.groupBy { it.record.group }.forEach { (group, list) ->
        val attached = list.count { it.status == TerminalStatus.ATTACHED || it.status == TerminalStatus.PANE_EXITED }
        val lost = list.count { it.status == TerminalStatus.SERVER_LOST }
        rows += RunnerRow(
            "tmux group “$group”", "${list.size} terminal(s): $attached reachable, $lost lost · its own server and socket, not a sandbox",
            when { lost == list.size -> RunnerState.UNAVAILABLE; attached > 0 -> RunnerState.CONNECTED; else -> RunnerState.DETACHED }, PIcons.terminal,
        )
    }
    val gui = runCatching { c.linuxGui.isAvailable() }.getOrDefault(false)
    rows += RunnerRow("Linux desktop", if (gui) "X display answering" else "Not running (the desktop add-on starts it when needed)", if (gui) RunnerState.CONNECTED else RunnerState.UNAVAILABLE, PIcons.monitor)
    val agents = c.sessionStore.list().mapNotNull { s -> c.supervisor.liveSession(s.id)?.takeIf { it.isProcessAlive }?.let { s.title } }
    rows += RunnerRow("Agent processes", if (agents.isEmpty()) "None running" else agents.joinToString(), if (agents.isEmpty()) RunnerState.UNAVAILABLE else RunnerState.CONNECTED, PIcons.bot)
    return rows
}

/**
 * Runners & Continuity (architecture spec §5, §8.1, §14): which runners are reachable now, how a session continues
 * after the app or the phone restarts, and what recovery does — without ever showing a UI's memory as a live runner.
 */
@Composable
fun RuntimeScreen(onOpenSession: (String) -> Unit) {
    val container = LocalContext.current.container
    var rows by remember { mutableStateOf<List<RunnerRow>?>(null) }
    var probedAt by remember { mutableStateOf(0L) }
    val contracts by container.contracts.all.collectAsStateWithLifecycle()
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val log by container.contracts.recent.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        while (true) {
            rows = runCatching { probeRunners(container) }.getOrNull()
            probedAt = System.currentTimeMillis()
            delay(10_000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow("Runtime continuity")
        Headline("A session is more", "than a process.", Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)
        PCard {
            Kicker("Live status requires evidence")
            val list = rows
            if (list == null) Subtle("Probing…", Modifier.padding(top = 6.dp))
            else list.forEach { r ->
                Gap(8.dp)
                PObject(r.name, r.detail, r.icon, trailing = { Tag(r.state.label, r.state.tone) })
            }
            Gap(8.dp)
            Subtle("Probed ${DateUtils.getRelativeTimeSpanString(probedAt)}. Detached is not dead; a screen that was rebuilt is not a runner that restarted.")
        }
        Gap(12.dp)
        PCard {
            Kicker("Session-scoped resume contracts")
            val withContract = sessions.filter { contracts.containsKey(it.id) }
            if (withContract.isEmpty()) Subtle("No session may resume by itself after a restart. Give one a contract from its menu in Current.", Modifier.padding(top = 6.dp))
            withContract.forEach { s ->
                val c = contracts.getValue(s.id)
                val why = container.contracts.eligibility(s.id)
                val until = c.expiresAtMs
                PSetting(s.title, (if (until == null) "Until the task completes" else "Until ${DateUtils.formatDateTime(container.context, until, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)}") + (if (c.allowedWork.isNotBlank()) " · ${c.allowedWork}" else "")) {
                    Tag(if (why == null) "Will resume" else "Will not resume", if (why == null) Tone.Good else Tone.Neutral)
                }
                PButton("Open", { onOpenSession(s.id) }, style = ButtonStyle.Ghost, tiny = true)
            }
            Gap(8.dp)
            Callout("A contract grants nothing new: permissions, the screen, root and credentials are checked again, and a lockdown still holds every session.", Tone.Neutral)
            log.takeLast(6).reversed().forEach { e ->
                Text("${DateUtils.formatDateTime(container.context, e.timeMs, DateUtils.FORMAT_SHOW_TIME)} ${e.kind} ${e.data?.toString()?.take(100).orEmpty()}", Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
            }
        }
        Gap(12.dp)
        PCard(style = CardStyle.Soft) {
            Kicker("Recovery engine")
            Gap(8.dp)
            FactGrid(
                listOf(
                    "Reattach" to "Terminals are found again in the same tmux server epoch; a lost server is reported, not replaced",
                    "Resume" to "A session continues from omp's saved conversation, with a note to look before acting",
                    "Reconcile" to "Leases from a restarted runner become uncertain until taken again",
                    "Selective" to "Only sessions with a contract resume by themselves; the rest wait for you",
                ),
            )
        }
        Gap(28.dp)
    }
}
