package app.umbra.agent.cards.templates

import app.umbra.agent.cards.*
import app.umbra.core.model.UmbraJson
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.*

/** Paths survive nested parsing because the agent needs to know which supplied value to correct. */
internal open class CardData(val json: JsonObject, private val path: String = "data") {
    fun error(key: String, why: String): Nothing = throw IllegalArgumentException("$path.$key $why")
    fun text(key: String, cap: Int = 600): String? {
        val value = json[key] ?: return null
        if (value == JsonNull) return null
        val p = value as? JsonPrimitive ?: error(key, "must be a string")
        if (!p.isString) error(key, "must be a string")
        return p.content.trim().take(cap).takeIf { it.isNotEmpty() }
    }
    fun required(key: String, cap: Int = 600): String = text(key, cap) ?: error(key, "is required")
    fun number(key: String): Double {
        val p = json[key] as? JsonPrimitive ?: error(key, "must be a finite number")
        return p.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: error(key, "must be a finite number")
    }
    fun fraction(key: String): Double = number(key).also { if (it !in 0.0..1.0) error(key, "must be between 0 and 1") }
    fun bool(key: String, default: Boolean = false): Boolean {
        val value = json[key] ?: return default
        if (value == JsonNull) return default
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error(key, "must be a boolean")
    }
    fun array(key: String, max: Int = 30): List<JsonElement> {
        val value = json[key] ?: return emptyList()
        if (value == JsonNull) return emptyList()
        return (value as? JsonArray)?.take(max) ?: error(key, "must be an array")
    }
    fun objects(key: String, max: Int = 30): List<CardData> = array(key, max).mapIndexed { i, e ->
        CardData(e as? JsonObject ?: error("$key[$i]", "must be an object"), "$path.$key[$i]")
    }
    fun strings(key: String, max: Int = 30): List<String> = array(key, max).mapIndexedNotNull { i, e ->
        val p = e as? JsonPrimitive ?: error("$key[$i]", "must be a string")
        if (!p.isString) error("$key[$i]", "must be a string")
        p.content.trim().take(600).takeIf { it.isNotEmpty() }
    }
    fun obj(key: String): CardData? {
        val value = json[key] ?: return null
        if (value == JsonNull) return null
        return CardData(value as? JsonObject ?: error(key, "must be an object"), "$path.$key")
    }
    inline fun <reified T : Enum<T>> enum(key: String, default: T): T {
        val value = text(key) ?: return default
        return enumValues<T>().firstOrNull { it.name.equals(value, true) } ?: error(key, "must be one of ${enumValues<T>().joinToString { it.name }}")
    }
    fun tone(key: String = "tone") = enum(key, CardTone.NEUTRAL)
    fun url(key: String): String? = text(key)?.also { https(it, "$path.$key") }
    fun at(key: String = "at"): String = required(key).also {
        if (runCatching { OffsetDateTime.parse(it) }.isFailure && runCatching { Instant.parse(it) }.isFailure) error(key, "must be an ISO datetime with a timezone")
    }
}

internal fun https(value: String, field: String) {
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri != null && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) { "$field must be an https URL" }
}

private val shapes = mapOf(
    "stats" to "array<object{label:string,value:string,unit?:string,delta?:string,caption?:string,deltaTone?:string}>",
    "details" to "object{[label]:string}",
    "parties" to "object{[label]:string}",
    "labels" to "object{[field]:string}",
    "stages" to "array<object{label:string,count:number,percentage:number}>",
    "items" to "array<object{title:string,detail?:string,meta?:string,trailing?:string,icon?:string,tone?:string}>",
    "events" to "array<object{time:string,title:string,detail?:string,tone?:string}>",
    "steps" to "array<object{title:string,detail?:string,state?:string}>",
    "checks" to "array<object{id:string,text:string,done?:boolean,detail?:string,quantity?:string,aisle?:string}>",
    "options" to "array<object{id:string,label:string,detail?:string,badge?:string,recommended?:boolean}>",
    "bars" to "array<object{label:string,value:number,display?:string,tone?:string}>",
    "points" to "array<object{label:string,value:number}>",
    "slices" to "array<object{label:string,value:number,display?:string}>",
    "values" to "array<number>",
    "columns" to "array<string>", "rows" to "array<array<string>>", "chips" to "array<string>",
    "aspects" to "array<string>",
    "variants" to "array<object{title:string,values:array<string>,badge?:string,highlight?:boolean}>",
    "person" to "object{name:string,role?:string,detail?:string}",
    "fields" to "array<object{id:string,label:string,kind?:string,options?:array<string>,placeholder?:string,value?:string,required?:boolean}>",
    "actions" to "array<object{id:string,label:string,send?:string,url?:string,copy?:string,style?:string}>",
).let { shapes -> shapes + listOf("decisions", "wins", "misses", "failures", "pros", "cons").associateWith { shapes.getValue("items") } +
    listOf("first", "second").associateWith { shapes.getValue("person") } }
