package app.umbra.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.container
import app.umbra.agent.data.GuestManager
import app.umbra.platform.api.exec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class GuestProcess(val pid: Int, val ageSeconds: Long, val rssKb: Long, val command: String)

/** What is running on behalf of the agent: processes in the Linux environment and apps opened by tasks (spec §25). */
@OptIn(ExperimentalMaterial3Api::class)
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Running now") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, "Refresh") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { item { Banner(it, tone = Semantic.danger) } }
            item { SectionTitle("Apps opened by tasks") }
            if (apps.isEmpty()) item { Text("None.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(apps, key = { "${it.first}/${it.second}" }) { (task, pkg) ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pkg, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                            Text("for “$task”", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { scope.launch { container.shellUser.exec("am force-stop $pkg"); tick++ } }) { Text("Close") }
                    }
                }
            }
            item { SectionTitle("In the Linux environment") }
            if (processes.isEmpty()) item { Text(if (guestState == GuestManager.State.Ready) "Nothing running." else "The Linux environment is not running.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(processes, key = { it.pid }) { p ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("pid ${p.pid} · ${formatMs(p.ageSeconds * 1000)} · ${p.rssKb / 1024} MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { scope.launch { container.guest.runtime.exec(listOf("kill", "-TERM", p.pid.toString())); delay(500); tick++ } }) { Text("End") }
                    }
                }
            }
        }
    }
}
