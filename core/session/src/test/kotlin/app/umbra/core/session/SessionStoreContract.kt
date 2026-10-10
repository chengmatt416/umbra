package app.umbra.core.session

import app.umbra.core.model.Clock
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive

/** Behaviour every [SessionStore] must have. Subclasses supply the implementation. */
abstract class SessionStoreContract {
    protected val time = AtomicLong(1_000)
    protected val clock = Clock { time.get() }

    abstract fun newStore(): SessionStore

    protected fun session(id: String, title: String = "Fix login crash", request: String = "Fix the login crash in the demo app", state: SessionState = SessionState.CREATED) =
        Session(id = id, title = title, request = request, state = state, createdAtMs = time.get())

    protected fun tick(ms: Long = 10) = time.addAndGet(ms)

    @Test
    fun createGetAndListRoundTrip(): Unit = runBlocking {
        val store = newStore()
        val created = store.create(session("s1").copy(goal = "login works", plan = listOf(PlanStep("p1", "reproduce"))))
        assertEquals(created, store.get("s1"))
        assertEquals(listOf("s1"), store.list().map { it.id })
        assertNull(store.get("missing"))
    }

    @Test
    fun creatingADuplicateIdFails(): Unit = runBlocking {
        val store = newStore()
        store.create(session("s1"))
        assertFailsWith<SessionExistsException> { store.create(session("s1")) }
    }

    @Test
    fun invalidIdsAreRejected(): Unit = runBlocking {
        val store = newStore()
        for (bad in listOf("", "../x", "a/b", "a b", ".hidden", "x".repeat(81))) {
            assertFailsWith<InvalidSessionIdException>("'$bad'") { store.create(session(bad)) }
        }
        assertNull(store.get("../etc"))
    }

    @Test
    fun updateIsAtomicAndBumpsUpdatedAt(): Unit = runBlocking {
        val store = newStore()
        store.create(session("s1"))
        tick()
        val updated = store.update("s1") { it.copy(state = SessionState.RUNNING, activeApps = listOf("com.example")) }
        assertNotNull(updated)
        assertEquals(SessionState.RUNNING, updated.state)
        assertEquals(time.get(), updated.updatedAtMs)
        assertNull(store.update("missing") { it })
    }

    @Test
    fun updateCannotChangeTheId(): Unit = runBlocking {
        val store = newStore()
        store.create(session("s1"))
        val updated = store.update("s1") { it.copy(id = "evil") }
        assertEquals("s1", updated?.id)
        assertNull(store.get("evil"))
    }

    @Test
    fun concurrentUpdatesDoNotLoseWrites(): Unit = runBlocking {
        val store = newStore()
        store.create(session("s1"))
        (1..100).map {
            async(Dispatchers.Default) {
                store.update("s1") { s -> s.copy(failureCount = s.failureCount + 1) }
            }
        }.awaitAll()
        assertEquals(100, store.get("s1")?.failureCount)
    }

    @Test
    fun listFiltersByStateAndQueryAndSortsByRecentActivity(): Unit = runBlocking {
        val store = newStore()
        store.create(session("a", title = "Alpha", request = "build the thing"))
        tick(); store.create(session("b", title = "Beta", request = "fix crash", state = SessionState.RUNNING))
        tick(); store.create(session("c", title = "Gamma", request = "write docs"))
        tick(); store.append("a", SessionEvent.Notice("info", "recent activity on a"))

        assertEquals(listOf("a", "c", "b"), store.list().map { it.id }, "activity on a moves it to the top")
        assertEquals(listOf("b"), store.list(SessionFilter(states = setOf(SessionState.RUNNING))).map { it.id })
        assertEquals(listOf("b"), store.list(SessionFilter(query = "CRASH")).map { it.id })
        assertEquals(listOf("c"), store.list(SessionFilter(query = "gamma")).map { it.id })
        assertEquals(2, store.list(SessionFilter(limit = 2)).size)
    }

    @Test
    fun eventsGetContiguousSequenceNumbersAndKeepOrder(): Unit = runBlocking {
        val store = newStore()
        store.create(session("s1"))
        val a = store.append("s1", SessionEvent.UserMessage("hello"))
        tick()
        val b = store.append("s1", SessionEvent.ToolCall("c1", "android_observe", "host"))
        val c = store.append("s1", SessionEvent.ToolResult("c1", "android_observe", "ok", "3 windows"))
        assertEquals(listOf(1L, 2L, 3L), listOf(a.seq, b.seq, c.seq))
        assertEquals(3, store.eventCount("s1"))

        val all = store.events("s1")
        assertEquals(listOf(1L, 2L, 3L), all.map { it.seq })
        assertIs<SessionEvent.UserMessage>(all[0].event)
        assertEquals("android_observe", (all[1].event as SessionEvent.ToolCall).tool)
        assertEquals(listOf(3L), store.events("s1", afterSeq = 2).map { it.seq })
        assertEquals(listOf(1L, 2L), store.events("s1", limit = 2).map { it.seq })
        assertEquals(listOf(2L, 3L), store.tail("s1", 2).map { it.seq })
        assertTrue(store.tail("s1", 0).isEmpty())
    }

