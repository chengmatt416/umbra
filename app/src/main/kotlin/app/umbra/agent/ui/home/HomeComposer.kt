package app.umbra.agent.ui.home

import android.speech.SpeechRecognizer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.shadow
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
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.UiMode
import app.umbra.agent.ui.models.EffortPicker
import app.umbra.agent.ui.models.ModelPicker
import app.umbra.agent.ui.models.effortLabel
import app.umbra.agent.ui.models.modelLabel
import app.umbra.core.session.Effort
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.porcelain.pcHue
import kotlinx.coroutines.launch

/**
 * The message field beside the menu button: the shared draft (the Capsule's composers show the same), the model the
 * next task takes, and the microphone, which becomes Send once there is something to send. Power adds where the task
 * works (a project, or on its own).
 */
@Composable
fun HomeComposer(modifier: Modifier = Modifier, dimmed: Boolean = false, hint: String? = null, agent: app.umbra.agent.agents.AgentProfile? = null, onAccounts: () -> Unit, onSend: (String) -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val draft by container.ui.draft.collectAsStateWithLifecycle()
    val chips by container.ui.draftContext.collectAsStateWithLifecycle()
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val projects by container.projects.all.collectAsStateWithLifecycle()
    val homeProject by container.ui.homeProject.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    var effortPicker by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    val canListen = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val listening by container.ui.listening.collectAsStateWithLifecycle()
    val power = prefs.uiMode != UiMode.SIMPLE
    val shape = RoundedCornerShape(26.dp)
    // The draft is cleared by the send itself once the message went (ScreenContext.send), not before.
    val send = { if (draft.isNotBlank()) onSend(draft.trim()) }

    // Speaking: the field turns into Umbra's listening panel where it is; the words join the draft.
    if (listening) {
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { container.ui.listening.value = false } }
        app.umbra.agent.ui.voice.VoicePanel(
            onHeard = { heard -> container.ui.saveDraft(listOf(container.ui.draft.value, heard).filter { it.isNotBlank() }.joinToString(" ")) },
            onClose = { container.ui.listening.value = false },
            modifier = modifier,
        )
        if (picker) ModelPicker(onDismiss = { picker = false }, onConnect = { picker = false; onAccounts() })
        return
    }

    Column(
        modifier.alpha(if (dimmed) 0.35f else 1f)
            .shadow(6.dp, shape, ambientColor = pcHue(0x1A455F49), spotColor = pcHue(0x26455F49))
            .clip(shape).background(pc(0xFFF6F8F2)).border(1.dp, P.line, shape)
            .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
    ) {
        chips.forEach { chip -> app.umbra.agent.capsule.ChipRow(chip) { container.ui.removeContext(chip.id) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f).padding(start = 8.dp).heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) Text(hint ?: stringResource(R.string.r_composer_hint), fontSize = 15.sp, color = P.muted, maxLines = 1)
                val label = stringResource(R.string.cap_message)
                BasicTextField(
                    draft, { container.ui.saveDraft(it) },
                    Modifier.fillMaxWidth().semantics { contentDescription = label },
                    textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = P.ink), cursorBrush = SolidColor(P.accent2), maxLines = 5,
                )
            }
            SendOrMic(draft.isNotBlank(), canListen, onMic = {
                container.ui.listening.value = true
            }, onSend = send)
        }
        // The words on top, what the next task takes underneath: the model, its effort, and in Power where it works.
        // Talking to an agent, the chips are that agent's own model and effort.
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (agent != null) {
                ModelChip(app.umbra.agent.ui.agents.modelName(agent.model), mono = power && agent.model != null) { picker = true }
                EffortChip(app.umbra.agent.ui.agents.effortText(agent.effort)) { effortPicker = true }
                return@Row
            }
            ModelChip(modelLabel(prefs, container.modelDirectory), mono = power) { picker = true }
            EffortChip(effortLabel(prefs)) { effortPicker = true }
            // The project's name gives way first (it shortens), never the model or the effort.
            if (power) Box(Modifier.weight(1f, fill = false)) {
                ModelChip(homeProject?.let { projects[it]?.name } ?: stringResource(R.string.composer_independent), mono = true) { projectMenu = true }
                DropdownMenu(projectMenu, { projectMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.composer_independent_session)) }, onClick = { projectMenu = false; container.ui.setHomeProject(null) })
                    projects.values.sortedBy { it.createdAtMs }.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { projectMenu = false; container.ui.setHomeProject(p.id) }) }
                }
            }
        }
    }
    if (agent != null) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        if (picker) ModelPicker(onDismiss = { picker = false }, onConnect = { picker = false; onAccounts() }, picked = agent.model, onPick = { ref ->
            container.agents.store.update(agent.id) { it.copy(model = ref) }
            agent.sessionId?.let { sid -> scope.launch { runCatching { container.supervisor.setModel(sid, ref) } } }
        })
        if (effortPicker) EffortPicker(
            current = agent.effort, supported = container.modelDirectory.model(agent.model)?.efforts.orEmpty(), modelName = agent.model?.modelId, modelIsAuto = agent.model == null,
            onChoose = { e ->
                val effort = if (e.auto) null else e
                container.agents.store.update(agent.id) { it.copy(effort = effort) }
                agent.sessionId?.let { sid -> scope.launch { runCatching { container.supervisor.setEffort(sid, effort) } } }
            },
            onDismiss = { effortPicker = false },
        )
        return
    }
    if (picker) ModelPicker(onDismiss = { picker = false }, onConnect = { picker = false; onAccounts() })
    if (effortPicker) {
        // The levels of the model the next task runs on; with "Auto" choosing the model, every level stays open.
        val fixed = if (prefs.autoModel) null else prefs.defaultModel
        EffortPicker(
            current = if (prefs.autoEffort || prefs.thinkingLevel == null) null else Effort(prefs.thinkingLevel!!),
            supported = container.modelDirectory.model(fixed)?.efforts.orEmpty(),
            modelName = fixed?.let { modelLabel(prefs, container.modelDirectory) },
            modelIsAuto = fixed == null,
            onChoose = { e -> container.settings.update { if (e.auto) it.copy(autoEffort = true) else it.copy(autoEffort = false, thinkingLevel = e.level) } },
            onDismiss = { effortPicker = false },
        )
    }
}

