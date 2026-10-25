package app.umbra.agent.telegram

import app.umbra.agent.agents.proactive.*
import kotlin.test.*

class OpportunityReviewsTest {
    private fun card() = OpportunityCard("o", "a", OpportunityCard.Kind.FOLLOW_UP, "Review", "A proposal", listOf("source:1"), .5, .9,
        PreparedNextStep("Review step", "agent_turn", "{}"), createdAtMs = 1)

    @Test fun adoptionRequiresReviewOfTheSameCompleteProposal() {
        val reviews = OpportunityReviews { 100 }
        val card = card()
        val token = reviews.create(card, 42)
        assertTrue(token.length <= 24)
        assertNull(reviews.get(token, 43))
        assertFalse(reviews.markReviewed(token, card, 43))
        assertFalse(reviews.validate(token, card, 42)!!.reviewed)
        assertTrue(reviews.markReviewed(token, card, 42))
        assertTrue(reviews.validate(token, card, 42)!!.reviewed)
        for (changed in listOf(card.copy(agentId = "other"), card.copy(id = "other"), card.copy(evidenceRefs = listOf("source:changed")),
            card.copy(summary = "Changed"), card.copy(preparedAction = card.preparedAction.copy(payloadJson = "{\"changed\":true}")),
            card.copy(state = OpportunityCard.State.ACCEPTED))) {
            val fresh = reviews.create(card, 42)
            assertTrue(reviews.markReviewed(fresh, card, 42))
            assertNull(reviews.validate(fresh, changed, 42))
            assertNull(reviews.validate(fresh, card, 42), "An invalidated button cannot revive")
        }
        reviews.remove(token)
        assertFalse(reviews.markReviewed(token, card, 42))
        assertNull(reviews.get(token, 42))
        val revoked = reviews.create(card, 42)
        reviews.clear()
        assertNull(reviews.get(revoked, 42))
    }

    @Test fun buttonsExpireAndRestartInvalidatesOldButtons() {
        var now = 100L
        val reviews = OpportunityReviews { now }
        val token = reviews.create(card(), 42)
        assertNull(OpportunityReviews { now }.get(token, 42))
        now += 600_000
        assertNull(reviews.get(token, 42))
        val short = card().copy(expiresAtMs = now + 1)
        val shortToken = reviews.create(short, 42)
        now++
        assertNull(reviews.validate(shortToken, short, 42))
        assertFailsWith<IllegalArgumentException> { reviews.create(short, 42) }
        assertFailsWith<IllegalArgumentException> { reviews.create(card().copy(state = OpportunityCard.State.DISMISSED), 42) }
    }
}
