package app.umbra.agent.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.AgentService
import app.umbra.agent.R
import app.umbra.agent.agents.AgentProfile
import app.umbra.agent.agents.Channels
import app.umbra.agent.agents.Templates
import app.umbra.agent.container
import app.umbra.agent.data.UiMode
import app.umbra.agent.ui.home.EffortChip
import app.umbra.agent.ui.home.ModelChip
import app.umbra.agent.ui.models.EffortPicker
import app.umbra.agent.ui.models.ModelPicker
import app.umbra.agent.ui.models.effortName
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.ModalBottomSheet
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PField
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.porcelain.pc
import app.umbra.core.orchestrator.AgentSupervisor
import app.umbra.core.orchestrator.SessionSpec
import app.umbra.core.session.Effort
import app.umbra.core.session.ModelRef
import kotlinx.coroutines.launch

/** A model as the chips name it: "Auto", or the directory's name for it. */
@Composable
internal fun modelName(ref: ModelRef?): String {
    val container = LocalContext.current.container
    val list by container.modelDirectory.directory.collectAsStateWithLifecycle()
    if (ref == null) return stringResource(R.string.r_model_auto)
    return list.providers.firstOrNull { it.id == ref.provider }?.models?.firstOrNull { it.id == ref.modelId }?.name ?: ref.modelId
}

@Composable
internal fun effortText(effort: Effort?): String = if (effort == null || effort.auto) stringResource(R.string.r_model_auto) else effortName(effort.level)

/** The SOUL.md a template starts with, in the user's language. */
@Composable
internal fun soulOf(template: String, name: String): String {
    val n = name.trim().ifEmpty { stringResource(R.string.ag_default_name) }
    return when (template) {
        Templates.ASSISTANT -> stringResource(R.string.ag_soul_assistant, n)
        Templates.INBOX -> stringResource(R.string.ag_soul_inbox, n)
        Templates.WORK -> stringResource(R.string.ag_soul_work, n)
        else -> stringResource(R.string.ag_soul_blank, n)
    }
}

/** How often an agent wakes, in words ("Every 10 min", "Every 2 h"). */
@Composable
internal fun intervalText(minutes: Int): String = if (minutes < 60) stringResource(R.string.ag_every_min, minutes) else stringResource(R.string.ag_every_h, minutes / 60)

internal val INTERVALS = listOf(5, 10, 15, 30, 60, 120, 240, 360)

