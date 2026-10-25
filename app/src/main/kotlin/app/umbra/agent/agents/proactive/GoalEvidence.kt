package app.umbra.agent.agents.proactive

import app.umbra.core.orchestrator.CompletionEvidence
import app.umbra.core.session.Session
import app.umbra.core.session.SessionState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Goal evidence links point to host-recorded receipts, not arbitrary model assertions or paths. */
object GoalEvidence {
    fun sessionId(reference: String): String = Regex("session:([A-Za-z0-9_-]{1,100})#verification")
        .matchEntire(reference)?.groupValues?.get(1) ?: error("Use a verified session:<id>#verification receipt as evidence")

    fun requireVerified(reference: String, agentId: String, session: Session?, delegatedSessionIds: Set<String> = emptySet()) {
        val id = sessionId(reference)
        require(session != null && session.id == id && session.state == SessionState.COMPLETED) { "The evidence task must be completed" }
        require(session.agentId == agentId || id in delegatedSessionIds) { "The evidence task does not belong to this agent or its verified handoffs" }
        val bundle = session.attachments[CompletionEvidence.KEY] as? JsonObject
        require(bundle?.get("sessionId") == JsonPrimitive(id) && (bundle["verification"] as? JsonObject)?.get("verified") == JsonPrimitive(true)) {
            "The task has no successful host verification receipt"
        }
    }
}
