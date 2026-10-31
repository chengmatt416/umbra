package app.umbra.agent.cards

import app.umbra.core.model.Clock
import app.umbra.runtime.omp.ContentBlock
import app.umbra.runtime.omp.HostToolCall
import app.umbra.runtime.omp.HostToolProgress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardToolsTest {
    private fun arguments(): JsonObject = buildJsonObject {
        put("template", "day_plan"); put("data", CardTemplates.get("day_plan")!!.example)
    }

    @Test fun theThirdCardIsRefusedPerSessionInARollingTwoMinuteWindow() {
        var now = 0L
        val tools = CardTools(Clock { now })
        assertFalse(tools.show("a", arguments()).isError)
        now = 30_000
        assertFalse(tools.show("a", arguments()).isError)
        val third = tools.show("a", arguments())
        assertTrue(third.isError)
        assertTrue((third.content.single() as ContentBlock.Text).text.contains("Use text"))
        assertFalse(tools.show("b", arguments()).isError)
        now = 119_999
        assertTrue(tools.show("a", arguments()).isError)
        now = 120_000
        assertFalse(tools.show("a", arguments()).isError)
        assertTrue(tools.show("a", arguments()).isError)
        now = 150_000
        assertFalse(tools.show("a", arguments()).isError)
    }

    @Test fun invalidCallsDoNotConsumeTheBudgetAndErrorsAreSpecific() {
        val tools = CardTools(Clock { 0 })
        repeat(3) {
            val bad = tools.show("a", buildJsonObject { put("template", "fligt"); put("data", buildJsonObject {}) })
            assertTrue(bad.isError)
            assertTrue((bad.content.single() as ContentBlock.Text).text.contains("flight"))
        }
        assertEquals("Shown day_plan.", (tools.show("a", arguments()).content.single() as ContentBlock.Text).text)
        assertFalse(tools.show("a", arguments()).isError)
        assertTrue(tools.show("a", arguments()).isError)
    }

    @Test fun registeredToolsShareTheSessionBudgetAndCatalogIsUsable() = runBlocking {
        val tools = CardTools(Clock { 0 })
        assertEquals(CardTools.NAMES, tools.forSession("a").map { it.name })
        val progress = HostToolProgress { }
        repeat(3) { n ->
            val tool = tools.forSession("a").single { it.name == CardCalls.SHOW }
            val result = tool.run(HostToolCall("$n", "$n", CardCalls.SHOW, arguments()), progress)
            assertEquals(n == 2, result.isError)
        }
        val catalog = tools.forSession("a").single { it.name == "card_templates" }
        val result = catalog.run(HostToolCall("catalog", "catalog", catalog.name, buildJsonObject { put("query", "flight") }), progress)
        assertFalse(result.isError)
        val text = (result.content.single() as ContentBlock.Text).text
        assertTrue(text.contains("Example data:"))
        assertTrue(text.contains("flight — use when"))
    }
}