/**
 * A new agent (Figma 10.4): its face and name, a role that writes the first SOUL.md, the messages it watches, its model,
 * effort and wake interval. Once made it runs until the user stops it.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NewAgentSheet(onDismiss: () -> Unit, onCreated: (String) -> Unit, onAccounts: () -> Unit) {
    val container = LocalContext.current.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf(app.umbra.agent.ui.agents.AgentAvatars.all.random().id) }
    var avatars by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf(Templates.ASSISTANT) }
    var soulEdited by remember { mutableStateOf<String?>(null) }
    var channels by remember { mutableStateOf(listOf(Channels.NOTIFICATIONS)) }
    var model by remember { mutableStateOf<ModelRef?>(null) }
    var effort by remember { mutableStateOf<Effort?>(null) }
    var interval by remember { mutableStateOf(AgentProfile.DEFAULT_HEARTBEAT_MIN) }
    var picker by remember { mutableStateOf(false) }
    var effortPicker by remember { mutableStateOf(false) }
    var intervalMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val soul = soulEdited ?: soulOf(template, name)
    val simple = prefs.uiMode == UiMode.SIMPLE
    val defaultName = stringResource(R.string.ag_default_name)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pc(0xFFFBFDF7)) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).imePadding().padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.ag_new_title), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = P.ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AgentAvatar(avatar, name, 60.dp, onClick = { avatars = !avatars })
                PField(name, { name = it.take(32) }, Modifier.weight(1f), label = stringResource(R.string.ag_name), placeholder = defaultName, singleLine = true)
            }
            if (avatars) {
                Text(stringResource(R.string.ag_pick_avatar), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = P.sub)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AgentAvatars.all.forEach { spec -> AgentAvatar(spec.id, name, 48.dp, selected = spec.id == avatar, onClick = { avatar = spec.id; avatars = false }) }
                    AgentAvatar("letter", name, 48.dp, selected = avatar == "letter", onClick = { avatar = "letter"; avatars = false })
                }
            }
            Label(stringResource(R.string.ag_role))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Templates.all.forEach { t -> Pill(templateName(t), t == template) { template = t; soulEdited = null } }
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.base2).padding(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.ag_soul_label), fontSize = 12.sp, color = P.sub)
                    val label = stringResource(R.string.ag_soul_label)
                    BasicTextField(
                        soul, { soulEdited = it }, Modifier.fillMaxWidth().semantics { contentDescription = label },
                        textStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = P.ink), cursorBrush = SolidColor(P.accent2), maxLines = 10,
                    )
                }
            }
            Label(stringResource(R.string.ag_watch))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Channels.apps.forEach { app ->
                    val on = app.id in channels
                    Pill(channelName(app.id), on, icon = PIcons.message) { channels = if (on) channels - app.id else channels + app.id }
                }
                val on = Channels.NOTIFICATIONS in channels
                Pill(stringResource(R.string.ag_ch_notifications), on, icon = PIcons.bell) { channels = if (on) channels - Channels.NOTIFICATIONS else channels + Channels.NOTIFICATIONS }
            }
            Text(stringResource(R.string.ag_watch_note), fontSize = 12.sp, lineHeight = 17.sp, color = P.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelChip(stringResource(R.string.ag_model_chip, modelName(model))) { picker = true }
                EffortChip(effortText(effort)) { effortPicker = true }
                Box {
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(P.base2).clickable(role = Role.Button, enabled = !simple) { intervalMenu = true }.padding(horizontal = 10.dp).heightIn(min = 32.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PIcon(PIcons.clock, size = 15.dp, tint = P.sub)
                        Text(intervalText(if (simple) AgentProfile.DEFAULT_HEARTBEAT_MIN else interval), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = P.ink)
                    }
                    DropdownMenu(intervalMenu, { intervalMenu = false }) {
                        INTERVALS.forEach { m -> DropdownMenuItem(text = { Text(intervalText(m)) }, onClick = { interval = m; intervalMenu = false }) }
                    }
                }
            }
            error?.let { Callout(it, Tone.Danger) }
            PButton(
                stringResource(R.string.ag_create),
                {
                    busy = true
                    scope.launch {
                        try {
                            val p = container.agents.create(
                                name = name.ifBlank { defaultName }, avatar = avatar, template = template, soul = soul,
                                model = model, effort = effort, channels = channels, heartbeatMinutes = interval,
                            )
                            onCreated(p.id)
                        } catch (e: Exception) {
                            error = e.message ?: e::class.simpleName
                        } finally {
                            busy = false
                        }
                    }
                },
                Modifier.fillMaxWidth(), enabled = !busy,
            )
        }
    }
    if (picker) ModelPicker(onDismiss = { picker = false }, onConnect = { picker = false; onAccounts() }, picked = model, onPick = { model = it })
    if (effortPicker) EffortPicker(
        current = effort, supported = container.modelDirectory.model(model)?.efforts.orEmpty(), modelName = null, modelIsAuto = model == null,
        onChoose = { effort = if (it.auto) null else it }, onDismiss = { effortPicker = false },
    )
}

/** A channel's name for people ("SMS" is "簡訊" in Chinese). */
@Composable
internal fun channelName(id: String): String = when (id) {
    Channels.SMS -> stringResource(R.string.ag_ch_sms)
    Channels.NOTIFICATIONS -> stringResource(R.string.ag_ch_notifications)
    "imessage" -> stringResource(R.string.ag_ch_imessage)
    else -> Channels.app(id)?.label ?: id
}

@Composable
internal fun Label(text: String) = Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = P.sub)

@Composable
internal fun Pill(text: String, on: Boolean, icon: app.umbra.agent.ui.porcelain.IconSpec? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) P.accent2 else P.base2).clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 12.dp).heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) PIcon(icon, size = 14.dp, tint = if (on) P.base else P.sub)
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (on) P.base else P.ink, maxLines = 1)
    }
}

