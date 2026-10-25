package app.umbra.agent.agents.proactive

import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class GoalScheduleTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli()
    private fun goal() = AgentGoal("g", "a", "Ship", "Work", createdAtMs = now, lastCheckedAtMs = now)

    @Test fun cadencePersistsAndCompletedOrPausedGoalsDoNotWake() {
        val goal = goal().copy(checkCadenceCron = "*/10 * * * *")
        assertEquals(now + 600_000, GoalSchedule.nextCheck(goal, zone))
        assertEquals(now + 300_000, GoalSchedule.nextCheck(goal(), zone))
        assertEquals(now, GoalSchedule.nextCheck(goal.copy(lastCheckedAtMs = null), zone))
        for (state in listOf(GoalState.COMPLETED, GoalState.ARCHIVED, GoalState.PAUSED, GoalState.SCHEDULED))
            assertNull(GoalSchedule.nextCheck(goal.copy(state = state), zone))
    }

    @Test fun deadlineAndStallBoundariesWakeEarlierWithoutRepeatingThePastBoundary() {
        val goal = goal().copy(checkCadenceCron = "0 0 * * *", deadlineMs = now + 120_000)
        assertEquals(now + 120_000, GoalSchedule.nextCheck(goal, zone))
        assertEquals(Instant.parse("2026-10-25T00:00:00Z").toEpochMilli(), GoalSchedule.nextCheck(goal.copy(lastCheckedAtMs = now + 120_000), zone))
        val stalled = goal().copy(lastProgressAtMs = now - 3_600_000 + 60_000, stalledAfterHours = 1)
        assertEquals(now + 60_000, GoalSchedule.nextCheck(stalled, zone))
        assertEquals(4, GoalSchedule.effectivePriority(goal().copy(priority = 4), now))
        assertEquals(3, GoalSchedule.effectivePriority(goal.copy(priority = 1), now))
        assertEquals(5, GoalSchedule.effectivePriority(goal, now + 120_000))
    }
}
