package app.umbra.agent.telegram

import app.umbra.agent.R
import app.umbra.agent.cards.*
import kotlin.test.*
import kotlinx.serialization.json.*

internal fun telegramTestText(id: Int): String = when (id) {
    R.string.tgf_form_app -> "Fill this in the Umbra app."
    R.string.tgf_now -> "now"
    R.string.tgf_countdown -> "in %1\$d days %2\$d hours"
    R.string.card_send -> "Send"
    R.string.card_checklist_done -> "Done"
    R.string.card_checklist_left -> "Left"
    R.string.tgf_image -> "Image"
    R.string.tgf_yes -> "Yes"
    R.string.tgf_no -> "No"
    R.string.tgf_skip -> "Skip"
    R.string.tgf_done -> "Done"
    R.string.tgf_type_answer -> "✏️ Type an answer"
    R.string.tgf_why_now -> "Why now: %1\$s"
    R.string.tgf_confirm_steps -> "If you confirm, %1\$s will:"
    R.string.tgf_needs -> "It needs from you: %1\$s"
    R.string.tgf_if_ignored -> "If you do nothing: %1\$s"
    R.string.tgf_based_on -> "Based on: %1\$s"
    else -> error("Missing test string $id")
}

internal fun telegramTestButtons(markup: JsonObject?): List<JsonObject> =
    markup?.get("inline_keyboard")?.jsonArray.orEmpty().flatMap { it.jsonArray }.map { it.jsonObject }
internal fun telegramTestData(markup: JsonObject?, label: String): String = telegramTestButtons(markup)
    .first { it["text"]?.jsonPrimitive?.content == label }.getValue("callback_data").jsonPrimitive.content

class TelegramCardsTest {
    private val renderer = TelegramCards(::telegramTestText)
    private val now = 1_800_000_000_000L

    @Test fun headAndAllDynamicTextAreEscaped() {
        val rendered = renderer.render(CardSpec("custom", title = "A < B & C", subtitle = "> subtitle", eyebrow = "notice",
            tone = CardTone.WARN, hero = CardHero("<42", "& units", "<caption>"),
            blocks = listOf(CardBlock.Text("<script>&", CardBlock.Text.TextStyle.LEAD)), footnote = "<source>"), now)
        assertTrue(rendered.html.startsWith("NOTICE\n⚠️ <b>A &lt; B &amp; C</b>\n<i>&gt; subtitle</i>"))
        assertTrue("<b>&lt;42</b> &amp; units\n&lt;caption&gt;" in rendered.html)
        assertTrue("<b>&lt;script&gt;&amp;</b>" in rendered.html)
        assertTrue(rendered.html.endsWith("<i>&lt;source&gt;</i>"))
        assertFalse("<script>" in rendered.html)
    }

    @Test fun everyBlockHasAReadableRendering() {
        val blocks = listOf(
            CardBlock.Text("Body"), CardBlock.Stats(listOf(CardBlock.Stat("Sales", "42", "orders", "+3"))),
            CardBlock.KeyValues(listOf(CardBlock.KeyValues.Row("Owner", "Lena"))),
            CardBlock.Items(listOf(CardBlock.Items.Item("Task", "Detail", "Meta", trailing = "Today")), numbered = true),
            CardBlock.Table(listOf("Name", "Count"), listOf(listOf("A", "4"))),
            CardBlock.Bars(listOf(CardBlock.Bars.Bar("A", 4.0)), unit = "orders"),
            CardBlock.Columns(listOf(CardBlock.Columns.Point("Mon", 1.0), CardBlock.Columns.Point("Tue", 4.0))),
            CardBlock.Line(listOf(CardBlock.Line.Series("Visits", listOf(1.0, 3.0))), listOf("Mon", "Tue")),
            CardBlock.Donut(listOf(CardBlock.Donut.Slice("Work", 45.0), CardBlock.Donut.Slice("Rest", 55.0))),
            CardBlock.Progress(.43, "Done"), CardBlock.Gauge(86.0, label = "Score"),
            CardBlock.Steps(CardBlock.Steps.State.entries.map { CardBlock.Steps.Step(it.name.lowercase(), state = it) }),
            CardBlock.Timeline(listOf(CardBlock.Timeline.Event("09:00", "Meet", "Discuss"))),
            CardBlock.Quote("Words", "Lena"), CardBlock.Code("echo hello"), CardBlock.Chips(listOf("a", "b")),
            CardBlock.Callout("Notice", CardTone.INFO, "Read"),
            CardBlock.Compare(listOf("Price"), listOf(CardBlock.Compare.Column("Plan", listOf("4"), "Best"))),
            CardBlock.Person("Lena", "Designer"), CardBlock.Link("Read", "https://example.com"),
            CardBlock.Countdown((now + (5 * 24 + 10) * 3_600_000L).toString()),
            CardBlock.Heatmap(listOf(0.0, 1.0), 2), CardBlock.Image("https://example.com/p.png", "Photo"),
            CardBlock.Divider, CardBlock.Choice("choice", listOf(CardBlock.Choice.Option("a", "Option"))),
            CardBlock.Checklist("check", listOf(CardBlock.Checklist.Check("a", "Item"))),
            CardBlock.Rating("rating"), CardBlock.Slider("slider", 0.0, 100.0, value = 50.0, label = "Volume"),
            CardBlock.Form("form", emptyList(), "Submit"),
        )
        for (block in blocks) {
            val html = renderer.block(block, now)
            assertTrue(html.isNotBlank(), "Empty rendering for ${block::class.simpleName}")
            assertFalse("{" in html || "interactiveId" in html || "CardBlock" in html, html)
        }
        assertEquals("⏳ in 5 days 10 hours", renderer.block(blocks[20], now))
        assertEquals("⏳ now", renderer.block(CardBlock.Countdown("2020-01-01T00:00:00Z"), now))
        assertEquals("Fill this in the Umbra app.", renderer.block(blocks.last(), now))
        assertEquals("Work — 45%\nRest — 55%", renderer.block(blocks[8], now))
        assertEquals("▓▓▓▓░░░░░░ 43% · Done", renderer.block(blocks[9], now))
        assertEquals("86 / 100 · Score", renderer.block(blocks[10], now))
    }

