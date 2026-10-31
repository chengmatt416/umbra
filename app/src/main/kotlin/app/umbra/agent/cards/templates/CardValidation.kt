package app.umbra.agent.cards.templates

import app.umbra.agent.cards.*
import app.umbra.core.model.UmbraJson
import kotlinx.serialization.json.*

/** Custom cards pass the same display bounds as templates before the UI gets their data. */
internal fun customCard(raw: JsonObject): CardSpec {
    val normalized = normalize(raw, "card") as JsonObject
    val spec = try {
        UmbraJson.decodeFromJsonElement(CardSpec.serializer(), JsonObject(normalized + ("template" to JsonPrimitive("custom"))))
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("card: ${e.message}", e)
    }
    val card = spec.copy(template = "custom", eyebrow = spec.eyebrow ?: "Card", icon = spec.icon ?: "grid")
    validateCard(card)
    return card
}

private fun normalize(value: JsonElement, path: String, key: String = "", kind: String? = null): JsonElement = when (value) {
    is JsonObject -> {
        val type = (value["type"] as? JsonPrimitive)?.content
        JsonObject(value.mapValues { (k, v) -> normalize(v, "$path.$k", k, type) }.filterValues { it != JsonNull })
    }
    is JsonArray -> {
        val limit = when {
            key == "actions" || key == "blocks" -> Int.MAX_VALUE
            key == "series" -> 2
            key == "points" && kind == "columns" -> 12
            key == "points" -> 40
            key == "labels" && kind == "line" -> 40
            key == "slices" -> 6
            key == "columns" && kind == "compare" -> 4
            key == "items" && kind == "stats" -> 6
            key == "values" && kind == "heatmap" -> 366
            else -> 30
        }
        JsonArray(value.take(limit).mapIndexed { i, v -> normalize(v, "$path[$i]", kind = kind) })
    }
    is JsonPrimitive -> {
        val enumNames: List<String>? = when {
            key == "tone" || key == "deltaTone" -> CardTone.entries.map { it.name }
            key == "state" -> CardBlock.Steps.State.entries.map { it.name }
            key == "kind" -> CardBlock.Form.Kind.entries.map { it.name }
            key == "style" && kind == "text" -> CardBlock.Text.TextStyle.entries.map { it.name }
            key == "style" && path.startsWith("card.actions[") -> CardAction.ActionStyle.entries.map { it.name }
            else -> null
        }
        if (enumNames != null && value != JsonNull) {
            require(value.isString && value.content.trim() in enumNames) { "$path must be one of ${enumNames.joinToString()}" }
        }
        if (value.isString) {
            val cap = when {
                key == "label" && path.startsWith("card.actions[") -> 40
                key in setOf("title", "label", "name", "id", "eyebrow", "icon", "unit", "badge", "sendLabel", "submitLabel") -> 80
                else -> 600
            }
            val text = value.content.trim().take(cap)
            if (text.isEmpty()) JsonNull else JsonPrimitive(text)
        } else value
    }
}

