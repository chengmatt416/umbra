package app.umbra.agent.telegram

import app.umbra.agent.agents.proactive.*
import kotlin.test.*

class TelegramFeaturesTest {
    @Test fun commandParsingPreservesWordsAndAcceptsTelegramMentions() {
        assertEquals(TelegramCommand("/findskill", "weekly reports"), telegramCommand(" /findskill weekly reports "))
        assertEquals(TelegramCommand("/findskill", "weekly reports"), telegramCommand("/findskill@umbra_bot\nweekly reports"))
        assertEquals(TelegramCommand("/skills", ""), telegramCommand("/skills"))
        assertEquals(TelegramCommand("/findskill", ""), telegramCommand("/findskill   "))
        assertNull(telegramCommand("Please find a skill"))
        assertNull(telegramCommand(null))
    }

    @Test fun suggestionReviewContainsOnlyThePlainLanguageExplanation() {
        val card = OpportunityCard("private-card-id", "private-agent-id", OpportunityCard.Kind.FOLLOW_UP,
            "Follow up <today>", "Prepare a note for Lena.", listOf("internal-evidence-id"), .8, .9,
            PreparedNextStep("internal label", "raw_action_type", "{\"payloadJson\":\"private-data\"}"), createdAtMs = 1,
            explain = CardExplanation("The meeting is tomorrow.", listOf("Draft a note.", "Show it to you."), "A date.", "The note waits.", listOf("Calendar · Meeting")))
        val html = telegramSuggestionText(card, "Mia & team", ::telegramTestText)
        listOf("payloadJson", "{", "raw_action_type", "private-card-id", "internal-evidence-id", "internal label").forEach { assertFalse(it in html, html) }
        listOf("<b>Follow up &lt;today&gt;</b>", "Why now: The meeting is tomorrow.", "If you confirm, Mia &amp; team will:",
            "1. Draft a note.\n2. Show it to you.", "It needs from you: A date.", "If you do nothing: The note waits.", "Based on: Calendar · Meeting").forEach { assertTrue(it in html, html) }
        val fallback = telegramSuggestionText(card.copy(explain = CardExplanation()), "Mia", ::telegramTestText)
        assertEquals("<b>Follow up &lt;today&gt;</b>\n\nPrepare a note for Lena.", fallback)
        assertEquals(fallback, telegramSuggestionText(card.copy(explain = null), "Mia", ::telegramTestText))
    }
}
