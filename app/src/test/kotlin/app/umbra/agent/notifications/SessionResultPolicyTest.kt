package app.umbra.agent.notifications

import app.umbra.core.session.Session
import app.umbra.core.session.SessionKind
import app.umbra.core.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionResultPolicyTest {
    private fun session(kind: SessionKind, state: SessionState) =
        Session("session", "Test", state, 1, request = "Test", kind = kind)

    @Test
    fun agentTurnsNeverAnnounceTaskFinishedButOtherTasksStillDo() {
        for (kind in SessionKind.entries) {
            for (before in listOf(SessionState.RUNNING, SessionState.WAITING_APPROVAL)) {
                assertEquals(kind != SessionKind.AGENT, shouldAnnounceResult(session(kind, SessionState.COMPLETED), before), "$kind from $before")
                assertTrue(shouldAnnounceResult(session(kind, SessionState.FAILED), before), "$kind failures remain visible")
            }
        }
    }

    @Test
    fun initialLoadsRepeatedStatesAndNonResultTransitionsStaySilent() {
        for (kind in SessionKind.entries) {
            for (state in SessionState.entries) {
                val session = session(kind, state)
                assertFalse(shouldAnnounceResult(session, null))
                assertFalse(shouldAnnounceResult(session, state))
                if (state != SessionState.COMPLETED && state != SessionState.FAILED) {
                    assertFalse(shouldAnnounceResult(session, SessionState.RUNNING))
                }
            }
        }
    }
}
