package app.umbra.agent.agents.proactive

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Structured suggestions still need the card's context when no instruction field was prepared. */
internal object AdoptionInstruction {
    fun of(card: OpportunityCard, payload: JsonElement): String {
        require(payload is JsonObject) { "Action payload must be an object" }
        val direct = listOf("instruction", "task", "text").firstNotNullOfOrNull { key ->
            (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        }
        return buildString {
            appendLine("Carry out this user-adopted suggestion:")
            appendLine("Title: ${card.title}")
            appendLine("Summary: ${card.summary}")
            if (direct != null) appendLine("Instruction: $direct")
            appendLine()
            appendLine("Prepared action data (JSON):")
            append(payload)
        }
    }
}
