package app.umbra.agent.agents

import kotlin.test.*

class WakeBudgetTest {
    private val min = 60_000L

    @Test fun backToBackRunsWaitAMinute() {
        val now = 100 * 60 * min
        assertTrue(WakeBudget.allows(emptyList(), now))
        val recent = WakeBudget.record(emptyList(), now)
        assertFalse(WakeBudget.allows(recent, now + 30_000))
        assertTrue(WakeBudget.allows(recent, now + min))
        assertEquals(now + min, WakeBudget.nextAllowedMs(recent, now + 10_000))
    }

    @Test fun aLoopThatReArmsItselfEveryMinuteGetsAtMostTwelveRunsAnHour() {
        var recent = emptyList<Long>()
        var runs = 0
        val start = 1_000 * min
        for (t in 0 until 60) {                 // a one-minute timer re-armed by every run, for an hour
            val now = start + t * min
            if (WakeBudget.allows(recent, now)) { recent = WakeBudget.record(recent, now); runs++ }
        }
        assertEquals(WakeBudget.MAX_PER_HOUR, runs)
        // The window rolls: an hour after the first run, one more is allowed.
        assertTrue(WakeBudget.allows(recent, start + 60 * min + 1))
    }

    @Test fun recordKeepsOnlyTheLastHour() {
        val now = 500 * min
        val old = listOf(now - 3 * 60 * min, now - 2 * 60 * min)
        assertEquals(listOf(now), WakeBudget.record(old, now))
    }
}
