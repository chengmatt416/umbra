package app.umbra.agent.ui.current

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PObject
import app.umbra.agent.ui.porcelain.PSetting
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.SerifTitle
import app.umbra.agent.ui.porcelain.StepItem
import app.umbra.agent.ui.porcelain.StepState
import app.umbra.agent.ui.porcelain.Steps
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.core.orchestrator.ChatItem
import app.umbra.core.orchestrator.ConversationReducer
import app.umbra.core.orchestrator.ConversationState
import app.umbra.runtime.omp.OmpGoal
import app.umbra.runtime.omp.OmpModelInfo
import app.umbra.runtime.omp.OmpSessionStats
import app.umbra.runtime.omp.OmpSubagent
import app.umbra.runtime.omp.OmpTodoItem
import app.umbra.runtime.omp.OmpTodoPhase
import app.umbra.runtime.omp.OmpTree
import app.umbra.runtime.omp.OmpTreeNode
import app.umbra.runtime.omp.branch
import app.umbra.runtime.omp.cancelSubagent
import app.umbra.runtime.omp.compact
import app.umbra.runtime.omp.fork
import app.umbra.runtime.omp.handoff
import app.umbra.runtime.omp.setSessionName
import app.umbra.runtime.omp.goal
import app.umbra.runtime.omp.models
import app.umbra.runtime.omp.setAutoCompaction
import app.umbra.runtime.omp.setAutoRetry
import app.umbra.runtime.omp.setCacheWarming
import app.umbra.runtime.omp.setFastMode
import app.umbra.runtime.omp.setFollowUpMode
import app.umbra.runtime.omp.setInterruptMode
import app.umbra.runtime.omp.setSteeringMode
import app.umbra.runtime.omp.setTodos
import app.umbra.runtime.omp.steerSubagent
import app.umbra.runtime.omp.subagentMessages
import app.umbra.runtime.omp.subagents
import app.umbra.runtime.omp.switchModel
import app.umbra.runtime.omp.thinkingLevels
import app.umbra.runtime.omp.tree

/** Which sheet is open over the conversation. */
enum class Sheet { None, Model, Session, Tree, Todos, Subagents, Goal, Modes }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmpSheet(sheet: Sheet, conversation: ConversationState, ops: SessionOps, onClose: () -> Unit, onOpen: (Sheet) -> Unit, onComposer: (String) -> Unit) {
    if (sheet == Sheet.None) return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = Color(0xFFEEF2EA)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            when (sheet) {
                Sheet.Model -> ModelSheet(conversation, ops, onClose)
                Sheet.Session -> SessionSheet(conversation, ops, onOpen, onClose)
                Sheet.Tree -> TreeSheet(ops, onComposer, onClose)
                Sheet.Todos -> TodosSheet(conversation, ops)
                Sheet.Subagents -> SubagentsSheet(ops)
                Sheet.Goal -> GoalSheet(conversation, ops)
                Sheet.Modes -> ModesSheet(conversation, ops)
                Sheet.None -> Unit
            }
        }
    }
}

