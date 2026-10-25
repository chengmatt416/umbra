package app.umbra.agent.ui

import app.umbra.agent.ui.porcelain.pc
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import app.umbra.agent.ui.porcelain.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.CatalogModel
import app.umbra.agent.data.ModelCatalog
import app.umbra.agent.data.ProviderConfig
import app.umbra.agent.data.ProviderPresets
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.DisabledReason
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PConfirm
import app.umbra.agent.ui.porcelain.PEmpty
import app.umbra.agent.ui.porcelain.PField
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PObject
import app.umbra.agent.ui.porcelain.SectionHead
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.core.session.ModelRef
import kotlinx.coroutines.launch

/** The AI providers, their keys and the default model; listing models needs Linux, and says so beside the button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(onBack: () -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val guestState by container.guest.state.collectAsStateWithLifecycle()
    val linuxReady = guestState == app.umbra.agent.data.GuestManager.State.Ready || guestState == app.umbra.agent.data.GuestManager.State.Installed
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ProviderConfig?>(null) }
    var catalog by remember { mutableStateOf<List<CatalogModel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var manual by remember(prefs.defaultModel) { mutableStateOf(prefs.defaultModel?.let { "${it.provider}/${it.modelId}" }.orEmpty()) }
    var filter by remember { mutableStateOf("") }

    PorcelainPage(
        stringResource(R.string.dest_models), onBack,
        actions = { if (prefs.providers.isNotEmpty()) PButton(stringResource(R.string.models_add), { adding = true }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.plus) },
    ) {
        if (prefs.providers.isEmpty()) {
            PEmpty(
                stringResource(R.string.models_empty_title), stringResource(R.string.models_empty_body), icon = PIcons.spark,
                action = { PButton(stringResource(R.string.models_add_provider), { adding = true }, icon = PIcons.plus) },
            )
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 6.dp, 18.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Eyebrow(stringResource(R.string.models_eyebrow)) }
            message?.let { item { Callout(it, Tone.Danger) } }
            items(prefs.providers, key = { it.id }) { provider ->
                val keyed = provider.apiKeyEnv?.let { container.secrets.has(it) } ?: true
                PObject(
                    provider.label,
                    when {
                        provider.custom -> provider.baseUrl.orEmpty()
                        keyed -> stringResource(R.string.models_key_saved)
                        else -> stringResource(R.string.models_no_key)
                    },
                    PIcons.key,
                    trailing = { PButton(stringResource(R.string.models_remove), { removing = provider }, style = ButtonStyle.Ghost, tiny = true) },
                )
            }
            item {
                SectionHead(
                    stringResource(R.string.models_default), Modifier.padding(top = 10.dp),
                    body = prefs.defaultModel?.let { stringResource(R.string.models_using, "${it.provider}/${it.modelId}") } ?: stringResource(R.string.models_choose),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PField(manual, { manual = it }, Modifier.weight(1f), label = stringResource(R.string.models_manual), singleLine = true, mono = true)
                    PButton(
                        stringResource(R.string.models_use),
                        { container.settings.update { it.copy(defaultModel = ModelRef(manual.substringBefore('/'), manual.substringAfter('/'))) } },
                        enabled = manual.contains('/') && manual.substringBefore('/').isNotBlank() && manual.substringAfter('/').isNotBlank(),
                    )
                }
            }
            item {
                PButton(
                    stringResource(R.string.models_list),
                    {
                        loading = true
                        scope.launch {
                            try {
                                catalog = ModelCatalog(container).fetch()
                                message = if (catalog.isEmpty()) resources.getString(R.string.models_none_found) else null
                            } catch (e: Exception) {
                                message = e.message
                            }
                            loading = false
                        }
                    },
                    style = ButtonStyle.Alt, tiny = true, icon = PIcons.search, enabled = !loading && linuxReady,
                )
                if (!linuxReady) {
                    DisabledReason(stringResource(R.string.models_needs_linux))
                    Box(Modifier.padding(top = 6.dp)) { PButton(stringResource(R.string.models_install_linux), onSetup, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.download) }
                }
                if (loading) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(18.dp), strokeWidth = 2.dp, color = P.accent2)
            }
            if (catalog.isNotEmpty()) {
                item { PField(filter, { filter = it }, label = stringResource(R.string.models_filter), singleLine = true) }
                val shown = catalog.filter { filter.isBlank() || it.selector.contains(filter, ignoreCase = true) || it.name.contains(filter, ignoreCase = true) }.take(80)
                items(shown, key = { it.selector }) { model ->
                    val selected = prefs.defaultModel?.let { it.provider == model.provider && it.modelId == model.id } == true
                    val tags = buildList {
                        add(model.selector)
                        model.contextWindow?.let { add(stringResource(R.string.models_context, it / 1000)) }
                        if (model.reasoning) add(stringResource(R.string.models_thinking_tag))
                        if (model.images) add(stringResource(R.string.models_images_tag))
                    }.joinToString(" · ")
                    PObject(
                        model.name, tags, if (selected) PIcons.check else PIcons.spark, selected = selected,
                        onClick = { container.settings.update { it.copy(defaultModel = ModelRef(model.provider, model.id)) }; manual = model.selector },
                    )
                }
            }
            item {
                SectionHead(stringResource(R.string.models_effort), Modifier.padding(top = 10.dp))
                Segmented(
                    listOf<Pair<String?, String>>(null to stringResource(R.string.effort_default), "low" to stringResource(R.string.effort_low), "medium" to stringResource(R.string.effort_medium), "high" to stringResource(R.string.effort_high)),
                    prefs.thinkingLevel, { level -> container.settings.update { it.copy(thinkingLevel = level) } }, fill = true,
                )
            }
        }
    }

    removing?.let { provider ->
        PConfirm(
            stringResource(R.string.models_remove_title, provider.label), stringResource(R.string.models_remove_body), stringResource(R.string.models_remove),
            onDismiss = { removing = null },
            onConfirm = {
                removing = null
                provider.apiKeyEnv?.let { container.secrets.remove(it) }
                container.settings.update { p -> p.copy(providers = p.providers - provider, defaultModel = p.defaultModel?.takeIf { it.provider != provider.id }) }
            },
        )
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
    var images by remember { mutableStateOf(false) }
    val custom = choice == "custom"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = pc(0xFFF2F6EF),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(P.radius),
        title = { Text(stringResource(R.string.models_add_provider), fontFamily = P.serif, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, letterSpacing = (-0.2).sp, fontSize = 22.sp, color = P.ink) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProviderPresets.all.forEach { preset -> FilterChip(selected = choice == preset.id, onClick = { choice = preset.id }, label = { Text(preset.label) }) }
                    FilterChip(selected = custom, onClick = { choice = "custom" }, label = { Text(stringResource(R.string.provider_other)) })
                }
                if (custom) {
                    PField(id, { id = it.filter { c -> c.isLetterOrDigit() || c in "._-" } }, label = stringResource(R.string.provider_name), singleLine = true)
                    PField(url, { url = it }, label = stringResource(R.string.provider_url), singleLine = true, mono = true)
                    PField(models, { models = it }, label = stringResource(R.string.provider_models), singleLine = true, mono = true)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("openai-completions", "openai-responses", "anthropic-messages", "google-generative-ai").forEach {
                            FilterChip(selected = api == it, onClick = { api = it }, label = { Text(it) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(images, { images = it })
                        Text(stringResource(R.string.provider_images), fontSize = 12.sp, color = P.sub)
                    }
                }
                if (choice != null) {
                    PField(
                        key, { key = it }, label = stringResource(if (custom) R.string.provider_key_optional else R.string.provider_key), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), mono = true,
                        placeholder = ProviderPresets.byId(choice.orEmpty())?.hint,
                    )
                    Subtle(stringResource(R.string.provider_key_note))
                }
            }
        },
        confirmButton = {
            val valid = when {
                choice == null -> false
                custom -> id.isNotBlank() && id !in ProviderPresets.all.map { it.id } && url.startsWith("http") && models.isNotBlank()
                else -> key.isNotBlank()
            }
            PButton(
                stringResource(R.string.action_save),
                enabled = valid,
                onClick = {
                    val preset = ProviderPresets.byId(choice.orEmpty())
                    val config = if (preset != null) {
                        ProviderConfig(preset.id, preset.label, apiKeyEnv = preset.keyEnv)
                    } else {
                        val env = "UMBRA_KEY_" + id.uppercase().replace(Regex("[^A-Z0-9]"), "_")
                        ProviderConfig(
                            id, id, apiKeyEnv = env.takeIf { key.isNotBlank() }, custom = true, baseUrl = url.trim(), api = api,
                            models = models.split(',').map { it.trim() }.filter { it.isNotEmpty() }, keyless = key.isBlank(), images = images,
                        )
                    }
                    onSave(config, key)
                },
            )
        },
        dismissButton = { PButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Ghost) },
    )
}
