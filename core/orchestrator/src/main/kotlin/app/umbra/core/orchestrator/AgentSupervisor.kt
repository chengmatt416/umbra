package app.umbra.core.orchestrator

import app.umbra.core.perf.PressureLevel
import app.umbra.core.perf.ResourceGovernor
import app.umbra.core.session.IllegalTransitionException
import app.umbra.core.session.ModelRef
import app.umbra.core.session.Session
import app.umbra.core.session.SessionCommand
import app.umbra.core.session.SessionIds
import app.umbra.core.session.SessionState
import app.umbra.core.session.StoreChange
import app.umbra.core.workspace.WorkspaceKind
import app.umbra.runtime.omp.ContentBlock
import app.umbra.runtime.omp.StreamingBehavior
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

/** What kind of session to make, who made it, and the name others call it by (sessions, subagents, agents). */
data class SessionSpec(
    val kind: app.umbra.core.session.SessionKind = app.umbra.core.session.SessionKind.CODING,
    val handle: String? = null,
    val spawnedBy: String? = null,
    val agentId: String? = null,
    /** The browser mode of a coding session (see [app.umbra.core.session.Session.browser]). */
    val browser: String? = null,
    val usageLimit: app.umbra.core.session.AiUsageLimit? = null,
    val turnMode: app.umbra.core.session.ProactiveTurnMode = app.umbra.core.session.ProactiveTurnMode.AUTHORIZED_EXECUTION,
    val routineRunId: String? = null,
    val connectorScope: List<String>? = null,
    val approvalTtlMs: Long? = null,
    val skills: List<String>? = null,
)

/** Raised when the device cannot take another running session right now (spec §30). */
class CapacityException(message: String) : IllegalStateException(message)

/**
 * All agent sessions of the app. It creates them, starts and stops their omp processes, limits how
 * many run at once according to what the device can take, mirrors pending approvals into the session
 * state, and puts sessions that a previous run of the app left half-done into a state the user can
 * resume from.
 */