    @Test
    fun everyEventTypeSurvivesPersistence(): Unit = runBlocking {
        val store = newStore()
        store.create(session("s1"))
        val events = listOf(
            SessionEvent.UserMessage("hi", 1),
            SessionEvent.AgentMessage("hello", "m1", "root"),
            SessionEvent.ToolCall("c1", "shell", "omp", JsonPrimitive("ls")),
            SessionEvent.ToolResult("c1", "shell", "ok", "done", "root_shell", 12, JsonPrimitive("out")),
            SessionEvent.Observation("login screen", 4, "/tmp/a.png"),
            SessionEvent.Approval("a1", "requested", "rm -rf x", "high", listOf("IMPORTANT_DATA_DELETION")),
            SessionEvent.PlanUpdated(listOf(PlanStep("1", "do it", "active"))),
            SessionEvent.Subagent("sub1", "started", "analysis"),
            SessionEvent.StateChanged(SessionState.RUNNING, SessionState.PAUSED, "user"),
            SessionEvent.Failure("shizuku", "binder died", true),
            SessionEvent.Recovery("shizuku", "restart", true, "after 2s"),
            SessionEvent.Decision("is login visible", "yes", 0.93, "laya"),
            SessionEvent.AppEvent("crash", "com.example", "NPE in LoginViewModel", "act-7"),
            SessionEvent.Notice("warn", "battery low", "perf"),
        )
        events.forEach { store.append("s1", it) }
        assertEquals(events, store.events("s1").map { it.event })
    }

    @Test
    fun appendingToAMissingSessionFails(): Unit = runBlocking {
        val store = newStore()
        assertFailsWith<SessionNotFoundException> { store.append("nope", SessionEvent.Notice("info", "x")) }
        assertTrue(store.events("nope").isEmpty())
        assertEquals(0, store.eventCount("nope"))
    }

    @Test
    fun concurrentAppendsGetUniqueContiguousSequenceNumbers(): Unit = runBlocking {
        val store = newStore()
        store.create(session("s1"))
        (1..200).map { i ->
            async(Dispatchers.Default) { store.append("s1", SessionEvent.Notice("info", "n$i")) }
        }.awaitAll()
        val seqs = store.events("s1", limit = 1000).map { it.seq }
        assertEquals((1L..200L).toList(), seqs)
        assertEquals(200, store.eventCount("s1"))
    }

    @Test
    fun deleteRemovesSessionAndTimeline(): Unit = runBlocking {
        val store = newStore()
        store.create(session("s1"))
        store.append("s1", SessionEvent.Notice("info", "x"))
        assertTrue(store.delete("s1"))
        assertFalse(store.delete("s1"))
        assertNull(store.get("s1"))
        assertTrue(store.events("s1").isEmpty())
        // The id can be reused afterwards with a clean timeline.
        store.create(session("s1"))
        assertEquals(0, store.eventCount("s1"))
    }

    @Test
    fun deleteAllClearsEverything(): Unit = runBlocking {
        val store = newStore()
        store.create(session("a")); store.create(session("b"))
        store.append("a", SessionEvent.Notice("info", "x"))
        store.deleteAll()
        assertTrue(store.list().isEmpty())
        assertTrue(store.events("a").isEmpty())
    }

    @Test
    fun duplicateStartsFreshButKeepsTheTask(): Unit = runBlocking {
        val store = newStore()
        val original = session("s1").copy(
            state = SessionState.FAILED,
            goal = "login works",
            plan = listOf(PlanStep("p1", "reproduce", "done")),
            workspaceId = "ws1",
            ompSessionFile = "/guest/.omp/s1.jsonl",
            displays = listOf(4),
            processes = listOf(ProcessRef(10, "omp")),
            failureCount = 3,
            lastError = "boom",
            attachments = mapOf("allow_rules" to JsonPrimitive("x")),
        )
        store.create(original)
        store.append("s1", SessionEvent.UserMessage("hello"))
        tick()

        val copy = assertNotNull(store.duplicate("s1", "s2"))
        assertEquals("Fix login crash (copy)", copy.title)
        assertEquals(SessionState.CREATED, copy.state)
        assertEquals("s1", copy.parentSessionId)
        assertEquals("login works", copy.goal)
        assertEquals("ws1", copy.workspaceId)
        assertEquals(original.request, copy.request)
        assertEquals(original.plan, copy.plan)
        assertNull(copy.ompSessionFile)
        assertTrue(copy.displays.isEmpty() && copy.processes.isEmpty() && copy.attachments.isEmpty())
        assertEquals(0, copy.failureCount)
        assertNull(copy.lastError)
        assertTrue(store.events("s2").isEmpty(), "history is not copied by default")
        assertNull(store.duplicate("missing", "s3"))
        assertFailsWith<SessionExistsException> { store.duplicate("s1", "s2") }
    }

    @Test
    fun duplicateWithHistoryCopiesTheTimelineAndKeepsNumbering(): Unit = runBlocking {
        val store = newStore()
        store.create(session("s1"))
        store.append("s1", SessionEvent.UserMessage("one"))
        store.append("s1", SessionEvent.UserMessage("two"))
        store.duplicate("s1", "s2", title = "Branch", withHistory = true)
        assertEquals(listOf(1L, 2L), store.events("s2").map { it.seq })
        val next = store.append("s2", SessionEvent.UserMessage("three"))
        assertEquals(3L, next.seq)
        assertEquals(2, store.eventCount("s1"), "the source is untouched")
    }

    @Test
    fun changesFlowReportsEveryMutation(): Unit = runBlocking {
        val store = newStore()
        val seen = mutableListOf<StoreChange>()
        val job = launch(Dispatchers.Unconfined) { store.changes.toList(seen) }
        store.create(session("s1"))
        store.update("s1") { it.copy(title = "Renamed") }
        store.append("s1", SessionEvent.Notice("info", "x"))
        store.delete("s1")
        withTimeout(2_000) { while (seen.size < 4) kotlinx.coroutines.yield() }
        job.cancel()
        assertEquals(
            listOf("SessionChanged", "SessionChanged", "EventAppended", "SessionDeleted"),
            seen.map { it::class.simpleName },
        )
    }
}
