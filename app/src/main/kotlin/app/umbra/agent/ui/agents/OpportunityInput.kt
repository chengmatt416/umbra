package app.umbra.agent.ui.agents

import app.umbra.agent.agents.proactive.PreparedNextStep
import app.umbra.core.model.UmbraJson
import kotlinx.serialization.json.*

/**
 * Plain instructions are edited as text; serialization retains the rest of the reviewed action. A message draft whose
 * payload is only the text and plain addressing fields is shown completely as readable fields ([context]) with its
 * text editable; anything else stays a full JSON review.
 */
internal class OpportunityInput private constructor(private val payload: JsonObject, private val key: String, val message: Boolean = false) {
    val text: String get() = payload.getValue(key).jsonPrimitive.content
    /** The other reviewed fields, read-only, in payload order. */
    val context: List<Pair<String, String>> get() = payload.filterKeys { it != key }.map { (k, v) -> k to v.jsonPrimitive.content }
    fun payload(text: String): String {
        require(text.isNotBlank() && text.length <= 20_000) { "Supply an instruction of at most 20000 characters" }
        return JsonObject(payload + (key to JsonPrimitive(text))).toString()
    }
    companion object {
        private val MESSAGE_FIELDS = setOf("to", "recipient", "chat", "channel", "connector", "subject")
        fun from(step: PreparedNextStep): OpportunityInput? {
            val payload = runCatching { UmbraJson.parseToJsonElement(step.payloadJson) as? JsonObject }.getOrNull() ?: return null
            if (step.actionType == "send_message_draft") {
                if ((payload["text"] as? JsonPrimitive)?.isString != true) return null
                val plain = payload.all { (k, v) -> k == "text" || k in MESSAGE_FIELDS && v is JsonPrimitive && v.isString }
                return if (plain) OpportunityInput(payload, "text", message = true) else null
            }
            if (step.actionType !in setOf("agent_turn", "start_coding")) return null
            val field = listOf("instruction", "task", "text").firstOrNull { it in payload } ?: return null
            if ((payload[field] as? JsonPrimitive)?.isString != true) return null
            return OpportunityInput(payload, field)
        }
    }
}
