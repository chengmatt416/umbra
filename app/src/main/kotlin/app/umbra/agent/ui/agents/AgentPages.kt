package app.umbra.agent.ui.agents

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.agents.AgentProfile
import app.umbra.agent.agents.AgentRule
import app.umbra.agent.agents.AgentState
import app.umbra.agent.agents.AgentTimer
import app.umbra.agent.agents.Channels
import app.umbra.agent.agents.Cron
import app.umbra.agent.agents.Memory
import app.umbra.agent.agents.QueueItem
import app.umbra.agent.container
import app.umbra.agent.data.UiMode
import app.umbra.agent.ui.models.EffortPicker
import app.umbra.agent.ui.models.ModelPicker
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PEmpty
import app.umbra.agent.ui.porcelain.PField
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIconButton
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.shell.NavRow
import app.umbra.agent.ui.shell.PageColumn
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.agent.ui.shell.RowGroup
import app.umbra.agent.ui.shell.SwitchRow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The agent's live profile, or null once it is gone. */
@Composable
private fun agent(agentId: String): AgentProfile? {
    val container = LocalContext.current.container
    val profiles by container.agents.store.profiles.collectAsStateWithLifecycle()
    return profiles.firstOrNull { it.id == agentId }
}

@Composable
private fun Gone(onBack: () -> Unit) = PorcelainPage(stringResource(R.string.ag_title), onBack) { PEmpty(stringResource(R.string.ag_gone), "", icon = PIcons.bot) }

/** The agent's files and counts, read again every few seconds while a page shows them. */
@Composable
private fun <T> polled(agentId: String, initial: T, everyMs: Long = 3_000, read: () -> T): T {
    val value by produceState(initial, agentId) { while (true) { value = runCatching(read).getOrDefault(value); delay(everyMs) } }
    return value
}

/**
 * The agent's page (Figma 11.3), opened from the top of its chat: who it is (soul), what it knows (memory), what it
 * watches, its timers and rules, the Telegram bot, model and effort, how often it wakes; and the button that stops it.
 */
