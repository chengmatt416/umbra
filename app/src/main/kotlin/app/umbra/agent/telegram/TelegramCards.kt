package app.umbra.agent.telegram

import app.umbra.agent.R
import app.umbra.agent.cards.CardBlock
import app.umbra.agent.cards.CardSpec
import app.umbra.agent.cards.CardTone
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.serialization.json.*

internal fun telegramEscape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
internal fun telegramPlain(html: String): String = html.replace(Regex("</?(?:b|i|pre)>"), "")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
internal fun telegramButton(label: String, data: String) = buildJsonObject { put("text", label); put("callback_data", data) }
internal fun telegramUrl(label: String, url: String): JsonObject? = url.takeIf {
    runCatching { java.net.URI(it).let { u -> u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null } }.getOrDefault(false)
}?.let { buildJsonObject { put("text", label); put("url", it) } }
internal fun telegramKeyboard(rows: List<List<JsonObject>>) = buildJsonObject {
    putJsonArray("inline_keyboard") { rows.filter { it.isNotEmpty() }.forEach { add(JsonArray(it)) } }
}

/** Expiring opaque tokens keep agent data out of callbacks and bound the memory held by old messages. */
internal class TelegramTokens<T>(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry<T>(val value: T, val until: Long)
    private val entries = linkedMapOf<String, Entry<T>>()
    @Synchronized fun put(value: T): String {
        entries.entries.removeAll { it.value.until <= now() }
        while (entries.size >= 500) entries.remove(entries.keys.first())
        return UUID.randomUUID().toString().replace("-", "").take(20).also { entries[it] = Entry(value, now() + 86_400_000) }
    }
    @Synchronized fun get(token: String): T? = entries[token]?.let {
        if (it.until > now()) it.value else { entries.remove(token); null }
    }
    @Synchronized fun remove(token: String) { entries.remove(token) }
    @Synchronized fun clear() { entries.clear() }
}

/** Each chunk closes and reopens formatting so Telegram never sees half a tag, entity or surrogate pair. */
internal fun telegramHtmlParts(html: String): List<String> {
    val parts = mutableListOf<String>()
    val tags = mutableListOf<String>()
    var part = StringBuilder()
    fun close() = tags.asReversed().joinToString("") { "</$it>" }
    val atoms = Regex("<[^>]*>|&(?:amp|lt|gt);|[\\s\\S]").findAll(html)
    for (match in atoms) {
        val atom = match.value
        val nextTags = tags.toMutableList()
        when (atom) {
            "<b>", "<i>", "<pre>" -> nextTags += atom.drop(1).dropLast(1)
            "</b>", "</i>", "</pre>" -> if (nextTags.isNotEmpty()) nextTags.removeAt(nextTags.lastIndex)
        }
        val closingLength = nextTags.sumOf { it.length + 3 }
        if (part.length + atom.length + closingLength > 3900) {
            parts += part.toString() + close()
            part = StringBuilder(tags.joinToString("") { "<$it>" })
        }
        part.append(atom)
        tags.clear()
        tags.addAll(nextTags)
    }
    if (part.isNotEmpty()) parts += part.toString() + close()
    return parts
}

/** Cards use the same answer words as CardUi, so switching surfaces does not change what the agent hears. */
internal class TelegramCards(private val text: (Int) -> String) {
    data class Rendered(val html: String, val keyboard: JsonObject? = null, internal val token: String? = null)
    internal class Interaction(val spec: CardSpec, val nowMs: Long) {
        var agentId: String? = null
        var chatId: Long? = null
        val selected = mutableMapOf<Int, MutableSet<Int>>()
        var sending = false
    }
    data class Reply(val html: String, val keyboard: JsonObject?, val answer: String? = null, val agentId: String? = null)
    private val tokens = TelegramTokens<Interaction>()

    fun clear() = tokens.clear()
    @Synchronized fun bind(rendered: Rendered, agentId: String, chatId: Long) {
        rendered.token?.let(tokens::get)?.let { it.agentId = agentId; it.chatId = chatId }
    }
    @Synchronized fun render(spec: CardSpec, nowMs: Long): Rendered {
        val interaction = Interaction(spec, nowMs)
        spec.blocks.forEachIndexed { i, block -> if (block is CardBlock.Checklist) {
            interaction.selected[i] = block.items.indices.filter { block.items[it].done }.toMutableSet()
        } }
        val token = tokens.put(interaction)
        return Rendered(body(spec, nowMs), keyboard(interaction, token), token)
    }

