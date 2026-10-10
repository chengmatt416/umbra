package app.umbra.core.control

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PlansTest {
    @TempDir
    lateinit var dir: Path

    private fun phases(vararg p: Pair<String, List<Pair<String, String>>>) = p.map { (n, t) -> PlanPhase(n, t.map { PlanTask(it.first, it.second) }) }

    @Test
    fun progressDoesNotMakeARevisionButAStructureChangeDoesAndHistoryStays() {
        val plans = PlanRegistry(ControlStore(dir))
        val r1 = plans.observe("s1", phases("Fix" to listOf("find" to "pending", "patch" to "pending")), "agent")
        assertEquals(1, r1!!.number)
        assertNull(plans.observe("s1", phases("Fix" to listOf("find" to "completed", "patch" to "in_progress")), "agent"), "status changes are progress")
        assertEquals("completed", plans.progressOf("s1")!!.phases[0].tasks[0].status)

        val r2 = plans.observe("s1", phases("Fix" to listOf("find" to "completed", "patch" to "in_progress"), "Verify" to listOf("run tests" to "pending")), "agent")!!
        assertEquals(2, r2.number)
        assertEquals(1, r2.baseRevision)
        assertTrue("phase added: Verify" in r2.changes, "${r2.changes}")
        val again = PlanRegistry(ControlStore(dir))
        assertEquals(listOf(1, 2), again.history("s1").map { it.number }, "revisions are kept and never rewritten")
        assertEquals(2, again.history("s1")[0].phases[0].tasks.size)
    }

    @Test
    fun anAgentRevisionThatDropsTheUsersTaskIsFlagged() {
        val plans = PlanRegistry(ControlStore(dir))
        plans.observe("s1", phases("Fix" to listOf("patch" to "pending")), "agent")
        plans.observe("s1", phases("Fix" to listOf("patch" to "pending", "keep the old API" to "pending")), "user")
        val r3 = plans.observe("s1", phases("Fix" to listOf("patch" to "in_progress", "rewrite the API" to "pending")), "agent")
        assertNotNull(r3!!.conflict)
        assertTrue("keep the old API" in r3.conflict!!)
    }

    @Test
    fun aCircularWaitIsFound() {
        val edges = listOf(WaitEdge("agent A", "terminal:1", "agent B"), WaitEdge("agent B", "gui:0", "agent A"), WaitEdge("agent C", "display:0", "you"))
        val cycles = WaitGraph.cycles(edges)
        assertEquals(1, cycles.size)
        assertEquals(setOf("agent A", "agent B"), cycles.single().toSet())
        assertTrue(WaitGraph.cycles(edges.drop(1)).isEmpty())
    }
}