/** The small chip that names the model (or the project) and opens its choices. */
@Composable
fun ModelChip(label: String, mono: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.clip(shape).background(P.base2).clickable(role = Role.Button, onClick = onClick).padding(start = 10.dp, end = 6.dp).heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, fontSize = if (mono) 12.sp else 13.sp, fontFamily = if (mono) P.mono else P.sans, fontWeight = FontWeight.Medium, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp))
        PIcon(PIcons.chevronDown, size = 14.dp, tint = P.sub)
    }
}

/** The effort chip: a gauge and the level ("Auto", "High"); its name is read out, not printed, to keep the row short. */
@Composable
fun EffortChip(level: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val described = stringResource(R.string.r_effort_chip, level)
    Row(
        Modifier.clip(shape).background(P.base2).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = described }
            .padding(start = 8.dp, end = 6.dp).heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        PIcon(PIcons.gauge, size = 15.dp, tint = P.sub)
        Text(level, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = P.ink, maxLines = 1, modifier = Modifier.padding(vertical = 6.dp))
        PIcon(PIcons.chevronDown, size = 14.dp, tint = P.sub)
    }
}

@Composable
private fun SendOrMic(canSend: Boolean, canListen: Boolean, onMic: () -> Unit, onSend: () -> Unit) {
    val sendLabel = stringResource(R.string.cap_send)
    val micLabel = stringResource(R.string.cap_speak)
    when {
        canSend -> Box(
            Modifier.size(44.dp).clip(CircleShape).background(P.accent2).clickable(role = Role.Button, onClickLabel = sendLabel, onClick = onSend).semantics { contentDescription = sendLabel },
            contentAlignment = Alignment.Center,
        ) { PIcon(PIcons.send, size = 20.dp, tint = P.base) }
        canListen -> Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = micLabel, onClick = onMic).semantics { contentDescription = micLabel },
            contentAlignment = Alignment.Center,
        ) { PIcon(PIcons.mic, size = 20.dp, tint = P.sub) }
        else -> Box(Modifier.size(44.dp))
    }
}
