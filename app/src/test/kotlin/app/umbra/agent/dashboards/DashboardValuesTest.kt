package app.umbra.agent.dashboards

import app.umbra.agent.cards.CardBlock
import app.umbra.agent.cards.CardTone
import app.umbra.core.model.UmbraJson
import app.umbra.core.session.Session
import app.umbra.core.session.SessionState
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Pure source parsing tests also verify that bindings retain the card's presentation metadata. */
internal class DashboardValuesTest {
    private fun snapshot(text: String) = DashboardValues.Snapshot("test", text = text)
    private fun apply(block: CardBlock, binding: Binding, text: String) = DashboardValues.apply(block, binding, snapshot(text), emptyList(), 100)

    @Test fun jsonPathsSupportObjectsArraysAndOptionalRoot() {
        val root = UmbraJson.parseToJsonElement("""{"a":{"b":[{"c":12}]},"empty":null}""")
        assertEquals("12", DashboardValues.jsonPath(root, "a.b[0].c").toString())
        assertEquals("12", DashboardValues.jsonPath(root, "$.a.b[0].c").toString())
        assertEquals(root, DashboardValues.jsonPath(root, ""))
        assertEquals(root, DashboardValues.jsonPath(root, "$"))
        assertEquals("12", DashboardValues.jsonPath(UmbraJson.parseToJsonElement("[12]"), "$[0]").toString())
        for (path in listOf("a..b", "a.b[9]", "a.b[-1]", "a.b[0]c", "a.", "a.[0]", "a.b[]", "a.b[0", "missing")) {
            assertFailsWith<IllegalArgumentException>(path) { DashboardValues.jsonPath(root, path) }
        }
    }

    @Test fun csvHandlesQuotedDelimitersQuotesNewlinesAndTsv() {
        assertEquals(listOf(listOf("name", "cost"), listOf("Kyoto, Japan", "12"), listOf("A \"quote\"\nnext", "3")),
            DashboardValues.csv("\uFEFFname,cost\r\n\"Kyoto, Japan\",12\r\n\"A \"\"quote\"\"\nnext\",3\r\n"))
        assertEquals(listOf(listOf("name", "cost"), listOf("a,b", "2")), DashboardValues.csv("name\tcost\na,b\t2\n"))
        assertEquals(listOf(listOf("a", "b"), listOf("", "")), DashboardValues.csv("a,b\n,\n"))
        for (bad in listOf("", "a,a\n1,2", "a,b\n1", "a,b\n\"broken,2", "a\n\"closed\"extra", "a\nun\"quoted")) {
            assertFailsWith<IllegalArgumentException> { DashboardValues.csv(bad) }
        }
    }

    @Test fun textTailAndCountUseLinesAndIgnoreTheFinalLineTerminator() {
        val text = "ok\r\nERROR one ERROR\r\nok\r\nERROR two\r\n"
        assertEquals("ok\nERROR two", DashboardValues.tail(text, 2))
        assertEquals("", DashboardValues.tail("", 10))
        assertEquals(2, DashboardValues.count(text, "ERROR"))
        assertEquals(4, DashboardValues.count(text, "^"))
        assertEquals(0, DashboardValues.count("", ".*"))
        assertFailsWith<IllegalArgumentException> { DashboardValues.count(text, "[") }
        assertFailsWith<IllegalArgumentException> { DashboardValues.count(text, "(?=ERROR)") }
        val code = CardBlock.Code("old", "log")
        assertEquals(code.copy(code = "ERROR two"), apply(code, Binding.TextTail("f", 1), text))
        val stats = CardBlock.Stats(listOf(CardBlock.Stat("Errors", "0", deltaTone = CardTone.DANGER)))
        assertEquals(stats.copy(items = listOf(stats.items[0].copy(value = "2"))), apply(stats, Binding.TextCount("f", "ERROR"), text))
    }

