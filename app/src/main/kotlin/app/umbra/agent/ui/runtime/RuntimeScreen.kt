package app.umbra.agent.ui.runtime

import androidx.compose.ui.res.stringResource
import app.umbra.agent.R
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
enum class RunnerState(@param:androidx.annotation.StringRes val label: Int, val tone: Tone) {
    CONNECTED(R.string.rs_connected, Tone.Good),
    DETACHED(R.string.rs_detached, Tone.Warn),
    RECOVERING(R.string.rs_recovering, Tone.Warn),
    UNAVAILABLE(R.string.rs_unavailable, Tone.Neutral),
}

data class RunnerRow(val name: String, val detail: String, val state: RunnerState, val icon: app.umbra.agent.ui.porcelain.IconSpec)

/** Asks every runner the app depends on how it is, now. */
suspend fun probeRunners(c: AppContainer): List<RunnerRow> {
    val rows = ArrayList<RunnerRow>()
    fun t(id: Int, vararg args: Any) = c.context.getString(id, *args)
    val a11y = runCatching { c.accessibility.isAvailable() }.getOrDefault(false)
    rows += RunnerRow(t(R.string.rr_android), t(if (a11y) R.string.rr_a11y_on else R.string.rr_a11y_off), if (a11y) RunnerState.CONNECTED else RunnerState.UNAVAILABLE, PIcons.phone)
    val helper = runCatching { c.helper.client.isUp() }.getOrDefault(false)
    rows += RunnerRow(t(R.string.rr_helper), t(if (helper) R.string.rr_helper_on else R.string.rr_not_running), if (helper) RunnerState.CONNECTED else RunnerState.UNAVAILABLE, PIcons.key)
    val rootHelper = runCatching { c.helper.rootClient.isUp() }.getOrDefault(false)
    rows += RunnerRow(t(R.string.rr_root_helper), t(if (rootHelper) R.string.rr_root_on else R.string.rr_not_running), if (rootHelper) RunnerState.CONNECTED else RunnerState.UNAVAILABLE, PIcons.shield)
    val g = c.guest.state.value
    val mode = t(if (c.guest.mode == LinuxMode.PROOT) R.string.rr_proot else R.string.rr_chroot)
    rows += when (g) {
        GuestManager.State.Ready -> RunnerRow(t(R.string.rr_linux), t(R.string.rr_linux_ready, mode), RunnerState.CONNECTED, PIcons.terminal)
        GuestManager.State.Installed -> RunnerRow(t(R.string.rr_linux), t(R.string.rr_linux_installed), RunnerState.DETACHED, PIcons.terminal)
        is GuestManager.State.Installing, is GuestManager.State.Downloading -> RunnerRow(t(R.string.rr_linux), t(R.string.rr_linux_installing), RunnerState.RECOVERING, PIcons.terminal)
        else -> RunnerRow(t(R.string.rr_linux), g.toString(), RunnerState.UNAVAILABLE, PIcons.terminal)
    }
    val terms = c.terminals.terminals.value
    terms.groupBy { it.record.group }.forEach { (group, list) ->
        val attached = list.count { it.status == TerminalStatus.ATTACHED || it.status == TerminalStatus.PANE_EXITED }
        val lost = list.count { it.status == TerminalStatus.SERVER_LOST }
        rows += RunnerRow(
            t(R.string.rr_tmux, group), t(R.string.rr_tmux_d, list.size, attached, lost),
            when { lost == list.size -> RunnerState.UNAVAILABLE; attached > 0 -> RunnerState.CONNECTED; else -> RunnerState.DETACHED }, PIcons.terminal,
        )
    }
    val gui = runCatching { c.linuxGui.isAvailable() }.getOrDefault(false)
    rows += RunnerRow(t(R.string.rr_desktop), t(if (gui) R.string.rr_desktop_on else R.string.rr_desktop_off), if (gui) RunnerState.CONNECTED else RunnerState.UNAVAILABLE, PIcons.monitor)
    val agents = c.sessionStore.list().mapNotNull { s -> c.supervisor.liveSession(s.id)?.takeIf { it.isProcessAlive }?.let { s.title } }
    rows += RunnerRow(t(R.string.rr_agents), if (agents.isEmpty()) t(R.string.rr_agents_none) else agents.joinToString(), if (agents.isEmpty()) RunnerState.UNAVAILABLE else RunnerState.CONNECTED, PIcons.bot)
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
        Eyebrow(stringResource(R.string.rt_eyebrow))
        Headline(stringResource(R.string.rt_headline), stringResource(R.string.rt_headline_tail), Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)
        PCard {
            Kicker(stringResource(R.string.rt_live))
            val list = rows
            if (list == null) Subtle(stringResource(R.string.rt_probing), Modifier.padding(top = 6.dp))
            else list.forEach { r ->
                Gap(8.dp)
                PObject(r.name, r.detail, r.icon, trailing = { Tag(stringResource(r.state.label), r.state.tone) })
            }
            Gap(8.dp)
            Subtle(stringResource(R.string.rt_probed, DateUtils.getRelativeTimeSpanString(probedAt).toString()))
        }
        Gap(12.dp)
        IsolationCard()
        Gap(12.dp)
        PCard {
            Kicker(stringResource(R.string.rt_contracts))
            val withContract = sessions.filter { contracts.containsKey(it.id) }
            if (withContract.isEmpty()) Subtle(stringResource(R.string.rt_no_contracts), Modifier.padding(top = 6.dp))
            withContract.forEach { s ->
                val c = contracts.getValue(s.id)
                val why = container.contracts.eligibility(s.id)
                val until = c.expiresAtMs
                PSetting(s.title, (if (until == null) stringResource(R.string.rt_until_done) else stringResource(R.string.rt_until, DateUtils.formatDateTime(container.context, until, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME))) + (if (c.allowedWork.isNotBlank()) " · ${c.allowedWork}" else "")) {
                    Tag(if (why == null) stringResource(R.string.rt_will_resume) else stringResource(R.string.rt_will_not), if (why == null) Tone.Good else Tone.Neutral)
                }
                PButton(stringResource(R.string.rt_open), { onOpenSession(s.id) }, style = ButtonStyle.Ghost, tiny = true)
            }
            Gap(8.dp)
            Callout(stringResource(R.string.rt_contract_note), Tone.Neutral)
            log.takeLast(6).reversed().forEach { e ->
                Text("${DateUtils.formatDateTime(container.context, e.timeMs, DateUtils.FORMAT_SHOW_TIME)} ${e.kind} ${e.data?.toString()?.take(100).orEmpty()}", Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
            }
        }
        Gap(12.dp)
        PCard(style = CardStyle.Soft) {
            Kicker(stringResource(R.string.rt_recovery))
            Gap(8.dp)
            FactGrid(
                listOf(
                    stringResource(R.string.rt_reattach) to stringResource(R.string.rt_reattach_d),
                    stringResource(R.string.rt_resume) to stringResource(R.string.rt_resume_d),
                    stringResource(R.string.rt_reconcile) to stringResource(R.string.rt_reconcile_d),
                    stringResource(R.string.rt_selective) to stringResource(R.string.rt_selective_d),
                ),
            )
        }
        Gap(28.dp)
    }
}

