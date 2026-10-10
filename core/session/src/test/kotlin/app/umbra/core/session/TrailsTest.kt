package app.umbra.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrailsTest {
    private fun s(id: String, state: SessionState, updated: Long, plan: List<PlanStep> = emptyList()) =
        Session(id = id, title = id, state = state, createdAtMs = updated, updatedAtMs = updated, request = id, plan = plan)

    @Test
    fun theFocusedLiveSessionIsCurrent() {
        val all = listOf(
            s("done", SessionState.COMPLETED, 50),
            s("older-done", SessionState.FAILED, 10),
            s("a", SessionState.RUNNING, 100),
            s("b", SessionState.WAITING_APPROVAL, 200),
        )
        val t = Trails.sessions(all, focusedId = "a")
        assertEquals("a", t.current?.id)
        assertEquals("b", t.next?.id)
        assertEquals("done", t.previous?.id)
    }

    @Test
    fun withoutAFocusedLiveSessionTheLatestLiveOneIsCurrent() {
        val all = listOf(s("a", SessionState.RUNNING, 100), s("b", SessionState.PAUSED, 300), s("finished", SessionState.COMPLETED, 400))
        val t = Trails.sessions(all, focusedId = "finished")
        assertEquals("b", t.current?.id)
        assertEquals("a", t.next?.id)
        assertEquals("finished", t.previous?.id)
    }

    @Test
    fun aCreatedSessionWaitsAsNext() {
        val t = Trails.sessions(listOf(s("a", SessionState.RUNNING, 100), s("queued", SessionState.CREATED, 150)), null)
        assertEquals("queued", t.next?.id)
    }

    @Test
    fun nothingLiveMeansNoCurrent() {
        val t = Trails.sessions(listOf(s("x", SessionState.COMPLETED, 1)), null)
        assertNull(t.current)
        assertNull(t.next)
        assertEquals("x", t.previous?.id)
    }

    @Test
    fun stepsAroundTheActiveOne() {
        val plan = listOf(
            PlanStep("1", "Inspect", "done"),
            PlanStep("2", "Build", "done"),
            PlanStep("3", "Install", "active"),
            PlanStep("4", "Verify", "pending"),
        )
        val t = Trails.steps(s("a", SessionState.RUNNING, 1, plan))
        assertEquals("Build", t.previous?.text)
        assertEquals("Install", t.current?.text)
        assertEquals("Verify", t.next?.text)
    }

    @Test
    fun withoutAnActiveStepTheNextPendingOneIsNext() {
        val plan = listOf(PlanStep("1", "Inspect", "done"), PlanStep("2", "Build", "pending"), PlanStep("3", "Ship", "pending"))
        val t = Trails.steps(s("a", SessionState.PAUSED, 1, plan))
        assertEquals("Inspect", t.previous?.text)
        assertNull(t.current)
        assertEquals("Build", t.next?.text)
    }

    @Test
    fun aFinishedPlanHasOnlyAPrevious() {
        val t = Trails.steps(s("a", SessionState.COMPLETED, 1, listOf(PlanStep("1", "A", "done"), PlanStep("2", "B", "skipped"))))
        assertEquals("B", t.previous?.text)
        assertNull(t.current)
        assertNull(t.next)
        assertEquals(StepTrail(null, null, null), Trails.steps(null))
    }
}