// ---- model and thinking ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelSheet(conversation: ConversationState, ops: SessionOps, onClose: () -> Unit) {
    var models by remember { mutableStateOf<List<OmpModelInfo>?>(null) }
    var levels by remember { mutableStateOf<List<String>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        ops.run("Reading the models") { c -> c.models() to c.thinkingLevels() }?.let { (m, l) -> models = m; levels = l }
    }
    val session = conversation.session
    Eyebrow("Model & thinking")
    SerifTitle(session?.model?.name ?: "No model", Modifier.padding(top = 6.dp), size = 24)
    session?.model?.let { Subtle("${it.provider} · ${it.contextWindow?.let { w -> "${w / 1000}k context" } ?: ""}${if (it.takesImages) " · reads pictures" else ""}") }
    Gap(12.dp)
    if (levels.isNotEmpty()) {
        Kicker("Thinking")
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            levels.forEach { l ->
                PButton(l, { ops.launch("Setting thinking") { it.setThinkingLevel(l) } }, style = if (session?.thinkingLevel == l) ButtonStyle.Primary else ButtonStyle.Alt, tiny = true)
            }
        }
        Gap(8.dp)
    }
    PSetting("Fast mode", "Priority service tier where the provider offers one (costs more).", divider = false) {
        Switch(session?.fastModeEnabled == true, { on -> ops.launch("Fast mode") { it.setFastMode(on) } })
    }
    Gap(8.dp)
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search models") }, singleLine = true)
    Gap(8.dp)
    val list = models
    if (list == null) Subtle("Reading the models omp can use…")
    else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val shown = list.filter { query.isBlank() || it.key.contains(query, true) || it.name.contains(query, true) }
        shown.groupBy { it.provider }.forEach { (provider, ms) ->
            item(key = "h-$provider") { Kicker(provider, Modifier.padding(top = 8.dp)) }
            items(ms, key = { it.key }) { m ->
                PObject(
                    m.name, listOfNotNull(m.id, m.contextWindow?.let { "${it / 1000}k" }, "reasoning".takeIf { m.reasoning }, "pictures".takeIf { m.takesImages }).joinToString(" · "),
                    selected = m.key == conversation.modelKey,
                    onClick = { ops.launch("Switching the model") { it.switchModel(m.provider, m.id) }; onClose() },
                )
            }
        }
        if (shown.isEmpty()) item { Subtle("No model matches. Add providers in Models & accounts.") }
    }
}

// ---- session actions ----

@Composable
private fun SessionSheet(conversation: ConversationState, ops: SessionOps, onOpen: (Sheet) -> Unit, onClose: () -> Unit) {
    var dialog by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<OmpSessionStats?>(null) }
    Eyebrow("Conversation")
    SerifTitle(conversation.title ?: "This session", Modifier.padding(top = 6.dp, bottom = 10.dp), size = 24)
    val rows: List<Triple<String, String, () -> Unit>> = listOf(
        Triple("Rename", "Name this conversation") { dialog = "rename" },
        Triple("Tree & branches", "Go back to an earlier message, or fork from it") { onOpen(Sheet.Tree) },
        Triple("Todos", "The agent's task list; edit it") { onOpen(Sheet.Todos) },
        Triple("Subagents", "Helpers the agent started: read, steer, cancel") { onOpen(Sheet.Subagents) },
        Triple("Goal", "Keep working toward an objective, with a budget") { onOpen(Sheet.Goal) },
        Triple("Compact…", "Summarise the conversation to free the context") { dialog = "compact" },
        Triple("Handoff…", "Write a handoff document and continue compacted") { dialog = "handoff" },
        Triple("Fork", "Copy this conversation into a new one and continue there") { ops.launch("Forking", reload = true) { it.fork() }; onClose() },
        Triple("New conversation", "Start over in this session (the old one stays in the tree)") { dialog = "new" },
        Triple("Export as HTML", "A standalone page of this conversation, to open or share") { ops.exportHtml(); onClose() },
        Triple("Copy the last answer", "To the clipboard") { ops.copyLastAnswer(); onClose() },
        Triple("Statistics", "Messages, tokens and cost") { ops.stats { stats = it } },
        Triple("Modes", "Steering, follow-ups, interrupts, compaction, retries, cache") { onOpen(Sheet.Modes) },
    )
    rows.forEach { (title, sub, action) ->
        PObject(title, sub, onClick = action)
        Gap(6.dp)
    }
    when (dialog) {
        "rename" -> TextDialog("Rename", conversation.title.orEmpty(), "Save", onDismiss = { dialog = null }) { name ->
            dialog = null
            ops.launch("Renaming") { it.setSessionName(name) }
        }
        "compact" -> TextDialog("Compact", "", "Compact", hint = "Optional: what the summary should keep", allowEmpty = true, onDismiss = { dialog = null }) { text ->
            dialog = null
            ops.launch("Compacting", reload = true) { it.compact(text.ifBlank { null }) }
            onClose()
        }
        "handoff" -> TextDialog("Handoff", "", "Write handoff", hint = "Optional: what the next agent should know", allowEmpty = true, onDismiss = { dialog = null }) { text ->
            dialog = null
            ops.launch("Writing the handoff", reload = true) { it.handoff(text.ifBlank { null }) }
            onClose()
        }
        "new" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Start a new conversation?") },
            text = { Text("The current one stays in this session's history.") },
            confirmButton = { TextButton(onClick = { dialog = null; ops.launch("Starting a new conversation", reload = true) { it.newSession() }; onClose() }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
    stats?.let { s ->
        AlertDialog(
            onDismissRequest = { stats = null },
            title = { Text("Statistics") },
            text = {
                Text(
                    "Messages: ${s.userMessages} from you, ${s.assistantMessages} from the agent, ${s.toolCalls} tool calls\n" +
                        "Tokens: ${s.inputTokens} in, ${s.outputTokens} out, ${s.cacheReadTokens} cache read, ${s.cacheWriteTokens} cache write\n" +
                        "Cost: $" + "%.4f".format(s.cost) + (s.contextUsage?.let { "\nContext: ${"%.0f".format(it.percent)}% of ${it.contextWindow / 1000}k" } ?: ""),
                )
            },
            confirmButton = { TextButton(onClick = { stats = null }) { Text("Close") } },
        )
    }
}

@Composable
fun TextDialog(title: String, initial: String, confirm: String, hint: String? = null, allowEmpty: Boolean = false, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = hint?.let { { Text(it) } }, minLines = if (allowEmpty) 3 else 1) },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }, enabled = allowEmpty || text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- tree ----

