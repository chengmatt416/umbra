package app.umbra.agent.agents.proactive

import app.umbra.core.session.Session
import kotlinx.serialization.json.JsonPrimitive

object GoalAuthority {
    fun allows(goal: AgentGoal, task: Session): Boolean =
        goal.agentId == task.agentId && goal.autoAdvance && goal.authorizedAtMs != null &&
            task.attachments["goal.authorization"] == JsonPrimitive(goal.authorizedAtMs) &&
            goal.state in setOf(GoalState.IN_PROGRESS, GoalState.WAITING_USER) &&
            goal.milestones.any { milestone ->
                milestone.status in setOf("active", "verifying") &&
                    (milestone.assignedTo == "#${task.id}" || milestone.assignedTo?.let { ref ->
                        ref.startsWith("dispatch:") && task.request.startsWith("[GoalRun ${ref.removePrefix("dispatch:")}]")
                    } == true)
            }
}