    @Test fun tablesPadAllVisibleCellsAndLimitRowsAndColumns() {
        assertEquals("<pre>Name  N \nA     12\nBee   3 </pre>", renderer.block(CardBlock.Table(listOf("Name", "N"), listOf(listOf("A", "12"), listOf("Bee", "3"))), now))
        val html = renderer.block(CardBlock.Table(listOf("A", "B", "C", "D", "hidden-column"), (1..31).map { listOf("row$it", "b", "c", "d", "hidden-cell") }), now)
        assertFalse("hidden" in html)
        assertTrue("row30" in html)
        assertFalse("row31" in html)
        assertTrue(html.endsWith("\n…</pre>"))
    }

    @Test fun barsUseTheAppScaleAndSparklinesKeepTheirEndpoints() {
        val items = listOf(CardBlock.Bars.Bar("A", 5.0), CardBlock.Bars.Bar("B", 10.0))
        assertEquals("A ▓▓▓▓▓░░░░░ 5\nB ▓▓▓▓▓▓▓▓▓▓ 10", renderer.block(CardBlock.Bars(items), now))
        assertTrue("▓░░░░░░░░░" in renderer.block(CardBlock.Bars(listOf(CardBlock.Bars.Bar("A", 10.0)), unit = "%"), now))
        assertTrue("▓▓▓▓▓░░░░░" in renderer.block(CardBlock.Bars(items, max = 20.0), now))
        assertEquals("Mon ▁█ Tue 4", renderer.block(CardBlock.Columns(listOf(CardBlock.Columns.Point("Mon", 1.0), CardBlock.Columns.Point("Tue", 4.0))), now))
    }

    @Test fun singleChoiceAndActionsAnswerOnceAndOnlyForTheirChat() {
        val spec = CardSpec("custom", "Choose", blocks = listOf(CardBlock.Choice("a".repeat(100), listOf(CardBlock.Choice.Option("b".repeat(100), "Blue")))),
            actions = listOf(CardAction("send", "Proceed", send = "Go ahead"), CardAction("url", "Read", url = "https://example.com"), CardAction("bad", "Unsafe", url = "http://example.com")))
        val rendered = renderer.render(spec, now)
        renderer.bind(rendered, "agent", 42)
        val data = telegramTestData(rendered.keyboard, "Blue")
        assertNull(renderer.callback(43, data))
        assertEquals("[Card · Choose] Blue", renderer.callback(42, data)?.answer)
        assertNull(renderer.callback(42, data))
        renderer.finish(data, false)
        assertNotNull(renderer.callback(42, data))
        renderer.finish(data, true)
        assertNull(renderer.callback(42, data))
        val buttons = telegramTestButtons(rendered.keyboard)
        assertTrue(buttons.any { it["url"]?.jsonPrimitive?.content == "https://example.com" })
        assertFalse(buttons.any { it["text"]?.jsonPrimitive?.content == "Unsafe" })
        buttons.mapNotNull { it["callback_data"]?.jsonPrimitive?.content }.forEach { assertTrue(it.toByteArray().size <= 64) }
        val action = renderer.render(spec, now)
        renderer.bind(action, "agent", 42)
        assertEquals("[Card · Choose] Go ahead", renderer.callback(42, telegramTestData(action.keyboard, "Proceed"))?.answer)
    }