@Composable
fun AgentProfileScreen(agentId: String, onBack: () -> Unit, onGo: (String) -> Unit, onAccounts: () -> Unit, onDeleted: () -> Unit) {
    val container = LocalContext.current.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val p = agent(agentId) ?: return Gone(onBack)
    val state = polled(agentId, AgentState()) { container.agents.store.state(agentId) }
    val memory = polled(agentId, emptyList<Memory.Entry>()) { container.agents.store.memory(agentId).entries() }
    val soul = polled(agentId, "") { container.agents.store.soul(agentId) }
    val timers = polled(agentId, emptyList<AgentTimer>()) { container.agents.store.timers(agentId) }
    val rules = polled(agentId, emptyList<AgentRule>()) { container.agents.store.rules(agentId) }
    val bot = polled(agentId, null as String?) { container.telegram.linkedName(agentId) }
    var picker by remember { mutableStateOf(false) }
    var effortPicker by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val today = state.day == LocalDate.now().toString()

    PorcelainPage(p.name, onBack, actions = {
        Box {
            PIconButton(PIcons.more, stringResource(R.string.ag_more), { menu = true }, size = 36.dp)
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.ag_wake_now)) }, onClick = { menu = false; scope.launch { container.agents.wake(agentId, app.umbra.agent.agents.AgentRuntime.Reason.MANUAL) } })
                DropdownMenuItem(text = { Text(stringResource(R.string.ag_delete), color = P.danger) }, onClick = { menu = false; confirmDelete = true })
            }
        }
    }) {
        PageColumn {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AgentAvatar(p.avatar, p.name, 88.dp)
                Text(p.name, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = P.ink)
                Text(
                    listOf(
                        templateName(p.template),
                        stringResource(if (p.running) R.string.ag_state_on else R.string.ag_state_off),
                        stringResource(R.string.ag_counts, if (today) state.wakesToday else 0, if (today) state.runsToday else 0),
                    ).joinToString(" · "),
                    fontSize = 13.sp, color = P.sub, textAlign = TextAlign.Center,
                )
            }
            RowGroup {
                NavRow(PIcons.heart, stringResource(R.string.ag_soul), "SOUL.md · " + soul.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()) { onGo("agent/$agentId/soul") }
                NavRow(PIcons.history, stringResource(R.string.ag_memory), "MEMORY.md · " + stringResource(R.string.ag_memory_sub, memory.size, memory.lastOrNull()?.text.orEmpty())) { onGo("agent/$agentId/memory") }
                NavRow(PIcons.message, stringResource(R.string.ag_sources), p.channels.filter { it != Channels.NOTIFICATIONS }.map { channelName(it) }.joinToString("、").ifEmpty { stringResource(R.string.ag_sources_none) }) { onGo("agent/$agentId/channels") }
                NavRow(PIcons.bell, stringResource(R.string.ag_ch_notifications), stringResource(if (Channels.NOTIFICATIONS in p.channels) R.string.ag_notifications_on else R.string.ag_notifications_off)) { onGo("notifications") }
            }
            RowGroup {
                NavRow(PIcons.clock, stringResource(R.string.ag_timers), stringResource(R.string.ag_timers_sub, timers.size, rules.size)) { onGo("agent/$agentId/timers") }
                NavRow(PIcons.arrowUp, stringResource(R.string.ag_telegram), bot?.let { stringResource(R.string.ag_telegram_linked, it) } ?: stringResource(R.string.ag_telegram_not)) { onGo("agent/$agentId/telegram") }
                NavRow(PIcons.gauge, stringResource(R.string.ag_model_effort), modelName(p.model) + " · " + effortText(p.effort)) { picker = true }
                NavRow(PIcons.zap, stringResource(R.string.ag_wake), intervalText(if (prefs.uiMode == UiMode.SIMPLE) AgentProfile.DEFAULT_HEARTBEAT_MIN else p.heartbeatMinutes)) { onGo("agent/$agentId/wake") }
                val isDefault = container.agents.defaultAgent()?.id == agentId
                SwitchRow(PIcons.target, stringResource(R.string.ag_default), stringResource(R.string.ag_default_sub), isDefault) { on -> if (on) container.agents.setDefault(agentId) }
            }
            val stopLabel = stringResource(if (p.running) R.string.ag_stop else R.string.ag_start)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(pc(0xFFF6F8F2)).clickable(role = Role.Button) { scope.launch { container.agents.setRunning(agentId, !p.running) } }.heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                PIcon(if (p.running) PIcons.stop else PIcons.play, size = 18.dp, tint = if (p.running) P.danger else P.accent2)
                Text(stopLabel, Modifier.padding(start = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (p.running) P.danger else P.accent2)
            }
            if (p.running) Text(stringResource(R.string.ag_stop_note), fontSize = 12.sp, color = P.muted, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
    if (picker) ModelPicker(onDismiss = { picker = false; effortPicker = true }, onConnect = { picker = false; onAccounts() }, picked = p.model, onPick = { ref ->
        container.agents.store.update(agentId) { it.copy(model = ref) }
        p.sessionId?.let { sid -> scope.launch { runCatching { container.supervisor.setModel(sid, ref) } } }
    })
    if (effortPicker) EffortPicker(
        current = p.effort, supported = container.modelDirectory.model(p.model)?.efforts.orEmpty(), modelName = p.model?.modelId, modelIsAuto = p.model == null,
        onChoose = { e ->
            val effort = if (e.auto) null else e
            container.agents.store.update(agentId) { it.copy(effort = effort) }
            p.sessionId?.let { sid -> scope.launch { runCatching { container.supervisor.setEffort(sid, effort) } } }
        },
        onDismiss = { effortPicker = false },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.ag_delete_title, p.name), fontFamily = P.serif, fontSize = 22.sp, color = P.ink) },
        text = { Text(stringResource(R.string.ag_delete_body), fontSize = 14.sp, color = P.sub) },
        confirmButton = { PButton(stringResource(R.string.ag_delete), { confirmDelete = false; scope.launch { container.agents.delete(agentId); onDeleted() } }, style = ButtonStyle.Danger) },
        dismissButton = { PButton(stringResource(R.string.action_cancel), { confirmDelete = false }, style = ButtonStyle.Ghost) },
    )
}