private val numbers = setOf("value", "min", "max", "spent", "limit", "current", "target", "streak", "best", "todo", "doing", "done", "total", "monthly", "yearly", "passed", "failed", "skipped", "added", "removed", "rating", "percentage", "rate")
private val booleans = setOf("multi")

/** Shared contracts make catalog examples and builders speak the same small input language. */
internal fun template(
    category: String, id: String, name: String, icon: String, useWhen: String, fields: String,
    example: String, layout: CardLayout.() -> Unit,
): CardTemplate {
    val keys = fields.split(' ').map { it.removeSuffix("*") }
    val extra = buildList {
        if (keys.any { it in setOf("figure", "value", "current", "spent", "values", "slices") }) addAll(listOf("label", "caption"))
        if ("slices" in keys) add("center")
        if ("points" in keys) add("series")
        if ("url" in keys) addAll(listOf("linkLabel", "linkDescription"))
        if (id == "receipt") add("totalLabel")
        if (id == "scorecard") addAll(listOf("label", "display"))
    }.joinToString(" ")
    val contracts = ("title* $fields $extra eyebrow subtitle text unit labels tone actions source as_of").split(' ').filter { it.isNotBlank() }
        .distinctBy { it.removeSuffix("*") }.map { token ->
            val key = token.removeSuffix("*")
            val type = shapes[key] ?: when (key) { in numbers -> "number"; in booleans -> "boolean"; "at" -> "iso-datetime"; else -> "string" }
            CardTemplate.Field(key, type, token.endsWith('*'), when (key) {
                "details" -> "Nonempty label/value pairs, in display order; omit unknown values."
                "points" -> "Labelled chart values; line 2–40, columns 5–12. Pass the numbers' unit."
                "checks" -> "Checklist entries with unique stable ids; quantity and aisle may be supplied."
                "stats" -> "2–6 labelled statistics; values are display strings, with optional units and deltas."
                "values" -> "Heatmap values from 0 to 1, in time order; at most 366."
                "slices" -> "2–6 nonnegative shares. Pass unit; display may override each formatted value."
                "options" -> "At least two choices with unique stable ids. A recommended choice needs a badge in the user's language."
                "fields" -> "Form fields with stable ids; kind is TEXT, TEXTAREA, NUMBER, DATE, TIME, TOGGLE or SELECT (needs options)."
                "steps" -> "Ordered steps; state is DONE, CURRENT, TODO, FAILED or SKIPPED."
                "tone" -> "Meaning only: NEUTRAL, GOOD (done/healthy), WARN (needs care/soon), DANGER (failed/overdue)."
                "unit" -> "Unit of the supplied numbers, in the user's language."
                "labels" -> "Optional labels for named fields (for example passed, failed, gate) in the user's language."
                "actions" -> when (id) {
                    "reminder" -> "Send actions with ids snooze and done, short verb labels and messages in the user's words."
                    "message_draft" -> "Send actions with ids send and edit; these ask the agent to act on the draft."
                    "confirm" -> "Send actions with ids confirm and cancel, describing the concrete action in the user's words."
                    else -> "At most three short verb labels, each with exactly one send, https url, or copy."
                }
                "severity" -> if (id == "risk_register") "Overall risk tone: NEUTRAL, WARN or DANGER." else "The supplied incident severity."
                "as_of" -> "When these facts were true; displayed in the footnote."
                else -> "${key.replace('_', ' ')} in the user's language; supply only known facts."
            })
        }
    return CardTemplate(id, category, name, useWhen, contracts, UmbraJson.parseToJsonElement(example) as JsonObject) { data ->
        finiteNumbers(data, "data")
        val d = CardLayout(data)
        contracts.forEach { field ->
            val value = data[field.name]
            if (value == null || value == JsonNull) {
                if (field.required) d.error(field.name, "is required")
                return@forEach
            }
            if (field.required && (value is JsonArray && value.isEmpty() || value is JsonObject && value.isEmpty())) d.error(field.name, "is required")
            when (field.type) {
                "string" -> if (field.required) d.required(field.name) else d.text(field.name)
                "number" -> d.number(field.name)
                "boolean" -> d.bool(field.name)
                "iso-datetime" -> d.at(field.name)
                "array<string>" -> if (d.strings(field.name).isEmpty() && field.required) d.error(field.name, "requires a nonempty string") else Unit
                else -> when {
                    field.type.startsWith("array<") -> d.array(field.name)
                    field.type.startsWith("object{") -> d.obj(field.name)
                    else -> Unit
                }
            }
            if (field.name == "tone") d.tone()
            if (field.required && field.type == "object{[label]:string}") {
                val rows = d.obj(field.name)!!
                if (rows.json.keys.none { rows.text(it) != null }) d.error(field.name, "requires at least one nonempty value")
            }
        }
        d.layout()
        d.text("text")?.let { text ->
            if (d.blocks.none { it is CardBlock.Text && it.text == text || it is CardBlock.Callout && it.text == text }) d.blocks += CardBlock.Text(text)
        }
        require(d.blocks.size in 1..6) { "data: expected 1–6 content blocks" }
        val actions = d.actions()
        val sendIds = when (id) {
            "reminder" -> setOf("snooze", "done")
            "message_draft" -> setOf("send", "edit")
            "confirm" -> setOf("confirm", "cancel")
            else -> emptySet()
        }
        if (sendIds.isNotEmpty() && (actions.any { it.send == null } || !actions.map { it.id }.containsAll(sendIds))) {
            d.error("actions", "requires send actions with ids ${sendIds.joinToString()}")
        }
        CardSpec(id, d.required("title", 80), d.text("subtitle"), d.text("eyebrow", 80) ?: name, icon,
            d.cardTone ?: d.tone(), d.hero, d.blocks.toList(), actions,
            listOfNotNull(d.text("source"), d.text("as_of")).takeIf { it.isNotEmpty() }?.joinToString(" · ")?.take(600))
            .also { validateCard(it) }
    }
}

