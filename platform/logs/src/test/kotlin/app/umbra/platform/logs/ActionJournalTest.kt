package app.umbra.platform.logs

import app.umbra.core.model.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActionJournalTest {
    private var now = 10_000L
    private val journal = ActionJournal(Clock { now }, capacity = 4, graceMs = 3_000)

    @Test
    fun aLineDuringAnActionBelongsToIt() {
        journal.begin("a1", "s", "tap", 0, "com.x")
        now = 10_500
        journal.end("a1")
        assertEquals("a1", journal.actionAt(10_200)?.actionId)
    }

    @Test
    fun aRunningActionOwnsEverythingAfterItStarted() {
        journal.begin("a1", "s", "shell", 0, null)
        assertEquals("a1", journal.actionAt(99_000)?.actionId)
    }

    @Test
    fun aLateLineIsAttributedWithinTheGracePeriodOnly() {
        journal.begin("a1", "s", "tap", 0, "com.x")
        now = 10_100
        journal.end("a1")
        assertEquals("a1", journal.actionAt(12_000)?.actionId, "1.9 s after the tap")
        assertNull(journal.actionAt(14_000), "3.9 s after the tap is too late to blame it")
    }

    @Test
    fun theLatestOverlappingActionWins() {
        journal.begin("a1", "s", "tap", 0, null)
        now = 10_300
        journal.begin("a2", "s", "type_text", 0, null)
        assertEquals("a2", journal.actionAt(10_400)?.actionId)
        assertEquals("a1", journal.actionAt(10_200)?.actionId)
    }

    @Test
    fun sessionAndPackageFiltersKeepOtherWorkOut() {
        journal.begin("a1", "s1", "tap", 0, "com.x")
        journal.begin("a2", "s2", "tap", 1, "com.y")
        assertEquals("a1", journal.actionAt(10_001, sessionId = "s1")?.actionId)
        assertEquals("a2", journal.actionAt(10_001, packageName = "com.y")?.actionId)
        assertNull(journal.actionAt(10_001, sessionId = "s3"))
    }

    @Test
    fun theJournalForgetsTheOldestEntries() {
        for (i in 1..6) {
            journal.begin("a$i", "s", "tap", 0, null)
            journal.end("a$i")
            now += 10
        }
        assertNull(journal.get("a1"))
        assertNull(journal.get("a2"))
        assertEquals(listOf("a3", "a4", "a5", "a6"), journal.recent().map { it.actionId })
    }
}