/** SOUL.md as text: who the agent is and its rules. The agent reads it at every wake. */
@Composable
fun SoulScreen(agentId: String, onBack: () -> Unit) {
    val container = LocalContext.current.container
    val p = agent(agentId) ?: return Gone(onBack)
    var text by remember(agentId) { mutableStateOf<String?>(null) }
    LaunchedEffect(agentId) { text = container.agents.store.soul(agentId) }
    var saved by remember { mutableStateOf(false) }
    PorcelainPage(stringResource(R.string.ag_soul_of, p.name), onBack) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ag_soul_body), fontSize = 13.sp, lineHeight = 19.sp, color = P.sub)
            text?.let { t -> PField(t, { text = it; saved = false }, minLines = 10) }
            if (saved) Callout(stringResource(R.string.ag_saved), Tone.Good)
            PButton(stringResource(R.string.action_save), { text?.let { container.agents.store.setSoul(agentId, it) }; saved = true }, Modifier.fillMaxWidth(), enabled = text != null)
        }
    }
}

/**
 * MEMORY.md (Figma 11.4): every entry with its date and source, newest first; search, change or delete one, forget a
 * stretch of time, or edit the file as it is.
 */
@Composable
fun MemoryScreen(agentId: String, onBack: () -> Unit) {
    val container = LocalContext.current.container
    val p = agent(agentId) ?: return Gone(onBack)
    val memory = container.agents.store.memory(agentId)
    var version by remember { mutableIntStateOf(0) }
    val entries by produceState(emptyList<Memory.Entry>(), agentId, version) { while (true) { value = memory.entries(); delay(3_000) } }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Memory.Entry?>(null) }
    var forgetting by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf(false) }
    val shown = entries.asReversed().filter { query.isBlank() || it.text.contains(query, true) || it.source.contains(query, true) }
    PorcelainPage(stringResource(R.string.ag_memory_of, p.name), onBack) {
        if (raw) {
            var text by remember { mutableStateOf(memory.raw()) }
            Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PField(text, { text = it }, minLines = 14, mono = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton(stringResource(R.string.action_cancel), { raw = false }, Modifier.weight(1f), style = ButtonStyle.Ghost)
                    PButton(stringResource(R.string.action_save), { memory.setRaw(text); raw = false; version++ }, Modifier.weight(1f))
                }
            }
            return@PorcelainPage
        }
        PageColumn {
            PField(query, { query = it }, placeholder = stringResource(R.string.ag_memory_search), singleLine = true)
            if (shown.isEmpty()) Text(stringResource(if (entries.isEmpty()) R.string.ag_memory_empty else R.string.ag_memory_none), fontSize = 14.sp, color = P.muted, modifier = Modifier.padding(8.dp))
            else RowGroup {
                shown.forEach { e -> MemoryRow(e, onEdit = { editing = e }, onDelete = { memory.removeAt(e.index); version++ }) }
            }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.ag_memory_add), false, PIcons.plus) { adding = true }
                Pill(stringResource(R.string.ag_memory_forget), false, PIcons.history) { forgetting = true }
                Pill(stringResource(R.string.ag_memory_raw), false, PIcons.file) { raw = true }
            }
        }
    }
    if (adding || editing != null) {
        var text by remember(editing) { mutableStateOf(editing?.text.orEmpty()) }
        val you = stringResource(R.string.ag_source_you)
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(stringResource(if (editing != null) R.string.ag_memory_edit else R.string.ag_memory_add), fontFamily = P.serif, fontSize = 22.sp, color = P.ink) },
            text = { PField(text, { text = it }, minLines = 3) },
            confirmButton = {
                PButton(stringResource(R.string.action_save), {
                    val e = editing
                    if (e != null) memory.edit(e.text, text) else if (text.isNotBlank()) memory.add(text, you)
                    adding = false; editing = null; version++
                }, enabled = text.isNotBlank())
            },
            dismissButton = { PButton(stringResource(R.string.action_cancel), { adding = false; editing = null }, style = ButtonStyle.Ghost) },
        )
    }
    if (forgetting) {
        var from by remember { mutableStateOf(LocalDate.now().minusDays(7).toString()) }
        var to by remember { mutableStateOf(LocalDate.now().toString()) }
        val valid = runCatching { LocalDate.parse(from) <= LocalDate.parse(to) }.getOrDefault(false)
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text(stringResource(R.string.ag_memory_forget), fontFamily = P.serif, fontSize = 22.sp, color = P.ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.ag_memory_forget_body), fontSize = 13.sp, color = P.sub)
                    PField(from, { from = it.trim() }, label = stringResource(R.string.ag_from_date), singleLine = true, mono = true)
                    PField(to, { to = it.trim() }, label = stringResource(R.string.ag_to_date), singleLine = true, mono = true)
                }
            },
            confirmButton = { PButton(stringResource(R.string.ag_forget), { memory.forgetBetween(LocalDate.parse(from), LocalDate.parse(to)); forgetting = false; version++ }, enabled = valid, style = ButtonStyle.Danger) },
            dismissButton = { PButton(stringResource(R.string.action_cancel), { forgetting = false }, style = ButtonStyle.Ghost) },
        )
    }
}

