package app.umbra.agent.dashboards

import app.umbra.agent.agents.AgentTimer
import app.umbra.agent.cards.CardBlock
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises persistence independently of Android and the app's service lifecycle. */
internal class DashboardStoreTest {
    private fun page(id: String = "d1", agentId: String = "a1") = Dashboard(id, agentId, "Budget", createdAtMs = 100,
        sources = listOf(Source("file", Source.Kind.FILE, "/workspace/budget.json")),
        tiles = listOf(Tile("total", "Spent", block = CardBlock.Stats(listOf(CardBlock.Stat("Spent", "0"))),
            binding = Binding.JsonValue("file", "spent"), updatedAtMs = 100)))

    private fun temporary(test: (File) -> Unit) {
        val root = Files.createTempDirectory("dashboards-store").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    @Test fun roundTripRevisionAndUiPaths() = temporary { root ->
        val timer = AgentTimer("t1", "refresh", 999, "*/5 * * * *", createdAtMs = 10)
        val store = DashboardStore(root) { listOf(timer) }
        val dashboard = page().copy(refreshTimerId = "t1", refreshCron = timer.cron,
            history = listOf(Update(100, "agent", "Started")))
        assertEquals(0L, store.revision.value)
        assertEquals(dashboard, store.save(dashboard))
        assertEquals(1L, store.revision.value)
        assertEquals(dashboard, DashboardStore(root).get(dashboard.id))
        assertTrue(File(root, "a1/dashboards.json").isFile)
        val path = store.paths("a1").single()
        assertEquals(999L, path.nextRefreshAtMs)
        assertEquals(dashboard.tiles, path.tiles)
        assertEquals(dashboard.sources, path.sources)
        assertEquals(dashboard, store.update("d1") { it })
        assertEquals(1L, store.revision.value)
        store.update("d1") { it.copy(status = Dashboard.Status.PAUSED) }
        assertEquals(2L, store.revision.value)
        assertTrue(store.delete("d1"))
        assertEquals(3L, store.revision.value)
        assertFalse(store.delete("d1"))
        assertNull(store.get("d1"))
    }

    @Test fun capsAndHistoryKeepTheNewestThirty() = temporary { root ->
        val store = DashboardStore(root)
        val saved = store.save(page().copy(history = (0L..49L).map { Update(it, "file", "Update $it") }))
        assertEquals((49L downTo 20L).toList(), saved.history.map { it.atMs })
        for (id in 2..20) store.save(page("d$id"))
        assertEquals(20, store.forAgent("a1").size)
        assertFailsWith<IllegalArgumentException> { store.save(page("d21")) }
        store.save(page().copy(tiles = (1..24).map { page().tiles.single().copy(id = "tile$it") }))
        assertFailsWith<IllegalArgumentException> { store.save(page().copy(tiles = (1..25).map { page().tiles.single().copy(id = "tile$it") })) }
        assertFailsWith<IllegalArgumentException> { store.save(page().copy(title = "x".repeat(201))) }
        assertFailsWith<IllegalArgumentException> { store.save(page().copy(tiles = listOf(page().tiles.single().copy(note = "x".repeat(2001))))) }
        assertFailsWith<IllegalArgumentException> { store.save(page().copy(tiles = listOf(page().tiles.single().copy(
            block = CardBlock.Stats(listOf(CardBlock.Stat("Spent", "x".repeat(20_001)))))))) }
        assertEquals(24, store.get("d1")!!.tiles.size)
    }

    @Test fun ownershipPathsAndBindingKindsAreValidated() = temporary { root ->
        val store = DashboardStore(root)
        store.save(page())
        assertFailsWith<IllegalArgumentException> { store.save(page(agentId = "a2")) }
        assertFailsWith<IllegalArgumentException> { store.forAgent("../outside") }
        assertFailsWith<IllegalArgumentException> { store.save(page().copy(sources = listOf(Source("file", Source.Kind.FILE, "/workspace/../private")))) }
        assertFailsWith<IllegalArgumentException> { store.save(page().copy(sources = listOf(Source("file", Source.Kind.FOLDER, "/workspace/data")))) }
        assertFailsWith<IllegalArgumentException> { store.update("d1") { it.copy(agentId = "a2") } }
        assertFailsWith<IllegalArgumentException> { store.save(page().copy(tiles = page().tiles + page().tiles)) }
        assertEquals(page(), store.get("d1"))
    }

    @Test fun corruptStateIsReportedAndPreserved() = temporary { root ->
        val store = DashboardStore(root)
        store.save(page())
        val state = File(root, "a1/dashboards.json")
        state.writeText("broken")
        assertFailsWith<IllegalArgumentException> { store.forAgent("a1") }
        assertFailsWith<IllegalArgumentException> { store.save(page()) }
        assertEquals("broken", state.readText())
    }
}