/** The agent's process as the kernel sees it: no tmux group, process group or proot is reported as a sandbox (INV-008). */
suspend fun probeIsolation(c: AppContainer): app.umbra.runtime.linux.IsolationReport? {
    if (c.guest.state.value != GuestManager.State.Ready) return null
    val out = c.guest.runtime.exec(listOf("bash", "-c", app.umbra.runtime.linux.IsolationProbe.SCRIPT), timeoutMs = 30_000)
    if (out.exitCode != 0 && out.stdout.isBlank()) return null
    val appNs = listOf("mnt", "pid", "net", "user", "uts", "ipc", "cgroup").associateWith { n -> runCatching { android.system.Os.readlink("/proc/self/ns/$n") }.getOrDefault("") }
    return app.umbra.runtime.linux.IsolationProbe.report(out.stdout, android.os.Process.myUid(), appNs, c.guest.mode)
}

@Composable
private fun IsolationCard() {
    val container = LocalContext.current.container
    var report by remember { mutableStateOf<app.umbra.runtime.linux.IsolationReport?>(null) }
    var measuredAt by remember { mutableStateOf<Long?>(null) }
    val guest by container.guest.state.collectAsStateWithLifecycle()
    LaunchedEffect(guest) {
        report = runCatching { probeIsolation(container) }.getOrNull()
        measuredAt = System.currentTimeMillis()
    }
    PCard {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Kicker(stringResource(R.string.rt_processes), Modifier.weight(1f))
            Tag(if (report == null) stringResource(R.string.rt_not_measured) else if (container.guest.mode == LinuxMode.PROOT) stringResource(R.string.rt_no_isolation) else stringResource(R.string.rt_user_level), if (report?.sameUserAsApp == false) Tone.Good else Tone.Warn)
        }
        val r = report
        if (r == null) Subtle(if (guest == GuestManager.State.Ready) stringResource(R.string.rt_measuring) else stringResource(R.string.rt_not_running), Modifier.padding(top = 6.dp))
        else {
            Subtle(r.summary, Modifier.padding(top = 6.dp))
            r.facts.forEach { f -> PSetting(f.name, "${f.value} · ${f.meaning}", divider = f != r.facts.last()) { Tag(if (f.good) stringResource(R.string.rt_holds) else stringResource(R.string.rt_limit), if (f.good) Tone.Good else Tone.Warn) } }
            measuredAt?.let { Subtle(stringResource(R.string.rt_measured, DateUtils.getRelativeTimeSpanString(it).toString()), Modifier.padding(top = 4.dp)) }
        }
    }
}
