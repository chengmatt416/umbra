package app.umbra.agent.capsule

import app.umbra.agent.AppContainer
import app.umbra.agent.agents.proactive.AttentionItem
import app.umbra.agent.agents.proactive.DeliveryTier
import app.umbra.agent.agents.proactive.OpportunityCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class CapsuleSuggestion(val id: String, val agentId: String, val agentName: String,
    val title: String, val summary: String, val priority: Double, val createdAtMs: Long, val expiresAtMs: Long?)
internal data class CapsuleSuggestionSnapshot(val items: List<CapsuleSuggestion> = emptyList(), val count: Int = 0,
    val nextExpiryAtMs: Long? = null, val unavailable: Boolean = false)

/** UI projections are cached by app-owned revisions and read only after a capsule broadcast. */
internal class CapsuleSuggestions(private val container: AppContainer) {
    private data class Owner(val id: String, val name: String, val running: Boolean)
    private val lock = Mutex()
    private var owners = emptyList<Owner>()
    private var revision = -1L
    private var cached = CapsuleSuggestionSnapshot()
    private var loaded = false
    suspend fun snapshot(now: Long): CapsuleSuggestionSnapshot = withContext(Dispatchers.IO) { lock.withLock {
        val currentOwners = container.agents.store.profiles.value.map { Owner(it.id, it.name, it.running) }.sortedBy { it.id }
        val store = container.agents.proactive.store
        val currentRevision = store.revision.value
        if (!loaded || currentOwners != owners || currentRevision != revision || cached.nextExpiryAtMs?.let { now >= it } == true) {
            cached = try {
                var count = 0
                var nextExpiry: Long? = null
                var top = emptyList<CapsuleSuggestion>()
                for (owner in currentOwners.filter { it.running }) {
                    val selected = select(owner.id, owner.name, store.opportunities(owner.id), store.attention(owner.id), now)
                    count += selected.size
                    selected.mapNotNull { it.expiresAtMs }.minOrNull()?.let { nextExpiry = minOf(nextExpiry ?: Long.MAX_VALUE, it) }
                    top = (top + selected).sortedWith(compareByDescending<CapsuleSuggestion> { it.priority }.thenByDescending { it.createdAtMs }).take(20)
                }
                CapsuleSuggestionSnapshot(top, count, nextExpiry)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { CapsuleSuggestionSnapshot(unavailable = true) }
            owners = currentOwners; revision = currentRevision; loaded = true
        }
        cached
    } }

    companion object {
        fun select(agentId: String, name: String, cards: List<OpportunityCard>, attention: List<AttentionItem>, now: Long): List<CapsuleSuggestion> {
            val visible = attention.filter { it.tier in setOf(DeliveryTier.CAPSULE_AMBIENT, DeliveryTier.STANDARD_NOTIFY, DeliveryTier.URGENT_BREAKTHROUGH) }.map { it.id }.toSet()
            return cards.filter { it.agentId == agentId && it.state == OpportunityCard.State.PENDING && it.id in visible &&
                (it.expiresAtMs == null || it.expiresAtMs > now) }.map {
                CapsuleSuggestion(it.id, agentId, name.take(100), it.title.take(200), it.summary.take(500), .6 * it.urgency + .4 * it.confidence, it.createdAtMs, it.expiresAtMs)
            }
        }
    }
}