    private fun body(spec: CardSpec, nowMs: Long) = buildList {
        add(listOfNotNull(spec.eyebrow?.let { telegramEscape(it.uppercase()) }, spec.title?.let { "${tone(spec.tone)}<b>${telegramEscape(it)}</b>" },
            spec.subtitle?.let { "<i>${telegramEscape(it)}</i>" }).joinToString("\n"))
        spec.hero?.let { add("<b>${telegramEscape(it.value)}</b>${unitHtml(it.unit)}" + optional(it.caption, "\n")) }
        spec.blocks.forEach { add(block(it, nowMs)) }
        spec.footnote?.let { add("<i>${telegramEscape(it)}</i>") }
    }.filter { it.isNotBlank() }.joinToString("\n\n")

    fun block(b: CardBlock, nowMs: Long): String = when (b) {
        is CardBlock.Text -> if (b.style == CardBlock.Text.TextStyle.LEAD) "<b>${telegramEscape(b.text)}</b>" else telegramEscape(b.text)
        is CardBlock.Stats -> b.items.joinToString("\n") { "${telegramEscape(it.label)}: <b>${telegramEscape(it.value)}</b>${unitHtml(it.unit)}" + optional(it.delta, " [", "]") }
        is CardBlock.KeyValues -> b.rows.joinToString("\n") { "${telegramEscape(it.label)} · <b>${telegramEscape(it.value)}</b>" }
        is CardBlock.Items -> b.items.mapIndexed { i, it -> "${if (b.numbered) "${i + 1}." else "•"} <b>${telegramEscape(it.title)}</b>" + optional(it.detail, " — ") + optional(it.meta, " · ") + optional(it.trailing, " · ") }.joinToString("\n")
        is CardBlock.Table -> table(b)
        is CardBlock.Bars -> {
            val max = b.max ?: if (b.unit == "%") 100.0 else b.items.maxOfOrNull { it.value } ?: 1.0
            b.items.joinToString("\n") { "${telegramEscape(it.label)} ${bar(if (max > 0) it.value / max else 0.0)} ${telegramEscape(it.display ?: number(it.value))}${unitHtml(b.unit)}" }
        }
        is CardBlock.Columns -> spark(b.points.map { it.value }, b.points.map { it.label }, b.unit)
        is CardBlock.Line -> b.series.joinToString("\n") { telegramEscape(it.name) + " " + spark(it.points, b.labels, b.unit) }
        is CardBlock.Donut -> {
            val total = b.slices.sumOf { it.value }.takeIf { it > 0 } ?: 1.0
            b.slices.joinToString("\n") { "${telegramEscape(it.label)} — ${number(it.value / total * 100)}%" }
        }
        is CardBlock.Progress -> "${bar(b.value)} ${number(b.value * 100)}%" + optional(b.label, " · ") + optional(b.caption, "\n")
        is CardBlock.Gauge -> "${telegramEscape(b.display ?: number(b.value))} / ${number(b.max)}" + optional(b.label, " · ")
        is CardBlock.Steps -> b.steps.joinToString("\n") {
            val symbol = when (it.state) { CardBlock.Steps.State.DONE -> "✓"; CardBlock.Steps.State.CURRENT -> "▶"; CardBlock.Steps.State.TODO -> "○"; CardBlock.Steps.State.FAILED -> "✗"; CardBlock.Steps.State.SKIPPED -> "–" }
            "$symbol ${telegramEscape(it.title)}" + optional(it.detail, " — ")
        }
        is CardBlock.Timeline -> b.events.joinToString("\n") { "${telegramEscape(it.time)} · ${telegramEscape(it.title)}" + optional(it.detail, " — ") }
        is CardBlock.Quote -> "<i>“${telegramEscape(b.text)}”</i>" + optional(b.author, " — ")
        is CardBlock.Code -> "<pre>${telegramEscape(b.code)}</pre>"
        is CardBlock.Chips -> b.items.joinToString(" · ") { "#${telegramEscape(it)}" }
        is CardBlock.Callout -> "${tone(b.tone).ifEmpty { "ℹ️ " }}" + optional(b.title, "", "\n") + telegramEscape(b.text)
        is CardBlock.Compare -> b.columns.joinToString("\n\n") { c -> "<b>${telegramEscape(c.title)}</b>" + optional(c.badge, " · ") + "\n" + c.values.mapIndexed { i, v -> optional(b.aspects.getOrNull(i), "", " · ") + telegramEscape(v) }.joinToString("\n") }
        is CardBlock.Person -> "👤 <b>${telegramEscape(b.name)}</b>" + optional(b.role, " — ") + optional(b.detail, "\n")
        is CardBlock.Link -> "🔗 ${telegramEscape(b.title)}" + optional(b.description, "\n")
        is CardBlock.Countdown -> {
            val at = b.at.toLongOrNull() ?: runCatching { Instant.parse(b.at).toEpochMilli() }.getOrDefault(nowMs)
            val hours = ((at - nowMs).coerceAtLeast(0) / 3_600_000)
            "⏳ " + if (at <= nowMs) telegramEscape(text(R.string.tgf_now)) else telegramEscape(text(R.string.tgf_countdown).format(hours / 24, hours % 24))
        }
        is CardBlock.Heatmap -> b.values.chunked(b.columns.coerceIn(1, 100)).joinToString("\n") { row -> row.joinToString("") { if (it > 0) "▣" else "▢" } } + optional(b.caption, "\n")
        is CardBlock.Image -> "🖼 " + telegramEscape(b.caption ?: text(R.string.tgf_image))
        CardBlock.Divider -> "—"
        is CardBlock.Choice -> listOfNotNull(b.question?.let(::telegramEscape), b.options.joinToString("\n") { telegramEscape(it.label) + optional(it.detail, " — ") }).joinToString("\n")
        is CardBlock.Checklist -> b.items.joinToString("\n") { "${if (it.done) "✓" else "○"} ${telegramEscape(it.text)}" + optional(it.detail, " — ") }
        is CardBlock.Rating -> optional(b.label, "", " · ") + "★".repeat(b.max.coerceIn(1, 10))
        is CardBlock.Slider -> telegramEscape(b.label) + " · ${number(b.value)}${unitHtml(b.unit)}"
        is CardBlock.Form -> telegramEscape(text(R.string.tgf_form_app))
    }

