package app.umbra.agent.agents.proactive

import app.umbra.core.model.UmbraJson
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.*

class DailyBriefEngineTest {
    @Test fun generatedBriefPersistsTasksAndCountsOnlyCompletedTasksAsDone() {
        val root = Files.createTempDirectory("brief-tasks-").toFile()
        val store = ProactiveStore(root)
        val tasks = listOf(BriefTask("Build", "completed", "Verified", "build", 1), BriefTask("Sync", "failed", "Offline", "sync", 2),
            BriefTask("Research", "running", linkedSessionId = "research"), BriefTask("Review", "waiting", linkedSessionId = "review"))
        val engine = DailyBriefEngine(store, ZoneId.of("UTC"))
        val brief = engine.generate("agent", DailyBrief.Kind.MORNING, 86_400_000L, tasks = tasks)
        assertEquals(tasks, brief.tasks)
        assertTrue(brief.overnightCompleted.isEmpty())
        assertEquals(BriefHeadline.of(DailyBrief.Kind.MORNING, 0, 0, 0, 1, failed = 1, running = 1, waiting = 1), brief.headline)
        assertEquals(brief, ProactiveStore(root).briefs("agent").single())
        assertEquals(brief, engine.generate("agent", DailyBrief.Kind.MORNING, 86_400_001L))
    }

    @Test fun taskCapAlsoBoundsHeadlineCounts() {
        val engine = DailyBriefEngine(ProactiveStore(Files.createTempDirectory("brief-cap-").toFile()), ZoneId.of("UTC"))
        val tasks = (1..35).map { BriefTask("Task $it", "completed") }
        val brief = engine.generate("agent", DailyBrief.Kind.MORNING, 86_400_000L, tasks = tasks)
        assertEquals(tasks.take(30), brief.tasks)
        assertEquals(BriefHeadline.of(DailyBrief.Kind.MORNING, 0, 0, 0, 30), brief.headline)
    }

    @Test fun legacyBriefsStillDecodeWithoutTasks() {
        val brief = UmbraJson.decodeFromString(DailyBrief.serializer(), """{
            "id":"old","date":"2026-10-24","kind":"MORNING","headline":"Old headline",
            "overnightCompleted":[{"title":"Old result","detail":"Saved"}],"generatedAtMs":1
        }""")
        assertTrue(brief.tasks.isEmpty())
        assertEquals("Old result", brief.overnightCompleted.single().title)
    }
}
