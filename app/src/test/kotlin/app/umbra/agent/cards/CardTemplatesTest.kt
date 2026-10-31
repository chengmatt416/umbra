package app.umbra.agent.cards

import app.umbra.core.model.UmbraJson
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CardTemplatesTest {
    private val expectedIds = """
        day_plan week_overview event reminder todo_list shopping_list meeting_agenda meeting_notes action_items
        decision_log availability poll countdown habit_tracker project_status goal_progress task_progress milestone
        sprint_board risk_register roadmap okr weekly_review time_tracking kpi kpi_grid trend comparison_bars breakdown
        leaderboard data_table funnel before_after ab_test distribution survey_results scorecard anomaly budget
        expense_breakdown receipt invoice bill_reminder subscription_list price_tracker savings_goal split_bill
        currency_convert flight hotel itinerary trip_overview route weather_now weather_forecast place restaurant
        packing_list message_draft email_summary contact chat_digest call_notes introduction follow_up thank_you
        build_status test_results deploy pull_request diff_stats error_report log_excerpt code_snippet api_endpoint
        incident uptime dependency_updates definition key_points summary_tldr how_to comparison pros_cons timeline_history
        quote quiz flashcards workout nutrition sleep recipe medication order_tracking package_delivery confirm options
        question status_update file
    """.trim().split(Regex("\\s+")).toSet()

    @Test fun everyRequestedTemplateHasAValidSerializableExample() {
        assertEquals(100, CardTemplates.all.size)
        assertEquals(expectedIds, CardTemplates.all.map { it.id }.toSet())
        CardTemplates.all.forEach { t ->
            assertEquals(t, CardTemplates.get(t.id))
            val spec = CardTemplates.build(t.id, t.example)
            assertEquals(t.id, spec.template)
            assertTrue(spec.blocks.size in 1..6 && spec.blocks.last() != CardBlock.Divider, t.id)
            assertTrue(spec.actions.size <= 3, t.id)
            assertFalse(spec.title.isNullOrBlank(), t.id)
            assertFalse(spec.eyebrow.isNullOrBlank(), t.id)
            assertFalse(spec.icon.isNullOrBlank(), t.id)
            assertEquals(spec, t.build(t.example))
            assertEquals(spec, UmbraJson.decodeFromString(CardSpec.serializer(), UmbraJson.encodeToString(CardSpec.serializer(), spec)))
            val custom = CardTemplates.fromArguments(buildJsonObject { put("card", UmbraJson.encodeToJsonElement(CardSpec.serializer(), spec)) }).getOrThrow()
            assertEquals(spec.copy(template = "custom"), custom, t.id)
        }
    }

    @Test fun requiredFieldsAreDocumentedAndErrorsNameThem() {
        CardTemplates.all.forEach { t ->
            assertEquals(t.fields.size, t.fields.map { it.name }.distinct().size, t.id)
            t.fields.filter { it.required }.forEach { field ->
                assertNotNull(t.example[field.name], "${t.id}.${field.name}")
                val error = assertFailsWith<IllegalArgumentException>("${t.id}.${field.name}") {
                    CardTemplates.build(t.id, JsonObject(t.example - field.name))
                }
                assertTrue(error.message.orEmpty().contains(field.name), error.message)
            }
        }
    }

    @Test fun nonFiniteNumbersAreRejectedIncludingUnusedAndNestedValues() {
        CardTemplates.all.forEach { t ->
            listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
                val data = JsonObject(t.example + ("invalid_figure" to JsonPrimitive(value)))
                val error = assertFailsWith<IllegalArgumentException> { t.build(data) }
                assertTrue(error.message.orEmpty().contains("invalid_figure"))
            }
        }
        val t = CardTemplates.get("trend")!!
        val points = (t.example.getValue("points") as JsonArray).toMutableList()
        points[1] = buildJsonObject { put("label", "Tue"); put("value", Double.NaN) }
        val error = assertFailsWith<IllegalArgumentException> {
            CardTemplates.build("trend", JsonObject(t.example + ("points" to JsonArray(points))))
        }
        assertTrue(error.message.orEmpty().contains("points[1].value"))
        assertTrue(error.message.orEmpty().contains("finite"))
    }

    @Test fun textAndCollectionSizesAreCapped() {
        val t = CardTemplates.get("summary_tldr")!!
        val item = buildJsonObject { put("title", "x".repeat(120)); put("detail", "y".repeat(800)) }
        val spec = CardTemplates.build(t.id, JsonObject(t.example + mapOf(
            "title" to JsonPrimitive("  " + "A".repeat(100) + "  "),
            "text" to JsonPrimitive("  " + "B".repeat(800) + "  "),
            "items" to JsonArray(List(50) { item }),
        )))
        assertEquals(80, spec.title!!.length)
        assertEquals(600, spec.blocks.filterIsInstance<CardBlock.Text>().single().text.length)
        val items = spec.blocks.filterIsInstance<CardBlock.Items>().single().items
        assertEquals(30, items.size)
        assertEquals(80, items.first().title.length)
        assertEquals(600, items.first().detail!!.length)
        val points = JsonArray(List(60) { buildJsonObject { put("label", "Day $it"); put("value", it) } })
        val trend = CardTemplates.get("trend")!!
        val line = CardTemplates.build(trend.id, JsonObject(trend.example + ("points" to points)))
            .blocks.filterIsInstance<CardBlock.Line>().single()
        assertEquals(40, line.series.single().points.size)
        assertEquals(40, line.labels.size)
        val distribution = CardTemplates.get("distribution")!!
        assertEquals(12, CardTemplates.build(distribution.id, JsonObject(distribution.example + ("points" to points)))
            .blocks.filterIsInstance<CardBlock.Columns>().single().points.size)
        val table = CardTemplates.get("data_table")!!
        val rows = table.example.getValue("rows") as JsonArray
        assertEquals(30, CardTemplates.build(table.id, JsonObject(table.example + ("rows" to JsonArray(List(50) { rows.first() }))))
            .blocks.filterIsInstance<CardBlock.Table>().single().rows.size)
    }

    @Test fun optionalValuesAreOmittedAndHeadersUseTheUsersLanguage() {
        val t = CardTemplates.get("breakdown")!!
        val data = JsonObject(t.example + mapOf(
            "details" to buildJsonObject { put("Unknown", JsonNull); put("Blank", "  ") },
            "subtitle" to JsonNull, "eyebrow" to JsonPrimitive("  支出  "),
            "source" to JsonPrimitive("  Ledger  "), "as_of" to JsonPrimitive("  Oct 28  "),
        ))
        val spec = CardTemplates.build(t.id, data)
        assertEquals(null, spec.subtitle)
        assertEquals("支出", spec.eyebrow)
        assertEquals("Ledger · Oct 28", spec.footnote)
        assertTrue(spec.blocks.none { it is CardBlock.KeyValues })
        assertFalse(UmbraJson.encodeToString(CardSpec.serializer(), spec).contains("null"))
    }

    @Test fun invalidFieldsHaveUsefulErrors() {
        fun bad(id: String, field: String, value: JsonElement) {
            val t = CardTemplates.get(id)!!
            val e = assertFailsWith<IllegalArgumentException> { CardTemplates.build(id, JsonObject(t.example + (field to value))) }
            assertTrue(e.message.orEmpty().contains(field), e.message)
        }
        bad("goal_progress", "value", JsonPrimitive(1.1))
        bad("budget", "limit", JsonPrimitive(0))
        bad("scorecard", "max", JsonPrimitive(-1))
        bad("poll", "multi", JsonPrimitive("true"))
        bad("trend", "points", JsonArray(emptyList()))
        bad("habit_tracker", "values", JsonArray(listOf(JsonPrimitive(1.1))))
        bad("hotel", "url", JsonPrimitive("http://example.com"))
        bad("event", "at", JsonPrimitive("tomorrow"))
        bad("kpi", "subtitle", JsonPrimitive(7))
        bad("options", "options", JsonArray(listOf(
            buildJsonObject { put("id", "a"); put("label", "A") },
            buildJsonObject { put("id", "a"); put("label", "B") },
        )))
        val unknown = assertFailsWith<IllegalArgumentException> { CardTemplates.build("fligt", buildJsonObject {}) }
        assertTrue(unknown.message.orEmpty().contains("flight"))
        assertEquals(3, unknown.message!!.substringAfter("Nearest ids: ").split(", ").size)
    }

    @Test fun interactiveIdsAreStableAndActionsAreBoundedSendMessages() {
        mapOf("availability" to "slots", "poll" to "poll", "question" to "form", "todo_list" to "checks").forEach { (id, blockId) ->
            val t = CardTemplates.get(id)!!
            assertEquals(blockId, t.build(t.example).blocks.mapNotNull { it.interactiveId }.single())
        }
        listOf("reminder", "message_draft", "confirm").forEach { id ->
            val t = CardTemplates.get(id)!!
            assertTrue(t.build(t.example).actions.all { it.send != null && it.url == null && it.copy == null })
            val error = assertFailsWith<IllegalArgumentException> {
                t.build(JsonObject(t.example + ("actions" to JsonArray(emptyList()))))
            }
            assertTrue(error.message.orEmpty().contains("actions"))
        }
        val t = CardTemplates.get("event")!!
        val actions = JsonArray(List(4) { n -> buildJsonObject { put("id", "a$n"); put("label", "Open option $n"); put("url", "https://example.com/$n") } })
        val e = assertFailsWith<IllegalArgumentException> { t.build(JsonObject(t.example + ("actions" to actions))) }
        assertTrue(e.message.orEmpty().contains("actions"))
    }

    @Test fun argumentsAcceptTemplateAndCustomAndRejectGarbage() {
        val t = CardTemplates.get("day_plan")!!
        assertEquals(t.id, CardTemplates.fromArguments(buildJsonObject { put("template", t.id); put("data", t.example) }).getOrThrow().template)
        val custom = UmbraJson.parseToJsonElement("""{"title":"Note","blocks":[{"type":"text","text":"Read the agenda."}]}""")
        assertEquals("custom", CardTemplates.fromArguments(buildJsonObject { put("card", custom) }).getOrThrow().template)
        val bad = listOf(
            "{}", "{\"template\":\"flight\"}", "{\"template\":12,\"data\":{}}", "{\"card\":false}",
            "{\"card\":{}}", "{\"template\":\"missing\",\"data\":{}}", "{\"garbage\":true}",
            "{\"template\":\"flight\",\"data\":{},\"card\":{}}",
            "{\"card\":{\"title\":\"Bad\",\"tone\":\"FANCY\",\"blocks\":[{\"type\":\"divider\"}]}}",
            "{\"card\":{\"title\":\"Bad\",\"blocks\":[{\"type\":\"image\",\"source\":\"file:///tmp/photo.png\"}]}}",
            "{\"card\":{\"title\":\"Bad\",\"blocks\":[{\"type\":\"progress\",\"value\":2}]}}",
        )
        bad.forEach { assertTrue(CardTemplates.fromArguments(UmbraJson.parseToJsonElement(it) as JsonObject).isFailure, it) }
    }

    @Test fun customCardsKeepTheSameBoundsAndLinkRules() {
        val raw = buildJsonObject {
            put("title", "  Custom  ")
            put("blocks", JsonArray(listOf(buildJsonObject {
                put("type", "list")
                put("items", JsonArray(List(50) { buildJsonObject { put("title", "Item $it") } }))
            })))
        }
        val spec = CardTemplates.fromArguments(buildJsonObject { put("card", raw) }).getOrThrow()
        assertEquals("Custom", spec.title)
        assertEquals(30, spec.blocks.filterIsInstance<CardBlock.Items>().single().items.size)
        val action = buildJsonObject { put("id", "open"); put("label", "Open"); put("url", "javascript:alert(1)") }
        assertTrue(CardTemplates.fromArguments(buildJsonObject { put("card", JsonObject(raw + ("actions" to JsonArray(listOf(action))))) }).isFailure)
    }

    @Test fun catalogFiltersByCategoryAndAllQueryWordsAndDocumentsCalls() {
        val money = CardTemplates.catalog(category = "money")
        assertTrue(money.contains("budget — use when"))
        assertFalse(money.contains("flight — use when"))
        val search = CardTemplates.catalog(query = "flight route")
        assertTrue(search.contains("flight — use when"))
        assertFalse(search.contains("hotel — use when"))
        val exact = CardTemplates.catalog(query = "kpi")
        assertTrue(exact.contains("Example data:"))
        assertFalse(exact.contains("kpi_grid — use when"))
        assertTrue(exact.contains("title*"))
        assertTrue(exact.contains("array<object"))
        assertTrue(CardTemplates.catalog(query = "nothingMatchesThis").contains("No matching templates"))
        assertTrue(money.contains("show_card"))
    }
}