@Composable
private fun TreeSheet(ops: SessionOps, onComposer: (String) -> Unit, onClose: () -> Unit) {
    var tree by remember { mutableStateOf<OmpTree?>(null) }
    LaunchedEffect(Unit) { tree = ops.run("Reading the tree") { it.tree() } }
    Eyebrow("Session tree")
    SerifTitle("Branches", Modifier.padding(top = 6.dp), size = 24)
    Subtle("The highlighted path is the conversation now. Edit a message of yours to branch from it, or fork from it into a new conversation.", Modifier.padding(top = 4.dp, bottom = 10.dp))
    val t = tree
    if (t == null) { Subtle("Reading…"); return }
    val path = remember(t) { t.currentPath() }
    val rows = remember(t) {
        val out = ArrayList<Pair<OmpTreeNode, Int>>()
        fun walk(n: OmpTreeNode, depth: Int) {
            out += n to depth
            // A single child continues the same line; branches indent.
            n.children.forEach { walk(it, if (n.children.size > 1) depth + 1 else depth) }
        }
        t.roots.forEach { walk(it, 0) }
        out.filter { (n, _) -> n.type == "message" && (n.role == "user" || n.role == "assistant") || n.type == "compaction" || n.type == "branch_summary" }
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(rows, key = { it.first.id }) { (n, depth) ->
            val on = n.id in path
            Row(Modifier.fillMaxWidth().padding(start = (depth * 14).dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (on) P.accent2 else Color(0xFFC5D4C2)))
                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                    Text((if (n.role == "user") "You: " else if (n.role == "assistant") "Agent: " else "") + n.preview, fontSize = 13.sp, color = if (on) P.ink else P.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (n.role == "user") Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        PButton("Edit from here", {
                            ops.launch("Branching", reload = true, then = { text -> if (text != null) onComposer(text) }) { it.branch(n.id) }
                            onClose()
                        }, style = ButtonStyle.Ghost, tiny = true)
                        PButton("Fork from here", { ops.launch("Forking", reload = true) { it.fork(n.id) }; onClose() }, style = ButtonStyle.Ghost, tiny = true)
                    }
                }
            }
        }
    }
}

// ---- todos ----