@Composable
private fun MemoryRow(e: Memory.Entry, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val date = runCatching { LocalDate.parse(e.date).format(DateTimeFormatter.ofPattern("M/d", locale)) }.getOrDefault(e.date)
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.text, fontSize = 15.sp, color = P.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("$date · ${e.source}", fontSize = 12.sp, color = P.sub)
        }
        Box {
            PIconButton(PIcons.more, stringResource(R.string.ag_more), { menu = true }, size = 36.dp, tint = P.sub)
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.ag_memory_edit)) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text(stringResource(R.string.ag_forget), color = P.danger) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/**
 * Timers and the proactive queue (Figma 11.5): the heartbeat and the timers the agent set, the "when … then …" rules,
 * and what the user queued for it. A due timer or a matching rule wakes the agent at once.
 */
@Composable
fun TimersScreen(agentId: String, onBack: () -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val p = agent(agentId) ?: return Gone(onBack)
    var version by remember { mutableIntStateOf(0) }
    val timers by produceState(emptyList<AgentTimer>(), agentId, version) { while (true) { value = container.agents.store.timers(agentId).sortedBy { it.nextMs }; delay(3_000) } }
    val rules by produceState(emptyList<AgentRule>(), agentId, version) { while (true) { value = container.agents.store.rules(agentId); delay(3_000) } }
    val queue by produceState(emptyList<QueueItem>(), agentId, version) { while (true) { value = container.agents.store.queue(agentId).filter { !it.done }; delay(3_000) } }
    val state by produceState(AgentState(), agentId) { while (true) { value = container.agents.store.state(agentId); delay(5_000) } }
    var menu by remember { mutableStateOf(false) }
    var addRule by remember { mutableStateOf(false) }
    var addTimer by remember { mutableStateOf(false) }
    var addQueue by remember { mutableStateOf(false) }
    val interval = container.agents.intervalMs(p)
    PorcelainPage(stringResource(R.string.ag_timers), onBack, actions = {
        Box {
            PIconButton(PIcons.plus, stringResource(R.string.ag_add), { menu = true }, size = 36.dp)
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.ag_add_timer)) }, onClick = { menu = false; addTimer = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.ag_add_rule)) }, onClick = { menu = false; addRule = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.ag_add_queue)) }, onClick = { menu = false; addQueue = true })
            }
        }
    }) {
        PageColumn {
            RowGroup(stringResource(R.string.ag_timers_agent)) {
                if (p.running && interval != null && state.nextWakeMs > 0) TimerRow(
                    DateUtils.formatDateTime(context, maxOf(state.lastWakeMs + interval, System.currentTimeMillis()), DateUtils.FORMAT_SHOW_TIME) + " " + stringResource(R.string.ag_heartbeat_note),
                    stringResource(R.string.ag_heartbeat_sub, intervalText((interval / 60_000).toInt())), null,
                )
                timers.forEach { t ->
                    TimerRow(
                        whenText(context, t), timerSub(t, p.name),
                        onDelete = { container.agents.store.setTimers(agentId, container.agents.store.timers(agentId).filterNot { it.id == t.id }); container.agents.reschedule(); version++ },
                    )
                }
                if (timers.isEmpty() && (!p.running || interval == null)) Text(stringResource(R.string.ag_timers_none), Modifier.padding(16.dp), fontSize = 14.sp, color = P.muted)
            }
            RowGroup(stringResource(R.string.ag_rules)) {
                if (rules.isEmpty()) Text(stringResource(R.string.ag_rules_none), Modifier.padding(16.dp), fontSize = 14.sp, color = P.muted)
                rules.forEach { r ->
                    TimerRow(
                        ruleText(r), r.whenSource?.let { channelName(it) } ?: stringResource(R.string.ag_rule_any), icon = PIcons.zap,
                        onDelete = { container.agents.store.setRules(agentId, container.agents.store.rules(agentId).filterNot { it.id == r.id }); version++ },
                    )
                }
            }
            RowGroup(stringResource(R.string.ag_queue)) {
                if (queue.isEmpty()) Text(stringResource(R.string.ag_queue_none), Modifier.padding(16.dp), fontSize = 14.sp, color = P.muted)
                queue.forEach { q ->
                    TimerRow(
                        q.text, stringResource(R.string.ag_queue_sub, p.name), icon = PIcons.menu2, value = stringResource(R.string.ag_queued),
                        onDelete = { container.agents.store.setQueue(agentId, container.agents.store.queue(agentId).filterNot { it.id == q.id }); version++ },
                    )
                }
            }
            PButton(stringResource(R.string.ag_add_rule), { addRule = true }, Modifier.fillMaxWidth(), style = ButtonStyle.Alt)
        }
    }
    if (addRule) RuleDialog(onDismiss = { addRule = false }) { source, text, then ->
        val now = System.currentTimeMillis()
        container.agents.store.setRules(agentId, container.agents.store.rules(agentId) + AgentRule("r" + now.toString(36), source, text?.ifBlank { null }, then, now))
        addRule = false; version++
    }
    if (addTimer) TimerDialog(onDismiss = { addTimer = false }) { note, at, cron ->
        val now = System.currentTimeMillis()
        container.agents.store.setTimers(agentId, container.agents.store.timers(agentId) + AgentTimer("t" + now.toString(36), note, at, cron, "user", now))
        container.agents.reschedule()
        addTimer = false; version++
    }
    if (addQueue) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addQueue = false },
            title = { Text(stringResource(R.string.ag_add_queue), fontFamily = P.serif, fontSize = 22.sp, color = P.ink) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(stringResource(R.string.ag_queue_body, p.name), fontSize = 13.sp, color = P.sub); PField(text, { text = it }, minLines = 2) } },
            confirmButton = {
                PButton(stringResource(R.string.ag_add), {
                    val now = System.currentTimeMillis()
                    container.agents.store.setQueue(agentId, container.agents.store.queue(agentId) + QueueItem("q" + now.toString(36), text.trim(), now))
                    addQueue = false; version++
                }, enabled = text.isNotBlank())
            },
            dismissButton = { PButton(stringResource(R.string.action_cancel), { addQueue = false }, style = ButtonStyle.Ghost) },
        )
    }
}