/**
 * A new coding session with its settings at hand (Figma 10.5): where it works, model and effort, how it uses a
 * browser, which agent hears its report, and the task. Home's field stays the quick way; this is the considered one.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NewCodingSheet(onDismiss: () -> Unit, onStarted: (String) -> Unit, onAccounts: () -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val projects by container.projects.all.collectAsStateWithLifecycle()
    val profiles by container.agents.store.profiles.collectAsStateWithLifecycle()
    val homeProject by container.ui.homeProject.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf(homeProject) }
    var model by remember { mutableStateOf(if (prefs.autoModel) null else prefs.defaultModel) }
    var effort by remember { mutableStateOf(if (prefs.autoEffort || prefs.thinkingLevel == null) null else Effort(prefs.thinkingLevel!!)) }
    var browser by remember { mutableStateOf("auto") }
    var reportTo by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var projectMenu by remember { mutableStateOf(false) }
    var reportMenu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var effortPicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val independent = stringResource(R.string.composer_independent)
    val nobody = stringResource(R.string.ag_report_nobody)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pc(0xFFFBFDF7)) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).imePadding().padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ag_new_coding_title), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = P.ink)
            Label(stringResource(R.string.ag_workspace))
            Box {
                Dropdown(project?.let { projects[it]?.name } ?: independent) { projectMenu = true }
                DropdownMenu(projectMenu, { projectMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.composer_independent_session)) }, onClick = { projectMenu = false; project = null })
                    projects.values.sortedBy { it.createdAtMs }.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { projectMenu = false; project = p.id }) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelChip(modelName(model), mono = model != null) { picker = true }
                EffortChip(effortText(effort)) { effortPicker = true }
            }
            Label(stringResource(R.string.ag_browser))
            Segmented(
                listOf("auto" to stringResource(R.string.ag_browser_auto), "dom" to stringResource(R.string.ag_browser_dom), "visual" to stringResource(R.string.ag_browser_visual)),
                browser, { browser = it }, Modifier.fillMaxWidth(), fill = true,
            )
            Label(stringResource(R.string.ag_report_to))
            Box {
                Dropdown(reportTo?.let { id -> profiles.firstOrNull { it.id == id }?.let { it.name + " " + context.getString(R.string.ag_report_agent_suffix) } } ?: nobody) { reportMenu = true }
                DropdownMenu(reportMenu, { reportMenu = false }) {
                    DropdownMenuItem(text = { Text(nobody) }, onClick = { reportMenu = false; reportTo = null })
                    profiles.filter { it.running }.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { reportMenu = false; reportTo = p.id }) }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.base2).padding(14.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
                    if (text.isEmpty()) Text(stringResource(R.string.r_composer_hint), fontSize = 15.sp, color = P.muted)
                    val label = stringResource(R.string.cap_message)
                    BasicTextField(
                        text, { text = it }, Modifier.fillMaxWidth().semantics { contentDescription = label },
                        textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = P.ink), cursorBrush = SolidColor(P.accent2), maxLines = 10,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PButton(
                        stringResource(R.string.ag_start_coding),
                        {
                            busy = true
                            scope.launch {
                                try {
                                    AgentService.start(context)
                                    val words = text.trim()
                                    val choice = if (model != null && effort != null) null else container.autoModel.choose(words, pictures = false)
                                    val s = container.supervisor.submit(
                                        words, title = AgentSupervisor.titleOf(words), model = model ?: choice?.model, projectId = project,
                                        effort = effort ?: choice?.effort, spec = SessionSpec(agentId = reportTo, browser = browser),
                                    )
                                    onStarted(s.id)
                                } catch (e: Exception) {
                                    error = e.message ?: e::class.simpleName
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        tiny = true, enabled = text.isNotBlank() && !busy, icon = PIcons.arrowUp,
                    )
                }
            }
            error?.let { Callout(it, Tone.Danger) }
        }
    }
    if (picker) ModelPicker(onDismiss = { picker = false }, onConnect = { picker = false; onAccounts() }, picked = model, onPick = { model = it })
    if (effortPicker) EffortPicker(
        current = effort, supported = container.modelDirectory.model(model)?.efforts.orEmpty(), modelName = model?.modelId, modelIsAuto = model == null,
        onChoose = { effort = if (it.auto) null else it }, onDismiss = { effortPicker = false },
    )
}

@Composable
private fun Dropdown(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.base2).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), fontSize = 15.sp, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        PIcon(PIcons.chevronDown, size = 18.dp, tint = P.sub)
    }
}
