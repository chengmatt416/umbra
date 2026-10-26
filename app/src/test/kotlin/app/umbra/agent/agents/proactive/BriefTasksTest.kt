package app.umbra.agent.agents.proactive

import app.umbra.core.session.Session
import app.umbra.core.session.SessionState
import kotlin.test.*

class BriefTasksTest {
    private val now = 3 * 86_400_000L
    private fun session(id: String, state: SessionState = SessionState.COMPLETED, updatedAtMs: Long = now - 1) =
        Session(id, "Task $id", state, createdAtMs = 1, updatedAtMs = updatedAtMs, request = "Work", agentId = "agent")

    @Test fun everySessionStateHasATaskStateAndKeepsItsResultAndLink() {
        val expected = mapOf(SessionState.CREATED to "running", SessionState.RUNNING to "running", SessionState.WAITING_APPROVAL to "waiting",
            SessionState.PAUSED to "paused", SessionState.STOPPED to "stopped", SessionState.COMPLETED to "completed", SessionState.FAILED to "failed")
        val sessions = expected.keys.map { state -> session(state.name, state).copy(resultSummary = "Result ${state.name}", lastError = "Old error") }
        val tasks = BriefTasks.of(sessions, "agent", now)
        assertEquals(expected.size, tasks.size)
        tasks.forEach { task ->
            val state = SessionState.valueOf(requireNotNull(task.linkedSessionId))
            assertEquals(expected[state], task.state)
            assertEquals("Task ${state.name}", task.title)
            assertEquals("Result ${state.name}", task.detail)
            assertEquals(now - 1, task.updatedAtMs)
        }
    }

    @Test fun missingOrBlankResultFallsBackToTheLastError() {
        val tasks = BriefTasks.of(listOf(session("failed", SessionState.FAILED).copy(lastError = "Connection lost"),
            session("stopped", SessionState.STOPPED).copy(resultSummary = " ", lastError = "Stopped by user"), session("empty")), "agent", now)
        assertEquals(listOf("Connection lost", "Stopped by user", ""), tasks.map { it.detail })
    }

    @Test fun previousBriefReplacesThe24HourFallbackAndUsesSessionUpdates() {
        val sessions = listOf(session("old", updatedAtMs = now - 36 * 3_600_000L), session("recent", updatedAtMs = now - 12 * 3_600_000L))
        assertEquals(listOf("recent"), BriefTasks.of(sessions, "agent", now).map { it.linkedSessionId })
        assertEquals(listOf("recent", "old"), BriefTasks.of(sessions, "agent", now, now - 48 * 3_600_000L).map { it.linkedSessionId })
        assertTrue(BriefTasks.of(sessions, "agent", now, now - 3_600_000L).isEmpty())
    }

    @Test fun windowExcludesItsStartFutureUpdatesAndOtherAgents() {
        val since = now - 86_400_000L
        val sessions = listOf(session("boundary", updatedAtMs = since), session("before", updatedAtMs = since - 1),
            session("future", updatedAtMs = now + 1), session("other").copy(agentId = "other"), session("current", updatedAtMs = now))
        assertEquals(listOf("current"), BriefTasks.of(sessions, "agent", now).map { it.linkedSessionId })
    }

    @Test fun runningAndWaitingComeFirstThenNewestTasksUpTo30() {
        val sessions = (1..40).map { session("done$it", updatedAtMs = now - it) } +
            session("running", SessionState.RUNNING, now - 100) + session("waiting", SessionState.WAITING_APPROVAL, now - 90)
        val tasks = BriefTasks.of(sessions.reversed(), "agent", now)
        assertEquals(30, tasks.size)
        assertEquals(listOf("waiting", "running", "done1", "done2"), tasks.take(4).map { it.linkedSessionId })
        assertEquals("done28", tasks.last().linkedSessionId)
    }
}