@Composable
private fun TimerRow(title: String, sub: String, onDelete: (() -> Unit)?, icon: app.umbra.agent.ui.porcelain.IconSpec = PIcons.clock, value: String? = null) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PIcon(icon, size = 22.dp, tint = P.sub)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) Text(value, fontSize = 13.sp, color = P.sub)
        if (onDelete != null) Box {
            PIconButton(PIcons.more, stringResource(R.string.ag_more), { menu = true }, size = 36.dp, tint = P.sub)
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.ag_remove), color = P.danger) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

private fun whenText(context: android.content.Context, t: AgentTimer): String {
    val cron = t.cron
    val time = DateUtils.formatDateTime(context, t.nextMs, DateUtils.FORMAT_SHOW_TIME)
    val day = when {
        cron != null && cron.split(' ').let { f -> f.size == 5 && f[2] == "*" && f[3] == "*" && f[4] == "*" } -> context.getString(R.string.ag_every_day)
        DateUtils.isToday(t.nextMs) -> ""
        DateUtils.isToday(t.nextMs - DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.ag_tomorrow)
        else -> DateUtils.formatDateTime(context, t.nextMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
    }
    return listOf(day, time, t.note).filter { it.isNotBlank() }.joinToString(" ")
}

@Composable
private fun timerSub(t: AgentTimer, agentName: String): String {
    val context = LocalContext.current
    val who = if (t.setBy == "user") stringResource(R.string.ag_source_you) else agentName
    val set = DateUtils.formatDateTime(context, t.createdAtMs, DateUtils.FORMAT_SHOW_TIME)
    return if (t.cron != null) "cron ${t.cron}" else stringResource(R.string.ag_timer_once, who, set)
}

@Composable
private fun ruleText(r: AgentRule): String {
    val cause = listOfNotNull(r.whenSource?.let { channelName(it) }, r.whenText?.let { "“$it”" }).joinToString(" ").ifEmpty { stringResource(R.string.ag_rule_any) }
    return "$cause → ${r.then}"
}

/** A rule: when something from [source] (or anything) mentions [text], wake the agent with what to do. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleDialog(onDismiss: () -> Unit, onAdd: (source: String?, text: String?, then: String) -> Unit) {
    var source by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var then by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ag_add_rule), fontFamily = P.serif, fontSize = 22.sp, color = P.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label(stringResource(R.string.ag_rule_when))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(stringResource(R.string.ag_rule_any), source == null) { source = null }
                    (Channels.apps.map { it.id } + Channels.NOTIFICATIONS).forEach { id -> Pill(channelName(id), source == id) { source = id } }
                }
                PField(text, { text = it }, label = stringResource(R.string.ag_rule_contains), singleLine = true)
                PField(then, { then = it }, label = stringResource(R.string.ag_rule_then), minLines = 2)
            }
        },
        confirmButton = { PButton(stringResource(R.string.ag_add), { onAdd(source, text.trim(), then.trim()) }, enabled = then.isNotBlank()) },
        dismissButton = { PButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Ghost) },
    )
}

/** A timer the user sets for the agent: once at a time today or later, or repeating (cron). */
@Composable
private fun TimerDialog(onDismiss: () -> Unit, onAdd: (note: String, at: Long, cron: String?) -> Unit) {
    val context = LocalContext.current
    var note by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("09:00") }
    var daily by remember { mutableStateOf(false) }
    val parsed = Regex("""^(\d{1,2}):(\d{2})$""").find(time.trim())?.let { m -> m.groupValues[1].toInt().takeIf { it < 24 }?.let { h -> m.groupValues[2].toInt().takeIf { it < 60 }?.let { h to it } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ag_add_timer), fontFamily = P.serif, fontSize = 22.sp, color = P.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PField(note, { note = it }, label = stringResource(R.string.ag_timer_note), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PField(time, { time = it }, Modifier.weight(1f), label = stringResource(R.string.ag_timer_time), singleLine = true, mono = true)
                    PButton(stringResource(R.string.ag_pick), {
                        android.app.TimePickerDialog(context, { _, h, m -> time = "%02d:%02d".format(h, m) }, parsed?.first ?: 9, parsed?.second ?: 0, android.text.format.DateFormat.is24HourFormat(context)).show()
                    }, tiny = true, style = ButtonStyle.Alt)
                }
                SwitchRow(null, stringResource(R.string.ag_every_day), null, daily) { daily = it }
            }
        },
        confirmButton = {
            PButton(stringResource(R.string.ag_add), {
                val (h, m) = parsed!!
                val cron = if (daily) "$m $h * * *" else null
                val at = if (cron != null) Cron(cron).next(System.currentTimeMillis()) else {
                    val zone = java.time.ZoneId.systemDefault()
                    var t = java.time.LocalDate.now().atTime(h, m).atZone(zone)
                    if (t.toInstant().toEpochMilli() <= System.currentTimeMillis()) t = t.plusDays(1)
                    t.toInstant().toEpochMilli()
                }
                onAdd(note.trim(), at, cron)
            }, enabled = note.isNotBlank() && parsed != null)
        },
        dismissButton = { PButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Ghost) },
    )
}

