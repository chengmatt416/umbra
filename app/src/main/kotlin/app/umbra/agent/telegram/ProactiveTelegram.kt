package app.umbra.agent.telegram

import app.umbra.agent.AppContainer
import app.umbra.agent.agents.AgentProfile
import app.umbra.agent.agents.proactive.OpportunityCard
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Buttons bind a reviewed proposal version to one agent and expire; restart invalidates outstanding callbacks. */
internal class ProactiveTelegram(
    private val container: AppContainer,
    private val send: suspend (Long, String, JsonObject?) -> Unit,
) {
    private val reviews = OpportunityReviews()
    private val store get() = container.agents.proactive.store
    suspend fun command(chatId: Long, text: String?, p: AgentProfile): Boolean {
        val output = when (text) {
            "/goals" -> store.goals(p.id).joinToString("\n\n") { g -> "${g.title} · ${g.state}\n${g.milestones.count { it.status == "done" }}/${g.milestones.size} milestones\n${g.description.take(600)}" }.ifBlank { "No goals." }
            "/routines" -> store.routines(p.id).joinToString("\n\n") { r -> "${r.title} · ${if (r.enabled) "enabled" else "disabled"}\n${r.trigger}\n${r.recentRuns.lastOrNull()?.let { "${it.status}: ${it.summary.ifBlank { it.error.orEmpty() }}" }.orEmpty()}" }.ifBlank { "No routines." }
            "/skills" -> container.skills.list().filter { it.ownerAgentId == null || it.ownerAgentId == p.id }.joinToString("\n\n") { "${it.name} · v${it.version} · ${it.status}\n${it.description}\nApproval boundary: ${it.approvalBoundary}" }.ifBlank { "No skills." }
            "/brief" -> store.briefs(p.id).maxByOrNull { it.generatedAtMs }?.let(app.umbra.agent.agents.proactive.BriefText::format) ?: "No brief yet. Set its time in Proactivity."
            "/suggestions" -> { suggestions(chatId, p); return true }
            else -> return false
        }
        send(chatId, protected("${p.name}\n$output"), null)
        return true
    }
    private suspend fun suggestions(chatId: Long, p: AgentProfile) {
        val cards = store.opportunities(p.id).filter { it.state == OpportunityCard.State.PENDING && it.expiresAtMs?.let { end -> end <= System.currentTimeMillis() } != true }.sortedByDescending { it.urgency * it.confidence }.take(5)
        if (cards.isEmpty()) { send(chatId, "No pending suggestions.", null); return }
        for (card in cards) sendCard(chatId, p, card)
    }
    suspend fun offer(chatId: Long, p: AgentProfile, cardId: String): Boolean {
        val card = store.opportunities(p.id).find { it.id == cardId } ?: return false
        if (card.state != OpportunityCard.State.PENDING || card.expiresAtMs?.let { it <= System.currentTimeMillis() } == true) return false
        sendCard(chatId, p, card)
        return true
    }
    private suspend fun sendCard(chatId: Long, p: AgentProfile, card: OpportunityCard) {
        val token = reviews.create(card, chatId)
        send(chatId, protected("${p.name} · ${card.title}\n${card.summary}\nEvidence: ${card.evidenceRefs.joinToString()}"), keyboard("Review prepared step", "op:r:$token"))
    }
    fun clearReviews() { reviews.clear() }
    suspend fun callback(chatId: Long, data: String): String? {
        if (!data.startsWith("op:")) return null
        val parts = data.split(':')
        val token = parts.getOrNull(2) ?: return "Invalid suggestion button."
        val review = reviews.get(token, chatId) ?: return "This button expired. Open /suggestions again."
        val card = store.opportunities(review.agentId).find { it.id == review.cardId } ?: return "This suggestion was removed."
        if (reviews.validate(token, card, chatId) == null) {
            reviews.remove(token); return "This suggestion changed or expired. Open /suggestions again."
        }
        return try {
            when (parts.getOrNull(1)) {
                "r" -> {
                    check(reviews.markReviewed(token, card, chatId)) { "This suggestion changed or expired. Open /suggestions again." }
                    send(chatId, protected("${card.preparedAction.label}\nAction: ${card.preparedAction.actionType}\n${card.preparedAction.payloadJson}\n\nAdopting creates a task. Its ordinary action approvals and usage limits remain in force."), keyboard("Adopt this reviewed step", "op:y:$token"))
                    "Review the prepared action above."
                }
                "y" -> {
                    check(review.reviewed) { "Review the prepared step first." }
                    check(container.telegram.state.value.messages) { "Telegram task messages are disabled." }
                    val task = container.agents.proactive.adopt(review.agentId, review.cardId)
                    reviews.remove(token)
                    send(chatId, "Created task: ${task.title}\n${task.id}", null)
                    "Task created."
                }
                else -> "Invalid suggestion action."
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { protected(e.message ?: "The prepared step could not start. Review it in the app.").take(180) }
    }
    private fun protected(text: String) = app.umbra.core.safety.OutboundDlpGuard(4) { container.protectedValues() }.redact(text)
    private fun keyboard(label: String, data: String) = buildJsonObject { putJsonArray("inline_keyboard") { add(buildJsonArray { add(buildJsonObject { put("text", label); put("callback_data", data) }) }) } }
}