@Composable
private fun TodosSheet(conversation: ConversationState, ops: SessionOps) {
    var phases by remember(conversation.session?.todos) { mutableStateOf(conversation.session?.todos.orEmpty()) }
    var adding by remember { mutableStateOf(false) }
    Eyebrow("Todos")
    SerifTitle("The agent's task list", Modifier.padding(top = 6.dp, bottom = 8.dp), size = 22)
    if (phases.isEmpty()) Subtle("No todos yet. The agent writes them as it plans; you can add one.")
    phases.forEachIndexed { pi, phase ->
        Kicker(phase.name, Modifier.padding(top = 10.dp))
        Steps(phase.tasks.map { StepItem(it.content, it.blocker ?: it.details, when (it.status) {
            "completed" -> StepState.Done
            "in_progress" -> StepState.Active
            "abandoned" -> StepState.Mute
            else -> StepState.Wait
        }) })
        phase.tasks.forEachIndexed { ti, task ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(task.content.take(28), Modifier.weight(1f), fontSize = 12.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOf("pending" to "Todo", "in_progress" to "Doing", "completed" to "Done").forEach { (status, label) ->
                    PButton(label, {
                        phases = phases.mapIndexed { i, p -> if (i != pi) p else p.copy(tasks = p.tasks.mapIndexed { j, t -> if (j == ti) t.copy(status = status) else t }) }
                    }, style = if (task.status == status) ButtonStyle.Primary else ButtonStyle.Ghost, tiny = true)
                }
                PButton("", { phases = phases.mapIndexed { i, p -> if (i != pi) p else p.copy(tasks = p.tasks.filterIndexed { j, _ -> j != ti }) } }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.close)
            }
        }
    }
    Gap(12.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PButton("Add a task", { adding = true }, style = ButtonStyle.Alt, tiny = true, icon = PIcons.plus)
        PButton("Save", {
            ops.launch("Saving todos", then = { _: List<OmpTodoPhase> -> ops.planEditedByUser(phases) }) { it.setTodos(phases) }
        }, tiny = true, enabled = phases != conversation.session?.todos.orEmpty())
    }
    if (adding) TextDialog("New task", "", "Add", onDismiss = { adding = false }) { text ->
        adding = false
        phases = if (phases.isEmpty()) listOf(OmpTodoPhase("Tasks", listOf(OmpTodoItem(text, "pending"))))
        else phases.dropLast(1) + phases.last().copy(tasks = phases.last().tasks + OmpTodoItem(text, "pending"))
    }
}

// ---- subagents ----

@Composable
private fun SubagentsSheet(ops: SessionOps) {
    var agents by remember { mutableStateOf<List<OmpSubagent>?>(null) }
    var open by remember { mutableStateOf<OmpSubagent?>(null) }
    var transcript by remember { mutableStateOf<List<ChatItem>>(emptyList()) }
    var steering by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { agents = ops.run("Reading subagents") { it.subagents() } }
    LaunchedEffect(open) {
        val a = open ?: return@LaunchedEffect
        val m = ops.run("Reading the transcript") { it.subagentMessages(subagentId = a.id) }
        transcript = m?.messages.orEmpty().mapIndexedNotNull { i, msg -> ConversationReducer.item(msg, "s$i") }
    }
    Eyebrow("Delegation")
    SerifTitle(open?.let { "${it.agent} #${it.index}" } ?: "Subagents", Modifier.padding(top = 6.dp, bottom = 8.dp), size = 22)
    val a = open
    if (a == null) {
        val list = agents
        if (list == null) Subtle("Reading…")
        else if (list.isEmpty()) Subtle("No subagents in this session.")
        else list.forEach { s ->
            PObject(s.description ?: s.task ?: s.agent, "${s.agent} · ${s.status}", PIcons.bot, onClick = { open = s }, trailing = { Tag(s.status, if (s.status == "running") Tone.Good else if (s.status == "failed") Tone.Danger else Tone.Neutral) })
            Gap(6.dp)
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PButton("Back", { open = null }, style = ButtonStyle.Alt, tiny = true)
            if (a.status == "running" || a.status == "pending") {
                PButton("Steer", { steering = true }, style = ButtonStyle.Alt, tiny = true)
                PButton("Cancel", { ops.launch("Cancelling") { it.cancelSubagent(a.id) }; open = null }, style = ButtonStyle.Danger, tiny = true)
            }
        }
        Gap(8.dp)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(transcript, key = { it.key }) { item ->
                when (item) {
                    is ChatItem.User -> Text("Task: " + item.text, fontSize = 13.sp, color = P.sub)
                    is ChatItem.Assistant -> Text(item.text.ifBlank { item.blocks.filterIsInstance<app.umbra.core.orchestrator.Block.ToolCall>().joinToString { "→ ${it.name}" } }, fontSize = 13.sp, color = P.ink)
                    is ChatItem.ToolResult -> Text("${item.toolName}: ${item.text.take(300)}", fontSize = 11.sp, fontFamily = P.mono, color = P.muted)
                    else -> Unit
                }
            }
        }
        if (steering) TextDialog("Steer ${a.agent}", "", "Send", onDismiss = { steering = false }) { text ->
            steering = false
            ops.launch("Steering") { it.steerSubagent(a.id, text) }
        }
    }
}

