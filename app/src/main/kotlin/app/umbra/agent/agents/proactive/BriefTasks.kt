package app.umbra.agent.agents.proactive

import app.umbra.core.session.Session
import app.umbra.core.session.SessionState

/** Use session updates since the last brief so unfinished work is reported alongside results. */
internal object BriefTasks {
    /** [conversationId]: the agent's own conversation, which is where it talks, not one of its tasks. */
    fun of(sessions: List<Session>, agentId: String, now: Long, sinceMs: Long? = null, conversationId: String? = null): List<BriefTask> {
        val since = sinceMs ?: (now - 86_400_000L)
        return sessions.asSequence().filter { it.agentId == agentId && it.id != conversationId && it.updatedAtMs > since && it.updatedAtMs <= now }
            .sortedWith(compareByDescending<Session> { it.state.isActive || it.state == SessionState.CREATED }.thenByDescending { it.updatedAtMs })
            .take(30).map { session ->
                val state = when (session.state) {
                    // Created sessions are queued work, not completed work.
                    SessionState.CREATED, SessionState.RUNNING -> "running"
                    SessionState.WAITING_APPROVAL -> "waiting"
                    SessionState.PAUSED -> "paused"
                    SessionState.STOPPED -> "stopped"
                    SessionState.COMPLETED -> "completed"
                    SessionState.FAILED -> "failed"
                }
                BriefTask(session.title, state, session.resultSummary?.takeIf { it.isNotBlank() } ?: session.lastError.orEmpty(), session.id, session.updatedAtMs)
            }.toList()
    }
}
