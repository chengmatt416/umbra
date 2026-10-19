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
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.AppContainer
import androidx.annotation.StringRes
import app.umbra.agent.R
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
    /** A string of the user's language, for work that runs outside composition. */
    fun string(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

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

    /** A thinking level chosen by hand: kept on the session (a restart starts with it), not only in the live process. */
    fun setEffort(level: String) = scope.launch {
        runCatching { container.supervisor.setEffort(sessionId, app.umbra.core.session.Effort(level)) }
            .onFailure { onError("Setting thinking: ${it.message ?: it::class.simpleName}") }
    }

    fun stats(onStats: (OmpSessionStats) -> Unit) = launch("Reading statistics", then = onStats) { it.sessionStats() }

    /** The user changed the plan: that is a new plan revision with the user as its source (§4.3). */
    fun planEditedByUser(phases: List<app.umbra.runtime.omp.OmpTodoPhase>) {
        container.plans.observe(sessionId, phases.map { p -> app.umbra.core.control.PlanPhase(p.name, p.tasks.map { app.umbra.core.control.PlanTask(it.content, it.status) }) }, "user")
    }

    /** Exports the conversation into the session's artifacts folder and opens it. */
    fun exportHtml() = scope.launch {
        val dirs = try { container.workspaces.sessionDirs(sessionId) } catch (e: Exception) { onError(context.getString(R.string.ops_export_failed, e.message.orEmpty())); return@launch }
        val host = dirs.artifacts.resolve("conversation-${System.currentTimeMillis()}.html")
        val guest = container.workspaceLayout.toGuest(host) ?: run { onError(context.getString(R.string.ops_export_no_path)); return@launch }
        val written = run("Exporting") { it.exportHtml(guest) } ?: return@launch
        val file = container.workspaceLayout.toHost(written)?.toFile()?.takeIf { it.exists() } ?: host.toFile()
        onInfo(context.getString(R.string.ops_saved_export, file.name))
        open(file, "text/html")
    }

    fun copyLastAnswer() = launch("Copying", then = { text: String ->
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("answer", text))
        onInfo(context.getString(R.string.ops_copied))
    }) { it.lastAssistantText().orEmpty() }

    fun open(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            onError(context.getString(R.string.ops_no_opener, file.name, e.message.orEmpty()))
        }
    }
}

private enum class View(@param:androidx.annotation.StringRes val label: Int) { Chat(R.string.view_chat), Activity(R.string.r_task_view_steps), Terminal(R.string.view_terminal), Changes(R.string.r_task_view_changes) }

/**
 * Current: the focused session as omp shows it (ADR 0013) — the conversation with tools, thinking and pictures,
 * omp's commands, model, branches, todos, subagents and goal — and the session's activity timeline.
 */
@Composable
fun CurrentScreen(onBack: () -> Unit, onOpenSession: (String) -> Unit, onAccounts: () -> Unit, onCommit: () -> Unit = {}) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val focused by container.ui.focusedSession.collectAsStateWithLifecycle()
    // null until the store has been read: an empty list then would wrongly say that no session is open.
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(null)
    val session = sessions?.firstOrNull { it.id == focused }

    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (session == null && focused != null && sessions == null) return@Column
        if (session == null) {
            Box(Modifier.padding(start = 12.dp, top = 10.dp)) { PIconButton(PIcons.chevronLeft, stringResource(R.string.action_back), onBack, size = 36.dp) }
            app.umbra.agent.ui.porcelain.PEmpty(stringResource(R.string.cur_none_title), stringResource(R.string.cur_none_body), icon = PIcons.activity)
            return
        }
        SessionView(session, context, onOpenSession, onBack, onCommit)
    }
}

