package app.umbra.agent.agents.proactive

import app.umbra.core.session.Session
import kotlin.test.*

class HandoffAuthorityTest {
    private val task = Session("task", "Delegated work", createdAtMs = 1, request = "[Handoff h]", agentId = "specialist", spawnedBy = "source")
    private val handoff = AgentHandoff("h", "owner", "specialist", "Work", null, null, "Checked report", 100, emptyList(), 1,
        status = "running", hop = 1, sourceSessionId = "source", executionSessionId = "task")

    @Test fun deadlineAndPersistedOwnershipApplyBeforeEachDescendantAction() {
        assertTrue(HandoffAuthority.allows(handoff, task, 99, null))
        assertTrue(HandoffAuthority.allows(handoff.copy(status = "reported"), task, 99, null))
        assertFalse(HandoffAuthority.allows(handoff, task, 100, null))
        assertFalse(HandoffAuthority.allows(null, task, 1, null))
        for (status in listOf("pending", "expired", "completed", "failed"))
            assertFalse(HandoffAuthority.allows(handoff.copy(status = status), task, 1, null))
        assertFalse(HandoffAuthority.allows(handoff, task.copy(agentId = "other"), 1, null))
        assertFalse(HandoffAuthority.allows(handoff, task.copy(spawnedBy = "other"), 1, null))
        assertFalse(HandoffAuthority.allows(handoff.copy(hop = 5), task, 1, null))
    }

    @Test fun pausedGoalsAndReplannedOrFinishedMilestonesRevokeDelegatedWork() {
        val scoped = handoff.copy(goalId = "goal", milestoneId = "step")
        val goal = AgentGoal("goal", "owner", "Ship", "Work", createdAtMs = 1,
            milestones = listOf(GoalMilestone("step", "Build", status = "active", assignedTo = "#task", updatedAtMs = 1)))
        assertTrue(HandoffAuthority.allows(scoped, task, 1, goal))
        assertFalse(HandoffAuthority.allows(scoped, task, 1, null))
        assertFalse(HandoffAuthority.allows(scoped, task, 1, goal.copy(agentId = "other")))
        for (state in listOf(GoalState.PAUSED, GoalState.COMPLETED, GoalState.ARCHIVED))
            assertFalse(HandoffAuthority.allows(scoped, task, 1, goal.copy(state = state)))
        assertFalse(HandoffAuthority.allows(scoped, task, 1, goal.copy(milestones = emptyList())))
        assertFalse(HandoffAuthority.allows(scoped, task, 1, goal.copy(milestones = goal.milestones.map { it.copy(status = "done") })))
        assertFalse(HandoffAuthority.allows(scoped, task, 1, goal.copy(milestones = goal.milestones.map { it.copy(assignedTo = "#replacement") })))
    }
}
