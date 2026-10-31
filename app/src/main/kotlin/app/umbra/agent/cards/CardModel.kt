package app.umbra.agent.cards

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Intelligent cards (2026-10-28): what an agent shows in a reply when structure says more than sentences (a comparison,
 * a schedule, numbers, choices to make), and what live dashboards are built from. A card is a short stack of blocks
 * drawn by one design system (CardUi.kt), so every template, however different its purpose, reads as part of Umbra.
 * Templates (CardTemplates) turn an agent's plain data into a [CardSpec]; the agent never writes layout.
 */

/** What a card means at a glance: colour is used only where it says something (good, needs care, failed). */
@Serializable
enum class CardTone { NEUTRAL, GOOD, WARN, DANGER, INFO, ACCENT }

/** One rendered card: a head (eyebrow, title, subtitle, icon), its blocks in order, then its actions. */
@Serializable
data class CardSpec(
    /** The template it was built from ("custom" when an agent composed blocks itself). */
    val template: String,
    val title: String? = null,
    val subtitle: String? = null,
    /** A short label above the title: the card's kind ("Flight", "Build"). */
    val eyebrow: String? = null,
    /** One of PIcons' names ("clock", "check", "alert"...); unknown names draw no icon. */
    val icon: String? = null,
    val tone: CardTone = CardTone.NEUTRAL,
    /** A large figure at the top (a price, a score, a temperature), when the card is about one number. */
    val hero: CardHero? = null,
    val blocks: List<CardBlock> = emptyList(),
    val actions: List<CardAction> = emptyList(),
    /** A quiet line at the bottom (a source, when it was true). */
    val footnote: String? = null,
)

@Serializable
data class CardHero(val value: String, val unit: String? = null, val caption: String? = null, val tone: CardTone = CardTone.NEUTRAL)

/**
 * A button under a card. Exactly one of [send] (a message to the agent, in the user's words), [url] (opened after the
 * user sees where it goes) or [copy] (to the clipboard) says what it does.
 */
@Serializable
data class CardAction(
    val id: String,
    val label: String,
    val style: ActionStyle = ActionStyle.SECONDARY,
    val send: String? = null,
    val url: String? = null,
    val copy: String? = null,
) {
    @Serializable enum class ActionStyle { PRIMARY, SECONDARY, GHOST, DANGER }
}

/** The building blocks. Every block is plain data; the renderer decides spacing, type and colour. */
@Serializable
sealed interface CardBlock {
    /** Interactive blocks carry an id so their answer and state belong to this block. */
    val interactiveId: String? get() = null

    @Serializable @SerialName("text")
    data class Text(val text: String, val style: TextStyle = TextStyle.BODY) : CardBlock {
        @Serializable enum class TextStyle { BODY, LEAD, MUTED, SERIF }
    }

    @Serializable @SerialName("stats")
    data class Stats(val items: List<Stat>) : CardBlock

    @Serializable
    data class Stat(val label: String, val value: String, val unit: String? = null, val delta: String? = null,
        val deltaTone: CardTone = CardTone.NEUTRAL, val caption: String? = null)

    @Serializable @SerialName("kv")
    data class KeyValues(val rows: List<Row>) : CardBlock {
        @Serializable data class Row(val label: String, val value: String, val tone: CardTone = CardTone.NEUTRAL, val emphasis: Boolean = false)
    }

    @Serializable @SerialName("list")
    data class Items(val items: List<Item>, val numbered: Boolean = false) : CardBlock {
        @Serializable data class Item(val title: String, val detail: String? = null, val meta: String? = null, val icon: String? = null,
            val tone: CardTone = CardTone.NEUTRAL, val trailing: String? = null)
    }

    @Serializable @SerialName("table")
    data class Table(val columns: List<String>, val rows: List<List<String>>, val numericColumns: List<Int> = emptyList(),
        val highlightRow: Int? = null) : CardBlock

    /** Horizontal bars, longest first is up to the template: comparisons, rankings, shares. */
    @Serializable @SerialName("bars")
    data class Bars(val items: List<Bar>, val max: Double? = null, val unit: String? = null) : CardBlock {
        @Serializable data class Bar(val label: String, val value: Double, val display: String? = null, val tone: CardTone = CardTone.NEUTRAL)
    }

    /** Vertical columns over a short sequence (days, months). */
    @Serializable @SerialName("columns")
    data class Columns(val points: List<Point>, val unit: String? = null, val highlightLast: Boolean = true) : CardBlock {
        @Serializable data class Point(val label: String, val value: Double)
    }

    /** One or two lines over shared labels; with one series and no labels, a sparkline. */
    @Serializable @SerialName("line")
    data class Line(val series: List<Series>, val labels: List<String> = emptyList(), val unit: String? = null) : CardBlock {
        @Serializable data class Series(val name: String, val points: List<Double>, val tone: CardTone = CardTone.ACCENT)
    }

