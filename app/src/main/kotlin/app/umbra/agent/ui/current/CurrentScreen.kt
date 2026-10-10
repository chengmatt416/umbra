package app.umbra.agent.ui.current

import app.umbra.runtime.omp.branch
import app.umbra.agent.ui.sessionsFlow
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.AppContainer
import app.umbra.agent.container
import app.umbra.agent.ui.ActivityCard
import app.umbra.agent.ui.AgentBubble
import app.umbra.agent.ui.ApprovalLogRow
import app.umbra.agent.ui.NoticeRow
import app.umbra.agent.ui.PlanCard
import app.umbra.agent.ui.StateDivider
import app.umbra.agent.ui.TimelineBuilder
import app.umbra.agent.ui.TimelineItem
import app.umbra.agent.ui.ToolRow
import app.umbra.agent.ui.UserBubble
import app.umbra.agent.ui.eventFlow
import app.umbra.agent.ui.inbox.ApprovalPanel
import app.umbra.agent.ui.inbox.QuestionPanel
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.FoldedOrb
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PIconButton
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.SerifTitle
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.sessions.sessionTone
import app.umbra.agent.ui.sessions.stateText
import app.umbra.agent.ui.shell.AppBar
import app.umbra.core.orchestrator.ChatItem
import app.umbra.core.orchestrator.ConversationEffect
import app.umbra.core.orchestrator.ConversationState
import app.umbra.core.session.SessionState
import app.umbra.runtime.omp.ContentBlock
import app.umbra.runtime.omp.OmpClient
import app.umbra.runtime.omp.OmpSessionStats
import app.umbra.runtime.omp.StreamingBehavior
import app.umbra.runtime.omp.abortBash
import app.umbra.runtime.omp.abortRetry
import app.umbra.runtime.omp.bash
import app.umbra.runtime.omp.branchMessages
import app.umbra.runtime.omp.exportHtml
import app.umbra.runtime.omp.fork
import app.umbra.runtime.omp.lastAssistantText
import app.umbra.runtime.omp.promoteQueuedMessage
import app.umbra.runtime.omp.removeQueuedMessage
import app.umbra.runtime.omp.sessionStats
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** Runs omp commands for one session and reports failures; what the GUI's controls call. */
class SessionOps(
    val sessionId: String,
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val context: Context,
    private val onError: (String) -> Unit,
    private val onInfo: (String) -> Unit,
) {
    suspend fun <T> run(label: String, block: suspend (OmpClient) -> T): T? = try {
        container.supervisor.omp(sessionId, block)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError("$label: ${e.message ?: e::class.simpleName}")
        null
    }

    fun <T> launch(label: String, reload: Boolean = false, then: (T) -> Unit = {}, block: suspend (OmpClient) -> T) {
        scope.launch {
            val result = run(label, block) ?: return@launch
            if (reload) container.supervisor.reloadConversation(sessionId)
            then(result)
        }
    }

    fun stats(onStats: (OmpSessionStats) -> Unit) = launch("Reading statistics", then = onStats) { it.sessionStats() }

    /** Exports the conversation into the session's artifacts folder and opens it. */
    fun exportHtml() = scope.launch {
        val dirs = try { container.workspaces.sessionDirs(sessionId) } catch (e: Exception) { onError("Export: ${e.message}"); return@launch }
        val host = dirs.artifacts.resolve("conversation-${System.currentTimeMillis()}.html")
        val guest = container.workspaceLayout.toGuest(host) ?: run { onError("Export: no guest path for the artifacts folder"); return@launch }
        val written = run("Exporting") { it.exportHtml(guest) } ?: return@launch
        val file = container.workspaceLayout.toHost(written)?.toFile()?.takeIf { it.exists() } ?: host.toFile()
        onInfo("Saved ${file.name} in this session's artifacts.")
        open(file, "text/html")
    }

    fun copyLastAnswer() = launch("Copying", then = { text: String ->
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("answer", text))
        onInfo("Copied.")
    }) { it.lastAssistantText().orEmpty() }

    fun open(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            onError("Nothing on this phone opens ${file.name} (${e.message}).")
        }
    }
}

private enum class View(val label: String) { Chat("Chat"), Activity("Activity") }

/**
 * Current: the focused session as omp shows it (ADR 0013) — the conversation with tools, thinking and pictures,
 * omp's commands, model, branches, todos, subagents and goal — and the session's activity timeline.
 */
