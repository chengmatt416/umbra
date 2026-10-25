package app.umbra.agent.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.*
import app.umbra.agent.ui.shell.RowGroup
import app.umbra.agent.ui.shell.SwitchRow
import app.umbra.core.safety.CustomActionRule
import app.umbra.core.safety.RuleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun CustomRulesPanel(agentId: String, onError: (String) -> Unit) {
    val container = LocalContext.current.container
    val store = container.agents.proactive.store
    val revision by store.revision.collectAsState()
    val rules = remember(revision, agentId) { runCatching { store.actionRules(agentId) } }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<CustomActionRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    Text(stringResource(R.string.proactive_rule_floor), color = P.sub)
    rules.exceptionOrNull()?.let { Text(it.message.orEmpty(), color = P.danger) }
    rules.getOrDefault(emptyList()).forEach { rule -> RowGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(rule.description, color = P.ink)
            Text(rule.disposition.name, color = P.sub)
            Text(listOfNotNull(rule.scope.appPackage, rule.scope.connectorOrTool, rule.scope.recipientOrDomain,
                rule.scope.maxAmountUsd?.let { "USD ≤ $it" }).joinToString(" · "), color = P.sub)
            if (rule.agentId == agentId) {
                PButton(stringResource(R.string.action_edit), { editing = rule }, style = ButtonStyle.Ghost)
                SwitchRow(null, stringResource(R.string.proactive_rule_enabled), checked = rule.enabled) { enabled -> scope.launch(Dispatchers.IO) {
                    try { store.saveActionRules(agentId, store.actionRules(agentId).filter { it.agentId == agentId }.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }) }
                    catch (e: Exception) { onError(e.message.orEmpty()) }
                } }
            }
        }
    } }
    PButton(stringResource(R.string.proactive_add_rule), { adding = true })
    if (adding || editing != null) RuleDialog(editing, onDismiss = { adding = false; editing = null }, onSave = { draft ->
        scope.launch(Dispatchers.IO) {
            try {
                val saved = draft.copy(id = editing?.id ?: store.id("rule"), agentId = agentId)
                store.saveActionRules(agentId, store.actionRules(agentId).filter { it.agentId == agentId && it.id != saved.id } + saved)
                adding = false; editing = null
            } catch (e: Exception) { onError(e.message.orEmpty()) }
        }
    })
}

@Composable
private fun RuleDialog(existing: CustomActionRule?, onDismiss: () -> Unit, onSave: (CustomActionRule) -> Unit) {
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var app by remember { mutableStateOf(existing?.scope?.appPackage.orEmpty()) }
    var tool by remember { mutableStateOf(existing?.scope?.connectorOrTool.orEmpty()) }
    var recipient by remember { mutableStateOf(existing?.scope?.recipientOrDomain.orEmpty()) }
    var amount by remember { mutableStateOf(existing?.scope?.maxAmountUsd?.toString().orEmpty()) }
    var disposition by remember { mutableStateOf(existing?.disposition ?: CustomActionRule.Disposition.ALWAYS_ASK) }
    var all by remember { mutableStateOf(existing?.scope == RuleScope()) }
    val options = listOf(CustomActionRule.Disposition.AUTO_EXECUTE to R.string.proactive_rule_auto,
        CustomActionRule.Disposition.PRE_APPROVED_SCOPE to R.string.proactive_rule_preapproved,
        CustomActionRule.Disposition.ALWAYS_ASK to R.string.proactive_rule_ask,
        CustomActionRule.Disposition.HANDOFF_TO_USER to R.string.proactive_rule_handoff)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.proactive_add_rule)) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PField(description, { description = it }, label = stringResource(R.string.proactive_rule_description))
            options.forEach { (value, title) -> PButton(stringResource(title), { disposition = value }, style = if (value == disposition) ButtonStyle.Primary else ButtonStyle.Ghost) }
            PField(app, { app = it.trim() }, label = stringResource(R.string.proactive_rule_app), singleLine = true)
            PField(tool, { tool = it.trim() }, label = stringResource(R.string.proactive_rule_tool), singleLine = true)
            PField(recipient, { recipient = it.trim() }, label = stringResource(R.string.proactive_rule_recipient), singleLine = true)
            PField(amount, { amount = it }, label = stringResource(R.string.proactive_rule_amount), singleLine = true)
            SwitchRow(null, stringResource(R.string.proactive_rule_all), checked = all) { all = it }
        }
    }, confirmButton = {
        val amountValid = amount.isBlank() || amount.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true
        PButton(stringResource(R.string.action_confirm), {
            onSave(CustomActionRule(existing?.id.orEmpty(), scope = RuleScope(existing?.scope?.categories.orEmpty(), app.ifBlank { null }, tool.ifBlank { null }, recipient.ifBlank { null }, amount.toDoubleOrNull()),
                disposition = disposition, description = description.trim()))
        }, enabled = description.isNotBlank() && amountValid && (all || app.isNotBlank() || tool.isNotBlank() || recipient.isNotBlank() || amount.isNotBlank()))
    }, dismissButton = { PButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Ghost) })
}
