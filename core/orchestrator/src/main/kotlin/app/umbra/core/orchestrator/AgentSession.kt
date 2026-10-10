package app.umbra.core.orchestrator

import app.umbra.core.model.Clock
import app.umbra.core.safety.ActionContext
import app.umbra.core.safety.ApprovalBroker
import app.umbra.core.safety.SafetyGate
import app.umbra.core.session.IllegalTransitionException
import app.umbra.core.session.Session
import app.umbra.core.session.SessionCommand
import app.umbra.core.session.SessionEvent
import app.umbra.core.session.SessionService
import app.umbra.core.session.SessionState
import app.umbra.core.session.SessionStore
import app.umbra.core.session.SubagentBrief
import app.umbra.core.workspace.WorkspaceManager
import app.umbra.platform.logs.ActionJournal
import app.umbra.platform.logs.LogMonitor
import app.umbra.platform.logs.MonitorEvent
import app.umbra.runtime.omp.ContentBlock
import app.umbra.runtime.omp.HostToolCall
import app.umbra.runtime.omp.HostToolHandler
import app.umbra.runtime.omp.OmpApprovalMode
import app.umbra.runtime.omp.OmpClientConfig
import app.umbra.runtime.omp.OmpEvent
import app.umbra.runtime.omp.OmpLaunch
import app.umbra.runtime.omp.OmpProcess
import app.umbra.runtime.omp.OmpSettings
import app.umbra.runtime.omp.PromptResult
import app.umbra.runtime.omp.PromptStatus
import app.umbra.runtime.omp.StreamingBehavior
import app.umbra.runtime.omp.ToolPolicy
import app.umbra.runtime.omp.UiRequest
import app.umbra.runtime.omp.UiResponse
import app.umbra.runtime.omp.messages
import app.umbra.runtime.omp.sessionState
import app.umbra.runtime.omp.slashCommands
import app.umbra.runtime.omp.switchSession
import app.umbra.tools.UmbraToolHost
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Everything a session needs from the rest of the app. */
class SessionDeps(
    val store: SessionStore,
    val service: SessionService,
    val workspaces: WorkspaceManager,
    val environment: AgentEnvironment,
    val providers: ProviderResolver,
    val toolHosts: (sessionId: String) -> UmbraToolHost,
    val gate: SafetyGate,
    val approvals: ApprovalBroker,
    val questions: QuestionBroker,
    /** What the agent is told about this device, read when its process starts. */
    val prompts: suspend () -> PromptContext,
    val logMonitor: LogMonitor? = null,
    val journal: ActionJournal? = null,
    val artifacts: ArtifactSink = ArtifactSink.None,
    val approvalMode: () -> app.umbra.core.safety.ApprovalMode = { app.umbra.core.safety.ApprovalMode.BALANCED },
    /** Called when a session reaches a terminal state: release what it held (background displays, leases). */
    val onFinished: suspend (Session) -> Unit = {},
    val clock: Clock = Clock.System,
    val clientConfig: OmpClientConfig = OmpClientConfig(),
    val policy: RecoveryLimits = RecoveryLimits(),
    /** The last transcript of each session, for showing it while no omp process runs. */
    val conversations: ConversationCache = ConversationCache.None,
    /** Non-null while an emergency input lockdown is in force: new managed input is refused (§8.2). */
    val fence: () -> String? = { null },
    /** The agent's todo phases as omp last reported them (plan revisions, §4.3). */
    val onTodos: suspend (sessionId: String, phases: List<app.umbra.runtime.omp.OmpTodoPhase>) -> Unit = { _, _ -> },
)

/** How hard a session tries to recover on its own before giving up and telling the user. */
data class RecoveryLimits(
    val maxProcessRestarts: Int = 3,
    val restartBackoffMs: Long = 2_000,
    val maxProviderRetries: Int = 2,
    val providerRetryBackoffMs: Long = 8_000,
    /** An idle (completed) session releases its omp process after this long; a follow-up starts it again from the saved conversation. */
    val idleReleaseMs: Long = 5 * 60_000,
    /** Under memory pressure, a finished task's process goes once nothing has used it for this long. */
    val pressureIdleMs: Long = 45_000,
)

