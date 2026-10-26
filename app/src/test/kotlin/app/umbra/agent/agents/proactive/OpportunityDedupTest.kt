package app.umbra.agent.agents.proactive

import java.nio.file.Files
import kotlin.test.*

class OpportunityDedupTest {
    private fun store() = ProactiveStore(Files.createTempDirectory("dedup-test-").toFile())
    private fun card(id: String, title: String, refs: List<String>, agent: String = "ag1", createdAt: Long = 1, state: OpportunityCard.State = OpportunityCard.State.PENDING) =
        OpportunityCard(id, agent, OpportunityCard.Kind.FOLLOW_UP, title, "Summary of $title", refs, .6, .8,
            PreparedNextStep("Review", "agent_turn", "{}"), state = state, createdAtMs = createdAt)

    @Test fun specificReferencesNameOneItem() {
        assertEquals("calendar:42", OpportunityDedup.specific(" Calendar:42 "))
        assertEquals("message:1", OpportunityDedup.specific("message:1"))
        assertNull(OpportunityDedup.specific("calendar"))
        assertNull(OpportunityDedup.specific("email:"))
        assertNotNull(OpportunityDedup.specific("https://example.com/orders/7781"))
    }

    @Test fun sameEventOrSameWordsIsTheSameSuggestion() {
        assertTrue(OpportunityDedup.same(card("a", "Prepare for dentist", listOf("calendar:42")), card("b", "Dentist tomorrow: bring forms", listOf("calendar:42", "email:9"))))
        assertTrue(OpportunityDedup.same(card("a", "Reply to Anna about the lease", emptyList()), card("b", "Reply to Anna about the lease", listOf("calendar"))))
        assertTrue(OpportunityDedup.same(card("a", "回覆房東租約問題", emptyList()), card("b", "回覆房東租約問題", emptyList())))
        assertFalse(OpportunityDedup.same(card("a", "Prepare for dentist", listOf("calendar")), card("b", "Pay the electricity bill", listOf("calendar"))))
    }

    @Test fun aRepeatUpdatesTheOpenCardInsteadOfAddingOne() {
        val store = store()
        assertIs<ProactiveStore.Proposal.Created>(store.proposeOpportunity(card("opp1", "Prepare for dentist", listOf("calendar:42")), emptyList(), 10))
        val again = store.proposeOpportunity(card("opp2", "Dentist moved to 3 pm", listOf("calendar:42"), createdAt = 20), emptyList(), 20)
        assertIs<ProactiveStore.Proposal.Updated>(again)
        val only = store.opportunities("ag1").single()
        assertEquals("opp1", only.id)
        assertEquals("Dentist moved to 3 pm", only.title)
        assertEquals(1, only.createdAtMs)
        assertEquals(20, only.updatedAtMs)
    }

    @Test fun dismissedAdoptedAndOtherAgentsCardsStopTheRepeat() {
        val store = store()
        store.saveOpportunity(card("opp1", "Renew the passport", listOf("email:7"), createdAt = 100, state = OpportunityCard.State.DISMISSED))
        assertIs<ProactiveStore.Proposal.Duplicate>(store.proposeOpportunity(card("opp2", "Passport renewal", listOf("email:7"), createdAt = 200), emptyList(), 200))
        // Two weeks later the dismissal no longer blocks it.
        val later = 100 + OpportunityDedup.SETTLED_WINDOW_MS + 1
        assertIs<ProactiveStore.Proposal.Created>(store.proposeOpportunity(card("opp3", "Passport renewal", listOf("email:7"), createdAt = later), emptyList(), later))

        store.saveOpportunity(card("opp9", "Book the flight", listOf("email:8"), agent = "ag2"))
        assertIs<ProactiveStore.Proposal.Duplicate>(store.proposeOpportunity(card("opp4", "Book flight to Tokyo", listOf("email:8")), listOf("ag1", "ag2"), 300))
        assertTrue(store.opportunities("ag1").none { it.id == "opp4" })
    }

