package app.umbra.agent.ui.models

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.AppPrefs
import app.umbra.agent.data.ModelDirectory
import app.umbra.agent.ui.porcelain.AlertDialog
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.ModalBottomSheet
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PField
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.pc
import app.umbra.core.session.ModelRef

/** What the model chip says: "Auto", the chosen model's name, or its id when the directory does not know it. */
@Composable
fun modelLabel(prefs: AppPrefs, directory: ModelDirectory): String {
    val list by directory.directory.collectAsStateWithLifecycle()
    if (prefs.autoModel || prefs.defaultModel == null) return stringResource(R.string.r_model_auto)
    val ref = prefs.defaultModel
    return list.providers.firstOrNull { it.id == ref.provider }?.models?.firstOrNull { it.id == ref.modelId }?.name ?: ref.modelId
}

/**
 * The models to pick from, the same sheet in every mode: "Auto", then each provider of the directory (connected ones
 * can be chosen, the others say they need connecting), the models of the user's own compatible providers, and
 * "Other model" for an id the list does not have. The list is the site's (refreshed by itself), merged here with
 * nothing invented: every id is omp's.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(onDismiss: () -> Unit, onConnect: () -> Unit) {
    val container = LocalContext.current.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val directory by container.modelDirectory.directory.collectAsStateWithLifecycle()
    val updatedAt by container.modelDirectory.updatedAtMs.collectAsStateWithLifecycle()
    var other by remember { mutableStateOf(false) }
    val connected = prefs.providers.map { it.id }.toSet()
    val choose = { ref: ModelRef? ->
        container.settings.update { if (ref == null) it.copy(autoModel = true) else it.copy(autoModel = false, defaultModel = ref) }
        onDismiss()
    }
    val chosen = if (prefs.autoModel) null else prefs.defaultModel
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pc(0xFFFBFDF7)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.r_model_title), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = P.ink)
                Text(
                    if (updatedAt > 0) stringResource(R.string.r_model_updated, DateUtils.getRelativeTimeSpanString(updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
                    else stringResource(R.string.r_model_bundled),
                    fontSize = 12.sp, color = P.muted,
                )
            }
            Option(stringResource(R.string.r_model_auto), stringResource(R.string.r_model_auto_body), chosen == null, enabled = true, mono = false) { choose(null) }
            // A provider the directory lists twice (an account and a key, both "OpenAI") shows once unless both are connected.
            val providers = directory.providers.filter { p -> p.id in connected || directory.providers.none { o -> o !== p && o.label == p.label && o.id in connected } && directory.providers.first { it.label == p.label } === p }
            providers.forEach { p ->
                val on = p.id in connected
                Group(stringResource(if (on) R.string.r_model_connected else R.string.r_model_connect_first, p.label))
                p.models.forEach { m ->
                    Option(m.name, null, chosen == ModelRef(p.id, m.id), enabled = on, mono = false) { if (on) choose(ModelRef(p.id, m.id)) else onConnect() }
                }
            }
            prefs.providers.filter { it.custom && it.models.isNotEmpty() }.forEach { p ->
                Group(stringResource(R.string.r_model_connected, p.label))
                p.models.forEach { id -> Option(id, null, chosen == ModelRef(p.id, id), enabled = true, mono = true) { choose(ModelRef(p.id, id)) } }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { if (connected.isEmpty()) onConnect() else other = true }.padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PIcon(PIcons.plus, size = 20.dp, tint = P.accent2)
                Text(stringResource(R.string.r_model_other), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.accent2)
            }
        }
    }
    if (other) OtherModelDialog(onDismiss = { other = false }, onUse = { ref -> other = false; choose(ref) })
}

@Composable
private fun Group(text: String) = Text(text, Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = P.sub)

@Composable
private fun Option(name: String, sub: String?, selected: Boolean, enabled: Boolean, mono: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.55f).clip(shape).then(if (selected) Modifier.background(P.accent3.copy(alpha = 0.6f)) else Modifier)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = if (mono) 14.sp else 15.sp, fontFamily = if (mono) P.mono else P.sans, fontWeight = FontWeight.Medium, color = P.ink)
            if (sub != null) Text(sub, Modifier.padding(top = 1.dp), fontSize = 12.sp, color = P.sub)
        }
        Box(
            Modifier.size(20.dp).clip(CircleShape).then(if (selected) Modifier.background(P.accent2) else Modifier.border(2.dp, P.muted, CircleShape)),
        )
    }
}

/** A model the list does not have: which connected provider, and its id as omp knows it. */
@Composable
private fun OtherModelDialog(onDismiss: () -> Unit, onUse: (ModelRef) -> Unit) {
    val container = LocalContext.current.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    var provider by remember { mutableStateOf(prefs.providers.firstOrNull()?.id) }
    var id by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.r_model_other_title), fontFamily = P.serif, fontSize = 22.sp, color = P.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (prefs.providers.isEmpty()) Text(stringResource(R.string.r_model_other_none), fontSize = 14.sp, color = P.sub)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    prefs.providers.forEach { p ->
                        val on = p.id == provider
                        val shape = RoundedCornerShape(16.dp)
                        Text(
                            p.label, Modifier.clip(shape).background(if (on) P.accent3 else P.base2).clickable(role = Role.RadioButton) { provider = p.id }.padding(horizontal = 12.dp, vertical = 7.dp),
                            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = P.ink, maxLines = 1,
                        )
                    }
                }
                PField(id, { id = it.trim() }, label = stringResource(R.string.r_model_other_id), singleLine = true, mono = true)
            }
        },
        confirmButton = {
            PButton(stringResource(R.string.r_model_other_use), { provider?.let { onUse(ModelRef(it, id)) } }, enabled = provider != null && id.isNotBlank(), icon = PIcons.check)
        },
        dismissButton = { PButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Ghost) },
    )
}