    @Test fun valueBindingsKeepLabelsTonesAndOtherStats() {
        val stats = CardBlock.Stats(listOf(CardBlock.Stat("Spent", "0", unit = "JPY", caption = "Budget", deltaTone = CardTone.WARN),
            CardBlock.Stat("Limit", "100")))
        val result = apply(stats, Binding.JsonValue("f", "spent"), """{"spent":12}""") as CardBlock.Stats
        assertEquals(stats.items[0].copy(value = "12"), result.items[0])
        assertEquals(stats.items[1], result.items[1])
        val progress = CardBlock.Progress(0.0, "Build", "Nightly", CardTone.GOOD)
        assertEquals(progress.copy(value = 0.5), apply(progress, Binding.JsonValue("f", "p"), """{"p":0.5}"""))
        val gauge = CardBlock.Gauge(0.0, max = 200.0, label = "Cost", display = "old", tone = CardTone.WARN)
        assertEquals(gauge.copy(value = 12.0, display = "12"), apply(gauge, Binding.JsonValue("f", "spent"), """{"spent":12}"""))
        assertFailsWith<IllegalArgumentException> { apply(progress, Binding.JsonValue("f", "p"), """{"p":"NaN"}""") }
        assertFailsWith<IllegalArgumentException> { apply(stats, Binding.JsonValue("f", "p"), """{"p":null}""") }
        val tile = Tile("spent", "Trip budget", Tile.Size.WIDE, gauge, Binding.JsonValue("f", "spent"), 1, "Keep this note")
        assertEquals(tile.copy(block = gauge.copy(value = 12.0, display = "12"), updatedAtMs = 100),
            DashboardValues.apply(tile, snapshot("""{"spent":12}"""), emptyList(), 100))
    }

    @Test fun tableAndChartBindingsSelectColumnsAndKeepPresentation() {
        val text = """{"rows":[{"name":"A","cost":2},{"name":"B","cost":4}]}"""
        val table = CardBlock.Table(listOf("Place", "Price"), emptyList(), numericColumns = listOf(1), highlightRow = 0)
        assertEquals(table.copy(rows = listOf(listOf("A", "2"), listOf("B", "4"))),
            apply(table, Binding.JsonTable("f", "rows", listOf("name", "cost")), text))
        val bars = CardBlock.Bars(listOf(CardBlock.Bars.Bar("old", 0.0, tone = CardTone.GOOD)), max = 10.0, unit = "JPY")
        val result = apply(bars, Binding.JsonTable("f", "rows", listOf("name", "cost")), text) as CardBlock.Bars
        assertEquals(bars.copy(items = listOf(CardBlock.Bars.Bar("A", 2.0, "2", CardTone.GOOD), CardBlock.Bars.Bar("B", 4.0, "4", CardTone.GOOD))), result)
        val columns = CardBlock.Columns(emptyList(), unit = "JPY", highlightLast = false)
        assertEquals(columns.copy(points = listOf(CardBlock.Columns.Point("A", 2.0))),
            apply(columns, Binding.CsvTable("f", listOf("name", "cost"), limit = 1), "name,cost\nA,2\nB,4"))
        val line = CardBlock.Line(listOf(CardBlock.Line.Series("Price", emptyList(), CardTone.INFO)), unit = "JPY")
        assertEquals(line.copy(labels = listOf("1", "2"), series = listOf(line.series[0].copy(points = listOf(2.0, 4.0)))),
            apply(line, Binding.CsvTable("f", listOf("cost")), "name,cost\nA,2\nB,4"))
        assertFailsWith<IllegalArgumentException> { apply(table, Binding.CsvTable("f", listOf("missing")), "name,cost\nA,2") }
    }