    private fun keyboard(s: Interaction, token: String): JsonObject? {
        val rows = mutableListOf<List<JsonObject>>()
        fun button(label: String, i: Int, op: String) = telegramButton(label, "tc:$token:$i:$op")
        s.spec.blocks.forEachIndexed { i, b ->
            when (b) {
                is CardBlock.Choice -> {
                    b.options.forEachIndexed { n, option -> rows += listOf(button((if (n in s.selected[i].orEmpty()) "✓ " else "") + option.label, i, "p$n")) }
                    if (b.multi) rows += listOf(button(b.submitLabel ?: text(R.string.card_send), i, "s"))
                }
                is CardBlock.Checklist -> {
                    b.items.forEachIndexed { n, item -> rows += listOf(button((if (n in s.selected[i].orEmpty()) "✓ " else "") + item.text, i, "p$n")) }
                    rows += listOf(button(b.sendLabel ?: text(R.string.card_send), i, "s"))
                }
                is CardBlock.Rating -> rows.addAll((1..b.max.coerceIn(1, 10)).map { button("$it ★", i, "p$it") }.chunked(5))
                is CardBlock.Slider -> rows += (0..4).map { n -> button(number(sliderValue(b, n)) + unitPlain(b.unit), i, "p$n") }
                is CardBlock.Link -> telegramUrl(b.title, b.url)?.let { rows += listOf(it) }
                is CardBlock.Image -> telegramUrl(b.caption ?: text(R.string.tgf_image), b.source)?.let { rows += listOf(it) }
                else -> Unit
            }
        }
        s.spec.actions.forEachIndexed { i, a ->
            if (a.send != null) rows += listOf(button(a.label, -1, "a$i"))
            else a.url?.let { telegramUrl(a.label, it) }?.let { rows += listOf(it) }
        }
        return rows.takeIf { it.isNotEmpty() }?.let(::telegramKeyboard)
    }

