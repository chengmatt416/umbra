package app.umbra.agent.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.MarkdownText
import app.umbra.agent.ui.porcelain.*
import app.umbra.agent.ui.shell.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun TeamPanel(agentId: String, onOpenSession: (String) -> Unit) {
    val container = LocalContext.current.container
    val store = container.agents.proactive.store
    val revision by store.revision.collectAsStateWithLifecycle()
    val profiles by container.agents.store.profiles.collectAsStateWithLifecycle()
    val groups by produceState(emptyList<app.umbra.agent.agents.proactive.AgentGroup>(), agentId, revision) {
        value = withContext(Dispatchers.IO) { store.groups().filter { agentId in it.memberAgentIds } }
    }
    val scope = rememberCoroutineScope()
    var create by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run(block: suspend () -> Unit) { scope.launch {
        try { withContext(Dispatchers.IO) { block() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "The group action failed" }
    } }
    PButton(stringResource(R.string.proactive_group_create), { create = true }, enabled = profiles.size >= 2)
    Text(stringResource(R.string.proactive_group_hint), color = P.sub)
    groups.forEach { group ->
        var text by remember(group.id) { mutableStateOf("") }
        val history by produceState(emptyList<app.umbra.agent.agents.proactive.GroupMessage>(), group.id, revision) { value = withContext(Dispatchers.IO) { store.groupMessages(group.id).takeLast(30) } }
        val turns by produceState(emptyList<app.umbra.agent.agents.proactive.GroupTurn>(), group.id, revision) { value = withContext(Dispatchers.IO) { store.groupTurns().filter { it.groupId == group.id } } }
        RowGroup { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(group.name, color = P.ink)
            Text(group.memberAgentIds.mapNotNull { id -> profiles.find { it.id == id }?.name }.joinToString(" · "), color = P.sub)
            history.forEach { message ->
                Text(message.fromAgentId?.let { id -> profiles.find { it.id == id }?.name } ?: stringResource(R.string.proactive_group_you), color = P.sub)
                MarkdownText(message.text)
                message.sessionId?.let { id -> PButton(stringResource(R.string.proactive_open_result), { onOpenSession(id) }, style = ButtonStyle.Ghost) }
            }
            val active = turns.lastOrNull { it.status in setOf("active", "summarizing", "paused") }
            active?.let { turn ->
                Text("${turn.status} · ${turn.hop}/4", color = P.sub)
                turn.error?.let { Text(it, color = P.amber) }
                if (turn.status == "paused") PButton(stringResource(R.string.action_resume), { run { container.teams.resume(group.id) } }, style = ButtonStyle.Ghost)
                PButton(stringResource(R.string.action_stop), { run { container.teams.stop(group.id) } }, style = ButtonStyle.Ghost)
            }
            PField(text, { text = it }, label = stringResource(R.string.proactive_group_message), minLines = 2)
            PButton(stringResource(R.string.action_send), { val sent = text; text = ""; run { container.teams.sendUser(group.id, sent) } }, enabled = text.isNotBlank() && active == null)
        } }
    }
    if (create) {
        var name by remember { mutableStateOf("") }
        var members by remember { mutableStateOf(setOf(agentId)) }
        var coordinator by remember { mutableStateOf(agentId) }
        AlertDialog(onDismissRequest = { create = false }, title = { Text(stringResource(R.string.proactive_group_create)) }, text = { Column {
            PField(name, { name = it }, label = stringResource(R.string.proactive_group_name))
            profiles.take(30).forEach { p -> SwitchRow(null, p.name, checked = p.id in members) { selected ->
                if (selected && members.size < 6) members = members + p.id
                else if (!selected) { members = members - p.id; if (coordinator == p.id) coordinator = members.firstOrNull().orEmpty() }
            } }
            if (members.isNotEmpty()) Segmented(profiles.filter { it.id in members }.map { it.id to it.name }, coordinator, { coordinator = it })
        } }, confirmButton = { PButton(stringResource(R.string.action_confirm), { run { container.teams.create(name, members.toList(), coordinator); withContext(Dispatchers.Main) { create = false } } }, enabled = name.isNotBlank() && members.size in 2..6 && coordinator in members) },
            dismissButton = { PButton(stringResource(R.string.action_cancel), { create = false }, style = ButtonStyle.Ghost) })
    }
    error?.let { Text(it, color = P.danger) }
}