    @Serializable @SerialName("donut")
    data class Donut(val slices: List<Slice>, val center: String? = null, val centerCaption: String? = null) : CardBlock {
        @Serializable data class Slice(val label: String, val value: Double, val display: String? = null)
    }

    @Serializable @SerialName("progress")
    data class Progress(val value: Double, val label: String? = null, val caption: String? = null, val tone: CardTone = CardTone.ACCENT) : CardBlock

    @Serializable @SerialName("gauge")
    data class Gauge(val value: Double, val min: Double = 0.0, val max: Double = 100.0, val label: String? = null, val display: String? = null,
        val tone: CardTone = CardTone.ACCENT) : CardBlock

    @Serializable @SerialName("steps")
    data class Steps(val steps: List<Step>) : CardBlock {
        @Serializable data class Step(val title: String, val detail: String? = null, val state: State = State.TODO)
        @Serializable enum class State { DONE, CURRENT, TODO, FAILED, SKIPPED }
    }

    @Serializable @SerialName("timeline")
    data class Timeline(val events: List<Event>) : CardBlock {
        @Serializable data class Event(val time: String, val title: String, val detail: String? = null, val tone: CardTone = CardTone.NEUTRAL)
    }

    @Serializable @SerialName("checklist")
    data class Checklist(val id: String, val items: List<Check>, val sendLabel: String? = null) : CardBlock {
        override val interactiveId: String get() = id
        @Serializable data class Check(val id: String, val text: String, val done: Boolean = false, val detail: String? = null)
    }

    @Serializable @SerialName("choice")
    data class Choice(val id: String, val options: List<Option>, val multi: Boolean = false, val submitLabel: String? = null,
        val question: String? = null) : CardBlock {
        override val interactiveId: String get() = id
        @Serializable data class Option(val id: String, val label: String, val detail: String? = null, val badge: String? = null)
    }

    @Serializable @SerialName("form")
    data class Form(val id: String, val fields: List<Field>, val submitLabel: String) : CardBlock {
        override val interactiveId: String get() = id
        @Serializable data class Field(val id: String, val label: String, val kind: Kind = Kind.TEXT, val options: List<String> = emptyList(),
            val placeholder: String? = null, val value: String? = null, val required: Boolean = false)
        @Serializable enum class Kind { TEXT, TEXTAREA, NUMBER, DATE, TIME, TOGGLE, SELECT }
    }

    @Serializable @SerialName("rating")
    data class Rating(val id: String, val max: Int = 5, val value: Int? = null, val label: String? = null, val submitLabel: String? = null) : CardBlock {
        override val interactiveId: String get() = id
    }

    @Serializable @SerialName("slider")
    data class Slider(val id: String, val min: Double, val max: Double, val step: Double = 1.0, val value: Double, val label: String,
        val unit: String? = null, val submitLabel: String? = null) : CardBlock {
        override val interactiveId: String get() = id
    }

    @Serializable @SerialName("quote")
    data class Quote(val text: String, val author: String? = null) : CardBlock

    @Serializable @SerialName("code")
    data class Code(val code: String, val language: String? = null) : CardBlock

    /** An image from an https URL or a file the session can read (shown, never fetched by the model). */
    @Serializable @SerialName("image")
    data class Image(val source: String, val caption: String? = null, val aspect: Float? = null) : CardBlock

    @Serializable @SerialName("chips")
    data class Chips(val items: List<String>, val tone: CardTone = CardTone.NEUTRAL) : CardBlock

    @Serializable @SerialName("callout")
    data class Callout(val text: String, val tone: CardTone = CardTone.INFO, val title: String? = null) : CardBlock

    /** Side-by-side options: one column per option, one row per aspect; [highlight] marks the recommended one. */
    @Serializable @SerialName("compare")
    data class Compare(val aspects: List<String>, val columns: List<Column>) : CardBlock {
        @Serializable data class Column(val title: String, val values: List<String>, val badge: String? = null, val highlight: Boolean = false)
    }

    @Serializable @SerialName("person")
    data class Person(val name: String, val role: String? = null, val detail: String? = null, val avatar: String? = null) : CardBlock

    @Serializable @SerialName("link")
    data class Link(val title: String, val url: String, val description: String? = null) : CardBlock

    /** Time left until [at] (ISO date-time or epoch milliseconds as text), ticking while it is shown. */
    @Serializable @SerialName("countdown")
    data class Countdown(val at: String, val label: String? = null) : CardBlock

    /** A grid of small squares, one per day or slot, shaded by value 0..1 (habits, activity). */
    @Serializable @SerialName("heatmap")
    data class Heatmap(val values: List<Double>, val columns: Int = 7, val labels: List<String> = emptyList(), val caption: String? = null) : CardBlock

    @Serializable @SerialName("divider")
    data object Divider : CardBlock
}