/** Streaming updates for a screen that is watching; not persisted (only complete messages are). */
sealed interface LiveUpdate {
    data class TextDelta(val messageId: String?, val text: String) : LiveUpdate
    data class ThinkingDelta(val messageId: String?, val text: String) : LiveUpdate
    data class ToolProgress(val toolCallId: String, val toolName: String) : LiveUpdate
    data object TurnStarted : LiveUpdate
    data object TurnEnded : LiveUpdate
}

/** Holds host-tool calls while a session is paused. */
internal class PauseGate {
    private val open = MutableStateFlow(true)
    fun pause() { open.value = false }
    fun resume() { open.value = true }
    suspend fun await() { if (!open.value) open.first { it } }
}

/**
 * One live agent: an omp process working on one [Session]. It starts the process in the Linux guest,
 * gives it the Android tools, answers its approval questions through the safety system, writes
 * everything it does into the session timeline, and keeps it alive across crashes and restarts.
 */
class AgentSession internal constructor(
    val sessionId: String,
    private val deps: SessionDeps,
    parentScope: CoroutineScope,
    private val onEnded: (AgentSession) -> Unit,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val lifecycle = Mutex()
    private val gate = PauseGate()
    private val toolHost: UmbraToolHost = deps.toolHosts(sessionId)
    private val recorder = TimelineRecorder(sessionId, deps.store, toolHost.toolNames.toSet(), deps.artifacts)
    private val mutableLive = MutableSharedFlow<LiveUpdate>(extraBufferCapacity = 512)
    private var process: OmpProcess.Started? = null
    private var idleJob: Job? = null
    private var restarts = 0
    private var providerRetries = 0

    private val stopping = AtomicBoolean(false)

    @Volatile private var turnActive = false

    /** A prompt sent while idle whose turn has not started yet: a late "settled" from the previous turn is not about it. */
    @Volatile private var pendingPromptId: String? = null

    @Volatile private var intentionalClose = false

    /** GUI commands in flight, and when the process was last used: an idle release never cuts one off. */
    private val inUse = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile private var lastUsedMs = 0L

    val live: SharedFlow<LiveUpdate> = mutableLive.asSharedFlow()

    private val conversationFlow = MutableStateFlow(ConversationState())
    private val effectsFlow = MutableSharedFlow<ConversationEffect>(extraBufferCapacity = 16)

    /** The conversation as a GUI shows it (ADR 0013), live while omp runs, the saved transcript otherwise. */
    val conversation: kotlinx.coroutines.flow.StateFlow<ConversationState> = conversationFlow

    /** What omp asks the client to do: open a sign-in page, put text in the composer. */
    val effects: SharedFlow<ConversationEffect> = effectsFlow.asSharedFlow()

    /** Pending reads of omp's state, conflated: at most one runs, and one more follows if something changed meanwhile. */
    private val stateReads = Channel<app.umbra.runtime.omp.OmpClient>(Channel.CONFLATED)

    init {
        scope.launch {
            val saved = try { deps.conversations.load(sessionId) } catch (_: Exception) { null } ?: return@launch
            conversationFlow.update { current -> if (current.items.isEmpty()) ConversationReducer.withMessages(current, saved) else current }
        }
        scope.launch {
            for (client in stateReads) {
                if (client.isClosed) continue
                try {
                    val state = app.umbra.runtime.omp.OmpSessionState.from(client.getState())
                    conversationFlow.update { ConversationReducer.withSession(it, state).copy(live = true) }
                    runCatching { deps.onTodos(sessionId, state.todos) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The process went away or did not answer; the next change asks again.
                }
            }
        }
    }

    val isProcessAlive: Boolean get() = process?.client?.isClosed == false

    /** The agent's process and everything it started, as the runner lists them right now. */
    suspend fun processTree(): List<app.umbra.core.session.ProcessRef> {
        val transport = process?.transport ?: return emptyList()
        val pid = try { transport.processId() } catch (_: Exception) { null } ?: return emptyList()
        val root = deps.environment.processes().firstOrNull { it.pid.toLong() == pid } ?: app.umbra.core.session.ProcessRef(pid.toInt(), "omp", deps.environment.name, null)
        return listOf(root) + deps.environment.descendants(pid)
    }

    /** Operating-system id of the omp process when it runs as a child of this one (development and tests). */
    val localProcessId: Long? get() = (process?.transport as? app.umbra.runtime.omp.ProcessTransport)?.pid?.takeIf { it > 0 }

    // ---- commands ----

    /** Sends the user's message. Starts the session or restarts a finished one; steers a running agent. */
    suspend fun send(message: String, images: List<ContentBlock.Image> = emptyList(), whileRunning: StreamingBehavior = StreamingBehavior.STEER) {
        lifecycle.withLock {
            check(!stopping.get()) { "this session has been released" }
            idleJob?.cancel()
            lastUsedMs = deps.clock.nowMs()
            // Marked before the state moves to running, so a late "settled" of the previous turn cannot finish it.
            val idle = !turnActive
            if (idle) pendingPromptId = SENDING
            try {
                prepareState()
                ensureProcess()
                recorder.userMessage(message, images.size)
                val handle = process!!.client.prompt(message, images, if (turnActive) whileRunning else null)
                if (idle && pendingPromptId == SENDING) pendingPromptId = handle.id
            } catch (e: Throwable) {
                if (pendingPromptId == SENDING) pendingPromptId = null
                throw e
            }
        }
    }

    /** Aborts the running turn and sends [message] in its place (omp `abort_and_prompt`). */
    suspend fun interrupt(message: String, images: List<ContentBlock.Image> = emptyList()) {
        lifecycle.withLock {
            check(!stopping.get()) { "this session has been released" }
            idleJob?.cancel()
            prepareState()
            ensureProcess()
            recorder.userMessage(message, images.size)
            pendingPromptId = process!!.client.abortAndPrompt(message, images).id
        }
    }

    /** Stops the current step and keeps the session resumable. */
    suspend fun pause(reason: String = "paused by the user") {
        lifecycle.withLock {
            val session = deps.store.get(sessionId) ?: return
            if (session.state != SessionState.RUNNING && session.state != SessionState.WAITING_APPROVAL) return
            gate.pause()
            deps.approvals.cancelSession(sessionId)
            deps.questions.cancelSession(sessionId)
            deps.service.apply(sessionId, SessionCommand.PAUSE, reason)
            process?.client?.takeIf { !it.isClosed }?.let { client ->
                try { client.abort() } catch (_: Exception) { /* nothing running */ }
            }
        }
    }

    /** Lets a paused session continue: the interrupted step is picked up from the conversation. */
    suspend fun resume(note: String? = null) {
        lifecycle.withLock {
            val session = deps.store.get(sessionId) ?: return
            if (session.state != SessionState.PAUSED) return
            deps.service.apply(sessionId, SessionCommand.RESUME)
            gate.resume()
            ensureProcess()
            val text = note?.takeIf { it.isNotBlank() }?.let { "The user resumed the task: $it" }
                ?: "The user resumed the task. Observe the current state, then continue where you left off."
            pendingPromptId = process!!.client.prompt(text).id
        }
    }

    /** Ends the session: aborts the agent, closes its process, frees its devices and pending approvals. */
    suspend fun stop(reason: String = "stopped by the user") {
        lifecycle.withLock {
            if (!stopping.compareAndSet(false, true)) return
            val session = deps.store.get(sessionId)
            if (session != null && !session.state.isTerminal) {
                try { deps.service.apply(sessionId, SessionCommand.STOP, reason) } catch (_: IllegalTransitionException) { }
            }
            teardown(abort = true)
            finished()
        }
        onEnded(this)
    }

    /** Releases the omp process of an idle session without ending it. */
    suspend fun releaseProcess() {
        lifecycle.withLock { teardown(abort = false) }
    }

    /**
     * Releases the process only at a safe point: no turn running, no GUI command in flight, and nothing sent for
     * [minIdleMs]. Returns whether it did. Used when the phone runs short of memory.
     */
    suspend fun releaseIfIdle(minIdleMs: Long): Boolean = lifecycle.withLock {
        if (process == null || turnActive || inUse.get() > 0 || deps.clock.nowMs() - lastUsedMs < minIdleMs) return@withLock false
        teardown(abort = false)
        true
    }

    // ---- lifecycle internals ----

    private suspend fun prepareState() {
        val session = deps.store.get(sessionId) ?: error("no such session: $sessionId")
        when (session.state) {
            SessionState.CREATED -> deps.service.apply(sessionId, SessionCommand.START)
            SessionState.COMPLETED, SessionState.STOPPED, SessionState.FAILED -> deps.service.apply(sessionId, SessionCommand.RESTART)
            SessionState.PAUSED -> {
                deps.service.apply(sessionId, SessionCommand.RESUME)
                gate.resume()
            }
            SessionState.RUNNING, SessionState.WAITING_APPROVAL -> Unit
        }
    }

    private suspend fun ensureProcess() {
        if (process?.client?.isClosed == false) return
        process = null
        intentionalClose = false
        val session = deps.store.get(sessionId) ?: error("no such session: $sessionId")
        val env = deps.environment
        env.prepare()
        env.problem()?.let { throw AgentStartException(it) }
        recorder.notice("info", "Starting the agent. The first start can take a minute or two.", "umbra")

        val selection = deps.providers.resolve(session.model)
        val files = env.agentFiles(selection.profile)
        files.write(SYSTEM_PROMPT_FILE, AgentPrompts.appendix(deps.prompts(), toolHost.toolNames))

        // The session's own folders (files, artifacts, logs) exist before anything refers to them.
        runCatching { deps.workspaces.sessionDirs(sessionId) }
        val cwd = workingDirectory(session)
        val settings = ompSettings()
        val launch = ompLaunch(session, selection, files, cwd)

        val started = OmpProcess.start(
            launch = launch,
            settings = settings,
            providers = selection.providers,
            files = files,
            scope = scope,
            environment = env.environment,
            secrets = selection.secrets,
            clientConfig = deps.clientConfig,
            spawner = env.spawner,
        )
        startClient(started, session, env)
    }

    private fun ompSettings() = OmpSettings(
            approvalMode = when (deps.approvalMode()) {
                app.umbra.core.safety.ApprovalMode.CAUTIOUS -> OmpApprovalMode.ALWAYS_ASK
                // The host classifies every call omp asks about, so omp must keep asking: never yolo.
                else -> OmpApprovalMode.WRITE
            },
            // The host's own gate decides about host tools; omp must not ask a second time.
            toolPolicies = toolHost.toolNames.associateWith { ToolPolicy.ALLOW },
            bashPatterns = OmpSettings.PRODUCT_BASH_PATTERNS,
            // The pinned omp is the only version this product supports, and there is no desktop to notify.
            extra = mapOf(
                "startup" to mapOf("checkUpdate" to false),
                "completion" to mapOf("notify" to "off"),
                "ask" to mapOf("notify" to "off"),
            ),
        )

    private fun ompLaunch(session: Session, selection: ProviderSelection, files: app.umbra.runtime.omp.AgentFiles, cwd: String) = OmpLaunch(
        executable = deps.environment.ompExecutable,
        workingDirectory = cwd,
        model = selection.model?.let { "${it.provider}/${it.modelId}" },
        sessionDir = deps.environment.ompSessionDir(sessionId),
        loadExtensions = false,
        extraArgs = buildList {
            addAll(listOf("--append-system-prompt", "${files.ompPath.trimEnd('/')}/$SYSTEM_PROMPT_FILE"))
            selection.thinkingLevel?.let { addAll(listOf("--thinking", it)) }
        },
    )

    /**
     * How to run omp's own terminal UI on this session's conversation (for what only the TUI offers: git UI,
     * extensions dashboard, agents hub). The RPC process must be released first: two omp processes must never
     * write one session file.
     */
    suspend fun tuiLaunch(): TuiLaunch {
        val session = deps.store.get(sessionId) ?: error("no such session: $sessionId")
        val env = deps.environment
        env.prepare()
        env.problem()?.let { throw AgentStartException(it) }
        val selection = deps.providers.resolve(session.model)
        val files = env.agentFiles(selection.profile)
        files.write(SYSTEM_PROMPT_FILE, AgentPrompts.appendix(deps.prompts(), emptyList()))
        if (selection.providers.isNotEmpty()) files.write(OmpProcess.MODELS_FILE, app.umbra.runtime.omp.OmpModelsFile.toYaml(selection.providers))
        files.write(OmpProcess.OVERLAY_FILE, ompSettings().toYaml())
        val cwd = workingDirectory(session)
        val launch = ompLaunch(session, selection, files, cwd).copy(settingsOverlay = "${files.ompPath.trimEnd('/')}/${OmpProcess.OVERLAY_FILE}")
        return TuiLaunch(
            command = launch.interactiveCommand(resume = session.ompSessionFile),
            cwd = cwd,
            environment = env.environment + mapOf("PI_CODING_AGENT_DIR" to files.ompPath, "PI_NO_TITLE" to "1"),
            secrets = selection.secrets,
        )
    }

    private suspend fun startClient(started: OmpProcess.Started, session: Session, env: AgentEnvironment) {
        val client = started.client
        client.uiHandler = OmpApprovalBridge(
            sessionId = sessionId,
            gate = deps.gate,
            context = { actionContext(session) },
            questions = deps.questions,
            fence = deps.fence,
        )
        // Collect before the handshake: events emitted while nobody collects are not replayed.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { handle(client, it) } }
        try {
            client.start()
            client.setHostTools(toolHost.definitions(), recordingHandler())
            client.setAskDialog(true)
            val dir = env.ompSessionDir(sessionId)
            val opened = client.openSession(dir)
            // omp reopens the newest conversation of the folder; the user may have switched to another one (or forked,
            // or started a new one) before the process went away. Continue where they were.
            val wanted = session.ompSessionFile?.takeIf { it != opened.sessionFile && it.startsWith(dir.trimEnd('/') + "/") }
            val file = if (wanted != null && runCatching { client.switchSession(wanted) }.getOrDefault(false)) wanted else opened.sessionFile
            deps.store.update(sessionId) { it.copy(ompSessionFile = file ?: it.ompSessionFile) }
            refreshConversation(client, commands = true)
        } catch (e: Throwable) {
            intentionalClose = true
            val tail = app.umbra.runtime.omp.summarizeStderr(started.transport.stderrTail)
            withContext(NonCancellable) { try { client.close(graceMs = 1_000) } catch (_: Exception) { } ; started.transport.destroy() }
            throw AgentStartException("omp did not start: ${e.message}${if (tail.isNotBlank()) " ($tail)" else ""}", e)
        }
        process = started
        startLogWatch()
        startProcessWatch(started.transport)
    }

    private fun workingDirectory(session: Session): String {
        val layout = deps.environment.workspaces
        return session.workspaceId?.let { layout.guestProjectDir(it) } ?: "${layout.guestSessionDir(sessionId)}/files"
    }

    private suspend fun actionContext(session: Session): ActionContext {
        val roots = deps.workspaces.riskRoots(sessionId)
        return ActionContext(
            cwd = workingDirectory(session),
            workspaceRoots = roots,
            tempRoots = ActionContext.DEFAULT_TEMP_ROOTS,
        )
    }

    private fun recordingHandler(): HostToolHandler = HostToolHandler { call, progress ->
        gate.await()
        recorder.hostToolCall(call)
        val started = deps.clock.nowMs()
        try {
            val payload = toolHost.handle(call, progress)
            recorder.hostToolResult(call, payload, deps.clock.nowMs() - started)
            trackResources(call, payload)
            payload
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                deps.store.append(sessionId, SessionEvent.ToolResult(call.toolCallId.ifBlank { call.id }, call.toolName, "cancelled", "The call was cancelled.", durationMs = deps.clock.nowMs() - started))
            }
            throw e
        }
    }

    /** Keeps the session's list of open apps and displays true to what its tools did (spec §24). */
    private suspend fun trackResources(call: HostToolCall, payload: app.umbra.runtime.omp.HostToolPayload) {
        val result = payload.details?.let {
            try { app.umbra.core.model.UmbraJson.decodeFromJsonElement(app.umbra.core.model.action.ActionResult.serializer(), it) } catch (_: Exception) { null }
        } ?: return
        if (!result.isOk) return
        fun arg(name: String) = (call.arguments[name] as? kotlinx.serialization.json.JsonPrimitive)?.content
        when (call.toolName) {
            "android_launch", "android_switch" -> arg("package")?.let { pkg -> deps.store.update(sessionId) { it.copy(activeApps = (it.activeApps - pkg) + pkg) } }
            "android_stop" -> arg("package")?.let { pkg -> deps.store.update(sessionId) { it.copy(activeApps = it.activeApps - pkg) } }
            "android_display" -> when (arg("action")) {
                "create" -> (result.data as? kotlinx.serialization.json.JsonObject)?.get("display")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                    ?.let { id -> deps.store.update(sessionId) { it.copy(displays = (it.displays - id) + id) } }
                "destroy" -> arg("display_id")?.toIntOrNull()?.let { id -> deps.store.update(sessionId) { it.copy(displays = it.displays - id) } }
                else -> Unit
            }
        }
    }

    // ---- events ----

    private suspend fun handle(client: app.umbra.runtime.omp.OmpClient, event: OmpEvent) {
        // A released process reports its end after a new one may already run: that end is not this session's.
        val stale = event is OmpEvent.Closed && process != null && process?.client !== client
        if (!stale) {
            var effect: ConversationEffect? = null
            conversationFlow.update { current -> ConversationReducer.reduce(current, event, deps.clock.nowMs()).also { effect = it.second }.first }
            effect?.let { effectsFlow.tryEmit(it) }
        }
        when (event) {
            is OmpEvent.StateChanged, is OmpEvent.ConfigChanged, is OmpEvent.SessionInfo, is OmpEvent.AgentEnd, is OmpEvent.QueueUpdate -> stateReads.trySend(client)
            // Compaction rewrites the transcript the GUI shows.
            is OmpEvent.CompactionEnded -> scope.launch { refreshConversation(client) }
            else -> Unit
        }
        when (event) {
            OmpEvent.AgentStart -> {
                turnActive = true
                pendingPromptId = null
                mutableLive.tryEmit(LiveUpdate.TurnStarted)
                markRunning()
            }
            is OmpEvent.AgentEnd -> {
                turnActive = false
                mutableLive.tryEmit(LiveUpdate.TurnEnded)
            }
            OmpEvent.SessionSettled -> settle()
            is OmpEvent.Closed -> onClosed(client, event)
            is OmpEvent.MessageUpdate -> when (event.kind) {
                "text_delta" -> event.textDelta?.let { mutableLive.tryEmit(LiveUpdate.TextDelta(event.messageId, it)) }
                "thinking_delta" -> (event.raw["assistantMessageEvent"] as? kotlinx.serialization.json.JsonObject)?.str("delta")
                    ?.let { mutableLive.tryEmit(LiveUpdate.ThinkingDelta(event.messageId, it)) }
                else -> Unit
            }
            is OmpEvent.ToolUpdate -> mutableLive.tryEmit(LiveUpdate.ToolProgress(event.toolCallId, event.toolName))
            // Prompt results and `session_settled` arrive on this one ordered stream, so a failed turn is recorded before the session can be called settled.
            is OmpEvent.Prompt -> onPromptResult(event.result)
            is OmpEvent.Subagent -> {
                recorder.omp(event)
                updateSubagents(event)
            }
            else -> recorder.omp(event)?.let { plan ->
                deps.store.update(sessionId) { it.copy(plan = plan) }
                deps.store.append(sessionId, SessionEvent.PlanUpdated(plan))
            }
        }
    }

    private suspend fun markRunning() {
        val state = deps.store.get(sessionId)?.state ?: return
        if (state == SessionState.COMPLETED || state == SessionState.STOPPED || state == SessionState.FAILED) {
            // omp woke itself up (a scheduled job, a background task finishing): the session is working again.
            try { deps.service.apply(sessionId, SessionCommand.RESTART, "woken by a background event") } catch (_: IllegalTransitionException) { }
        }
    }

    private suspend fun updateSubagents(event: OmpEvent.Subagent) {
        val id = event.payload.str("id") ?: event.payload.str("agentId") ?: return
        val state = when (event.kind) {
            "lifecycle" -> when (event.payload.str("event") ?: event.payload.str("state")) {
                "end", "finished", "completed" -> "finished"
                "aborted" -> "aborted"
                "failed", "error" -> "failed"
                else -> "running"
            }
            else -> "running"
        }
        deps.store.update(sessionId) { s ->
            val brief = SubagentBrief(id, event.payload.str("role") ?: event.payload.str("agent"), state, event.payload.str("summary"))
            s.copy(subagents = s.subagents.filterNot { it.id == id } + brief)
        }
    }

    private suspend fun onPromptResult(result: PromptResult) {
        // The pending prompt is answered (it may never have started a turn, e.g. a command omp handled locally).
        if (result.id == pendingPromptId) pendingPromptId = null
        when (result.status) {
            PromptStatus.COMPLETED -> {
                providerRetries = 0
                restarts = 0
                if (result.sessionSettled) settle()
            }
            PromptStatus.ABORTED -> Unit // pause and stop already moved the state
            PromptStatus.ERROR -> {
                val error = result.error
                val message = error?.message ?: "the model request failed"
                if (error?.retryable == true && providerRetries < deps.policy.maxProviderRetries) {
                    providerRetries++
                    recorder.failure("model", message, recoverable = true)
                    delay(deps.policy.providerRetryBackoffMs * providerRetries)
                    lifecycle.withLock {
                        if (stopping.get() || process?.client?.isClosed != false) return
                        val state = deps.store.get(sessionId)?.state
                        if (state != SessionState.RUNNING) return
                        recorder.recovery("model", "retry $providerRetries", success = true)
                        process!!.client.prompt("The previous request failed with a temporary error. Continue the task.")
                    }
                } else {
                    recorder.failure("model", message, recoverable = false)
                    fail(message)
                }
            }
        }
    }

    private suspend fun settle() {
        // The state is decided first, with nothing suspending in between: omp reports a settled turn twice (its prompt
        // result and `session_settled`), and a follow-up sent between the two must not be marked finished.
        val state = deps.store.get(sessionId)?.state
        if (state == SessionState.RUNNING && !turnActive && pendingPromptId == null) {
            try { deps.service.apply(sessionId, SessionCommand.COMPLETE, "the agent finished") } catch (_: IllegalTransitionException) { }
            finished()
            scheduleIdleRelease()
        }
        process?.client?.let { client -> scope.launch { refreshConversation(client, save = true) } }
    }

    private suspend fun finished() {
        val session = deps.store.get(sessionId) ?: return
        try { deps.onFinished(session) } catch (_: Exception) { /* cleanup is best effort */ }
        deps.store.update(sessionId) { it.copy(displays = emptyList(), activeApps = emptyList()) }
    }

    private suspend fun fail(message: String) {
        try { deps.service.apply(sessionId, SessionCommand.FAIL, error = message) } catch (_: IllegalTransitionException) { }
        finished()
        deps.approvals.cancelSession(sessionId)
        deps.questions.cancelSession(sessionId)
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(deps.policy.idleReleaseMs)
            val state = deps.store.get(sessionId)?.state
            if (!turnActive && state != SessionState.RUNNING && state != SessionState.WAITING_APPROVAL && state != SessionState.PAUSED) releaseProcess()
        }
    }

    private suspend fun onClosed(client: app.umbra.runtime.omp.OmpClient, event: OmpEvent.Closed) {
        // A process that is being replaced or released is not a failure; neither is one that never finished starting.
        if (intentionalClose || stopping.get() || process?.client !== client) return
        val wasWorking = turnActive
        turnActive = false
        process = null
        val detail = buildString {
            append("exit code ").append(event.exitCode ?: "unknown")
            event.stderrTail.takeLast(4).forEach { append("\n").append(it) }
        }
        recorder.failure("omp", "The agent process ended unexpectedly ($detail)")
        val state = deps.store.get(sessionId)?.state
        if (state != SessionState.RUNNING && state != SessionState.WAITING_APPROVAL) return // idle: it is restarted by the next message
        if (restarts >= deps.policy.maxProcessRestarts) {
            fail("The agent process kept ending unexpectedly.")
            return
        }
        restarts++
        delay(deps.policy.restartBackoffMs * restarts)
        lifecycle.withLock {
            if (stopping.get()) return
            try {
                ensureProcess()
                recorder.recovery("omp", "restarted the agent process (attempt $restarts)", success = true)
                if (wasWorking) {
                    process!!.client.prompt("Your process was restarted after an unexpected failure. Re-observe the current state, then continue the task from where you left off.")
                }
            } catch (e: Exception) {
                recorder.recovery("omp", "restart the agent process", success = false, detail = e.message)
                fail("Could not restart the agent: ${e.message}")
            }
        }
    }

    // ---- the omp GUI (ADR 0013) ----

    /**
     * Runs [block] against this session's omp, starting the process when it is not running (without sending the
     * agent anything). For everything the GUI does besides sending messages: model, thinking, modes, compaction,
     * tree, branch, fork, todos, goals, subagents, sign-in, stats, export.
     */
    suspend fun <T> withOmp(block: suspend (app.umbra.runtime.omp.OmpClient) -> T): T {
        val client = lifecycle.withLock {
            check(!stopping.get()) { "this session has been released" }
            inUse.incrementAndGet()
            lastUsedMs = deps.clock.nowMs()
            try {
                ensureProcess()
            } catch (e: Throwable) {
                inUse.decrementAndGet()
                throw e
            }
            process!!.client
        }
        try {
            return block(client)
        } finally {
            lastUsedMs = deps.clock.nowMs()
            inUse.decrementAndGet()
            // A process started only for this (a session that is not working) does not stay forever.
            if (!turnActive && deps.store.get(sessionId)?.state?.let { it != SessionState.RUNNING && it != SessionState.WAITING_APPROVAL } == true) {
                scheduleIdleRelease()
            }
        }
    }

    /** Something changed the transcript itself (branch, fork, switch, new session, compaction): read it again. */
    suspend fun reloadConversation() {
        process?.client?.takeIf { !it.isClosed }?.let { refreshConversation(it, commands = true, save = true) }
    }

    private suspend fun refreshConversation(client: app.umbra.runtime.omp.OmpClient, commands: Boolean = false, save: Boolean = false) {
        try {
            val messages = client.messages()
            val state = client.sessionState()
            val available = if (commands) runCatching { client.slashCommands() }.getOrNull() else null
            conversationFlow.update { current ->
                val withTranscript = ConversationReducer.withSession(ConversationReducer.withMessages(current, messages), state)
                withTranscript.copy(live = true, commands = available ?: withTranscript.commands)
            }
            runCatching { deps.onTodos(sessionId, state.todos) }
            // Branch, fork, switch and new move omp to another file; a restarted process continues there.
            state.sessionFile?.let { f -> if (deps.store.get(sessionId)?.ompSessionFile != f) deps.store.update(sessionId) { it.copy(ompSessionFile = f) } }
            if (save) runCatching { deps.conversations.save(sessionId, messages) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not fatal: the events keep the conversation current, and the next settle reads it again.
        }
    }

    // ---- log monitor ----

    private var logJob: Job? = null
    private var processJob: Job? = null

    private fun startLogWatch() {
        val monitor = deps.logMonitor ?: return
        if (logJob?.isActive == true) return
        logJob = scope.launch {
            monitor.events.collect { event ->
                if (event !is MonitorEvent.CrashDetected || !event.isNew) return@collect
                val report = event.report
                val mine = report.actionId?.let { deps.journal?.get(it)?.sessionId == sessionId } ?: false
                if (!mine) return@collect
                recorder.appEvent(report)
                val client = process?.client
                if (turnActive && client != null && !client.isClosed) {
                    try {
                        client.steer("Device log monitor: ${report.summary()}. Look at it if it matters for your task (the logs tool has the details).")
                    } catch (_: Exception) {
                        // The agent is between turns; the timeline already has the event.
                    }
                }
            }
        }
    }

    /** While the agent works, keeps the session's list of running processes current (spec §24). */
    private fun startProcessWatch(transport: app.umbra.runtime.omp.OmpTransport) {
        processJob?.cancel()
        processJob = scope.launch {
            var last: List<app.umbra.core.session.ProcessRef> = emptyList()
            while (true) {
                delay(PROCESS_POLL_MS)
                val state = deps.store.get(sessionId)?.state
                if (state != SessionState.RUNNING && state != SessionState.WAITING_APPROVAL) continue
                val pid = try { transport.processId() } catch (_: Exception) { null } ?: continue
                val now = try { deps.environment.descendants(pid) } catch (_: Exception) { continue }
                if (now.map { it.pid } != last.map { it.pid }) {
                    last = now
                    deps.store.update(sessionId) { it.copy(processes = now) }
                }
            }
        }
    }

    private suspend fun teardown(abort: Boolean) {
        intentionalClose = true
        idleJob?.cancel()
        processJob?.cancel()
        processJob = null
        logJob?.cancel()
        logJob = null
        gate.resume()
        deps.approvals.cancelSession(sessionId)
        deps.questions.cancelSession(sessionId)
        val started = process
        process = null
        if (started != null) {
            val client = started.client
            if (abort && !client.isClosed) withTimeoutOrNull(3_000) { try { client.abort() } catch (_: Exception) { } }
            withContext(NonCancellable) {
                withTimeoutOrNull(15_000) { client.close(graceMs = 4_000) }
                started.transport.destroy()
            }
        }
        turnActive = false
        if (abort) scope.cancel()
    }

    class AgentStartException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    /** The omp TUI command for a terminal, with the variables and credentials it needs. */
    data class TuiLaunch(val command: List<String>, val cwd: String, val environment: Map<String, String>, val secrets: Map<String, String>) {
        override fun toString() = "TuiLaunch(command=$command, cwd=$cwd, secrets=${secrets.keys})"
    }

    companion object {
        const val SYSTEM_PROMPT_FILE = "umbra-system.md"
        private const val SENDING = "(sending)"
        private const val PROCESS_POLL_MS = 10_000L
    }
}