    @Test fun multiChoiceAndChecklistToggleBeforeSendingAppCompatibleAnswers() {
        val multi = renderer.render(CardSpec("custom", "Plan", blocks = listOf(CardBlock.Choice("multi", listOf(CardBlock.Choice.Option("a", "A"), CardBlock.Choice.Option("b", "B")), multi = true))), now)
        renderer.bind(multi, "agent", 42)
        val toggled = assertNotNull(renderer.callback(42, telegramTestData(multi.keyboard, "B")))
        assertNull(toggled.answer)
        assertNotNull(telegramTestData(toggled.keyboard, "✓ B"))
        assertEquals("[Card · Plan] B", renderer.callback(42, telegramTestData(toggled.keyboard, "Send"))?.answer)
        val check = renderer.render(CardSpec("custom", "Tasks", blocks = listOf(CardBlock.Checklist("check", listOf(CardBlock.Checklist.Check("a", "Read", true), CardBlock.Checklist.Check("b", "Write"))))), now)
        renderer.bind(check, "agent", 42)
        assertNotNull(telegramTestData(check.keyboard, "✓ Read"))
        val both = assertNotNull(renderer.callback(42, telegramTestData(check.keyboard, "Write")))
        assertNotNull(telegramTestData(both.keyboard, "✓ Write"))
        val again = assertNotNull(renderer.callback(42, telegramTestData(both.keyboard, "✓ Write")))
        assertEquals("[Card · Tasks] Done: Read. Left: Write.", renderer.callback(42, telegramTestData(again.keyboard, "Send"))?.answer)
    }

    @Test fun ratingSliderAndFormHaveTheExpectedControls() {
        val rating = renderer.render(CardSpec("custom", "Score", blocks = listOf(CardBlock.Rating("rate", max = 5))), now)
        renderer.bind(rating, "agent", 42)
        assertEquals(5, telegramTestButtons(rating.keyboard).size)
        assertEquals("[Card · Score] 4/5", renderer.callback(42, telegramTestData(rating.keyboard, "4 ★"))?.answer)
        val slider = renderer.render(CardSpec("custom", "Sound", blocks = listOf(CardBlock.Slider("slide", 0.0, 100.0, value = 20.0, label = "Volume", unit = "%"))), now)
        renderer.bind(slider, "agent", 42)
        assertEquals(listOf("0 %", "25 %", "50 %", "75 %", "100 %"), telegramTestButtons(slider.keyboard).map { it.getValue("text").jsonPrimitive.content })
        assertEquals("[Card · Sound] Volume: 75 %", renderer.callback(42, telegramTestData(slider.keyboard, "75 %"))?.answer)
        assertNull(renderer.render(CardSpec("custom", blocks = listOf(CardBlock.Form("form", emptyList(), "Submit"))), now).keyboard)
    }

    @Test fun linksAndImagesHaveHttpsButtonsAndTenRatingsFitAcrossRows() {
        val card = renderer.render(CardSpec("custom", "Resources", blocks = listOf(
            CardBlock.Link("Read", "https://example.com/read"), CardBlock.Image("https://example.com/photo.png", "Photo"),
            CardBlock.Link("Unsafe", "http://example.com"), CardBlock.Rating("score", max = 10),
        )), now)
        val buttons = telegramTestButtons(card.keyboard)
        assertEquals(listOf("https://example.com/read", "https://example.com/photo.png"), buttons.mapNotNull { it["url"]?.jsonPrimitive?.content })
        assertEquals(10, buttons.count { it["callback_data"] != null })
        assertTrue(card.keyboard!!["inline_keyboard"]!!.jsonArray.all { it.jsonArray.size <= 8 })
    }

    @Test fun tokensExpireAndAreBounded() {
        var clock = 0L
        val tokens = TelegramTokens<Int> { clock }
        val first = tokens.put(1)
        repeat(500) { tokens.put(it) }
        assertNull(tokens.get(first))
        val last = tokens.put(999)
        assertEquals(999, tokens.get(last))
        clock = 86_400_000
        assertNull(tokens.get(last))
    }

    @Test fun htmlSplittingKeepsTagsEntitiesAndEmojiWhole() {
        val source = "<b><i>" + "A &amp; &lt; 😊 ".repeat(900) + "</i></b>"
        val parts = telegramHtmlParts(source)
        assertTrue(parts.size > 1)
        assertEquals(telegramPlain(source), parts.joinToString("") { telegramPlain(it) })
        parts.forEach {
            assertTrue(it.length <= 4096)
            assertTrue(it.startsWith("<b><i>") && it.endsWith("</i></b>"))
            assertFalse(it.contains('\uFFFD'))
            assertFalse(Regex("&(?!amp;|lt;|gt;)").containsMatchIn(it))
            assertFalse(it.last().isHighSurrogate())
        }
    }
}
