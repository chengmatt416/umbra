package app.umbra.agent.dashboards

import app.umbra.agent.agents.AgentProfile
import app.umbra.agent.agents.AgentStore
import app.umbra.agent.agents.AgentTimer
import app.umbra.core.model.UmbraJson
import app.umbra.runtime.omp.HostToolCall
import app.umbra.runtime.omp.HostToolPayload
import app.umbra.runtime.omp.HostToolProgress
import app.umbra.tools.HostSideTool
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tool tests use real file stores and injected scheduling, with no Android runtime. */
internal class DashboardToolsTest {
    private val time = Instant.parse("2026-10-28T00:00:00Z").toEpochMilli()

    private suspend fun call(tools: List<HostSideTool>, name: String, json: String): HostToolPayload = tools.single { it.name == name }.run(
        HostToolCall("call", "model-call", name, UmbraJson.parseToJsonElement(json) as JsonObject), HostToolProgress { })

    @Test fun createUpdateScheduleAndRemoveUseTheAgentTimerStore() = runBlocking {
        val root = Files.createTempDirectory("dashboard-tools").toFile()
        try {
            val agents = AgentStore(root)
            agents.save(AgentProfile("a", "Agent", createdAtMs = time))
            agents.setTimers("a", listOf(AgentTimer("unrelated", "keep", time + 1000, createdAtMs = time)))
            val store = DashboardStore(root, agents::timers)
            var scheduled = 0
            val tools = DashboardTools(store, agents, { scheduled++ }, { time }).forAgent("a", "session")
            val created = call(tools, "dashboard_create", """{
                "title":"Budget", "sources":[{"kind":"FILE","path":"/workspace/budget.json"}],
                "tiles":[{"title":"Spent","block":{"type":"stats","items":[{"label":"Spent","value":"0"}]},
                    "bind":{"type":"json_value","sourceId":"source1","path":"spent"}}],
                "refresh_cron":"*/5 * * * *", "refresh_instruction":"Read the budget and update the summary."
            }""")
            assertFalse(created.isError, created.toString())
            val dashboard = store.forAgent("a").single()
            val timer = agents.timers("a").single { it.id == dashboard.refreshTimerId }
            assertEquals("Update dashboard \"Budget\" (${dashboard.id}): Read the budget and update the summary.", timer.note)
            assertEquals("*/5 * * * *", timer.cron)
            assertEquals(1, scheduled)
            assertEquals(timer.nextMs, store.paths("a").single().nextRefreshAtMs)
            val updated = call(tools, "dashboard_update", """{"id":"${dashboard.id}","tiles":[{"title":"Spent","note":"Checked"}],"note":"Reviewed budget"}""")
            assertFalse(updated.isError, updated.toString())
            assertEquals("Checked", store.get(dashboard.id)!!.tiles.single().note)
            assertEquals(dashboard.tiles.single().binding, store.get(dashboard.id)!!.tiles.single().binding)
            assertEquals("Reviewed budget", store.get(dashboard.id)!!.history.first().text)
            val replacement = call(tools, "dashboard_schedule", """{"id":"${dashboard.id}","cron":"*/10 * * * *"}""")
            assertFalse(replacement.isError, replacement.toString())
            assertEquals(2, agents.timers("a").size)
            assertEquals(timer.id, store.get(dashboard.id)!!.refreshTimerId)
            assertEquals(timer.note, agents.timers("a").first { it.id == timer.id }.note)
            assertFalse(call(tools, "dashboard_schedule", """{"id":"${dashboard.id}"}""").isError)
            assertNull(store.get(dashboard.id)!!.refreshTimerId)
            assertEquals(listOf("unrelated"), agents.timers("a").map { it.id })
            assertFalse(call(tools, "dashboard_schedule", """{"id":"${dashboard.id}","cron":"*/5 * * * *"}""").isError)
            assertEquals(2, agents.timers("a").size)
            assertFalse(call(tools, "dashboard_remove", """{"id":"${dashboard.id}"}""").isError)
            assertNull(store.get(dashboard.id))
            assertEquals(listOf("unrelated"), agents.timers("a").map { it.id })
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidCreatesLeaveNoDashboardOrTimerAndOwnershipIsEnforced() = runBlocking {
        val root = Files.createTempDirectory("dashboard-tools-invalid").toFile()
        try {
            val agents = AgentStore(root)
            agents.save(AgentProfile("a", "Agent", createdAtMs = time))
            agents.save(AgentProfile("b", "Other", createdAtMs = time))
            val store = DashboardStore(root)
            val toolFactory = DashboardTools(store, agents, {}, { time })
            val tools = toolFactory.forAgent("a")
            for (block in listOf("""{"type":"Stats","items":[]}""", """{"type":"progress"}""")) {
                assertTrue(call(tools, "dashboard_create", """{"title":"Bad","tiles":[{"title":"Value","block":$block}]}""").isError)
            }
            assertTrue(call(tools, "dashboard_create", """{"title":"Bad","tiles":[],"refresh_cron":"* * * * *"}""").isError)
            assertTrue(call(tools, "dashboard_create", """{"title":"${"x".repeat(201)}","tiles":[],"refresh_cron":"*/5 * * * *"}""").isError)
            assertTrue(store.all().isEmpty())
            assertTrue(agents.timers("a").isEmpty())
            store.save(Dashboard("other", "b", "Other", createdAtMs = time))
            assertTrue(call(tools, "dashboard_update", """{"id":"other","note":"steal"}""").isError)
            assertTrue(call(tools, "dashboard_remove", """{"id":"other"}""").isError)
            assertTrue(call(tools, "dashboard_schedule", """{"id":"other","cron":"*/5 * * * *"}""").isError)
            assertNotNull(store.get("other"))
            agents.setTimers("a", (1..50).map { AgentTimer("t$it", "keep", time + 1000, createdAtMs = time) })
            assertTrue(call(tools, "dashboard_create", """{"title":"Full","tiles":[],"refresh_cron":"*/5 * * * *"}""").isError)
            assertTrue(store.forAgent("a").isEmpty())
            assertEquals(50, agents.timers("a").size)
            store.save(Dashboard("own", "a", "Own", createdAtMs = time, refreshTimerId = "t1", refreshCron = "*/5 * * * *"))
            assertFalse(call(tools, "dashboard_schedule", """{"id":"own","cron":"*/10 * * * *"}""").isError)
            assertEquals(50, agents.timers("a").size)
            assertEquals("t1", store.get("own")!!.refreshTimerId)
        } finally { root.deleteRecursively() }
    }

    @Test fun schemaUsesTheCardsActualLowercaseDiscriminatorsAndRequiredFields() {
        val variants = DashboardSchemas.block["oneOf"] as JsonArray
        val byType = variants.associate { element ->
            val schema = element as JsonObject
            val properties = schema["properties"] as JsonObject
            ((properties["type"] as JsonObject)["const"] as JsonPrimitive).content to schema
        }
        assertEquals(setOf("text", "stats", "kv", "list", "table", "bars", "columns", "line", "donut", "progress", "gauge", "steps", "timeline",
            "checklist", "choice", "form", "rating", "slider", "quote", "code", "image", "chips", "callout", "compare", "person", "link", "countdown", "heatmap", "divider"), byType.keys)
        assertEquals(setOf(JsonPrimitive("type"), JsonPrimitive("value")), (byType.getValue("progress")["required"] as JsonArray).toSet())
        assertEquals(JsonPrimitive(false), byType.getValue("stats")["additionalProperties"])
        assertEquals(8, (DashboardSchemas.binding["oneOf"] as JsonArray).size)
    }

    @Test fun cronValidationChecksLaterGapsAndHourAndDayBoundaries() {
        val zone = ZoneId.of("UTC")
        val next = DashboardSchedules.next("*/5 * * * *", time, zone)
        assertEquals(time + 5 * 60_000, next)
        for (bad in listOf("* * * * *", "0,10,11 * * * *", "2,58 * * * *", "2,58 0,23 * * *", "*/0 * * * *",
            "0 0 * *", "0 0 * * * *", "0/5/2 * * * *", "0 0 31 2 *")) {
            assertFailsWith<IllegalArgumentException>(bad) { DashboardSchedules.next(bad, time, zone) }
        }
        assertTrue(DashboardSchedules.next("0 9 * * 1-5", time, zone) > time)
        assertTrue(DashboardSchedules.next("2,58 0,23 * * 1", time, zone) > time)
        assertTrue(DashboardSchedules.next("2,58 2 * * *", time, zone) > time)
    }
}