class AgentSupervisor(
    private val deps: SessionDeps,
    private val scope: CoroutineScope,
    private val governor: ResourceGovernor? = null,
) {
    private val live = ConcurrentHashMap<String, AgentSession>()

    /** Sessions open in the omp TUI: the GUI waits while the TUI's terminal lives (one writer per session file). */
    private val tuiHolds = ConcurrentHashMap<String, suspend () -> Boolean>()
    private val counted = ConcurrentHashMap.newKeySet<String>()
    private var observers = emptyList<Job>()

    /** Navigation may cancel the waiting screen; accepted work belongs to the supervisor. */
    private suspend fun <T> owned(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        return scope.async { block() }.await()
    }

    // ---- lifecycle of the supervisor ----

    /**
     * Call once at startup. Sessions that were running when the app died have lost their agent
     * process; they are paused with an explanation so the user can resume them from the saved conversation.
     */
    suspend fun recover() {
        try { deps.environment.cleanup() } catch (_: Exception) { }
        for (s in deps.store.list()) {
            if (s.state == SessionState.RUNNING || s.state == SessionState.WAITING_APPROVAL) {
                try {
                    deps.service.apply(s.id, SessionCommand.PAUSE, "The app was restarted while this session was running.")
                } catch (_: IllegalTransitionException) {
                }
            }
        }
    }

    fun start() {
        if (observers.isNotEmpty()) return
        observers = listOf(
            scope.launch { mirrorApprovals() },
            scope.launch { releaseCapacity() },
            scope.launch { relieveMemoryPressure() },
            scope.launch { admitQueued() },
        )
    }

    /**
     * A finished task keeps its agent process for follow-ups, which costs hundreds of megabytes. When the
     * phone runs short of memory those processes go first: the task stays, and a follow-up starts the agent
     * again from the saved conversation (spec §31).
     */
    private suspend fun relieveMemoryPressure() {
        val governor = governor ?: return
        // Level-triggered, not edge-triggered: the pressure often begins while the task is still working, and the
        // process only becomes idle later, with the decision unchanged.
        while (true) {
            if (governor.decision.value.level >= PressureLevel.HIGH) {
                for ((id, session) in live.entries.toList()) {
                    // Only at a safe point: a process the user is using right now (a GUI command) stays.
                    if (deps.store.get(id)?.state == SessionState.COMPLETED) runCatching { session.releaseIfIdle(deps.policy.pressureIdleMs) }
                }
            }
            kotlinx.coroutines.delay(1_500)
        }
    }

    suspend fun shutdown() {
        observers.forEach { it.cancel() }
        observers = emptyList()
        for (session in live.values.toList()) session.releaseProcess()
        live.clear()
    }

    // ---- sessions ----

    suspend fun create(
        request: String,
        title: String? = null,
        workspaceId: String? = null,
        model: ModelRef? = null,
        projectId: String? = null,
        effort: app.umbra.core.session.Effort? = null,
        kind: app.umbra.core.session.SessionKind = app.umbra.core.session.SessionKind.CODING,
        handle: String? = null,
        spawnedBy: String? = null,
        agentId: String? = null,
        browser: String? = null,
        usageLimit: app.umbra.core.session.AiUsageLimit? = null,
        turnMode: app.umbra.core.session.ProactiveTurnMode = app.umbra.core.session.ProactiveTurnMode.AUTHORIZED_EXECUTION,
        routineRunId: String? = null,
        connectorScope: List<String>? = null,
        approvalTtlMs: Long? = null,
        skills: List<String>? = null,
    ): Session {
        deps.validateModelInput(request)
        title?.let(deps.validateModelInput)
        val now = deps.clock.nowMs()
        spawnedBy?.let { parent -> SpawnGuard.refusal(deps.store.list(), parent, now)?.let { throw IllegalStateException(it) } }
        val id = SessionIds.generate(now)
        val name = title?.takeIf { it.isNotBlank() } ?: titleOf(request)
        val workspace = workspaceId
            ?: deps.workspaces.create(name, WorkspaceKind.PROJECT, sessionId = id).id
        if (workspaceId != null) deps.workspaces.attachSession(workspaceId, id)
        val session = Session(
            id = id,
            title = name,
            createdAtMs = now,
            request = request,
            workspaceId = workspace,
            model = model,
            projectId = projectId,
            effort = effort,
            kind = kind,
            handle = handle,
            spawnedBy = spawnedBy,
            agentId = agentId,
            browser = browser,
            usageLimit = usageLimit,
            turnMode = turnMode,
            routineRunId = routineRunId,
            connectorScope = connectorScope,
            approvalTtlMs = approvalTtlMs,
            skills = skills,
        )
        return deps.store.create(session)
    }

    /** Creates a session and starts working on the request, with [images] (a screen selection, a photo) attached. */
    suspend fun launch(request: String, title: String? = null, workspaceId: String? = null, model: ModelRef? = null, projectId: String? = null, images: List<ContentBlock.Image> = emptyList(), effort: app.umbra.core.session.Effort? = null, spec: SessionSpec = SessionSpec()): Session = owned {
        // What would refuse the start refuses it before a session exists: a refused request leaves no failed session.
        // (send checks again, and takes the slot.)
        checkFence()
        deps.admission(projectId, runningByProject())?.let { throw CapacityException(it) }
        deps.serviceAdmission(null)?.let { throw CapacityException(it) }
        governor?.decision?.value?.let { if (!it.allowNewSessions) throw CapacityException(capacityReason()) }
        val session = create(request, title, workspaceId, model, projectId = projectId, effort = effort, kind = spec.kind, handle = spec.handle, spawnedBy = spec.spawnedBy, agentId = spec.agentId, browser = spec.browser, usageLimit = spec.usageLimit, turnMode = spec.turnMode, routineRunId = spec.routineRunId, connectorScope = spec.connectorScope, approvalTtlMs = spec.approvalTtlMs, skills = spec.skills)
        try {
            send(session.id, request, images)
        } catch (e: Exception) {
            // The session exists but could not start; the user sees why and can retry. The start may already have
            // moved it to running (or not): mark it failed from whichever state it is in, and keep the real error.
            runCatching {
                if (deps.store.get(session.id)?.state == SessionState.CREATED) deps.service.apply(session.id, SessionCommand.START, "starting")
                deps.service.apply(session.id, SessionCommand.FAIL, error = e.message ?: e::class.simpleName)
            }
            throw e
        }
        deps.store.get(session.id) ?: session
    }

    /**
     * Starts the request now when the phone, the project's budget and the shared services admit it and nothing is
     * waiting ahead of it; otherwise keeps it as a queued logical agent, a session with no process yet, admitted in
     * fair order ([AdmissionOrder]) when a slot frees (architecture spec AC-022). The number of agent processes stays
     * what the phone can take. Refused outright: during a lockdown, when [QUEUE_LIMIT] requests already wait, and a
     * request with pictures that cannot start now (pictures are not kept for later).
     */
    suspend fun submit(request: String, title: String? = null, workspaceId: String? = null, model: ModelRef? = null, projectId: String? = null, images: List<ContentBlock.Image> = emptyList(), effort: app.umbra.core.session.Effort? = null, spec: SessionSpec = SessionSpec()): Session = owned {
        checkFence()
        val problem = waitingReason(projectId)
        if (problem == null && (images.isNotEmpty() || queued().isEmpty())) return@owned launch(request, title, workspaceId, model, projectId, images, effort, spec)
        if (images.isNotEmpty()) throw CapacityException("$problem A request with a picture is not queued; send it again when a slot is free.")
        if (queued().size >= QUEUE_LIMIT) throw CapacityException("${problem ?: "Requests are waiting."} The queue is full ($QUEUE_LIMIT waiting); stop or wait for some.")
        val session = create(request, title, workspaceId, model, projectId = projectId, effort = effort, kind = spec.kind, handle = spec.handle, spawnedBy = spec.spawnedBy, agentId = spec.agentId, browser = spec.browser, usageLimit = spec.usageLimit, turnMode = spec.turnMode, routineRunId = spec.routineRunId, connectorScope = spec.connectorScope, approvalTtlMs = spec.approvalTtlMs, skills = spec.skills)
        val waiting = deps.store.update(session.id) { it.copy(queuedAtMs = deps.clock.nowMs()) } ?: session
        wake.trySend(Unit)
        waiting
    }

    /** Sessions waiting for admission, longest-waiting first. */
    suspend fun enqueue(sessionId: String): Session = owned {
        checkFence()
        val session = deps.store.get(sessionId) ?: throw app.umbra.core.session.SessionNotFoundException(sessionId)
        require(session.state == SessionState.CREATED) { "Only an unstarted task can enter the queue" }
        if (session.queuedAtMs != null) return@owned session
        if (queued().size >= QUEUE_LIMIT) throw CapacityException("The queue is full ($QUEUE_LIMIT waiting).")
        val waiting = deps.store.update(sessionId) { it.copy(queuedAtMs = deps.clock.nowMs()) } ?: session
        wake.trySend(Unit)
        waiting
    }

    /** Sessions waiting for admission, longest-waiting first. */
    suspend fun queued(): List<Session> =
        deps.store.list().filter { it.state == SessionState.CREATED && it.queuedAtMs != null }.sortedBy { it.queuedAtMs }

    /** Why a new session of [projectId] cannot start now, or null when it can. */
    suspend fun waitingReason(projectId: String?): String? {
        deps.fence()?.let { return "Emergency input lockdown is in force ($it)." }
        deps.admission(projectId, runningByProject())?.let { return it }
        deps.serviceAdmission(null)?.let { return it }
        val g = governor ?: return null
        return if (!g.canStartSession()) capacityReason() else null
    }

    /** Takes a queued request out of the queue: it is stopped before it ever ran. */
    suspend fun dequeue(sessionId: String) {
        deps.store.update(sessionId) { it.copy(queuedAtMs = null) }
        tryApply(sessionId, SessionCommand.STOP, "taken out of the queue by the user")
    }

    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Admits queued sessions when something changed (a session ended, pressure eased) and every two seconds. */
    private suspend fun admitQueued() {
        while (true) {
            kotlinx.coroutines.withTimeoutOrNull(2_000) { wake.receive() }
            runCatching { admitWaiting() }
        }
    }

    private suspend fun admitWaiting() {
        while (deps.fence() == null) {
            if (deps.serviceAdmission(null) != null) return
            if (governor != null && !governor.canStartSession()) return
            val running = runningByProject()
            val next = AdmissionOrder.next(queued(), running) { p, r -> deps.admission(p, r) == null } ?: return
            deps.store.update(next.id) { it.copy(queuedAtMs = null) }
            try {
                send(next.id, next.request)
            } catch (e: CapacityException) {
                // Lost the slot in between: back in its place, tried again on the next change.
                deps.store.update(next.id) { it.copy(queuedAtMs = next.queuedAtMs) }
                return
            } catch (e: Exception) {
                runCatching {
                    if (deps.store.get(next.id)?.state == SessionState.CREATED) deps.service.apply(next.id, SessionCommand.START, "admitted from the queue")
                    deps.service.apply(next.id, SessionCommand.FAIL, error = e.message ?: e::class.simpleName)
                }
            }
        }
    }

    fun liveSession(id: String): AgentSession? = live[id]

    /** The conversation of a session as the omp GUI shows it (ADR 0013): live while its agent runs, the saved one otherwise. */
    suspend fun conversation(sessionId: String): kotlinx.coroutines.flow.StateFlow<ConversationState> {
        deps.store.get(sessionId) ?: throw app.umbra.core.session.SessionNotFoundException(sessionId)
        return sessionFor(sessionId).conversation
    }

    /** Sign-in pages to open and composer text, as omp asks for them. */
    suspend fun effects(sessionId: String): kotlinx.coroutines.flow.SharedFlow<ConversationEffect> {
        deps.store.get(sessionId) ?: throw app.umbra.core.session.SessionNotFoundException(sessionId)
        return sessionFor(sessionId).effects
    }

    /**
     * Runs an omp command for a session: switch model or thinking level, compact, branch, fork, read the tree,
     * todos, subagents, sign in, export. Starts the agent's process when it is not running, without sending it
     * anything; a process started only for this is released again when the session stays idle.
     */
    suspend fun <T> omp(sessionId: String, block: suspend (app.umbra.runtime.omp.OmpClient) -> T): T {
        deps.store.get(sessionId) ?: throw app.umbra.core.session.SessionNotFoundException(sessionId)
        checkTui(sessionId)
        checkFence()
        val session = sessionFor(sessionId)
        if (!session.isProcessAlive) governor?.decision?.value?.let { if (!it.allowNewSessions) throw CapacityException(capacityReason()) }
        return session.withOmp(block)
    }

    /**
     * Changes how hard a session thinks, from now on: kept on the session (a restarted process starts with it) and
     * given to its omp process when one is running. A session without a process takes it at its next start.
     */
    suspend fun setEffort(sessionId: String, effort: app.umbra.core.session.Effort?) {
        deps.store.update(sessionId) { it.copy(effort = effort) } ?: throw app.umbra.core.session.SessionNotFoundException(sessionId)
        if (effort != null) live[sessionId]?.takeIf { it.isProcessAlive }?.withOmp { it.setThinkingLevel(effort.level) }
    }

    /**
     * Changes the model a session runs on, from now on (null: the default model at its next start). A running process
     * switches at once.
     */
    suspend fun setModel(sessionId: String, model: ModelRef?) {
        deps.store.update(sessionId) { it.copy(model = model) } ?: throw app.umbra.core.session.SessionNotFoundException(sessionId)
        if (model != null) live[sessionId]?.takeIf { it.isProcessAlive }?.withOmp { it.setModel(model.provider, model.modelId) }
    }

    /**
     * Lets the session's agent process go if nothing has used it for [minIdleMs] and no turn is running: an agent
     * between wakes holds no memory (docs/design/agents-2026-10.md §8). Returns whether it did.
     */
    suspend fun releaseIfIdle(sessionId: String, minIdleMs: Long): Boolean = live[sessionId]?.releaseIfIdle(minIdleMs) ?: false

    /** Whether an omp process runs for the session now. */
    fun isProcessAlive(sessionId: String): Boolean = live[sessionId]?.isProcessAlive == true

    suspend fun configureFiles(sessionId: String, block: suspend (Session) -> Unit) {
        checkTui(sessionId)
        sessionFor(sessionId).configureFiles(block)
    }

    suspend fun setUsageLimit(sessionId: String, limit: app.umbra.core.session.AiUsageLimit?) {
        checkTui(sessionId)
        deps.store.update(sessionId) { it.copy(usageLimit = limit) } ?: throw app.umbra.core.session.SessionNotFoundException(sessionId)
    }

    suspend fun checkAgentUsageEditing(agentId: String) {
        deps.store.list().filter { it.agentId == agentId }.forEach { checkTui(it.id) }
    }

    /** Read an existing writer or its saved transcript; never start a process to read history. */
    suspend fun historyMessages(sessionId: String): List<kotlinx.serialization.json.JsonObject> {
        deps.store.get(sessionId) ?: throw app.umbra.core.session.SessionNotFoundException(sessionId)
        return live[sessionId]?.historyMessages() ?: deps.conversations.load(sessionId).orEmpty()
    }

    /** The session's last answer: the text of its latest assistant message (empty when it has none). */
    suspend fun finalText(sessionId: String): String {
        val items = runCatching { conversation(sessionId).value.items }.getOrNull() ?: return ""
        return items.asReversed().firstNotNullOfOrNull { (it as? ChatItem.Assistant)?.text?.takeIf(String::isNotBlank) } ?: ""
    }

    /**
     * What the session did lately, one line per thing, oldest first: messages, tool calls and their results. Used by
     * other sessions to follow its progress without interrupting it.
     */
    suspend fun activity(sessionId: String, limit: Int = 20): List<String> {
        val state = runCatching { conversation(sessionId).value }.getOrNull() ?: return emptyList()
        val lines = ArrayList<String>()
        for (item in state.items) {
            when (item) {
                is ChatItem.User -> lines += "user: " + item.text.oneLine(200)
                is ChatItem.Assistant -> {
                    item.blocks.forEach { b ->
                        when (b) {
                            is Block.Text -> if (b.text.isNotBlank()) lines += "said: " + b.text.oneLine(300)
                            is Block.ToolCall -> lines += "ran ${b.name}: " + (b.intent ?: b.arguments.toString()).oneLine(160)
                            else -> Unit
                        }
                    }
                    item.error?.let { lines += "error: " + it.oneLine(200) }
                }
                is ChatItem.ToolResult -> if (item.isError) lines += "${item.toolName} failed: " + item.text.oneLine(200)
                is ChatItem.Bash -> lines += "bash: " + item.command.oneLine(120) + (item.exitCode?.let { " → exit $it" } ?: "")
                is ChatItem.Notice -> lines += "notice: " + item.text.oneLine(160)
                else -> Unit
            }
        }
        if (state.working) lines += "(working now)"
        return lines.takeLast(limit)
    }

    private fun String.oneLine(max: Int): String = replace('\n', ' ').trim().let { if (it.length > max) it.take(max - 1) + "…" else it }

    /** After a command that changed the transcript (branch, fork, switch, new): the GUI reads it again. */
    suspend fun reloadConversation(sessionId: String) {
        live[sessionId]?.reloadConversation()
    }

    private suspend fun sessionFor(id: String): AgentSession {
        val initial = deps.store.get(id) ?: throw app.umbra.core.session.SessionNotFoundException(id)
        return live.getOrPut(id) { AgentSession(initial, deps, scope) { ended -> live.remove(ended.sessionId, ended) } }
    }

    /** Raised for new managed input during an emergency input lockdown. */
    class LockdownException(reason: String) : IllegalStateException("Emergency input lockdown is in force ($reason). Lift it in Emergency Control to give the agent new input.")

    private fun checkFence() {
        deps.fence()?.let { throw LockdownException(it) }
    }

    /** Raised while a session's conversation is open in the omp TUI. */
    class OpenInTuiException(sessionId: String) : IllegalStateException("This conversation is open in the omp TUI terminal. Close the TUI (or its terminal) to continue here.")

    private suspend fun checkTui(sessionId: String) {
        val stillOpen = tuiHolds[sessionId] ?: return
        if (stillOpen()) throw OpenInTuiException(sessionId)
        tuiHolds.remove(sessionId)
        // The TUI may have moved to another conversation file (new, fork, resume): continue with the newest one.
        deps.store.update(sessionId) { it.copy(ompSessionFile = null) }
    }

    fun isOpenInTui(sessionId: String): Boolean = tuiHolds.containsKey(sessionId)

    /** Lets the omp TUI take the conversation: releases the agent's process (refused while it works). */
    suspend fun releaseForTui(sessionId: String, stillOpen: suspend () -> Boolean): AgentSession.TuiLaunch {
        val state = deps.store.get(sessionId)?.state ?: throw app.umbra.core.session.SessionNotFoundException(sessionId)
        check(state != SessionState.RUNNING && state != SessionState.WAITING_APPROVAL) { "The agent is working on this session; pause or stop it before opening the TUI." }
        val session = sessionFor(sessionId)
        session.releaseProcess()
        val launch = session.tuiLaunch()
        tuiHolds[sessionId] = stillOpen
        return launch
    }

    suspend fun send(sessionId: String, message: String, images: List<ContentBlock.Image> = emptyList(), whileRunning: StreamingBehavior = StreamingBehavior.STEER, messageSource: String = "user"): Unit = owned {
        val state = deps.store.get(sessionId)?.state ?: throw app.umbra.core.session.SessionNotFoundException(sessionId)
        checkTui(sessionId)
        checkFence()
        if (sessionId !in counted) {
            // Every counted session holds one slot of the governor. A new or restarted task has to fit; one that is
            // already running or paused (for instance resumed after the app restarted) is let through.
            // A follow-up to a session whose agent process already runs (kept for follow-ups, or started by a GUI
            // command) needs no new process, so it is admitted like a running one.
            val alreadyHere = state == SessionState.PAUSED || state == SessionState.RUNNING || state == SessionState.WAITING_APPROVAL ||
                live[sessionId]?.isProcessAlive == true
            if (!alreadyHere) {
                // The resource policy's maximum for the session's project (the device's own limit comes next).
                val session = deps.store.get(sessionId)
                deps.admission(session?.projectId, runningByProject())?.let { throw CapacityException(it) }
                deps.serviceAdmission(sessionId)?.let { throw CapacityException(it) }
            }
            val took = governor?.tryStartSession() ?: false
            if (!took && !alreadyHere && governor != null) throw CapacityException(capacityReason())
            if (took) counted.add(sessionId)
        }
        // Started by hand while it waited in the queue: it is not queued any more.
        if (state == SessionState.CREATED) deps.store.update(sessionId) { if (it.queuedAtMs != null) it.copy(queuedAtMs = null) else it }
        try {
            sessionFor(sessionId).send(message, images, whileRunning, messageSource)
        } catch (e: Exception) {
            if (counted.remove(sessionId)) governor?.endSession()
            throw e
        }
    }

    /** Aborts the running turn and sends [message] instead. Falls back to [send] when nothing is running. */
    suspend fun interrupt(sessionId: String, message: String, images: List<ContentBlock.Image> = emptyList()) {
        checkTui(sessionId)
        val session = live[sessionId]
        checkFence()
        if (session != null && session.isProcessAlive) session.interrupt(message, images) else send(sessionId, message, images)
    }

    suspend fun pause(sessionId: String, reason: String = "paused by the user") {
        live[sessionId]?.pause(reason) ?: tryApply(sessionId, SessionCommand.PAUSE, reason)
    }

    suspend fun resume(sessionId: String, note: String? = null) {
        checkFence()
        val session = live[sessionId]
        if (session != null) session.resume(note) else send(sessionId, note ?: "The user resumed the task. Observe the current state, then continue where you left off.")
    }

    suspend fun stop(sessionId: String, reason: String = "stopped by the user") {
        val session = live.remove(sessionId)
        if (session != null) session.stop(reason) else tryApply(sessionId, SessionCommand.STOP, reason)
        if (counted.remove(sessionId)) governor?.endSession()
    }

    /** Starts a new session from an existing one (same request, goal, plan and workspace) and runs it again. */
    suspend fun duplicate(sessionId: String, run: Boolean = true, withHistory: Boolean = false): Session {
        val original = deps.store.get(sessionId) ?: throw app.umbra.core.session.SessionNotFoundException(sessionId)
        val copy = deps.store.duplicate(sessionId, SessionIds.generate(deps.clock.nowMs()), "${original.title} (copy)", withHistory)
            ?: throw app.umbra.core.session.SessionNotFoundException(sessionId)
        original.workspaceId?.let { runCatching { deps.workspaces.attachSession(it, copy.id) } }
        if (run) send(copy.id, original.request)
        return deps.store.get(copy.id) ?: copy
    }

    /**
     * Moves a session to another owning project, or makes it independent (null). Moving changes the owner only;
     * running work, its workspace and its links are untouched (architecture spec §2.1: Move ≠ Link).
     */
    suspend fun moveSession(sessionId: String, projectId: String?): Session =
        deps.store.update(sessionId) { it.copy(projectId = projectId, linkedContextIds = it.linkedContextIds - setOfNotNull(projectId)) }
            ?: throw app.umbra.core.session.SessionNotFoundException(sessionId)

    /** Links (or unlinks) a project's context to a session without changing its owner. */
    suspend fun linkContext(sessionId: String, projectId: String, linked: Boolean = true): Session =
        deps.store.update(sessionId) { s ->
            when {
                !linked -> s.copy(linkedContextIds = s.linkedContextIds - projectId)
                s.projectId == projectId || projectId in s.linkedContextIds -> s
                else -> s.copy(linkedContextIds = s.linkedContextIds + projectId)
            }
        } ?: throw app.umbra.core.session.SessionNotFoundException(sessionId)

    /**
     * Stops the session and deletes it with its timeline and its private files. Once begun it finishes: a caller
     * that goes away halfway (a screen showing the session it deletes) would otherwise leave its terminals running.
     */
    suspend fun delete(sessionId: String) = withContext(NonCancellable) {
        stop(sessionId, "deleted")
        deps.store.get(sessionId)?.workspaceId?.let { runCatching { deps.workspaces.detachSession(it, sessionId) } }
        runCatching { deps.conversations.delete(sessionId) }
        deps.store.delete(sessionId)
        runCatching { deps.onDeleted(sessionId) }
        Unit
    }

    /**
     * Deletes a workspace's folder once none of its tasks is new, queued, working, waiting or paused; a finished task
     * that still keeps its agent for follow-ups lets it go first. Throws WorkspaceInUseException naming the others.
     */
    suspend fun deleteWorkspace(workspaceId: String): Boolean {
        suspend fun active(sessionId: String) = deps.store.get(sessionId)?.state?.isTerminal == false
        val attached = deps.workspaces.get(workspaceId)?.sessionIds ?: return false
        for (sessionId in attached) if (!active(sessionId)) live.remove(sessionId)?.releaseProcess()
        return deps.workspaces.deleteIfIdle(workspaceId) { active(it) }
    }

    /** Stops every session and deletes all of them with their timelines and private files (spec §32: clear session data). */
    suspend fun deleteAll() {
        for (session in deps.store.list()) delete(session.id)
    }

    /** Stops every session that is working, waiting or paused, and empties the admission queue. */
    suspend fun stopAll() {
        for (session in deps.store.list()) {
            if (session.state == SessionState.CREATED && session.queuedAtMs != null) dequeue(session.id)
            else if (!session.state.isTerminal && session.state != SessionState.CREATED) stop(session.id)
        }
    }

    private suspend fun tryApply(sessionId: String, command: SessionCommand, reason: String) {
        try {
            deps.service.apply(sessionId, command, reason)
        } catch (_: IllegalTransitionException) {
        }
    }

    /** Sessions that hold an agent slot now, by project (null = independent). */
    suspend fun runningByProject(): Map<String?, Int> =
        deps.store.list().filter { it.state == SessionState.RUNNING || it.state == SessionState.WAITING_APPROVAL }.groupingBy { it.projectId }.eachCount()

    private fun capacityReason(): String {
        val decision = governor?.decision?.value
        return if (decision != null && !decision.allowNewSessions) {
            "The phone is under pressure (${decision.reasons.joinToString().ifBlank { decision.level.name.lowercase() }}); new sessions are paused until it recovers."
        } else {
            "The phone is already running as many agent sessions as it can handle (${decision?.maxConcurrentSessions ?: "?"}). Stop or wait for one to finish."
        }
    }

    // ---- observers ----

    /** WAITING_APPROVAL while the user has an approval or a question to answer for a session, RUNNING again when they have. */
    private suspend fun mirrorApprovals() {
        var previous = emptySet<String>()
        kotlinx.coroutines.flow.combine(deps.approvals.pending, deps.questions.pending) { approvals, questions ->
            approvals.map { it.sessionId }.toSet() + questions.map { it.sessionId }.toSet()
        }.collect { waiting ->
            for (id in waiting - previous) {
                if (deps.store.get(id)?.state == SessionState.RUNNING) tryApply(id, SessionCommand.AWAIT_APPROVAL, "waiting for the user")
            }
            for (id in previous - waiting) {
                if (deps.store.get(id)?.state == SessionState.WAITING_APPROVAL) tryApply(id, SessionCommand.APPROVAL_RESOLVED, "answered")
            }
            previous = waiting
        }
    }

    /** A session that is done holds no slot, so another can start (a queued one, if any waits). */
    private suspend fun releaseCapacity() {
        deps.store.changes.collect { change ->
            if (change is StoreChange.SessionChanged && change.session.state.isTerminal) {
                if (counted.remove(change.session.id)) governor?.endSession()
                wake.trySend(Unit)
            }
        }
    }

    companion object {
        /** At most this many requests wait for admission; beyond it a request is refused, not held. */
        const val QUEUE_LIMIT = 256

        fun titleOf(request: String): String {
            val line = request.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return if (line.length <= 60) line.ifEmpty { "New task" } else line.take(57).trimEnd() + "…"
        }
    }
}