@Composable
fun CurrentScreen(onMenu: () -> Unit, onOpenSession: (String) -> Unit, onSessions: () -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val focused by container.ui.focusedSession.collectAsStateWithLifecycle()
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val session = sessions.firstOrNull { it.id == focused }

    Column(Modifier.fillMaxSize()) {
        AppBar(onMenu)
        if (session == null) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Eyebrow("Current")
                SerifTitle("No session open", Modifier.padding(top = 8.dp), size = 26)
                Subtle("Open one from Sessions, or start new work on Home.", Modifier.padding(top = 8.dp, bottom = 14.dp))
                PButton("Sessions", onSessions, style = ButtonStyle.Alt)
            }
            return
        }
        SessionView(session, context, onOpenSession)
    }
}

@Composable
private fun SessionView(session: app.umbra.core.session.Session, context: Context, onOpenSession: (String) -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val id = session.id
    var error by remember(id) { mutableStateOf<String?>(null) }
    var info by remember(id) { mutableStateOf<String?>(null) }
    val ops = remember(id) { SessionOps(id, container, scope, context, onError = { error = it }, onInfo = { info = it }) }
    val conversationFlow by produceState<kotlinx.coroutines.flow.StateFlow<ConversationState>?>(null, id) { value = runCatching { container.supervisor.conversation(id) }.getOrNull() }
    val conversation = conversationFlow?.collectAsStateWithLifecycle()?.value ?: ConversationState()
    var composer by remember(id) { mutableStateOf("") }
    var view by remember(id) { mutableStateOf(View.Chat) }
    var sheet by remember(id) { mutableStateOf(Sheet.None) }
    var menu by remember { mutableStateOf(false) }

    // omp asks the client to open a sign-in page or prefill the composer.
    LaunchedEffect(id) {
        val effects = runCatching { container.supervisor.effects(id) }.getOrNull() ?: emptyFlow()
        effects.collect { e ->
            when (e) {
                is ConversationEffect.OpenUrl -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { error = "Open this link to continue: ${e.url}" }
                is ConversationEffect.SetComposer -> composer = e.text
            }
        }
    }

    val approvals by container.approvals.pending.collectAsStateWithLifecycle()
    val questions by container.questions.pending.collectAsStateWithLifecycle()
    val mine = approvals.filter { it.sessionId == id }
    val myQuestions = questions.filter { it.sessionId == id }
    val busy = session.state == SessionState.RUNNING || session.state == SessionState.WAITING_APPROVAL
    val project = session.projectId?.let { container.projects[it] }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Header: where this is, what state it is in, which model; controls for the session.
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Eyebrow(project?.let { "Session · ${it.name}" } ?: "Independent session")
            Row(verticalAlignment = Alignment.CenterVertically) {
                SerifTitle(conversation.title ?: session.title, Modifier.weight(1f).padding(top = 4.dp), size = 24)
                Box {
                    PIconButton(PIcons.more, "Session actions", { menu = true }, size = 36.dp)
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Conversation…") }, onClick = { menu = false; sheet = Sheet.Session })
                        if (busy) DropdownMenuItem(text = { Text("Pause") }, onClick = { menu = false; scope.launch { container.supervisor.pause(id) } })
                        if (session.state == SessionState.PAUSED) DropdownMenuItem(text = { Text("Resume") }, onClick = { menu = false; scope.launch { runCatching { container.supervisor.resume(id) }.onFailure { error = it.message } } })
                        if (!session.state.isTerminal) DropdownMenuItem(text = { Text("Stop the task") }, onClick = { menu = false; scope.launch { container.supervisor.stop(id) } })
                        DropdownMenuItem(text = { Text("Run again") }, onClick = { menu = false; scope.launch { runCatching { container.supervisor.duplicate(id) }.onSuccess { onOpenSession(it.id) }.onFailure { error = it.message } } })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; scope.launch { container.supervisor.delete(id); container.ui.focus(null) } })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(stateText(session.state), sessionTone(session.state))
                PButton(conversation.session?.model?.name ?: session.model?.modelId ?: "Model", { sheet = Sheet.Model }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.spark)
                conversation.session?.thinkingLevel?.let { PButton("Thinking: $it", { sheet = Sheet.Model }, style = ButtonStyle.Ghost, tiny = true) }
                conversation.session?.contextUsage?.let { Tag("Context ${"%.0f".format(it.percent)}%", if (it.percent > 85) Tone.Warn else Tone.Neutral) }
                conversation.session?.todos?.takeIf { it.isNotEmpty() }?.let { ph ->
                    val tasks = ph.flatMap { it.tasks }
                    PButton("Todos ${tasks.count { it.status == "completed" }}/${tasks.size}", { sheet = Sheet.Todos }, style = ButtonStyle.Ghost, tiny = true)
                }
                if (!conversation.live) Tag("Agent not running", Tone.Neutral)
            }
            Row(Modifier.padding(top = 8.dp)) { Segmented(View.entries.map { it to it.label }, view, { view = it }) }
        }
        error?.let { Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) { Callout(it, Tone.Danger) } }
        info?.let { Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) { Callout(it, Tone.Good) } }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (view) {
                View.Chat -> ChatList(session, conversation, mine, myQuestions, ops, onComposer = { composer = it })
                View.Activity -> ActivityList(id)
            }
        }
        if (view == View.Chat) {
            ChatComposer(
                conversation, composer, { composer = it }, busy = busy, paused = session.state == SessionState.PAUSED,
                actions = object : ComposerActions {
                    override fun send(text: String, images: List<ContentBlock.Image>, mode: WhileWorking) {
                        error = null
                        scope.launch {
                            try {
                                when {
                                    mode == WhileWorking.Interrupt -> container.supervisor.interrupt(id, text, images)
                                    mode == WhileWorking.Queue -> container.supervisor.send(id, text, images, StreamingBehavior.FOLLOW_UP)
                                    else -> container.supervisor.send(id, text, images, StreamingBehavior.STEER)
                                }
                            } catch (e: Exception) { error = e.message ?: e::class.simpleName }
                        }
                    }
                    override fun slash(text: String) = ops.launch(text.substringBefore(' ')) { it.prompt(text, streamingBehavior = if (conversation.working) StreamingBehavior.FOLLOW_UP else null) }
                    override fun bang(command: String) = ops.launch("! $command", reload = true) { it.bash(command) }
                    override fun stop() { scope.launch { container.supervisor.pause(id) } }
                    override fun removeQueued(text: String, queue: String) = ops.launch("Taking it back") { it.removeQueuedMessage(text, queue) }
                    override fun promoteQueued(text: String) = ops.launch("Sending it now") { it.promoteQueuedMessage(text) }
                },
            )
        }
    }
    OmpSheet(sheet, conversation, ops, onClose = { sheet = Sheet.None }, onOpen = { sheet = it }, onComposer = { composer = it })
}

