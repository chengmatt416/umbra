package app.umbra.agent.agents.proactive

import kotlinx.serialization.json.Json
import kotlin.test.*

class AdoptionInstructionTest {
    private val card = OpportunityCard("orders", "agent", OpportunityCard.Kind.TASK_DISCOVERY,
        "Two Shopee orders look late", "Check delivery status and whether a refund is needed.",
        emptyList(), .5, .8, PreparedNextStep("Check orders", "agent_turn", "{}"), createdAtMs = 1)

    @Test fun taskInstructionKeepsTheCardAndStructuredData() {
        val payload = Json.parseToJsonElement("""{"task":"Check the orders","order_ids":["one","two"]}""")
        val instruction = AdoptionInstruction.of(card, payload)
        assertTrue("Instruction: Check the orders" in instruction)
        assertTrue(card.title in instruction && card.summary in instruction)
        assertTrue(payload.toString() in instruction)
    }

    @Test fun structuredPayloadWithoutInstructionUsesTheCard() {
        val payload = Json.parseToJsonElement("""{"order_ids":["one","two"],"store":"Shopee"}""")
        val instruction = AdoptionInstruction.of(card, payload)
        assertTrue(card.title in instruction && card.summary in instruction)
        assertTrue(payload.toString() in instruction)
    }

    @Test fun emptyObjectStillProducesAnInstruction() {
        val instruction = AdoptionInstruction.of(card, Json.parseToJsonElement("{}"))
        assertTrue(card.title in instruction && card.summary in instruction)
        assertTrue(instruction.endsWith("{}"))
    }

    @Test fun blankOrStructuredInstructionFieldsDoNotHideAUsefulTask() {
        for (payload in listOf("""{"instruction":" ","task":"Check orders"}""", """{"instruction":{},"task":"Check orders"}"""))
            assertTrue("Instruction: Check orders" in AdoptionInstruction.of(card, Json.parseToJsonElement(payload)))
        assertTrue("Instruction: Review" in AdoptionInstruction.of(card, Json.parseToJsonElement("""{"text":"Review"}""")))
    }

    @Test fun nonObjectPayloadsAreRejected() {
        for (payload in listOf("[]", "null", "42", "\"task\""))
            assertFailsWith<IllegalArgumentException> { AdoptionInstruction.of(card, Json.parseToJsonElement(payload)) }
    }
}
