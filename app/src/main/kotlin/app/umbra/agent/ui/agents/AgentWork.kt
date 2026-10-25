package app.umbra.agent.ui.agents

import app.umbra.core.session.Session
import app.umbra.core.session.SessionState

/** Includes grandchildren and specialist handoffs even when they have a different owner agent. */
internal fun agentWork(agentId: String, conversationId: String?, sessions: List<Session>): List<Session> {
    val related = sessions.filter { it.agentId == agentId }.mapTo(mutableSetOf()) { it.id }
    conversationId?.let(related::add)
    var frontier = related.toSet()
    var depth = 0
    while (frontier.isNotEmpty() && depth++ < 32) {
        val next = sessions.filter { it.spawnedBy in frontier && it.id !in related }.mapTo(mutableSetOf()) { it.id }
        related += next
        frontier = next
    }
    return sessions.filter { it.id in related && it.id != conversationId && !it.state.isTerminal }
        .sortedWith(compareBy<Session> { when (it.state) {
            SessionState.RUNNING -> 0; SessionState.WAITING_APPROVAL -> 1; SessionState.PAUSED -> 2; else -> 3
        } }.thenByDescending { it.updatedAtMs })
}
