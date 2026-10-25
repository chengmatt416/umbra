package app.umbra.agent.agents.proactive

import app.umbra.core.orchestrator.CompletionEvidence
import app.umbra.core.session.Session
import app.umbra.core.session.SessionState
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GoalEvidenceTest {
    private fun task(agent: String = "owner", verified: Boolean = true) = Session("task", "Task", createdAtMs = 0, request = "Verify", agentId = agent,
        state = SessionState.COMPLETED, attachments = mapOf(CompletionEvidence.KEY to buildJsonObject {
            put("sessionId", "task"); put("verification", buildJsonObject { put("verified", verified) })
        }))

    @Test fun onlySuccessfulOwnedOrDelegatedReceiptsCompleteMilestones() {
        GoalEvidence.requireVerified("session:task#verification", "owner", task())
        GoalEvidence.requireVerified("session:task#verification", "owner", task("specialist"), setOf("task"))
        assertFailsWith<IllegalArgumentException> { GoalEvidence.requireVerified("session:task#verification", "owner", task("other")) }
        assertFailsWith<IllegalArgumentException> { GoalEvidence.requireVerified("session:task#verification", "owner", task(verified = false)) }
        assertFailsWith<IllegalArgumentException> { GoalEvidence.requireVerified("session:task#verification", "owner", task().copy(state = SessionState.RUNNING)) }
        assertFailsWith<IllegalArgumentException> { GoalEvidence.requireVerified("session:task#verification", "owner", null) }
        assertFailsWith<IllegalStateException> { GoalEvidence.sessionId("/tmp/model-claimed-proof.txt") }
        assertFailsWith<IllegalArgumentException> { GoalEvidence.requireVerified("session:other#verification", "owner", task()) }
    }
}
