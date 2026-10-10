package app.umbra.core.session

import app.umbra.core.session.SessionCommand.APPROVAL_RESOLVED
import app.umbra.core.session.SessionCommand.AWAIT_APPROVAL
import app.umbra.core.session.SessionCommand.COMPLETE
import app.umbra.core.session.SessionCommand.FAIL
import app.umbra.core.session.SessionCommand.PAUSE
import app.umbra.core.session.SessionCommand.RESTART
import app.umbra.core.session.SessionCommand.RESUME
import app.umbra.core.session.SessionCommand.START
import app.umbra.core.session.SessionCommand.STOP
import app.umbra.core.session.SessionState.COMPLETED
import app.umbra.core.session.SessionState.CREATED
import app.umbra.core.session.SessionState.FAILED
import app.umbra.core.session.SessionState.PAUSED
import app.umbra.core.session.SessionState.RUNNING
import app.umbra.core.session.SessionState.STOPPED
import app.umbra.core.session.SessionState.WAITING_APPROVAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SessionStateMachineTest {
    @Test
    fun legalTransitions() {
        val legal = mapOf(
            (CREATED to START) to RUNNING,
            (CREATED to STOP) to STOPPED,
            (RUNNING to PAUSE) to PAUSED,
            (RUNNING to AWAIT_APPROVAL) to WAITING_APPROVAL,
            (RUNNING to STOP) to STOPPED,
            (RUNNING to COMPLETE) to COMPLETED,
            (RUNNING to FAIL) to FAILED,
            (WAITING_APPROVAL to APPROVAL_RESOLVED) to RUNNING,
            (WAITING_APPROVAL to PAUSE) to PAUSED,
            (WAITING_APPROVAL to STOP) to STOPPED,
            (WAITING_APPROVAL to FAIL) to FAILED,
            (PAUSED to RESUME) to RUNNING,
            (PAUSED to STOP) to STOPPED,
            (PAUSED to FAIL) to FAILED,
            (STOPPED to RESTART) to RUNNING,
            (COMPLETED to RESTART) to RUNNING,
            (FAILED to RESTART) to RUNNING,
        )
        for ((key, expected) in legal) {
            assertEquals(expected, SessionStateMachine.next(key.first, key.second), "${key.first} + ${key.second}")
        }
        // Everything else is illegal.
        for (state in SessionState.entries) for (cmd in SessionCommand.entries) {
            if ((state to cmd) !in legal) assertNull(SessionStateMachine.next(state, cmd), "$state + $cmd must be illegal")
        }
    }

    @Test
    fun terminalStatesOnlyAllowRestart() {
        for (s in listOf(STOPPED, COMPLETED, FAILED)) {
            assertTrue(s.isTerminal)
            assertEquals(setOf(RESTART), SessionService(InMemorySessionStore()).allowedCommands(s))
        }
        assertTrue(RUNNING.isActive && WAITING_APPROVAL.isActive && !PAUSED.isActive)
    }

    private fun running() = runBlocking {
        val store = InMemorySessionStore()
        store.create(Session(id = "s1", title = "t", request = "r", createdAtMs = 1, state = RUNNING))
        store to SessionService(store)
    }

    @Test
    fun applyingACommandUpdatesTheSessionAndRecordsTheTransition(): Unit = runBlocking {
        val (store, service) = running()
        val paused = service.apply("s1", PAUSE, reason = "user pressed pause")
        assertEquals(PAUSED, paused.state)
        assertEquals(PAUSED, store.get("s1")?.state)
        val event = store.events("s1").single().event
        assertIs<SessionEvent.StateChanged>(event)
        assertEquals(RUNNING, event.from)
        assertEquals(PAUSED, event.to)
        assertEquals("user pressed pause", event.reason)
    }

    @Test
    fun anIllegalCommandThrowsAndLeavesNoTrace(): Unit = runBlocking {
        val (store, service) = running()
        val error = assertFailsWith<IllegalTransitionException> { service.apply("s1", RESUME) }
        assertEquals(RUNNING, error.state)
        assertEquals(RUNNING, store.get("s1")?.state)
        assertTrue(store.events("s1").isEmpty())
    }

    @Test
    fun applyingToAMissingSessionFails(): Unit = runBlocking {
        assertFailsWith<SessionNotFoundException> { SessionService(InMemorySessionStore()).apply("nope", START) }
    }

    @Test
    fun failingRecordsTheErrorAndCountsFailures(): Unit = runBlocking {
        val (store, service) = running()
        service.apply("s1", FAIL, error = "OMP crashed")
        val failed = store.get("s1")!!
        assertEquals(FAILED, failed.state)
        assertEquals(1, failed.failureCount)
        assertEquals("OMP crashed", failed.lastError)

        service.apply("s1", RESTART)
        val restarted = store.get("s1")!!
        assertEquals(RUNNING, restarted.state)
        assertNull(restarted.lastError, "a restart clears the last error")
        assertEquals(1, restarted.failureCount, "the failure count is history, not cleared")
    }

    @Test
    fun stoppingReleasesLiveResourcesInTheRecord(): Unit = runBlocking {
        val store = InMemorySessionStore()
        store.create(
            Session(
                id = "s1", title = "t", request = "r", createdAtMs = 1, state = RUNNING,
                processes = listOf(ProcessRef(1, "omp")),
                subagents = listOf(SubagentBrief("a", state = "running"), SubagentBrief("b", state = "finished")),
            ),
        )
        SessionService(store).apply("s1", STOP, reason = "user")
        val stopped = store.get("s1")!!
        assertTrue(stopped.processes.isEmpty())
        assertEquals(listOf("aborted", "finished"), stopped.subagents.map { it.state })
    }

    @Test
    fun theApprovalRoundTripKeepsTheSessionRunning(): Unit = runBlocking {
        val (store, service) = running()
        service.apply("s1", AWAIT_APPROVAL)
        assertEquals(WAITING_APPROVAL, store.get("s1")?.state)
        service.apply("s1", APPROVAL_RESOLVED)
        assertEquals(RUNNING, store.get("s1")?.state)
        assertEquals(2, store.eventCount("s1"))
    }
}