internal fun validateCard(card: CardSpec) {
    fun check(ok: Boolean, path: String, why: String) { require(ok) { "$path $why" } }
    check(!card.title.isNullOrBlank(), "card.title", "is required")
    check(card.blocks.size in 1..6, "card.blocks", "requires 1–6 blocks")
    check(card.actions.size <= 3, "card.actions", "allows at most three actions")
    check(card.actions.map { it.id }.distinct().size == card.actions.size, "card.actions", "ids must be unique")
    card.actions.forEachIndexed { i, a ->
        val p = "card.actions[$i]"
        check(a.id.isNotBlank() && a.label.isNotBlank(), p, "requires id and label")
        check(listOfNotNull(a.send, a.url, a.copy).size == 1, p, "requires exactly one send, url or copy")
        a.url?.let { https(it, "$p.url") }
    }
    val interactive = card.blocks.mapNotNull { it.interactiveId }
    check(interactive.all { it.isNotBlank() } && interactive.distinct().size == interactive.size, "card.blocks", "interactive ids must be nonempty and unique")
    val charts = mutableSetOf<String>()
    card.blocks.forEachIndexed { i, b ->
        val p = "card.blocks[$i]"
        fun count(n: Int, min: Int, max: Int, key: String) = check(n in min..max, "$p.$key", "requires $min–$max entries")
        fun ids(values: List<String>, key: String) = check(values.all { it.isNotBlank() } && values.distinct().size == values.size, "$p.$key", "ids must be nonempty and unique")
        fun chart(name: String) = check(charts.add(name), p, "duplicates a $name chart")
        when (b) {
            is CardBlock.Text -> check(b.text.isNotBlank(), "$p.text", "is required")
            is CardBlock.Stats -> {
                count(b.items.size, 2, 6, "items")
                check(b.items.all { it.label.isNotBlank() && it.value.isNotBlank() }, "$p.items", "requires labels and values")
            }
            is CardBlock.KeyValues -> {
                count(b.rows.size, 1, 30, "rows")
                check(b.rows.all { it.label.isNotBlank() && it.value.isNotBlank() }, "$p.rows", "requires labels and values")
            }
            is CardBlock.Items -> { count(b.items.size, 1, 30, "items"); check(b.items.all { it.title.isNotBlank() }, "$p.items", "requires titles") }
            is CardBlock.Table -> {
                count(b.columns.size, 1, 30, "columns"); count(b.rows.size, 1, 30, "rows")
                check(b.rows.all { it.size == b.columns.size }, "$p.rows", "must match columns")
                check(b.numericColumns.all { it in b.columns.indices }, "$p.numericColumns", "must reference columns")
                check(b.highlightRow == null || b.highlightRow in b.rows.indices, "$p.highlightRow", "must reference a row")
            }
            is CardBlock.Bars -> { chart("bars"); count(b.items.size, 1, 30, "items"); check(b.max == null || b.max > 0, "$p.max", "must be positive"); check(!b.unit.isNullOrBlank(), "$p.unit", "is required") }
            is CardBlock.Columns -> { chart("columns"); count(b.points.size, 5, 12, "points"); check(!b.unit.isNullOrBlank(), "$p.unit", "is required") }
            is CardBlock.Line -> {
                chart("line"); count(b.series.size, 1, 2, "series")
                b.series.forEachIndexed { si, s -> count(s.points.size, 2, 40, "series[$si].points") }
                check(b.series.map { it.points.size }.distinct().size == 1, "$p.series", "must have matching point counts")
                val points = b.series.flatMap { it.points }
                check((points.maxOrNull()!! - points.minOrNull()!!).isFinite(), "$p.series", "points must have a finite range")
                check(b.labels.isEmpty() || b.labels.size == b.series.first().points.size, "$p.labels", "must match points")
                check(!b.unit.isNullOrBlank(), "$p.unit", "is required")
            }
            is CardBlock.Donut -> {
                chart("donut"); count(b.slices.size, 2, 6, "slices")
                b.slices.forEachIndexed { si, s -> check(s.value >= 0, "$p.slices[$si].value", "must be nonnegative") }
                check(b.slices.sumOf { it.value }.isFinite(), "$p.slices", "total must be finite")
            }
            is CardBlock.Progress -> { chart("progress"); check(b.value in 0.0..1.0, "$p.value", "must be between 0 and 1") }
            is CardBlock.Gauge -> {
                chart("gauge"); check(b.max > b.min && (b.max - b.min).isFinite(), "$p.max", "must exceed min with a finite range")
                check(b.value in b.min..b.max, "$p.value", "must be within min and max")
            }
            is CardBlock.Steps -> count(b.steps.size, 1, 30, "steps")
            is CardBlock.Timeline -> count(b.events.size, 1, 30, "events")
            is CardBlock.Checklist -> { count(b.items.size, 1, 30, "items"); ids(b.items.map { it.id }, "items") }
            is CardBlock.Choice -> { count(b.options.size, 2, 30, "options"); ids(b.options.map { it.id }, "options") }
            is CardBlock.Form -> {
                count(b.fields.size, 1, 30, "fields"); ids(b.fields.map { it.id }, "fields")
                b.fields.forEachIndexed { fi, f ->
                    check(f.kind != CardBlock.Form.Kind.SELECT || f.options.isNotEmpty(), "$p.fields[$fi].options", "is required for SELECT")
                }
            }
            is CardBlock.Rating -> { check(b.max in 1..10, "$p.max", "must be between 1 and 10"); check(b.value == null || b.value in 0..b.max, "$p.value", "must be within the rating scale") }
            is CardBlock.Slider -> {
                check(b.max > b.min && (b.max - b.min).isFinite(), "$p.max", "must exceed min with a finite range")
                check(b.step > 0, "$p.step", "must be positive"); check(b.value in b.min..b.max, "$p.value", "must be within min and max")
            }
            is CardBlock.Quote -> check(b.text.isNotBlank(), "$p.text", "is required")
            is CardBlock.Code -> check(b.code.isNotBlank(), "$p.code", "is required")
            is CardBlock.Image -> { https(b.source, "$p.source"); check(b.aspect == null || b.aspect.isFinite() && b.aspect > 0, "$p.aspect", "must be positive and finite") }
            is CardBlock.Chips -> count(b.items.size, 1, 30, "items")
            is CardBlock.Callout -> check(b.text.isNotBlank(), "$p.text", "is required")
            is CardBlock.Compare -> { count(b.columns.size, 2, 4, "columns"); count(b.aspects.size, 1, 30, "aspects"); check(b.columns.all { it.values.size == b.aspects.size }, "$p.columns", "values must match aspects") }
            is CardBlock.Person -> check(b.name.isNotBlank(), "$p.name", "is required")
            is CardBlock.Link -> https(b.url, "$p.url")
            is CardBlock.Countdown -> CardData(buildJsonObject { put("at", b.at) }, p).at()
            is CardBlock.Heatmap -> { chart("heatmap"); count(b.values.size, 1, 366, "values"); b.values.forEachIndexed { vi, v -> check(v in 0.0..1.0, "$p.values[$vi]", "must be between 0 and 1") }; check(b.columns in 1..14, "$p.columns", "must be between 1 and 14") }
            CardBlock.Divider -> Unit
        }
    }
}
