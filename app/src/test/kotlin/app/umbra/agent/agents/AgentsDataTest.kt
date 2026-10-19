package app.umbra.agent.agents

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentsDataTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun show(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime().toString()

    @Test
    fun cronFindsTheNextMatchingMinute() {
        assertEquals("2026-10-19T09:00", show(Cron("0 9 * * *").next(at("2026-10-19T08:30"), zone)))
        assertEquals("2026-10-20T09:00", show(Cron("0 9 * * *").next(at("2026-10-19T09:00"), zone)))
        // Weekdays only: Friday evening goes to Monday.
        assertEquals("2026-10-26T09:00", show(Cron("0 9 * * 1-5").next(at("2026-10-23T18:00"), zone)))
        // Every 15 minutes.
        assertEquals("2026-10-19T10:15", show(Cron("*/15 * * * *").next(at("2026-10-19T10:01"), zone)))
        // Sunday as 7 and as 0.
        assertEquals(Cron("0 8 * * 0").next(at("2026-10-19T00:00"), zone), Cron("0 8 * * 7").next(at("2026-10-19T00:00"), zone))
        // Both day fields: either matches (the 1st, or a Monday).
        assertEquals("2026-10-26T07:00", show(Cron("0 7 1 * 1").next(at("2026-10-19T08:00"), zone)))
    }

    @Test
    fun badCronIsRefused() {
        assertFalse(Cron.isValid("0 9 * *"))
        assertFalse(Cron.isValid("61 * * * *"))
        assertFailsWith<IllegalArgumentException> { Cron("0 25 * * *") }
        assertTrue(Cron.isValid("0 9-17/2 * * 1-5"))
    }

    @Test
    fun memoryKeepsDatedSourcedLines() {
        val dir = Files.createTempDirectory("mem").toFile()
        val m = Memory(File(dir, "MEMORY.md"))
        m.add("Drinks oat milk", "WhatsApp", LocalDate.parse("2026-10-12"))
        m.add("Boss is Kevin; ask before replying", "user", LocalDate.parse("2026-10-15"))
        assertEquals(listOf("Drinks oat milk", "Boss is Kevin; ask before replying"), m.entries().map { it.text })
        assertTrue(m.edit("oat milk", "Drinks oat milk, never cow's milk"))
        assertEquals("Drinks oat milk, never cow's milk", m.entries().first().text)
        assertEquals(1, m.forgetBetween(LocalDate.parse("2026-10-14"), LocalDate.parse("2026-10-16")))
        assertEquals(1, m.entries().size)
        assertTrue(m.raw().startsWith(Memory.HEADER))
        assertEquals(1, m.forget("oat"))
        assertTrue(m.entries().isEmpty())
    }

    @Test
    fun storeRoundTripsProfilesAndLists() {
        val dir = Files.createTempDirectory("agents").toFile()
        val store = AgentStore(dir)
        store.save(AgentProfile("ag1", "Mia", createdAtMs = 1, sessionId = "s1"))
        store.setTimers("ag1", listOf(AgentTimer("t1", "milk", 5, null, "agent", 1)))
        store.setQueue("ag1", listOf(QueueItem("q1", "book a table", 2)))
        val again = AgentStore(dir)
        assertEquals("Mia", again.get("ag1")?.name)
        assertEquals("ag1", again.bySession("s1")?.id)
        assertEquals("milk", again.timers("ag1").single().note)
        assertEquals("book a table", again.queue("ag1").single().text)
    }
}