@Composable
private fun ChatList(
    session: app.umbra.core.session.Session,
    conversation: ConversationState,
    approvals: List<app.umbra.core.safety.ApprovalRequest>,
    questions: List<app.umbra.core.orchestrator.QuestionBroker.Pending>,
    ops: SessionOps,
    onComposer: (String) -> Unit,
) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val list = rememberLazyListState()
    val items = conversation.items.filter { it !is ChatItem.ToolResult }
    val resultImages = remember(conversation.items) { conversation.items.filterIsInstance<ChatItem.ToolResult>().filter { it.images.isNotEmpty() }.associate { it.toolCallId to it.images } }
    val atBottom by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 2 } ?: true } }
    val streamedLength = (conversation.items.lastOrNull() as? ChatItem.Assistant)?.let { a -> a.blocks.sumOf { b -> (b as? app.umbra.core.orchestrator.Block.Text)?.text?.length ?: 0 } } ?: 0
    LaunchedEffect(items.size, approvals.size, questions.size, streamedLength / 80, conversation.tools.size) {
        if (atBottom && list.layoutInfo.totalItemsCount > 0) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    val actions = remember(session.id) {
        MessageActions(
            copy = { text -> context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("message", text)) },
            editFrom = { user -> branchAt(ops, user, conversation, fork = false, onComposer) },
            forkFrom = { user -> branchAt(ops, user, conversation, fork = true, onComposer) },
        )
    }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (items.isEmpty()) item(key = "empty") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                FoldedOrb(Modifier.fillMaxWidth().height(150.dp), working = conversation.working)
                Subtle(if (session.state == SessionState.CREATED) "This session has not started. Send a message." else "The conversation appears here once the agent has started.")
                if (session.request.isNotBlank() && session.state != SessionState.CREATED) Subtle("Request: ${session.request}", Modifier.padding(top = 6.dp))
            }
        }
        items(items, key = { it.key }) { item ->
            when (item) {
                is ChatItem.User -> UserMessage(item, actions)
                is ChatItem.Assistant -> AssistantMessage(item, conversation.tools, resultImages, actions)
                is ChatItem.Bash -> BashRun(item)
                is ChatItem.Summary -> SummaryMarker(item)
                is ChatItem.Custom -> CustomMessage(item)
                is ChatItem.Files -> FilesMention(item)
                is ChatItem.CommandOutput -> CommandOutputCard(item)
                is ChatItem.Notice -> NoticeLine(item)
                is ChatItem.ToolResult -> Unit
            }
        }
        conversation.retry?.let { r ->
            item(key = "retry") {
                Callout("The provider failed (${r.error}). Retrying, attempt ${r.attempt} of ${r.maxAttempts}.", Tone.Warn)
                Gap(6.dp)
                PButton("Stop retrying", { ops.launch("Stopping the retries") { it.abortRetry() } }, style = ButtonStyle.Alt, tiny = true)
            }
        }
        if (conversation.compacting) item(key = "compacting") { Callout("Compacting the conversation…", Tone.Neutral) }
        items(approvals, key = { it.id }) { r -> ApprovalPanel(r, null, onDecision = { container.approvals.resolve(r.id, it) }) }
        items(questions, key = { it.request.id }) { q -> QuestionPanel(q, null, onAnswer = { container.questions.answer(q.request.id, it) }) }
        if (session.state == SessionState.FAILED) item(key = "failed") { Callout(session.lastError ?: "The task failed.", Tone.Danger) }
    }
}