/**
 * Wake and power (Figma 14.1): today's wakes, model runs and an estimate of the battery they took; the wake interval
 * (Power and Dev; Simple keeps ten minutes); the power savers; quiet hours; and the battery-optimization exemption that
 * keeps timers and the Telegram bot on time in Doze.
 */
@Composable
fun WakeScreen(agentId: String, onBack: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val container = LocalContext.current.container
    val context = LocalContext.current
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val p = agent(agentId) ?: return Gone(onBack)
    val state = polled(agentId, AgentState(), 5_000) { container.agents.store.state(agentId) }
    val today = state.day == LocalDate.now().toString()
    val percent = remember(state) { container.agents.estimatedBatteryPercent(if (today) state else AgentState()) }
    val exempt = polled(agentId, false, 2_000) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val simple = prefs.uiMode == UiMode.SIMPLE
    var quiet by remember { mutableStateOf(false) }
    PorcelainPage(stringResource(R.string.ag_wake_title, p.name), onBack) {
        PageColumn {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(pc(0xFFF6F8F2)).padding(vertical = 16.dp)) {
                Stat("${if (today) state.wakesToday else 0}", stringResource(R.string.ag_stat_wakes), Modifier.weight(1f))
                Stat("${if (today) state.runsToday else 0}", stringResource(R.string.ag_stat_runs), Modifier.weight(1f))
                Stat(String.format(locale, "%.1f%%", percent), stringResource(R.string.ag_stat_battery), Modifier.weight(1f))
            }
            Label(stringResource(R.string.ag_wake))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(pc(0xFFF6F8F2)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val minutes = if (simple) AgentProfile.DEFAULT_HEARTBEAT_MIN else p.heartbeatMinutes
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(intervalText(minutes), Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.ink)
                    Text(stringResource(R.string.ag_power_tag), Modifier.clip(RoundedCornerShape(10.dp)).background(P.base2).padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 11.sp, color = P.sub)
                }
                val index = INTERVALS.indexOf(minutes).takeIf { it >= 0 } ?: INTERVALS.indexOf(AgentProfile.DEFAULT_HEARTBEAT_MIN)
                Slider(
                    value = index.toFloat(), enabled = !simple,
                    onValueChange = { v -> container.agents.store.update(agentId) { it.copy(heartbeatMinutes = INTERVALS[v.toInt().coerceIn(0, INTERVALS.lastIndex)]) } },
                    onValueChangeFinished = { container.agents.reschedule() },
                    valueRange = 0f..INTERVALS.lastIndex.toFloat(), steps = INTERVALS.size - 2,
                    colors = SliderDefaults.colors(thumbColor = P.accent2, activeTrackColor = P.accent2, inactiveTrackColor = P.base2),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(5, 10, 30, 60, 360).forEach { Text(if (it < 60) stringResource(R.string.ag_min_short, it) else stringResource(R.string.ag_h_short, it / 60), fontSize = 11.sp, color = P.muted) }
                }
                if (simple) Text(stringResource(R.string.ag_wake_simple), fontSize = 12.sp, color = P.muted)
            }
            RowGroup(stringResource(R.string.ag_power_saving)) {
                SwitchRow(PIcons.battery, stringResource(R.string.ag_power_hot), stringResource(R.string.ag_power_hot_sub), prefs.agentSlowWhenHot) { on -> container.settings.update { it.copy(agentSlowWhenHot = on) }; container.agents.reschedule() }
                SwitchRow(PIcons.battery, stringResource(R.string.ag_power_low), stringResource(R.string.ag_power_low_sub), prefs.agentPauseLowBattery) { on -> container.settings.update { it.copy(agentPauseLowBattery = on) }; container.agents.reschedule() }
                NavRow(PIcons.moon, stringResource(R.string.ag_quiet), stringResource(R.string.ag_quiet_sub), value = quietText(p)) { quiet = true }
                NavRow(PIcons.zap, stringResource(R.string.ag_battery_opt), stringResource(R.string.ag_battery_opt_sub), value = stringResource(if (exempt) R.string.ag_allowed else R.string.ag_not_allowed)) {
                    val intent = if (!exempt) Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                    else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            Text(stringResource(R.string.ag_wake_flow), fontSize = 12.sp, lineHeight = 18.sp, color = P.muted, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
    if (quiet) QuietDialog(p, onDismiss = { quiet = false }) { start, end ->
        container.agents.store.update(agentId) { it.copy(quietStartMin = start, quietEndMin = end) }
        quiet = false
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = P.ink)
        Text(label, fontSize = 12.sp, color = P.sub)
    }
}

private fun hhmm(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

@Composable
private fun quietText(p: AgentProfile): String {
    val s = p.quietStartMin
    val e = p.quietEndMin
    return if (s == null || e == null) stringResource(R.string.ag_quiet_off) else "${hhmm(s).take(2)}–${hhmm(e).take(2)}"
}

@Composable
private fun QuietDialog(p: AgentProfile, onDismiss: () -> Unit, onSave: (Int?, Int?) -> Unit) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(p.quietStartMin != null && p.quietEndMin != null) }
    var start by remember { mutableIntStateOf(p.quietStartMin ?: 23 * 60) }
    var end by remember { mutableIntStateOf(p.quietEndMin ?: 7 * 60) }
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ag_quiet), fontFamily = P.serif, fontSize = 22.sp, color = P.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ag_quiet_sub), fontSize = 13.sp, color = P.sub)
                SwitchRow(null, stringResource(R.string.ag_quiet_on), null, on) { on = it }
                if (on) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton(stringResource(R.string.ag_quiet_from, hhmm(start)), { android.app.TimePickerDialog(context, { _, h, m -> start = h * 60 + m }, start / 60, start % 60, is24).show() }, Modifier.weight(1f), style = ButtonStyle.Alt, tiny = true)
                    PButton(stringResource(R.string.ag_quiet_to, hhmm(end)), { android.app.TimePickerDialog(context, { _, h, m -> end = h * 60 + m }, end / 60, end % 60, is24).show() }, Modifier.weight(1f), style = ButtonStyle.Alt, tiny = true)
                }
            }
        },
        confirmButton = { PButton(stringResource(R.string.action_save), { if (on) onSave(start, end) else onSave(null, null) }) },
        dismissButton = { PButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Ghost) },
    )
}

