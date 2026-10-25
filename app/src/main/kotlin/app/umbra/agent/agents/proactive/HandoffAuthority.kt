package app.umbra.agent.agents.proactive

import app.umbra.core.session.Session

object HandoffAuthority {
    fun allows(handoff: AgentHandoff?, task: Session, now: Long, goal: AgentGoal?): Boolean {
        if (handoff == null || handoff.executionSessionId != task.id || handoff.toAgentId != task.agentId ||
            handoff.sourceSessionId != task.spawnedBy || handoff.hop !in 1..4 ||
            handoff.status !in setOf("running", "reported") || handoff.deadlineMs?.let { it <= now } == true) return false
        if (handoff.goalId == null) return handoff.milestoneId == null
        if (goal == null || goal.id != handoff.goalId || goal.agentId != handoff.fromAgentId ||
            goal.state in setOf(GoalState.PAUSED, GoalState.COMPLETED, GoalState.ARCHIVED)) return false
        return handoff.milestoneId == null || goal.milestones.any {
            it.id == handoff.milestoneId && it.assignedTo == "#${task.id}" && it.status in setOf("active", "verifying")
        }
    }
}