    @Synchronized fun callback(chatId: Long, data: String): Reply? {
        val parts = data.split(':')
        val token = parts.getOrNull(1) ?: return null
        val s = tokens.get(token)?.takeIf { it.chatId == chatId && it.agentId != null && !it.sending } ?: return null
        val i = parts.getOrNull(2)?.toIntOrNull() ?: return null
        val op = parts.getOrNull(3) ?: return null
        val b = s.spec.blocks.getOrNull(i)
        val n = op.drop(1).toIntOrNull()
        val picked = s.selected.getOrPut(i) { mutableSetOf() }
        val answer = when {
            i == -1 && op.startsWith('a') -> s.spec.actions.getOrNull(n ?: -1)?.send ?: return null
            b is CardBlock.Choice && !b.multi -> b.options.getOrNull(n ?: -1)?.label ?: return null
            (b is CardBlock.Choice || b is CardBlock.Checklist) && op.startsWith('p') -> {
                val size = if (b is CardBlock.Choice) b.options.size else (b as CardBlock.Checklist).items.size
                if (n == null || n !in 0 until size) return null
                if (!picked.add(n)) picked.remove(n)
                return Reply(body(s.spec, s.nowMs), keyboard(s, token))
            }
            b is CardBlock.Choice && op == "s" -> b.options.filterIndexed { index, _ -> index in picked }.joinToString(", ") { it.label }.takeIf { it.isNotEmpty() } ?: return null
            b is CardBlock.Checklist && op == "s" -> {
                val done = b.items.filterIndexed { index, _ -> index in picked }.joinToString(", ") { it.text }.ifEmpty { "—" }
                val left = b.items.filterIndexed { index, _ -> index !in picked }.joinToString(", ") { it.text }.ifEmpty { "—" }
                "${text(R.string.card_checklist_done)}: $done. ${text(R.string.card_checklist_left)}: $left."
            }
            b is CardBlock.Rating && n != null && n in 1..b.max -> "$n/${b.max}"
            b is CardBlock.Slider && n != null && n in 0..4 -> "${b.label}: ${number(sliderValue(b, n))}${unitPlain(b.unit)}"
            else -> return null
        }
        s.sending = true
        return Reply(body(s.spec, s.nowMs) + "\n\n✓ ${telegramEscape(answer)}", null, (s.spec.title?.let { "[Card · $it] " } ?: "[Card] ") + answer, s.agentId)
    }
    @Synchronized fun finish(data: String, success: Boolean) {
        val token = data.split(':').getOrNull(1) ?: return
        if (success) tokens.remove(token) else tokens.get(token)?.sending = false
    }
    private fun sliderValue(b: CardBlock.Slider, n: Int): Double = b.min + (b.max - b.min) * n / 4
    private fun table(b: CardBlock.Table): String {
        val rows = listOf(b.columns.take(4)) + b.rows.take(30).map { row -> b.columns.take(4).indices.map { row.getOrElse(it) { "" } } }
        val widths = b.columns.take(4).indices.map { c -> rows.maxOfOrNull { it.getOrElse(c) { "" }.length } ?: 0 }
        val table = rows.joinToString("\n") { row -> row.mapIndexed { c, v -> v.padEnd(widths[c]) }.joinToString("  ") }
        return "<pre>${telegramEscape(table)}${if (b.rows.size > 30 || b.columns.size > 4) "\n…" else ""}</pre>"
    }
    private fun spark(values: List<Double>, labels: List<String>, unit: String?): String {
        val low = values.minOrNull() ?: 0.0
        val range = ((values.maxOrNull() ?: 0.0) - low).takeIf { it > 0 } ?: 1.0
        return optional(labels.firstOrNull(), "", " ") + values.joinToString("") { "▁▂▃▄▅▆▇█"[((it - low) / range * 7).toInt().coerceIn(0, 7)].toString() } +
            optional(labels.lastOrNull(), " ") + (values.lastOrNull()?.let { " ${number(it)}${unitHtml(unit)}" } ?: "")
    }
    private fun bar(ratio: Double): String = (if (ratio.isFinite()) (ratio.coerceIn(0.0, 1.0) * 10).roundToInt() else 0).let { "▓".repeat(it) + "░".repeat(10 - it) }
    private fun optional(value: String?, before: String, after: String = "") = value?.takeIf { it.isNotBlank() }?.let { before + telegramEscape(it) + after }.orEmpty()
    private fun unitHtml(value: String?) = optional(value, " ")
    private fun unitPlain(value: String?) = value?.let { " $it" }.orEmpty()
    private fun tone(t: CardTone) = when (t) { CardTone.WARN -> "⚠️ "; CardTone.DANGER -> "⛔ "; CardTone.GOOD -> "✅ "; else -> "" }
    private fun number(n: Double) = if (n.isFinite()) app.umbra.agent.cards.formatNumber(n) else "—"
}
