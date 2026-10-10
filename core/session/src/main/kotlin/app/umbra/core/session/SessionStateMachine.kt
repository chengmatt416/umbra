package app.umbra.core.session

import app.umbra.core.session.SessionState.COMPLETED
import app.umbra.core.session.SessionState.CREATED
import app.umbra.core.session.SessionState.FAILED
import app.umbra.core.session.SessionState.PAUSED
import app.umbra.core.session.SessionState.RUNNING
import app.umbra.core.session.SessionState.STOPPED
import app.umbra.core.session.SessionState.WAITING_APPROVAL

/** User and system commands that move a session between states (spec §24, §32). */
enum class SessionCommand {
    START, PAUSE, RESUME, STOP, RESTART, COMPLETE, FAIL, AWAIT_APPROVAL, APPROVAL_RESOLVED,
}

object SessionStateMachine {
    /** The state after applying [command] in [state], or null if that is not allowed. */
    fun next(state: SessionState, command: SessionCommand): SessionState? = when (state) {
        CREATED -> when (command) {
            SessionCommand.START -> RUNNING
            SessionCommand.STOP -> STOPPED
            else -> null
        }
        RUNNING -> when (command) {
            SessionCommand.PAUSE -> PAUSED
            SessionCommand.AWAIT_APPROVAL -> WAITING_APPROVAL
            SessionCommand.STOP -> STOPPED
            SessionCommand.COMPLETE -> COMPLETED
            SessionCommand.FAIL -> FAILED
            else -> null
        }
        WAITING_APPROVAL -> when (command) {
            SessionCommand.APPROVAL_RESOLVED -> RUNNING
            SessionCommand.PAUSE -> PAUSED
            SessionCommand.STOP -> STOPPED
            SessionCommand.FAIL -> FAILED
            else -> null
        }
        PAUSED -> when (command) {
            SessionCommand.RESUME -> RUNNING
            SessionCommand.STOP -> STOPPED
            SessionCommand.FAIL -> FAILED
            else -> null
        }
        STOPPED, COMPLETED, FAILED -> when (command) {
            SessionCommand.RESTART -> RUNNING
            else -> null
        }
    }
}

class IllegalTransitionException(val state: SessionState, val command: SessionCommand) :
    IllegalStateException("cannot ${command.name.lowercase()} a session that is ${state.name.lowercase()}")

/** Applies [SessionCommand]s to stored sessions and records each change on the timeline. */
class SessionService(private val store: SessionStore) {
    suspend fun apply(id: String, command: SessionCommand, reason: String? = null, error: String? = null): Session {
        var from: SessionState? = null
        var failure: IllegalTransitionException? = null
        val updated = store.update(id) { s ->
            val next = SessionStateMachine.next(s.state, command)
            if (next == null) {
                failure = IllegalTransitionException(s.state, command)
                s
            } else {
                from = s.state
                s.copy(
                    state = next,
                    failureCount = if (command == SessionCommand.FAIL) s.failureCount + 1 else s.failureCount,
                    lastError = when {
                        command == SessionCommand.FAIL -> error ?: reason
                        command == SessionCommand.RESTART || command == SessionCommand.START -> null
                        else -> s.lastError
                    },
                    // A session that is no longer running holds no live resources.
                    processes = if (next.isTerminal) emptyList() else s.processes,
                    subagents = if (next.isTerminal) s.subagents.map { if (it.state == "running") it.copy(state = "aborted") else it } else s.subagents,
                )
            }
        } ?: throw SessionNotFoundException(id)
        failure?.let { throw it }
        store.append(id, SessionEvent.StateChanged(from!!, updated.state, reason ?: error))
        return updated
    }

    /** Convenience for the common "what can the user do now" question. */
    fun allowedCommands(state: SessionState): Set<SessionCommand> =
        SessionCommand.entries.filter { SessionStateMachine.next(state, it) != null }.toSet()
}
