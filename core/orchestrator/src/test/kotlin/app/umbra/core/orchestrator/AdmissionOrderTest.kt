package app.umbra.core.orchestrator

import app.umbra.core.session.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdmissionOrderTest {
    private fun queued(id: String, project: String?, at: Long) = Session(id, id, createdAtMs = at, request = id, projectId = project, queuedAtMs = at)

    @Test
    fun aHundredLogicalAgentsRunAtMostTheSlotsAndEveryOwnerGetsItsTurn() {
        // 100 requests: 70 from one busy project, 20 from another, 10 independent, all waiting on a phone with 3 slots.
        val waiting = ArrayList<Session>()
        var t = 0L
        repeat(70) { waiting += queued("a$it", "busy", t++) }
        repeat(20) { waiting += queued("b$it", "small", t++) }
        repeat(10) { waiting += queued("c$it", null, t++) }
        val slots = 3
        val running = LinkedHashMap<String, String?>()
        val started = ArrayList<Session>()
        var peak = 0
        while (waiting.isNotEmpty() || running.isNotEmpty()) {
            while (running.size < slots) {
                val counts = running.values.groupingBy { it }.eachCount()
                val next = AdmissionOrder.next(waiting, counts) { _, _ -> true } ?: break
                waiting.remove(next)
                running[next.id] = next.projectId
                started += next
            }
            peak = maxOf(peak, running.size)
            running.remove(running.keys.first())
        }
        assertEquals(100, started.size, "every logical agent was admitted in the end")
        assertEquals(slots, peak, "never more processes than slots")
        // Fair: the first nine starts come three from each owner, though the busy project queued 70 first.
        assertEquals(mapOf("busy" to 3, "small" to 3, null to 3), started.take(9).groupingBy { it.projectId }.eachCount())
        // Within an owner, the longest-waiting goes first.
        assertEquals((0 until 70).map { "a$it" }, started.filter { it.projectId == "busy" }.map { it.id })
    }

    @Test
    fun aFullBudgetIsPassedOverWithoutBlockingTheOthers() {
        val q = listOf(queued("p1", "full", 1), queued("i1", null, 2))
        val next = AdmissionOrder.next(q, mapOf("full" to 2)) { p, r -> p != "full" || (r[p] ?: 0) < 2 }
        assertEquals("i1", next?.id)
        assertNull(AdmissionOrder.next(listOf(q[0]), mapOf("full" to 2)) { p, r -> p != "full" || (r[p] ?: 0) < 2 })
        // The forecast still places the waiting one, after the others.
        assertEquals(listOf("i1", "p1"), AdmissionOrder.forecast(q, mapOf("full" to 2)) { p, r -> p != "full" || (r[p] ?: 0) < 2 }.map { it.id })
    }

    @Test
    fun theForecastIsTheOrderWhenNothingFinishes() {
        val q = listOf(queued("a1", "a", 1), queued("a2", "a", 2), queued("b1", "b", 3))
        val order = AdmissionOrder.forecast(q, mapOf("a" to 1))
        assertEquals(listOf("b1", "a1", "a2"), order.map { it.id })
        assertTrue(AdmissionOrder.forecast(emptyList(), emptyMap()).isEmpty())
    }
}
