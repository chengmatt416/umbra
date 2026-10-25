package app.umbra.agent.capsule

import app.umbra.agent.agents.proactive.*
import kotlin.test.*

class CapsuleSuggestionsTest {
    private fun card(id: String, expires: Long? = null, owner: String = "a", state: OpportunityCard.State = OpportunityCard.State.PENDING) =
        OpportunityCard(id, owner, OpportunityCard.Kind.FOLLOW_UP, "Review $id", "Prepare a draft", listOf("fixture"), .7, .9,
            PreparedNextStep("Review draft", "agent_turn", "{}"), state, 1, expires)
    private fun attention(id: String, tier: DeliveryTier) = AttentionItem(id, "Review", "Evidence", .7, .7, .9, "fixture", 1, tier = tier)
    @Test fun quietAndBriefOnlySuggestionsDoNotBecomeAmbientCapsuleNotifications() {
        val cards = listOf(card("quiet"), card("brief"), card("ambient"), card("notify"), card("urgent"), card("unranked"))
        val ranks = listOf(attention("quiet", DeliveryTier.SILENT_LOG), attention("brief", DeliveryTier.BRIEF_QUEUE),
            attention("ambient", DeliveryTier.CAPSULE_AMBIENT), attention("notify", DeliveryTier.STANDARD_NOTIFY), attention("urgent", DeliveryTier.URGENT_BREAKTHROUGH))
        assertEquals(listOf("ambient", "notify", "urgent"), CapsuleSuggestions.select("a", "Owner", cards, ranks, 2).map { it.id })
    }
    @Test fun expiredDismissedAdoptedAndForeignCardsCannotAppearOrExecuteFromTheCapsule() {
        val cards = listOf(card("expired", 10), card("dismissed", state = OpportunityCard.State.DISMISSED),
            card("adopted", state = OpportunityCard.State.ACCEPTED), card("claiming", state = OpportunityCard.State.ACCEPTING),
            card("foreign", owner = "b"), card("current", 20))
        val ranks = cards.map { attention(it.id, DeliveryTier.CAPSULE_AMBIENT) }
        val visible = CapsuleSuggestions.select("a", "Owner", cards, ranks, 10)
        assertEquals(listOf("current"), visible.map { it.id })
        assertEquals(20L, visible.single().expiresAtMs)
        assertEquals("a", visible.single().agentId)
        assertTrue(CapsuleSuggestions.select("a", "Owner", cards, ranks, 20).isEmpty())
    }
}