    @Test fun folderStatsAndListingUseDirectFilesAndStableSorting() {
        val root = Files.createTempDirectory("dashboard-folder").toFile()
        try {
            val a = File(root, "a.txt").apply { writeText("abc"); check(setLastModified(1000)) }
            File(root, "b.txt").apply { writeText("12345"); check(setLastModified(2000)) }
            File(root, "nested").mkdir()
            File(root, "nested/ignored.txt").writeText("ignored")
            val snapshot = DashboardValues.read(root, Source.Kind.FOLDER)
            val stats = DashboardValues.apply(CardBlock.Stats(emptyList()), Binding.FolderStats("f"), snapshot, emptyList(), 10) as CardBlock.Stats
            assertEquals(listOf("2", "8 B", TileWords.time(2000, 10)), stats.items.map { it.value })
            assertTrue(stats.items.none { it.value.contains('T') && it.value.endsWith("Z") }, "no ISO timestamps")
            // The agent's own choice of figures and labels is kept: one stat asked for, one filled.
            val one = DashboardValues.apply(CardBlock.Stats(listOf(CardBlock.Stat("Receipts", "0"))), Binding.FolderStats("f"), snapshot, emptyList(), 10) as CardBlock.Stats
            assertEquals(listOf("Receipts" to "2"), one.items.map { it.label to it.value })
            val items = CardBlock.Items(listOf(CardBlock.Items.Item("old", icon = "file", tone = CardTone.INFO)), numbered = true)
            val listing = DashboardValues.apply(items, Binding.FolderList("f", limit = 1), snapshot, emptyList(), 10) as CardBlock.Items
            assertEquals(items.copy(items = listOf(items.items[0].copy(title = "b.txt", detail = "5 B", meta = TileWords.time(2000, 10)))), listing)
            val named = DashboardValues.apply(items, Binding.FolderList("f", sort = FolderSort.NAME), snapshot, emptyList(), 10) as CardBlock.Items
            assertEquals(listOf("a.txt", "b.txt"), named.items.map { it.title })
            a.writeText("longer")
            assertNotEquals(snapshot.fingerprint, DashboardValues.read(root, Source.Kind.FOLDER).fingerprint)
        } finally { root.deleteRecursively() }
    }

    @Test fun oversizedAndBinaryFilesAreRejectedBeforeBinding() {
        val root = Files.createTempDirectory("dashboard-files").toFile()
        try {
            val file = File(root, "source")
            file.writeBytes(byteArrayOf(0, 1, 2))
            assertFailsWith<IllegalArgumentException> { DashboardValues.read(file, Source.Kind.FILE) }
            file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            assertFailsWith<IllegalArgumentException> { DashboardValues.read(file, Source.Kind.FILE) }
            file.writeBytes(ByteArray(2 * 1024 * 1024 + 1) { 65 })
            assertFailsWith<IllegalArgumentException> { DashboardValues.read(file, Source.Kind.FILE) }
            file.writeText("hello\nworld")
            assertEquals("hello\nworld", DashboardValues.read(file, Source.Kind.FILE).text)
        } finally { root.deleteRecursively() }
    }

    @Test fun tasksCountOnlyTheOwnerAndTodaysTerminalSessions() {
        val start = LocalDate.of(2026, 10, 28).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun session(id: String, state: SessionState, agent: String = "a", at: Long = start + 1000) =
            Session(id, id, state, at, request = "work", agentId = agent)
        val sessions = listOf(session("running", SessionState.RUNNING), session("waiting", SessionState.WAITING_APPROVAL),
            session("done", SessionState.COMPLETED), session("failed", SessionState.FAILED),
            session("yesterday", SessionState.COMPLETED, at = start - 1), session("other", SessionState.RUNNING, "b"))
        val stats = DashboardValues.apply(CardBlock.Stats(emptyList()), Binding.Tasks("a"), null, sessions, start + 5000) as CardBlock.Stats
        assertEquals(listOf("1", "1", "1", "1"), stats.items.map { it.value })
        val items = DashboardValues.apply(CardBlock.Items(emptyList()), Binding.Tasks("a"), null, sessions, start + 5000) as CardBlock.Items
        assertEquals(setOf("running", "waiting", "done", "failed"), items.items.map { it.title }.toSet())
        // States in words and a time, never the session's id.
        assertTrue(items.items.none { it.meta in setOf("running", "waiting", "done", "failed") }, "no ids in the list")
        assertEquals(TileWords.state(SessionState.WAITING_APPROVAL), items.items.single { it.title == "waiting" }.detail)
    }
}
