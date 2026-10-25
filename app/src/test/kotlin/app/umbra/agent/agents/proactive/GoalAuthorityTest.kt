package app.umbra.agent.agents.proactive

import app.umbra.core.session.Session
import kotlin.test.*
import kotlinx.serialization.json.JsonPrimitive

class GoalAuthorityTest {
    private val goal = AgentGoal("g", "owner", "Ship", "Work", createdAtMs = 1,
        autoAdvance = true, authorizedAtMs = 10,
        milestones = listOf(GoalMilestone("a", "Build", status = "active", assignedTo = "#task", updatedAtMs = 1)))
    private val task = Session("task", "Build", createdAtMs = 1, request = "[GoalRun key]\nBuild", agentId = "owner",
        attachments = mapOf("goal.authorization" to JsonPrimitive(10)))

    @Test fun finishedOrReassignedMilestonesRevokeThePreviousRun() {
        assertTrue(GoalAuthority.allows(goal, task))
        for (status in listOf("pending", "done", "failed", "skipped"))
            assertFalse(GoalAuthority.allows(goal.copy(milestones = goal.milestones.map { it.copy(status = status) }), task))
        assertFalse(GoalAuthority.allows(goal.copy(milestones = goal.milestones.map { it.copy(assignedTo = "#replacement") }), task))
        assertFalse(GoalAuthority.allows(goal.copy(agentId = "other"), task))
        assertTrue(GoalAuthority.allows(goal.copy(milestones = goal.milestones.map { it.copy(status = "verifying", assignedTo = "dispatch:key") }), task))
    }

    @Test fun pauseAndGrantChangesTakeEffectBeforeAnotherManagedAction() {
        for (state in listOf(GoalState.PAUSED, GoalState.ARCHIVED, GoalState.COMPLETED, GoalState.BLOCKED, GoalState.SCHEDULED))
            assertFalse(GoalAuthority.allows(goal.copy(state = state), task))
        assertFalse(GoalAuthority.allows(goal.copy(autoAdvance = false, authorizedAtMs = null), task))
        assertFalse(GoalAuthority.allows(goal.copy(authorizedAtMs = 11), task))
        assertTrue(GoalAuthority.allows(goal.copy(state = GoalState.WAITING_USER), task))
    }

    @Test fun modelsCannotReopenPausedArchivedOrCompletedGoals() {
        for (state in listOf(GoalState.PAUSED, GoalState.ARCHIVED, GoalState.COMPLETED)) {
            val stopped = goal.copy(state = state)
            GoalUpdates.requireModelUpdate(stopped, stopped.copy(description = "New evidence"))
            assertEquals(state, GoalUpdates.replannedState(stopped))
            for (next in GoalState.entries.filter { it != state })
                assertFailsWith<IllegalArgumentException> { GoalUpdates.requireModelUpdate(stopped, stopped.copy(state = next)) }
        }
        GoalUpdates.requireModelUpdate(goal.copy(state = GoalState.BLOCKED), goal)
        assertEquals(GoalState.IN_PROGRESS, GoalUpdates.replannedState(goal.copy(state = GoalState.BLOCKED)))
    }
}
