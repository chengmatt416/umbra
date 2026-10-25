package app.umbra.agent.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.agents.proactive.Demonstration
import app.umbra.agent.ui.porcelain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun TeachPanel(agentId: String, onError: (String) -> Unit) {
    val container = LocalContext.current.container
    val recorder = container.demonstrations
    val active by recorder.active.collectAsStateWithLifecycle()
    val revision by recorder.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<String?>(null) }
    val records by produceState(emptyList<Demonstration>(), agentId, revision) { value = withContext(Dispatchers.IO) { recorder.list(agentId) } }
    fun run(block: suspend () -> Unit) { scope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(e.message ?: "Teaching failed") }
    } }
    PCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Kicker(stringResource(R.string.teach_title))
            Text(stringResource(R.string.teach_hint), color = P.sub)
            if (active == null) PButton(stringResource(R.string.teach_start), { run { recorder.start(agentId) } })
            else Text(stringResource(R.string.teach_recording), color = P.amber)
            // Teach on a paired computer whose screen is shared: the user operates it below while it records.
            val nodes by container.nodes.all.collectAsStateWithLifecycle()
            val computers = nodes.values.filter { it.revocation == null && it.info?.screen?.available == true }
            if (active == null) computers.forEach { node ->
                PButton(stringResource(R.string.teach_start_computer, node.name), { run { recorder.startRemote(agentId, node.id) } }, style = ButtonStyle.Ghost)
            }
            if (active == null) PButton(stringResource(R.string.teach_start_linux), { run { recorder.startLinuxDesktop(agentId) } }, style = ButtonStyle.Ghost)
            if (active?.agentId == agentId && active?.target == app.umbra.agent.agents.proactive.Demonstration.LINUX_DESKTOP) Text(stringResource(R.string.teach_linux_hint), color = P.sub)
            active?.takeIf { it.agentId == agentId }?.target?.removePrefix("node:")?.takeIf { active?.target?.startsWith("node:") == true }?.let { nodeId ->
                Text(stringResource(R.string.teach_computer_hint), color = P.sub)
                app.umbra.agent.ui.nodes.RemoteNodeViewer(nodeId, initiallyControlling = true, embedded = true)
            }
            if (active?.agentId == agentId) PButton(stringResource(R.string.teach_stop), { run { recorder.stop() } })
            if (active == null) PButton(stringResource(R.string.teach_return), { run { container.returnControl("display:0") } }, style = ButtonStyle.Ghost)
            records.forEach { record ->
                Text("${java.time.Instant.ofEpochMilli(record.startedAtMs)} · ${record.steps.size} · ${record.status}${if (record.incomplete) " · incomplete" else ""}", color = P.sub)
                PButton(stringResource(R.string.teach_trace), { expanded = if (expanded == record.id) null else record.id }, style = ButtonStyle.Ghost)
                if (expanded == record.id) Text(record.steps.joinToString("\n") { "${it.elapsedMs / 1000}s · ${it.action} · ${if (record.remoteNode != null) "${it.windowTitle} (${it.bounds.left}, ${it.bounds.top})" else it.packageName} · ${it.resourceId.orEmpty()} · ${it.label}" }, color = P.sub)
                PButton(stringResource(R.string.teach_extract), { run { recorder.extract(agentId, record.id) } },
                    enabled = record.status == "completed" && !record.incomplete && record.steps.isNotEmpty(), style = ButtonStyle.Ghost)
                PButton(stringResource(R.string.teach_delete), { run { recorder.remove(agentId, record.id) } }, enabled = active?.id != record.id, style = ButtonStyle.Ghost)
            }
        }
    }
}
