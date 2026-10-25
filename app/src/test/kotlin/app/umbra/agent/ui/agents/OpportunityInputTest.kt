package app.umbra.agent.ui.agents

import app.umbra.agent.agents.proactive.PreparedNextStep
import kotlin.test.*
import kotlinx.serialization.json.*

class OpportunityInputTest {
    @Test fun instructionEditingPreservesUnicodeQuotesNewlinesAndOtherReviewedFields() {
        val input = assertNotNull(OpportunityInput.from(PreparedNextStep("Review", "agent_turn", """{"instruction":"Original","recipient":"someone@example.com","options":{"draft":true}}""")))
        assertEquals("Original", input.text)
        val message = "請改成「明天」\nSay \"hello\" 👋 ثم تابع"
        val payload = Json.parseToJsonElement(input.payload(message)).jsonObject
        assertEquals(message, payload["instruction"]!!.jsonPrimitive.content)
        assertEquals("someone@example.com", payload["recipient"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), payload["options"]!!.jsonObject["draft"])
        assertFailsWith<IllegalArgumentException> { input.payload("   ") }
        assertFailsWith<IllegalArgumentException> { input.payload("x".repeat(20_001)) }
    }

    @Test fun structuredActionsRemainFullyReviewedAndFieldPrecedenceMatchesExecution() {
        // A message draft with nested or unknown fields stays a full JSON review.
        assertNull(OpportunityInput.from(PreparedNextStep("Review", "send_message_draft", """{"text":"Hello","options":{"auto":true}}""")))
        assertNull(OpportunityInput.from(PreparedNextStep("Review", "send_message_draft", """{"text":"Hello","schedule":"now"}""")))
        assertNull(OpportunityInput.from(PreparedNextStep("Review", "run_skill", """{"instruction":"Hello"}""")))
        assertNull(OpportunityInput.from(PreparedNextStep("Review", "agent_turn", """{"instruction":null,"task":"Must not override"}""")))
        val coding = assertNotNull(OpportunityInput.from(PreparedNextStep("Review", "start_coding", """{"task":"Build","text":"Other field"}""")))
        assertEquals("Build", coding.text)
        assertEquals("Revised", Json.parseToJsonElement(coding.payload("Revised")).jsonObject["task"]!!.jsonPrimitive.content)
    }

    @Test fun plainMessageDraftsShowEveryFieldAndEditOnlyTheText() {
        val input = assertNotNull(OpportunityInput.from(PreparedNextStep("Review", "send_message_draft", """{"to":"Acme · WhatsApp","text":"Could we move our call to 16:00?"}""")))
        assertTrue(input.message)
        assertEquals("Could we move our call to 16:00?", input.text)
        assertEquals(listOf("to" to "Acme · WhatsApp"), input.context)
        val payload = Json.parseToJsonElement(input.payload("Could we do 17:30 instead?")).jsonObject
        assertEquals("Acme · WhatsApp", payload["to"]!!.jsonPrimitive.content)
        assertEquals("Could we do 17:30 instead?", payload["text"]!!.jsonPrimitive.content)
        assertFalse(assertNotNull(OpportunityInput.from(PreparedNextStep("Review", "agent_turn", """{"instruction":"x"}"""))).message)
    }
}
