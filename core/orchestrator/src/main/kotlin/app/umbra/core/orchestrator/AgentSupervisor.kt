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
import kotlinx.coroutines.launch

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
        approvalMode: String? = null,
        projectId: String? = null,
    ): Session {
        val now = deps.clock.nowMs()
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
            approvalMode = approvalMode ?: deps.approvalMode().name.lowercase(),
            projectId = projectId,
        )
        return deps.store.create(session)
    }

    /** Creates a session and starts working on the request. */
    suspend fun launch(request: String, title: String? = null, workspaceId: String? = null, model: ModelRef? = null, projectId: String? = null): Session {
        val session = create(request, title, workspaceId, model, projectId = projectId)
        try {
            send(session.id, request)
        } catch (e: Exception) {
            // The session exists but could not start; the user sees why and can retry. The start may already have
            // moved it to running (or not): mark it failed from whichever state it is in, and keep the real error.
            runCatching {
                if (deps.store.get(session.id)?.state == SessionState.CREATED) deps.service.apply(session.id, SessionCommand.START, "starting")
                deps.service.apply(session.id, SessionCommand.FAIL, error = e.message ?: e::class.simpleName)
            }
            throw e
        }
        return deps.store.get(session.id) ?: session
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

    /** After a command that changed the transcript (branch, fork, switch, new): the GUI reads it again. */
    suspend fun reloadConversation(sessionId: String) {
        live[sessionId]?.reloadConversation()
    }

    private fun sessionFor(id: String): AgentSession = live.getOrPut(id) {
        AgentSession(id, deps, scope) { ended -> live.remove(ended.sessionId, ended) }
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

    suspend fun send(sessionId: String, message: String, images: List<ContentBlock.Image> = emptyList(), whileRunning: StreamingBehavior = StreamingBehavior.STEER) {
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
            val took = governor?.tryStartSession() ?: false
            if (!took && !alreadyHere && governor != null) throw CapacityException(capacityReason())
            if (took) counted.add(sessionId)
        }
        try {
            sessionFor(sessionId).send(message, images, whileRunning)
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

    suspend fun pause(sessionId: String) {
        live[sessionId]?.pause() ?: tryApply(sessionId, SessionCommand.PAUSE, "paused by the user")
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

    /** Stops the session and deletes it with its timeline and its private files. */
    suspend fun delete(sessionId: String) {
        stop(sessionId, "deleted")
        deps.store.get(sessionId)?.workspaceId?.let { runCatching { deps.workspaces.detachSession(it, sessionId) } }
        runCatching { deps.conversations.delete(sessionId) }
        deps.store.delete(sessionId)
    }

    /** Stops every session and deletes all of them with their timelines and private files (spec §32: clear session data). */
    suspend fun deleteAll() {
        for (session in deps.store.list()) delete(session.id)
    }

    /** Stops every session that is working, waiting or paused. */
    suspend fun stopAll() {
        for (session in deps.store.list()) if (!session.state.isTerminal && session.state != SessionState.CREATED) stop(session.id)
    }

    private suspend fun tryApply(sessionId: String, command: SessionCommand, reason: String) {
        try {
            deps.service.apply(sessionId, command, reason)
        } catch (_: IllegalTransitionException) {
        }
    }

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

    /** A session that is done holds no slot, so another can start. */
    private suspend fun releaseCapacity() {
        deps.store.changes.collect { change ->
            if (change is StoreChange.SessionChanged && change.session.state.isTerminal && counted.remove(change.session.id)) governor?.endSession()
        }
    }

    companion object {
        fun titleOf(request: String): String {
            val line = request.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return if (line.length <= 60) line.ifEmpty { "New task" } else line.take(57).trimEnd() + "…"
        }
    }
}
