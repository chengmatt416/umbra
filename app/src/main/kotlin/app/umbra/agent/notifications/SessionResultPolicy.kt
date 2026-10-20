package app.umbra.agent.notifications

import app.umbra.core.session.Session
import app.umbra.core.session.SessionKind
import app.umbra.core.session.SessionState

internal fun shouldAnnounceResult(session: Session, before: SessionState?): Boolean {
    if (before != SessionState.RUNNING && before != SessionState.WAITING_APPROVAL) return false
    return when (session.state) {
        SessionState.COMPLETED -> session.kind != SessionKind.AGENT
        SessionState.FAILED -> true
        else -> false
    }
}