/** Edit-and-resend or fork from a user message: omp addresses entries by id, the n-th user message of the branch. */
private fun branchAt(ops: SessionOps, user: ChatItem.User, conversation: ConversationState, fork: Boolean, onComposer: (String) -> Unit) {
    val users = conversation.transcript.filterIsInstance<ChatItem.User>()
    val index = users.indexOfFirst { it.key == user.key }
    ops.launch(if (fork) "Forking" else "Branching", reload = true, then = { text: String? -> if (!fork && text != null) onComposer(text) }) { client ->
        val points = client.branchMessages()
        // Match by position among the user messages of this branch; fall back to the text.
        val entry = points.getOrNull(index)?.takeIf { it.second.trim() == user.text.trim() } ?: points.lastOrNull { it.second.trim() == user.text.trim() }
            ?: error("that message is not on the current branch")
        if (fork) { client.fork(entry.first); null } else client.branch(entry.first)
    }
}

/** The session's activity timeline (what the safety system and the tools recorded). */
@Composable
private fun ActivityList(sessionId: String) {
    val container = LocalContext.current.container
    val records by remember(sessionId) { container.sessionStore.eventFlow(sessionId) }.collectAsStateWithLifecycle(emptyList())
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val session = sessions.firstOrNull { it.id == sessionId }
    val items = remember(records) { TimelineBuilder.build(records) }
    val plan = remember(records) { TimelineBuilder.latestPlan(records) ?: session?.plan.orEmpty() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (plan.isNotEmpty()) item(key = "plan") { PlanCard(plan) }
        session?.let { s -> item(key = "activity") { ActivityCard(s) } }
        items(items, key = { it.key }) { item ->
            when (item) {
                is TimelineItem.User -> UserBubble(item.text, item.imageCount)
                is TimelineItem.Agent -> AgentBubble(item.text)
                is TimelineItem.Tool -> ToolRow(item)
                is TimelineItem.Approval -> ApprovalLogRow(item)
                is TimelineItem.StateChange -> StateDivider(item.to, item.reason)
                is TimelineItem.Notice -> NoticeRow(item.level, item.title, item.text)
                is TimelineItem.Subagent -> NoticeRow("info", "Helper ${item.id}: ${item.kind}", item.summary.orEmpty())
                is TimelineItem.Decision -> NoticeRow("info", "Quick decision (${item.engine ?: "local"})", "${item.question} → ${item.answer}")
            }
        }
    }
}
