package app.umbra.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PIconButton
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PObject
import app.umbra.agent.ui.porcelain.SectionHead
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.agent.container
import app.umbra.agent.data.GuestManager
import app.umbra.platform.api.exec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class GuestProcess(val pid: Int, val ageSeconds: Long, val rssKb: Long, val command: String)

/** What is running on behalf of the agent: processes in the Linux environment and apps opened by tasks (spec §25). */
@Composable
fun ProcessesScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val guestState by container.guest.state.collectAsStateWithLifecycle()
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    var processes by remember { mutableStateOf<List<GuestProcess>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick, guestState) {
        if (guestState != GuestManager.State.Ready) { processes = emptyList(); return@LaunchedEffect }
        error = null
        processes = try {
            val out = container.guest.runtime.exec(listOf("ps", "-u", "agent", "-o", "pid=,etimes=,rss=,args=", "--sort=-rss"), timeoutMs = 15_000)
            out.stdout.lineSequence().mapNotNull { line ->
                val m = Regex("""^\s*(\d+)\s+(\d+)\s+(\d+)\s+(.*)$""").find(line) ?: return@mapNotNull null
                GuestProcess(m.groupValues[1].toInt(), m.groupValues[2].toLong(), m.groupValues[3].toLong(), m.groupValues[4])
            }.filter { !it.command.startsWith("ps ") }.toList()
        } catch (e: Exception) {
            error = e.message
            emptyList()
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(5_000); tick++ } }

    val apps = sessions.filter { !it.state.isTerminal }.flatMap { s -> s.activeApps.map { app -> s.title to app } }
    PorcelainPage(
        stringResource(R.string.dest_processes), onBack,
        actions = { PIconButton(PIcons.refresh, stringResource(R.string.proc_refresh), { tick++ }, size = 36.dp) },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 6.dp, 18.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Eyebrow(stringResource(R.string.proc_eyebrow)) }
            error?.let { item { Callout(it, Tone.Danger) } }
            item { SectionHead(stringResource(R.string.proc_apps)) }
            if (apps.isEmpty()) item { Subtle(stringResource(R.string.proc_apps_none)) }
            items(apps, key = { "${it.first}/${it.second}" }) { (task, pkg) ->
                PObject(
                    pkg, stringResource(R.string.proc_for, task), PIcons.phone,
                    trailing = { PButton(stringResource(R.string.proc_close), { scope.launch { container.shellUser.exec("am force-stop $pkg"); tick++ } }, style = ButtonStyle.Alt, tiny = true) },
                )
            }
            item { SectionHead(stringResource(R.string.proc_linux), Modifier.padding(top = 8.dp)) }
            if (processes.isEmpty()) item { Subtle(stringResource(if (guestState == GuestManager.State.Ready) R.string.proc_linux_none else R.string.proc_linux_off)) }
            items(processes, key = { it.pid }) { p ->
                PObject(
                    p.command, stringResource(R.string.proc_meta, p.pid, formatMs(p.ageSeconds * 1000), (p.rssKb / 1024).toInt()), PIcons.terminal,
                    trailing = { PButton(stringResource(R.string.proc_end), { scope.launch { container.guest.runtime.exec(listOf("kill", "-TERM", p.pid.toString())); delay(500); tick++ } }, style = ButtonStyle.Alt, tiny = true) },
                )
            }
        }
    }
}
