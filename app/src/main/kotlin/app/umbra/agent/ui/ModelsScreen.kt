package app.umbra.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.container
import app.umbra.agent.data.CatalogModel
import app.umbra.agent.data.ModelCatalog
import app.umbra.agent.data.ProviderConfig
import app.umbra.agent.data.ProviderPresets
import app.umbra.core.session.ModelRef
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<List<CatalogModel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var manual by remember(prefs.defaultModel) { mutableStateOf(prefs.defaultModel?.let { "${it.provider}/${it.modelId}" }.orEmpty()) }
    var filter by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add provider") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            message?.let { item { Banner(it, tone = Semantic.danger, actionLabel = "Dismiss", onAction = { message = null }) } }
            if (prefs.providers.isEmpty()) item {
                EmptyState("No provider yet", "Add the AI provider you want Umbra to use. Your key stays on this phone, encrypted.") {
                    Button(onClick = { adding = true }) { Text("Add provider") }
                }
            }
            items(prefs.providers, key = { it.id }) { provider ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(provider.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            val keyed = provider.apiKeyEnv?.let { container.secrets.has(it) } ?: true
                            Text(
                                when {
                                    provider.custom -> provider.baseUrl.orEmpty()
                                    keyed -> "Key saved"
                                    else -> "No key saved"
                                },
                                style = MaterialTheme.typography.bodySmall, color = if (keyed) MaterialTheme.colorScheme.onSurfaceVariant else Semantic.danger,
                            )
                        }
                        TextButton(onClick = {
                            provider.apiKeyEnv?.let { container.secrets.remove(it) }
                            container.settings.update { p -> p.copy(providers = p.providers - provider, defaultModel = p.defaultModel?.takeIf { it.provider != provider.id }) }
                        }) { Text("Remove") }
                    }
                }
            }
            if (prefs.providers.isNotEmpty()) {
                item {
                    Text("Default model", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        prefs.defaultModel?.let { "Using ${it.provider}/${it.modelId}" } ?: "Choose the model new tasks use.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(manual, { manual = it }, Modifier.weight(1f), label = { Text("provider/model") }, singleLine = true)
                        Button(
                            enabled = manual.contains('/') && manual.substringBefore('/').isNotBlank() && manual.substringAfter('/').isNotBlank(),
                            onClick = { container.settings.update { it.copy(defaultModel = ModelRef(manual.substringBefore('/'), manual.substringAfter('/'))) } },
                        ) { Text("Use") }
                    }
                }
                item {
                    OutlinedButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            scope.launch {
                                try {
                                    catalog = ModelCatalog(container).fetch()
                                    if (catalog.isEmpty()) message = "No models found. The Linux environment must be installed to list models; you can still type one above."
                                } catch (e: Exception) {
                                    message = e.message
                                }
                                loading = false
                            }
                        },
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                        Text("List available models")
                    }
                }
                if (catalog.isNotEmpty()) {
                    item { OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("Filter") }, singleLine = true) }
                    val shown = catalog.filter { filter.isBlank() || it.selector.contains(filter, ignoreCase = true) || it.name.contains(filter, ignoreCase = true) }.take(80)
                    items(shown, key = { it.selector }) { model ->
                        val selected = prefs.defaultModel?.let { it.provider == model.provider && it.modelId == model.id } == true
                        Row(
                            Modifier.fillMaxWidth().clickable { container.settings.update { it.copy(defaultModel = ModelRef(model.provider, model.id)) }; manual = model.selector },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Column(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp)) {
                                Text(model.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    buildString {
                                        append(model.selector)
                                        model.contextWindow?.let { append(" · ${it / 1000}K context") }
                                        if (model.reasoning) append(" · thinking")
                                        if (model.images) append(" · images")
                                    },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item {
                    Text("Thinking effort", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null to "Default", "low" to "Low", "medium" to "Medium", "high" to "High").forEach { (level, label) ->
                            FilterChip(selected = prefs.thinkingLevel == level, onClick = { container.settings.update { it.copy(thinkingLevel = level) } }, label = { Text(label) })
                        }
                    }
                }
            }
        }
    }

    if (adding) AddProviderDialog(
        existing = prefs.providers.map { it.id }.toSet(),
        onDismiss = { adding = false },
        onSave = { provider, key ->
            if (key.isNotBlank() && provider.apiKeyEnv != null) container.secrets.put(provider.apiKeyEnv, key.trim())
            container.settings.update { p ->
                val suggested = ProviderPresets.byId(provider.id)?.suggestedModel
                p.copy(
                    providers = p.providers.filterNot { it.id == provider.id } + provider,
                    defaultModel = p.defaultModel ?: suggested?.let { ModelRef(provider.id, it) } ?: provider.models.firstOrNull()?.let { ModelRef(provider.id, it) },
                )
            }
            adding = false
        },
    )
}

@Composable
private fun AddProviderDialog(existing: Set<String>, onDismiss: () -> Unit, onSave: (ProviderConfig, String) -> Unit) {
    var choice by remember { mutableStateOf<String?>(null) } // preset id, or "custom"
    var key by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var api by remember { mutableStateOf("openai-completions") }
    var models by remember { mutableStateOf("") }
    val custom = choice == "custom"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProviderPresets.all.forEach { preset -> FilterChip(selected = choice == preset.id, onClick = { choice = preset.id }, label = { Text(preset.label) }) }
                    FilterChip(selected = custom, onClick = { choice = "custom" }, label = { Text("Other (OpenAI-compatible)") })
                }
                if (custom) {
                    OutlinedTextField(id, { id = it.filter { c -> c.isLetterOrDigit() || c in "._-" } }, label = { Text("Name (letters, digits, - _ .)") }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text("Base URL, e.g. https://host/v1") }, singleLine = true)
                    OutlinedTextField(models, { models = it }, label = { Text("Model ids, comma separated") }, singleLine = true)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("openai-completions", "openai-responses", "anthropic-messages", "google-generative-ai").forEach {
                            FilterChip(selected = api == it, onClick = { api = it }, label = { Text(it) })
                        }
                    }
                }
                if (choice != null) {
                    OutlinedTextField(
                        key, { key = it }, label = { Text(if (custom) "API key (optional)" else "API key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text(ProviderPresets.byId(choice.orEmpty())?.hint.orEmpty()) },
                    )
                    Text("Stored encrypted on this phone. Only the agent process sees it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            val valid = when {
                choice == null -> false
                custom -> id.isNotBlank() && id !in ProviderPresets.all.map { it.id } && url.startsWith("http") && models.isNotBlank()
                else -> key.isNotBlank()
            }
            TextButton(
                enabled = valid,
                onClick = {
                    val preset = ProviderPresets.byId(choice.orEmpty())
                    val config = if (preset != null) {
                        ProviderConfig(preset.id, preset.label, apiKeyEnv = preset.keyEnv)
                    } else {
                        val env = "UMBRA_KEY_" + id.uppercase().replace(Regex("[^A-Z0-9]"), "_")
                        ProviderConfig(
                            id, id, apiKeyEnv = env.takeIf { key.isNotBlank() }, custom = true, baseUrl = url.trim(), api = api,
                            models = models.split(',').map { it.trim() }.filter { it.isNotEmpty() }, keyless = key.isBlank(),
                        )
                    }
                    onSave(config, key)
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