// ---- goal ----

@Composable
private fun GoalSheet(conversation: ConversationState, ops: SessionOps) {
    var goal by remember { mutableStateOf(conversation.session?.goal) }
    var creating by remember { mutableStateOf(false) }
    var budget by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { ops.run("Reading the goal") { it.goal("get") }.let { goal = it } }
    Eyebrow("Goal mode")
    val g: OmpGoal? = goal
    SerifTitle(g?.objective ?: "No goal", Modifier.padding(top = 6.dp, bottom = 6.dp), size = 22)
    if (g != null) {
        Subtle("${g.status} · ${g.tokensUsed} tokens · ${g.timeUsedSeconds / 60} min" + (g.tokenBudget?.let { " · budget $it" } ?: ""))
        Gap(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (g.status == "active") PButton("Pause", { ops.launch("Pausing the goal", then = { goal = it }) { it.goal("pause") } }, style = ButtonStyle.Alt, tiny = true)
            else PButton("Resume", { ops.launch("Resuming the goal", then = { goal = it }) { it.goal("resume") } }, tiny = true)
            PButton("Drop", { ops.launch("Dropping the goal") { it.goal("drop") }; goal = null }, style = ButtonStyle.Danger, tiny = true)
        }
    } else {
        Subtle("The agent keeps working toward a goal across turns until it is met, paused, or the budget runs out.")
        Gap(8.dp)
        OutlinedTextField(budget, { budget = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), placeholder = { Text("Token budget (optional)") }, singleLine = true)
        Gap(8.dp)
        PButton("Set a goal", { creating = true }, tiny = true)
    }
    if (creating) TextDialog("Goal", "", "Start", hint = "What should be true when it is done?", onDismiss = { creating = false }) { objective ->
        creating = false
        ops.launch("Setting the goal", then = { goal = it }) { it.goal("create", objective, budget.toLongOrNull()) }
    }
}

// ---- modes ----

@Composable
private fun ModesSheet(conversation: ConversationState, ops: SessionOps) {
    val s = conversation.session
    var cache by remember { mutableStateOf("idle") }
    Eyebrow("Modes")
    SerifTitle("How this session behaves", Modifier.padding(top = 6.dp, bottom = 8.dp), size = 22)
    PSetting("Steering", "Deliver steering messages all at once, or one at a time") {
        Segmented(listOf("all" to "All", "one-at-a-time" to "One"), s?.steeringMode ?: "all", { m -> ops.launch("Steering mode") { it.setSteeringMode(m) } })
    }
    PSetting("Follow-ups", "Queued messages: all at once, or one per turn") {
        Segmented(listOf("all" to "All", "one-at-a-time" to "One"), s?.followUpMode ?: "one-at-a-time", { m -> ops.launch("Follow-up mode") { it.setFollowUpMode(m) } })
    }
    PSetting("Interrupts", "Whether steering stops a running tool") {
        Segmented(listOf("immediate" to "Now", "wait" to "Wait"), s?.interruptMode ?: "immediate", { m -> ops.launch("Interrupt mode") { it.setInterruptMode(m) } })
    }
    PSetting("Auto-compaction", "Summarise automatically when the context fills up") {
        Switch(s?.autoCompaction ?: true, { on -> ops.launch("Auto-compaction") { it.setAutoCompaction(on) } })
    }
    PSetting("Auto-retry", "Retry provider errors that are worth retrying") {
        var on by remember { mutableStateOf(true) }
        Switch(on, { v -> on = v; ops.launch("Auto-retry") { it.setAutoRetry(v) } })
    }
    PSetting("Cache warming", "Keep the provider's prompt cache warm", divider = false) {
        Segmented(listOf("off" to "Off", "streaming" to "Working", "idle" to "Idle"), cache, { m -> cache = m; ops.launch("Cache warming") { it.setCacheWarming(m) } })
    }
}
