package app.umbra.agent.agents.proactive

import app.umbra.agent.agents.Cron
import java.time.ZoneId

/** Native reconciliation deadlines; a scheduled check does not itself call a model. */
object GoalSchedule {
    fun tracks(goal: AgentGoal) = goal.state in setOf(GoalState.IN_PROGRESS, GoalState.WAITING_USER, GoalState.BLOCKED)

    fun nextCheck(goal: AgentGoal, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!tracks(goal)) return null
        val checked = goal.lastCheckedAtMs ?: return goal.createdAtMs
        val cadence = goal.checkCadenceCron?.let { Cron(it).next(checked, zone) } ?: (checked + 300_000L)
        val boundaries = listOfNotNull(goal.deadlineMs?.minus(86_400_000L), goal.deadlineMs,
            goal.lastProgressAtMs + goal.stalledAfterHours * 3_600_000L).filter { it > checked }
        return (boundaries + cadence).minOrNull()
    }

    fun effectivePriority(goal: AgentGoal, now: Long): Int = when {
        goal.deadlineMs?.let { it <= now } == true -> 5
        goal.deadlineMs?.let { it - now <= 86_400_000L } == true -> maxOf(3, goal.priority)
        else -> goal.priority
    }
}