/** What the agent watches: the message apps (each set up under Message apps) and the phone's notifications. */
@Composable
fun ChannelsScreen(agentId: String, onBack: () -> Unit, onGo: (String) -> Unit) {
    val container = LocalContext.current.container
    val p = agent(agentId) ?: return Gone(onBack)
    PorcelainPage(stringResource(R.string.ag_sources), onBack) {
        PageColumn {
            Text(stringResource(R.string.ag_sources_body, p.name), fontSize = 13.sp, lineHeight = 19.sp, color = P.sub, modifier = Modifier.padding(horizontal = 6.dp))
            RowGroup {
                Channels.apps.forEach { app ->
                    SwitchRow(PIcons.message, channelName(app.id), container.connectors.summary(app.id), app.id in p.channels) { on ->
                        container.agents.store.update(agentId) { it.copy(channels = if (on) (it.channels + app.id).distinct() else it.channels - app.id) }
                    }
                }
                SwitchRow(PIcons.bell, stringResource(R.string.ag_ch_notifications), stringResource(R.string.ag_notifications_sub), Channels.NOTIFICATIONS in p.channels) { on ->
                    container.agents.store.update(agentId) { it.copy(channels = if (on) (it.channels + Channels.NOTIFICATIONS).distinct() else it.channels - Channels.NOTIFICATIONS) }
                }
            }
            RowGroup {
                NavRow(PIcons.users, stringResource(R.string.ag_message_apps), stringResource(R.string.ag_message_apps_sub)) { onGo("messages") }
                NavRow(PIcons.bell, stringResource(R.string.ag_notification_reader), stringResource(R.string.ag_notification_reader_sub)) { onGo("notifications") }
            }
        }
    }
}