internal class CardLayout(data: JsonObject) : CardData(data) {
    val blocks = mutableListOf<CardBlock>()
    var hero: CardHero? = null
    var cardTone: CardTone? = null
    fun add(block: CardBlock) { blocks += block }
    fun lead(key: String = "text", style: CardBlock.Text.TextStyle = CardBlock.Text.TextStyle.LEAD) { text(key)?.let { add(CardBlock.Text(it, style)) } }
    fun figure(key: String = "figure", caption: String? = text("caption")) { hero = CardHero(required(key, 80), text("unit", 80), caption, tone()) }
    fun kv(key: String = "details", emphasis: Set<String> = emptySet()) {
        val data = obj(key) ?: return
        val rows = data.json.keys.mapNotNull { label -> data.text(label)?.let { CardBlock.KeyValues.Row(label.trim().take(80), it, emphasis = label in emphasis) } }
            .filter { it.label.isNotEmpty() }.take(30)
        if (rows.isNotEmpty()) add(CardBlock.KeyValues(rows))
    }
    fun rows(vararg keys: String) {
        val labels = obj("labels")
        val rows = keys.mapNotNull { key -> text(key)?.let { CardBlock.KeyValues.Row(labels?.text(key, 80) ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }, it) } }
        if (rows.isNotEmpty()) add(CardBlock.KeyValues(rows))
    }
    fun stats(key: String = "stats") {
        val items = objects(key, 6).map { CardBlock.Stat(it.required("label", 80), it.required("value", 80), it.text("unit", 80), it.text("delta", 80), it.tone("deltaTone"), it.text("caption")) }
        if (items.isEmpty()) return
        if (items.size < 2) error(key, "needs 2–6 statistics")
        val groups = if (items.size == 5) listOf(items.take(3), items.drop(3)) else items.chunked(if (items.size == 6) 3 else 4)
        groups.forEach { add(CardBlock.Stats(it)) }
    }
    fun countStats(vararg keys: String) {
        val labels = obj("labels")
        add(CardBlock.Stats(keys.map {
            val value = number(it)
            if (value < 0 || value % 1 != 0.0) error(it, "must be a nonnegative integer")
            CardBlock.Stat(labels?.text(it, 80) ?: it.replaceFirstChar { c -> c.uppercase() }, value.toString().removeSuffix(".0"))
        }))
    }
    /** [fixedIcon] gives a list a mark of its own beside its tone, so two lists never differ by colour alone. */
    fun items(key: String = "items", numbered: Boolean = false, fixedTone: CardTone? = null, fixedIcon: String? = null) {
        val items = objects(key).map { CardBlock.Items.Item(it.required("title", 80), it.text("detail"), it.text("meta", 80), fixedIcon ?: it.text("icon", 80), fixedTone ?: it.tone(), it.text("trailing", 80)) }
        if (items.isNotEmpty()) add(CardBlock.Items(items, numbered))
    }
    fun steps(key: String = "steps") {
        val steps = objects(key).map { CardBlock.Steps.Step(it.required("title", 80), it.text("detail"), it.enum("state", CardBlock.Steps.State.TODO)) }
        if (steps.isNotEmpty()) add(CardBlock.Steps(steps))
    }
    fun timeline(key: String = "events") {
        val events = objects(key).map { CardBlock.Timeline.Event(it.required("time", 80), it.required("title", 80), it.text("detail"), it.tone()) }
        if (events.isNotEmpty()) add(CardBlock.Timeline(events))
    }
    fun checks(key: String = "checks") {
        val checks = objects(key).map { c -> CardBlock.Checklist.Check(c.required("id", 80), c.required("text", 80), c.bool("done"),
            listOfNotNull(c.text("detail"), c.text("quantity", 80), c.text("aisle", 80)).takeIf { it.isNotEmpty() }?.joinToString(" · ")?.take(600)) }
        unique(checks.map { it.id }, key)
        if (checks.isNotEmpty()) add(CardBlock.Checklist("checks", checks, text("sendLabel", 80)))
    }
    fun choice(id: String = "poll", multi: Boolean = bool("multi")) {
        val options = objects("options").map {
            val badge = it.text("badge", 80)
            if (it.bool("recommended") && badge == null) it.error("badge", "is required for a recommended option; use the user's language")
            CardBlock.Choice.Option(it.required("id", 80), it.required("label", 80), it.text("detail"), badge)
        }
        unique(options.map { it.id }, "options")
        if (options.size < 2) error("options", "needs at least two choices")
        add(CardBlock.Choice(id, options, multi, text("submitLabel", 80), text("question")))
    }
    fun bars(key: String = "bars", unit: String? = text("unit", 80), max: Double? = null) {
        val bars = objects(key).map { CardBlock.Bars.Bar(it.required("label", 80), it.number("value"), it.text("display", 80), it.tone()) }
        // Percentages are drawn against 100, not against the largest one: 60 % must not look like a full bar.
        val scale = max ?: if (unit?.trim() == "%" && bars.all { it.value in 0.0..100.0 }) 100.0 else null
        if (bars.isNotEmpty()) add(CardBlock.Bars(bars, max = scale, unit = unit))
    }
    fun points(max: Int = 40) = objects("points", max)
    fun line(spark: Boolean = false) {
        val points = points()
        if (points.size < 2) error("points", "needs 2–40 points")
        val unit = required("unit", 80)
        add(CardBlock.Line(listOf(CardBlock.Line.Series(text("series", 80) ?: required("title", 80), points.map { it.number("value") })),
            if (spark) emptyList() else points.map { it.required("label", 80) }, unit))
    }
    fun columns() {
        val points = points(12)
        if (points.size < 5) error("points", "needs 5–12 points")
        add(CardBlock.Columns(points.map { CardBlock.Columns.Point(it.required("label", 80), it.number("value")) }, required("unit", 80)))
    }
    fun donut() {
        val slices = objects("slices", 6)
        if (slices.size < 2) error("slices", "needs 2–6 slices")
        val unit = required("unit", 80)
        val values = slices.map { s ->
            val value = s.number("value"); if (value < 0) s.error("value", "must be nonnegative")
            CardBlock.Donut.Slice(s.required("label", 80), value, s.text("display", 80) ?: "${value.toString().removeSuffix(".0")} $unit")
        }
        add(CardBlock.Donut(values, text("center", 80), text("caption")))
    }
    fun progress(key: String = "value") { add(CardBlock.Progress(fraction(key), text("label", 80), text("caption"), tone())) }
    fun ratio(numerator: String, denominator: String) {
        val top = number(denominator)
        if (top <= 0) error(denominator, "must be greater than zero")
        val value = number(numerator)
        if (value < 0) error(numerator, "must be nonnegative")
        add(CardBlock.Progress((value / top).coerceIn(0.0, 1.0), text("label", 80), text("caption"), tone()))
    }
    fun heatmap() {
        val values = array("values", 366).mapIndexed { i, e -> CardData(buildJsonObject { put("value", e) }, "data.values[$i]").fraction("value") }
        add(CardBlock.Heatmap(values, caption = text("caption")))
    }
    fun table() {
        val columns = strings("columns", 8).map { it.take(80) }
        if (columns.isEmpty()) error("columns", "is required")
        val rows = array("rows").mapIndexed { i, e ->
            val cells = e as? JsonArray ?: error("rows[$i]", "must be an array of strings")
            if (cells.size != columns.size) error("rows[$i]", "must have ${columns.size} cells")
            CardData(buildJsonObject { put("cells", cells) }).strings("cells", 8)
                .also { if (it.size != columns.size) error("rows[$i]", "must not contain empty cells") }
        }
        add(CardBlock.Table(columns, rows))
    }
    fun chips(key: String = "chips") { strings(key).takeIf { it.isNotEmpty() }?.let { add(CardBlock.Chips(it.map { s -> s.take(80) })) } }
    fun link() { url("url")?.let { add(CardBlock.Link(text("linkLabel", 80) ?: required("title", 80), it, text("linkDescription"))) } }
    fun countdown() { add(CardBlock.Countdown(at(), text("label", 80))) }
    fun compare() {
        val aspects = strings("aspects").map { it.take(80) }
        val columns = objects("variants", 4).map {
            val values = it.strings("values")
            if (values.size != aspects.size) it.error("values", "must match aspects")
            CardBlock.Compare.Column(it.required("title", 80), values, it.text("badge", 80), it.bool("highlight"))
        }
        if (columns.size < 2) error("variants", "needs 2–4 columns")
        add(CardBlock.Compare(aspects, columns))
    }
    fun person(key: String = "person") {
        obj(key)?.let { add(CardBlock.Person(it.required("name", 80), it.text("role", 80), it.text("detail"))) }
    }
    fun code() { add(CardBlock.Code(required("code"), text("language", 80))) }
    fun callout(key: String = "text", tone: CardTone = tone()) { add(CardBlock.Callout(required(key), tone)) }
    fun actions(): List<CardAction> = objects("actions", 4).map { a ->
        val send = a.text("send"); val url = a.url("url"); val copy = a.text("copy")
        if (listOfNotNull(send, url, copy).size != 1) a.error("send/url/copy", "requires exactly one action")
        CardAction(a.required("id", 80), a.required("label", 40), a.enum("style", CardAction.ActionStyle.SECONDARY), send, url, copy)
    }.also { if (it.size > 3) error("actions", "allows at most three actions"); unique(it.map { a -> a.id }, "actions") }
    fun unique(ids: List<String>, key: String) { if (ids.distinct().size != ids.size) error(key, "ids must be unique") }
}