@Composable
private fun SessionView(session: app.umbra.core.session.Session, context: Context, onOpenSession: (String) -> Unit, onBack: () -> Unit, onCommit: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val id = session.id
    var error by remember(id) { mutableStateOf<String?>(null) }
    var info by remember(id) { mutableStateOf<String?>(null) }
    val ops = remember(id) { SessionOps(id, container, scope, context, onError = { error = it }, onInfo = { info = it }) }
    val conversationFlow by produceState<kotlinx.coroutines.flow.StateFlow<ConversationState>?>(null, id) { value = runCatching { container.supervisor.conversation(id) }.getOrNull() }
    val conversation = conversationFlow?.collectAsStateWithLifecycle()?.value ?: ConversationState()
    var composer by remember(id) { mutableStateOf("") }
    var chosenView by remember(id) { mutableStateOf(View.Chat) }
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    // Simple shows the conversation only; Power and Dev the four views.
    val simple = prefs.uiMode == app.umbra.agent.data.UiMode.SIMPLE
    val view = if (simple) View.Chat else chosenView
    var expanded by remember(id) { mutableStateOf(false) }
    var sheet by remember(id) { mutableStateOf(Sheet.None) }
    var effortSheet by remember(id) { mutableStateOf(false) }
    var directory by remember(id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var contractDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var titleOpen by remember(id) { mutableStateOf(false) }

    if (directory) SessionsDirectorySheet(current = id, onOpenSession = onOpenSession, onDismiss = { directory = false })
    if (effortSheet) {
        val live = conversation.session?.model
        app.umbra.agent.ui.models.EffortPicker(
            current = session.effort,
            supported = live?.efforts.orEmpty(),
            modelName = live?.name,
            modelIsAuto = false,
            onChoose = { e -> scope.launch { runCatching { container.supervisor.setEffort(id, e) }.onFailure { error = it.message } } },
            onDismiss = { effortSheet = false },
        )
    }

    // omp asks the client to open a sign-in page or prefill the composer.
    LaunchedEffect(id) {
        val effects = runCatching { container.supervisor.effects(id) }.getOrNull() ?: emptyFlow()
        effects.collect { e ->
            when (e) {
                is ConversationEffect.OpenUrl -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { error = context.getString(R.string.cur_open_link, e.url) }
                is ConversationEffect.SetComposer -> composer = e.text
            }
        }
    }

    val approvals by container.approvals.pending.collectAsStateWithLifecycle()
    val questions by container.questions.pending.collectAsStateWithLifecycle()
    // "Agent not running" is said of an unfinished session whose agent has stayed away for a moment, not while the
    // page is still attaching to it (finished ones say so in their state already).
    val showNotRunning by produceState(false, id, conversationFlow != null, conversation.live, session.state.isTerminal) {
        value = false
        if (conversationFlow != null && !conversation.live && !session.state.isTerminal) {
            kotlinx.coroutines.delay(2_500)
            value = true
        }
    }
    val mine = approvals.filter { it.sessionId == id }
    val myQuestions = questions.filter { it.sessionId == id }
    val busy = session.state == SessionState.RUNNING || session.state == SessionState.WAITING_APPROVAL
    val project = session.projectId?.let { container.projects[it] }

    // Typing in the session's terminal: the header keeps only the title and the views, so the keyboard does not
    // squeeze the terminal away.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val typingInTerminal = view == View.Terminal && androidx.compose.foundation.layout.WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize().imePadding()) {
        // Header: where this is and what state it is in (one line), the title (one line, tap for all of it), the
        // session's facts in a row that fades where it continues, then the views.
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            if (!typingInTerminal && !simple) Row(Modifier.padding(start = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(project?.let { stringResource(R.string.cur_eyebrow_project, it.name) } ?: stringResource(R.string.cur_eyebrow_independent), Modifier.weight(1f))
                Tag(stateText(session), sessionTone(session))
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PIconButton(PIcons.chevronLeft, stringResource(R.string.action_back), onBack, size = 36.dp)
                Box(Modifier.padding(start = 8.dp))
                Text(
                    conversation.title ?: session.title,
                    Modifier.weight(1f).clickable(role = androidx.compose.ui.semantics.Role.Button) { titleOpen = !titleOpen }.animateContentSize(),
                    fontFamily = P.serif, fontSize = 21.sp, lineHeight = 25.sp, color = P.ink, letterSpacing = (-0.3).sp,
                    maxLines = if (titleOpen) 5 else 1, overflow = TextOverflow.Ellipsis,
                )
                Box {
                    PIconButton(PIcons.more, stringResource(R.string.cur_actions), { menu = true }, size = 36.dp)
                    DropdownMenu(menu, { menu = false }) {
                        MenuItem(stringResource(R.string.cur_menu_conversation)) { menu = false; sheet = Sheet.Session }
                        MenuItem(stringResource(R.string.ses_menu_directory)) { menu = false; directory = true }
                        MenuItem(stringResource(R.string.cur_menu_contract)) { menu = false; contractDialog = true }
                        MenuItem(stringResource(R.string.cur_menu_tui)) {
                            menu = false
                            scope.launch {
                                try {
                                    container.ompTui.open(id, conversation.title ?: session.title)
                                    chosenView = View.Terminal
                                    info = context.getString(R.string.cur_tui_opened)
                                } catch (e: Exception) { error = e.message ?: e::class.simpleName }
                            }
                        }
                        androidx.compose.material3.HorizontalDivider(color = P.line2)
                        if (busy) MenuItem(stringResource(R.string.cur_menu_pause), stringResource(R.string.cur_menu_pause_detail)) { menu = false; scope.launch { container.supervisor.pause(id) } }
                        if (session.state == SessionState.PAUSED) MenuItem(stringResource(R.string.cur_menu_resume)) { menu = false; scope.launch { runCatching { container.supervisor.resume(id) }.onFailure { error = it.message } } }
                        if (!session.state.isTerminal) MenuItem(stringResource(R.string.cur_menu_stop), stringResource(R.string.cur_menu_stop_detail)) { menu = false; scope.launch { container.supervisor.stop(id) } }
                        MenuItem(stringResource(R.string.cur_menu_run_again)) { menu = false; scope.launch { runCatching { container.supervisor.duplicate(id) }.onSuccess { onOpenSession(it.id) }.onFailure { error = it.message } } }
                        androidx.compose.material3.HorizontalDivider(color = P.line2)
                        MenuItem(stringResource(R.string.cur_menu_delete), danger = true) { menu = false; confirmDelete = true }
                    }
                }
            }
            if (!typingInTerminal && !simple) app.umbra.agent.ui.porcelain.FadingRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                PButton(conversation.session?.model?.name ?: session.model?.modelId ?: stringResource(R.string.cur_model), { sheet = Sheet.Model }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.spark)
                // How hard it thinks: the session's own effort ("Auto → High"), else what omp reports.
                (session.effort?.let { app.umbra.agent.ui.models.sessionEffortLabel(it) } ?: conversation.session?.thinkingLevel?.let { app.umbra.agent.ui.models.effortName(it) })?.let {
                    PButton(stringResource(R.string.r_effort_chip, it), { effortSheet = true }, style = ButtonStyle.Ghost, tiny = true)
                }
                conversation.session?.contextUsage?.let { Tag(stringResource(R.string.cur_context, it.percent.toInt()), if (it.percent > 85) Tone.Warn else Tone.Neutral) }
                conversation.session?.todos?.takeIf { it.isNotEmpty() }?.let { ph ->
                    val tasks = ph.flatMap { it.tasks }
                    PButton(stringResource(R.string.cur_todos, tasks.count { it.status == "completed" }, tasks.size), { sheet = Sheet.Todos }, style = ButtonStyle.Ghost, tiny = true)
                }
                if (showNotRunning) Tag(stringResource(R.string.cur_not_running), Tone.Neutral)
            }
            if (!typingInTerminal) {
                ParentLink(session, onOpenSession)
                SubagentStrip(session, onOpenSession)
            }
            if (!simple) Segmented(View.entries.map { it to stringResource(it.label) }, view, { chosenView = it }, Modifier.padding(top = 8.dp), fill = true)
        }
        error?.let { Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) { Callout(it, Tone.Danger) } }
        info?.let { Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) { Callout(it, Tone.Good) } }
        if (session.state == SessionState.CREATED && session.queuedAtMs != null) {
            var why by remember(id) { mutableStateOf<String?>(null) }
            LaunchedEffect(id) { while (true) { why = runCatching { container.supervisor.waitingReason(session.projectId) }.getOrNull(); kotlinx.coroutines.delay(2_000) } }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                Callout(stringResource(R.string.cur_queued) + (why?.let { " " + stringResource(R.string.cur_queued_why, it) } ?: ""), Tone.Warn)
                PButton(stringResource(R.string.cur_dequeue), { scope.launch { container.supervisor.dequeue(id) } }, Modifier.padding(top = 6.dp), style = ButtonStyle.Ghost, tiny = true)
            }
        }

        val live = rememberLiveTarget(session, conversation)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (view) {
                View.Chat -> androidx.compose.runtime.CompositionLocalProvider(LocalCompactTools provides simple) {
                    ChatList(session, conversation, mine, myQuestions, ops, onComposer = { composer = it }, live = live, onExpand = { expanded = true }, simple = simple)
                }
                View.Activity -> ActivityList(id)
                View.Terminal -> Box(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp)) { app.umbra.agent.ui.terminal.TerminalsScreen(sessionId = id, cwd = session.workspaceId?.let(container.workspaceLayout::guestProjectDir), embedded = true) }
                View.Changes -> ChangesView(session, onCommit)
            }
        }
        if (expanded && live != null) LiveDisplayDialog(session, live, onDismiss = { expanded = false })
        if (view == View.Chat) {
            ChatComposer(
                id, conversation, composer, { composer = it }, busy = busy, paused = session.state == SessionState.PAUSED,
                onFileError = { error = it },
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
    if (contractDialog) ResumeContractDialog(id, onDismiss = { contractDialog = false })
    if (confirmDelete) app.umbra.agent.ui.sessions.ConfirmDeleteSession(session, onDismiss = { confirmDelete = false }) {
        confirmDelete = false
        // In the app's scope: deleting the session takes this page (and its scope) away halfway through.
        container.scope.launch { container.supervisor.delete(id); container.ui.focus(null) }
    }
}

/** Lets one session continue by itself after the app or the phone restarts (architecture spec §8.1). */
@Composable
private fun ResumeContractDialog(sessionId: String, onDismiss: () -> Unit) {
    val container = LocalContext.current.container
    val existing = container.contracts[sessionId]
    var resume by remember { mutableStateOf(existing?.resumeAfterRestart ?: true) }
    var duration by remember { mutableStateOf(if (existing?.expiresAtMs == null) "complete" else "custom") }
    var allowed by remember { mutableStateOf(existing?.allowedWork.orEmpty()) }
    app.umbra.agent.ui.porcelain.PConfirm(
        title = stringResource(R.string.contract_title),
        body = stringResource(R.string.contract_body),
        confirmLabel = stringResource(R.string.action_save),
        danger = false,
        dismissLabel = stringResource(if (existing != null) R.string.contract_remove else R.string.action_cancel),
        onDismiss = onDismiss,
        onDismissButton = { if (existing != null) container.contracts.revoke(sessionId); onDismiss() },
        onConfirm = {
            val now = System.currentTimeMillis()
            val expires = when (duration) { "day" -> now + 86_400_000L; "week" -> now + 7 * 86_400_000L; "custom" -> existing?.expiresAtMs; else -> null }
            container.contracts.set(sessionId, resume, expires, allowed)
            onDismiss()
        },
        extra = {
            app.umbra.agent.ui.porcelain.PToggle(stringResource(R.string.contract_switch), checked = resume, onChange = { resume = it }, divider = false)
            Text(stringResource(R.string.contract_how_long), fontSize = 12.sp, color = P.muted)
            Segmented(listOf("complete" to stringResource(R.string.contract_until_done), "day" to stringResource(R.string.contract_day), "week" to stringResource(R.string.contract_week)), duration, { duration = it }, fill = true)
            app.umbra.agent.ui.porcelain.PField(allowed, { allowed = it }, placeholder = stringResource(R.string.contract_allowed))
        },
    )
}

/** A menu entry with an optional second line that says what it does. */
@Composable
private fun MenuItem(title: String, detail: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column(Modifier.padding(vertical = 2.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = if (danger) P.danger else P.ink)
                if (detail != null) Text(detail, Modifier.padding(top = 1.dp), fontSize = 11.sp, lineHeight = 15.sp, color = P.muted)
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun ChatList(
    session: app.umbra.core.session.Session,
    conversation: ConversationState,
    approvals: List<app.umbra.core.safety.ApprovalRequest>,
    questions: List<app.umbra.core.orchestrator.QuestionBroker.Pending>,
    ops: SessionOps,
    onComposer: (String) -> Unit,
    live: LiveTarget? = null,
    onExpand: () -> Unit = {},
    simple: Boolean = false,
) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val list = rememberLazyListState()
    // Simple leaves out what is for developers: system notices and command output.
    val items = conversation.items.filter { it !is ChatItem.ToolResult && !(simple && (it is ChatItem.Custom || it is ChatItem.CommandOutput)) }
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
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 40.dp)) {
                Subtle(stringResource(if (session.state == SessionState.CREATED) R.string.cur_empty_new else R.string.cur_empty_wait))
                if (session.request.isNotBlank() && session.state != SessionState.CREATED) Subtle(stringResource(R.string.cur_request, session.request), Modifier.padding(top = 6.dp))
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
                Callout(stringResource(R.string.cur_retrying, r.error, r.attempt, r.maxAttempts), Tone.Warn)
                Gap(6.dp)
                PButton(stringResource(R.string.cur_stop_retrying), { ops.launch("Stopping the retries") { it.abortRetry() } }, style = ButtonStyle.Alt, tiny = true)
            }
        }
        if (conversation.compacting) item(key = "compacting") { Callout(stringResource(R.string.cur_compacting), Tone.Neutral) }
        if (live != null) item(key = "live") { LiveDisplayCard(session, live, onExpand) }
        items(approvals, key = { it.id }) { r -> ApprovalPanel(r, null, onDecision = { container.approvals.resolve(r.id, it) }) }
        items(questions, key = { it.request.id }) { q -> QuestionPanel(q, null, onAnswer = { container.questions.answer(q.request.id, it) }) }
        if (session.state == SessionState.FAILED) item(key = "failed") { Callout(session.lastError ?: stringResource(R.string.cur_failed), Tone.Danger) }
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
            ?: error(ops.string(R.string.ops_not_on_branch))
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
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val labels = remember(context) {
        TimelineBuilder.Labels(
            problemIn = { resources.getString(R.string.tl_problem, it) },
            recovered = resources.getString(R.string.tl_recovered),
            recoveryFailed = resources.getString(R.string.tl_recovery_failed),
            appEvent = { resources.getString(R.string.tl_app_event, it) },
            notice = resources.getString(R.string.tl_notice),
        )
    }
    val items = remember(records, labels) { TimelineBuilder.build(records, labels) }
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
                is TimelineItem.Subagent -> NoticeRow("info", stringResource(R.string.act_helper, item.id, item.kind), item.summary.orEmpty())
                is TimelineItem.Decision -> NoticeRow("info", stringResource(R.string.act_quick, item.engine ?: stringResource(R.string.act_local)), "${item.question} → ${item.answer}")
            }
        }
    }
}