    @Test fun cardsUmbraKeepsAreNeitherMergedNorChangedByAgents() {
        val store = store()
        store.saveOpportunity(card("deadline:g1:5:false", "Goal due soon: thesis", listOf("goal:g1")))
        assertIs<ProactiveStore.Proposal.Duplicate>(store.proposeOpportunity(card("opp1", "Thesis due", listOf("goal:g1")), emptyList(), 2))
        assertFailsWith<IllegalArgumentException> { store.removeOpportunity("ag1", "deadline:g1:5:false") }
        assertFailsWith<IllegalArgumentException> { store.updateOpportunity("ag1", "deadline:g1:5:false", 3) { it.copy(title = "x") } }
    }

    @Test fun agentsUpdateAndWithdrawOnlyOpenCards() {
        val store = store()
        store.saveOpportunity(card("opp1", "Prepare for dentist", listOf("calendar:42")))
        store.stage("ag1", AttentionItem("opp1", "Prepare for dentist", "old", .6, .7, .8, "FOLLOW_UP", 1, tier = DeliveryTier.CAPSULE_AMBIENT))
        assertEquals("New time", store.updateOpportunity("ag1", "opp1", 5) { it.copy(title = "New time", state = OpportunityCard.State.ACCEPTED) }.title)
        assertEquals(OpportunityCard.State.PENDING, store.opportunities("ag1").single().state)
        store.removeOpportunity("ag1", "opp1")
        assertTrue(store.opportunities("ag1").isEmpty())
        assertTrue(store.attention("ag1").isEmpty())

        store.saveOpportunity(card("opp2", "Call the bank", listOf("message:3"), state = OpportunityCard.State.DISMISSED))
        assertFailsWith<IllegalArgumentException> { store.removeOpportunity("ag1", "opp2") }
        assertFailsWith<IllegalArgumentException> { store.updateOpportunity("ag1", "opp2", 6) { it } }
    }

    @Test fun existingDuplicatesFoldIntoTheNewest() {
        val store = store()
        store.saveOpportunity(card("opp1", "Prepare for dentist", listOf("calendar:42"), createdAt = 1))
        store.saveOpportunity(card("opp2", "Dentist prep", listOf("calendar:42", "email:1"), createdAt = 5))
        store.saveOpportunity(card("opp3", "Pay rent", listOf("message:9"), createdAt = 3))
        store.stage("ag1", AttentionItem("opp1", "Prepare for dentist", "", .6, .7, .8, "FOLLOW_UP", 1))
        assertEquals(1, store.collapseDuplicates("ag1"))
        assertEquals(setOf("opp2", "opp3"), store.opportunities("ag1").map { it.id }.toSet())
        assertEquals(listOf("calendar:42", "email:1"), store.opportunities("ag1").first { it.id == "opp2" }.evidenceRefs)
        assertTrue(store.attention("ag1").isEmpty())
        assertEquals(0, store.collapseDuplicates("ag1"))
    }

    @Test fun smallEventsAreNotGoals() {
        val now = 1_000_000_000L
        val events = listOf("Dentist appointment", "Team sync")
        assertNotNull(GoalScope.refusal("Dentist", "Go to the dentist", 1, null, now, events))
        assertNotNull(GoalScope.refusal("Pick up laundry", "Errand", 2, now + 3_600_000L, now, emptyList()))
        assertNotNull(GoalScope.refusal("Dentist appointment", "Prepare and attend", 3, null, now, events))
        assertNull(GoalScope.refusal("Run a half marathon", "Train for the October race", 4, now + 60 * 86_400_000L, now, events))
        assertNull(GoalScope.refusal("Ship the 1.0 release", "Finish the remaining features", 3, null, now, events))
        assertTrue(GoalScope.requested("Make this a goal please"))
        assertTrue(GoalScope.requested("幫我設一個目標"))
        assertFalse(GoalScope.requested("Remind me about the dentist"))
        assertFalse(GoalScope.requested(null))
    }
}
