package app.umbra.agent.telegram

import app.umbra.agent.agents.proactive.OpportunityCard
import app.umbra.agent.agents.proactive.SkillStore
import app.umbra.core.model.UmbraJson
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Review and adoption buttons refer to the same complete proposal and expire after ten minutes. */
internal class OpportunityReviews(private val now: () -> Long = System::currentTimeMillis) {
    data class Review(val chatId: Long, val agentId: String, val cardId: String, val hash: String, val untilMs: Long, val reviewed: Boolean = false)
    private val values = ConcurrentHashMap<String, Review>()

    @Synchronized fun create(card: OpportunityCard, chatId: Long): String {
        require(pending(card)) { "This suggestion has already been handled or expired" }
        values.entries.removeIf { it.value.untilMs <= now() }
        if (values.size >= 200) values.clear()
        val token = UUID.randomUUID().toString().replace("-", "").take(24)
        values[token] = Review(chatId, card.agentId, card.id, hash(card), now() + 600_000)
        return token
    }
    fun get(token: String, chatId: Long): Review? = values[token]?.takeIf { it.chatId == chatId }?.let {
        if (it.untilMs > now()) it else { values.remove(token, it); null }
    }
    fun validate(token: String, card: OpportunityCard, chatId: Long): Review? = get(token, chatId)?.let {
        if (it.agentId == card.agentId && it.cardId == card.id && pending(card) && it.hash == hash(card)) it
        else { values.remove(token, it); null }
    }
    fun markReviewed(token: String, card: OpportunityCard, chatId: Long): Boolean {
        val previous = validate(token, card, chatId) ?: return false
        return values.replace(token, previous, previous.copy(reviewed = true))
    }
    fun clear() { values.clear() }
    fun remove(token: String) { values.remove(token) }
    private fun pending(card: OpportunityCard) = card.state == OpportunityCard.State.PENDING && card.expiresAtMs?.let { it > now() } != false
    private fun hash(card: OpportunityCard) = SkillStore.hash(UmbraJson.encodeToString(OpportunityCard.serializer(), card))
}
