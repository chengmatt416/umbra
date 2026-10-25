package app.umbra.agent.agents.proactive

/** A model may record evidence for a stopped goal, but only the user can reopen it. */
object GoalUpdates {
    private val closed = setOf(GoalState.PAUSED, GoalState.COMPLETED, GoalState.ARCHIVED)

    fun requireModelUpdate(previous: AgentGoal, next: AgentGoal) {
        require(previous.state !in closed || next.state == previous.state) {
            "Resume or reopen this goal in the app before continuing it."
        }
    }

    fun replannedState(previous: AgentGoal) = if (previous.state in closed) previous.state else GoalState.IN_PROGRESS
}
