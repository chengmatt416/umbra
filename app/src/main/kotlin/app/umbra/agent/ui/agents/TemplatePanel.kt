package app.umbra.agent.ui.agents

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.umbra.agent.R
import app.umbra.agent.agents.proactive.AgentTemplates
import app.umbra.agent.container
import app.umbra.agent.ui.MarkdownText
import app.umbra.agent.ui.porcelain.*
import app.umbra.agent.ui.shell.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun TemplatePanel(agentId: String) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val container = context.container
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    fun run(block: suspend () -> Unit) { scope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Template exchange failed" }
    } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run { withContext(Dispatchers.IO) {
            val json = container.templateExchange.export(agentId)
            check(json.length <= 1_000_000)
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(json.toByteArray()) }
        }; result = resources.getString(R.string.proactive_template_exported) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val text = withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                val bytes = input.readNBytes(1_000_001); require(bytes.size <= 1_000_000); bytes.toString(Charsets.UTF_8)
            } }
            AgentTemplates.parse(text)
            pending = text
        }
    }
    Text(stringResource(R.string.proactive_template_body), color = P.sub)
    PButton(stringResource(R.string.proactive_template_export), { export.launch("agent.umbra-agent.json") }, style = ButtonStyle.Ghost)
    PButton(stringResource(R.string.proactive_template_import), { import.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, style = ButtonStyle.Ghost)
    pending?.let { text ->
        val bundle = remember(text) { AgentTemplates.parse(text) }
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(bundle.name) }, text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MarkdownText(bundle.soul)
            Text("${bundle.skills.size} skills · ${bundle.routines.size} routines", color = P.sub)
            Text(bundle.requiredConnectors.joinToString(), color = P.amber)
            bundle.skills.forEach { Text("${it.skill.name}\n${it.instructions}", color = P.sub) }
            bundle.routines.forEach { Text("${it.title}\n${it.instruction}", color = P.sub) }
        } }, confirmButton = { PButton(stringResource(R.string.proactive_template_import), { run {
            val p = withContext(Dispatchers.IO) { container.templateExchange.import(text) }
            pending = null; result = resources.getString(R.string.proactive_template_imported, p.name)
        } }) }, dismissButton = { PButton(stringResource(R.string.action_cancel), { pending = null }, style = ButtonStyle.Ghost) })
    }
    result?.let { Text(it, color = P.sub) }
    error?.let { Text(it, color = P.danger) }
}
