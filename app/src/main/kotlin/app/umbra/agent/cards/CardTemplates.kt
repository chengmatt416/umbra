package app.umbra.agent.cards

import app.umbra.agent.cards.templates.*
import app.umbra.core.model.UmbraJson
import kotlinx.serialization.json.*

/** A discoverable data contract keeps layout out of the agent's reply. */
data class CardTemplate(
    val id: String,
    val category: String,
    val name: String,
    val useWhen: String,
    val fields: List<Field>,
    val example: JsonObject,
    val build: (JsonObject) -> CardSpec,
) {
    data class Field(val name: String, val type: String, val required: Boolean, val description: String)
}

/** Templates share validation so a malformed figure cannot quietly become a plausible chart. */
object CardTemplates {
    val all: List<CardTemplate> = planningTemplates() + projectTemplates() + metricTemplates() + moneyTemplates() +
        travelTemplates() + peopleTemplates() + developmentTemplates() + knowledgeTemplates() + homeTemplates() + systemTemplates()
    private val byId = all.associateBy { it.id }

    fun get(id: String): CardTemplate? = byId[id]

    fun build(id: String, data: JsonObject): CardSpec {
        val template = get(id) ?: throw IllegalArgumentException(
            "Unknown template '$id'. Nearest ids: " + all.sortedWith(compareBy<CardTemplate> { distance(id, it.id) }.thenBy { it.id })
                .take(3).joinToString { it.id },
        )
        finiteNumbers(data, "data")
        return template.build(data)
    }

    fun fromArguments(args: JsonObject): Result<CardSpec> = runCatching {
        finiteNumbers(args, "arguments")
        require(args.keys.all { it in setOf("template", "data", "card") }) { "arguments: use {template, data} or {card}" }
        if ("card" in args) {
            require("template" !in args && "data" !in args) { "card: cannot combine card with template or data" }
            val card = args["card"] as? JsonObject ?: throw IllegalArgumentException("card must be an object")
            customCard(card)
        } else {
            val id = CardData(args).required("template", 80)
            val data = args["data"] as? JsonObject ?: throw IllegalArgumentException("data must be an object")
            build(id, data)
        }
    }

    fun catalog(category: String? = null, query: String? = null): String = buildString {
        val words = query.orEmpty().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val exactId = query?.trim()?.let { get(it) }?.id
        val matches = all.filter { t ->
            (category.isNullOrBlank() || t.category.equals(category.trim(), true)) &&
                (if (exactId != null) t.id == exactId else words.all { word -> "${t.id} ${t.name} ${t.category} ${t.useWhen} ${t.fields.joinToString { it.name }}".lowercase().contains(word) })
        }
        matches.groupBy { it.category }.forEach { (group, templates) ->
            appendLine("$group:")
            templates.forEach { t ->
                appendLine("${t.id} — use when ${t.useWhen} — fields: " + t.fields.joinToString { it.name + if (it.required) "*" else "" })
            }
        }
        if (matches.isEmpty()) appendLine("No matching templates.")
        append("Call show_card({template: \"id\", data: {...}}), or show_card({card: <CardSpec JSON>}) for custom. ")
        append("* = required. card_templates query matches all words. Template contracts and examples: ")
        // The compact catalog stays one line per template; a single-id lookup exposes the nested contracts.
        val exact = matches.singleOrNull()
        if (exact != null) {
            appendLine()
            exact.fields.forEach { appendLine("${it.name}: ${it.type} — ${it.description}") }
            append("Example data: ${exact.example}")
        } else append("filter by an id to see its field types and example.")
    }

    private fun distance(a: String, b: String): Int {
        var row = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, ac ->
            val next = IntArray(b.length + 1); next[0] = i + 1
            b.forEachIndexed { j, bc -> next[j + 1] = minOf(next[j] + 1, row[j + 1] + 1, row[j] + if (ac == bc) 0 else 1) }
            row = next
        }
        return row.last()
    }
}

internal fun finiteNumbers(element: JsonElement, path: String) {
    when (element) {
        is JsonObject -> element.forEach { (key, value) -> finiteNumbers(value, "$path.$key") }
        is JsonArray -> element.forEachIndexed { i, value -> finiteNumbers(value, "$path[$i]") }
        is JsonPrimitive -> if (!element.isString && element != JsonNull && element.booleanOrNull == null) {
            require(element.doubleOrNull?.isFinite() == true) { "$path must be a finite number" }
        }
    }
}
