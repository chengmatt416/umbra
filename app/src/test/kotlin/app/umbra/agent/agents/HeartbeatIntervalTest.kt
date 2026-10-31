package app.umbra.agent.agents

import kotlin.test.Test
import kotlin.test.assertEquals

class HeartbeatIntervalTest {
    @Test
    fun emptyHeartbeatsDoubleTheIntervalUpToFourTimes() {
        val interval = 10 * 60_000L
        assertEquals(interval, heartbeatIntervalMs(interval, 0))
        assertEquals(interval * 2, heartbeatIntervalMs(interval, 1))
        assertEquals(interval * 4, heartbeatIntervalMs(interval, 2))
        assertEquals(interval * 4, heartbeatIntervalMs(interval, Int.MAX_VALUE))
    }

    @Test
    fun activityRestoresTheConfiguredInterval() {
        val interval = 5 * 60_000L
        assertEquals(interval * 4, heartbeatIntervalMs(interval, 3))
        assertEquals(interval, heartbeatIntervalMs(interval, 0))
        assertEquals(interval, heartbeatIntervalMs(interval, -1))
    }
}
