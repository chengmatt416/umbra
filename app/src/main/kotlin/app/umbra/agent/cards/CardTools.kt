package app.umbra.agent.cards

import app.umbra.core.model.Clock
import app.umbra.runtime.omp.HostToolDefinition
import app.umbra.runtime.omp.HostToolPayload
import app.umbra.runtime.omp.objectSchema
import app.umbra.tools.HostSideTool
import java.util.ArrayDeque
import kotlinx.serialization.json.*

/** One instance belongs to the container so rebuilding a session's tool list cannot reset its card budget. */
class CardTools(private val clock: Clock = Clock { System.nanoTime() / 1_000_000 }) {
    private val lock = Any()
    private val shown = mutableMapOf<String, ArrayDeque<Long>>()

    fun forSession(sessionId: String): List<HostSideTool> = listOf(
        HostSideTool(HostToolDefinition("card_templates", "Find structured card templates. Filter by category or words; query an exact id for field types and example data.", objectSchema {
            string("category", "Planning, Projects, Metrics, Money, Travel, People, Development, Knowledge, Home or System.")
            string("query", "Words to match, or an exact template id.")
        })) { call, _ ->
            runCatching {
                val args = app.umbra.agent.cards.templates.CardData(call.arguments)
                CardTemplates.catalog(args.text("category"), args.text("query"))
            }.fold({ HostToolPayload.text(it) }, { HostToolPayload.error(it.message ?: "Invalid catalog arguments") })
        },
        HostSideTool(HostToolDefinition(CardCalls.SHOW, "Show structured information using {template, data}, or {card: CardSpec}. Use card_templates to discover fields. At most two cards per session in two minutes; otherwise use text.", objectSchema {
            string("template", "Template id from card_templates.")
            raw("data", buildJsonObject { put("type", "object"); put("additionalProperties", true); put("description", "Template data, including title and its required fields.") })
            raw("card", buildJsonObject { put("type", "object"); put("additionalProperties", true); put("description", "Custom CardSpec JSON; use instead of template and data.") })
        })) { call, _ -> show(sessionId, call.arguments) },
    )

    internal fun show(sessionId: String, arguments: JsonObject): HostToolPayload {
        val spec = CardTemplates.fromArguments(arguments).getOrElse { return HostToolPayload.error(it.message ?: "Invalid card arguments") }
        if (!allow(sessionId)) return HostToolPayload.error("Card limit reached: at most 2 cards per session per 2 minutes. Use text for this reply.")
        return HostToolPayload.text("Shown ${spec.template}.")
    }

    private fun allow(sessionId: String): Boolean = synchronized(lock) {
        val now = clock.nowMs()
        shown.values.forEach { times -> while (times.isNotEmpty() && now - times.first() >= WINDOW_MS) times.removeFirst() }
        shown.entries.removeAll { it.value.isEmpty() }
        val times = shown.getOrPut(sessionId) { ArrayDeque() }
        if (times.size >= 2) false else { times.addLast(now); true }
    }

    companion object {
        val NAMES = listOf("card_templates", CardCalls.SHOW)
        private const val WINDOW_MS = 120_000L
        const val GUIDE = "Use cards for structure: several numbers, a schedule, options to compare or choose, a checklist, a form, or a status with steps. " +
            "Keep plain answers, conversation, a single fact, explanations and code discussion in text. " +
            "Show at most one or two cards per reply, with a sentence of text around them. " +
            "Pick a template with card_templates when unsure. " +
            "Put the user's language in titles and labels. " +
            "Never put secrets, tokens or raw ids in a card. " +
            "Answers from interactive cards arrive as \"[Card · <title>] …\" messages."
    }
}
