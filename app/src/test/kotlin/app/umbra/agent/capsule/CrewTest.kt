package app.umbra.agent.capsule

import app.umbra.core.session.EventRecord
import app.umbra.core.session.Session
import app.umbra.core.session.SessionEvent
import app.umbra.core.session.SessionState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrewTest {
    private val now = 1_000_000_000L

    private fun session(id: String, state: SessionState, created: Long = now - 60_000, updated: Long = now - 1_000, title: String = "Task $id") =
        Session(id = id, title = title, state = state, createdAtMs = created, updatedAtMs = updated, request = title)

    private fun mood(s: Session, a: SessionActivity? = null, needsYou: Boolean = false, finishedAt: Long? = null, dismissed: Boolean = false) =
        Crews.moodOf(s, a, needsYou, finishedAt, dismissed, now)

    @Test
    fun aRunningSessionThinksWorksCompactsOrShowsTheRetryAsAnError() {
        val s = session("a", SessionState.RUNNING)
        assertEquals(Mood.THINKING, mood(s))
        assertEquals(Mood.WORKING, mood(s, SessionActivity(toolRunning = true)))
        assertEquals(Mood.COMPACTING, mood(s, SessionActivity(compacting = true, toolRunning = true)))
        assertEquals(Mood.ERROR, mood(s, SessionActivity(retrying = true, compacting = true)))
    }

    @Test
    fun anApprovalOrAQuestionMeansTheSessionNeedsYou() {
        assertEquals(Mood.WAITING, mood(session("a", SessionState.WAITING_APPROVAL)))
        assertEquals(Mood.WAITING, mood(session("a", SessionState.RUNNING), SessionActivity(toolRunning = true), needsYou = true))
        assertEquals(Mood.WAITING, mood(session("a", SessionState.PAUSED), needsYou = true))
        // A request left over from a session that has ended is not shown as waiting.
        assertNull(mood(session("a", SessionState.STOPPED), needsYou = true))
    }

    @Test
    fun pausedErrorAndDoneHaveTheirOwnStatesAndWindows() {
        assertEquals(Mood.PAUSED, mood(session("a", SessionState.PAUSED)))
        assertEquals(Mood.ERROR, mood(session("a", SessionState.FAILED, updated = now - 60_000)))
        assertNull(mood(session("a", SessionState.FAILED, updated = now - 60_000), dismissed = true))
        assertNull(mood(session("a", SessionState.FAILED, updated = now - Crews.FAILED_MS - 1)))
        assertEquals(Mood.DONE, mood(session("a", SessionState.COMPLETED), finishedAt = now - 500))
        assertNull(mood(session("a", SessionState.COMPLETED), finishedAt = now - Crews.DONE_MS))
        assertNull(mood(session("a", SessionState.COMPLETED)))
        assertNull(mood(session("a", SessionState.STOPPED)))
        assertNull(mood(session("a", SessionState.CREATED)))
    }

    @Test
    fun withNothingGoingOnUmbraIsAtRest() {
        val crew = Crews.of(listOf(session("a", SessionState.COMPLETED), session("b", SessionState.STOPPED)), emptyMap(), emptySet(), emptyMap(), emptySet(), now)
        assertEquals(Crews.AT_REST, crew)
        assertTrue(crew.atRest)
    }

    @Test
    fun atMostThreeCompanionsTheOnesThatNeedYouFirstInTheirOrderOfCreation() {
        val sessions = listOf(
            session("s1", SessionState.RUNNING, created = now - 50_000, updated = now - 100),
            session("s2", SessionState.PAUSED, created = now - 40_000, updated = now - 50),
            session("s3", SessionState.RUNNING, created = now - 30_000, updated = now - 300),
            session("s4", SessionState.WAITING_APPROVAL, created = now - 20_000, updated = now - 5_000),
            session("s5", SessionState.RUNNING, created = now - 10_000, updated = now - 200),
            session("s6", SessionState.COMPLETED, created = now - 90_000),
        )
        val activity = mapOf("s3" to SessionActivity(toolRunning = true, runStartedAtMs = now - 7_000))
        val crew = Crews.of(sessions, activity, emptySet(), emptyMap(), emptySet(), now)
        // s4 needs you; then the two running sessions that changed last (s1, s5) win over s3; the paused s2 is out.
        assertEquals(listOf("s1", "s4", "s5"), crew.members.map { it.sessionId })
        assertEquals(listOf(Mood.THINKING, Mood.WAITING, Mood.THINKING), crew.members.map { it.mood })
        assertEquals(2, crew.overflow)
        assertFalse(crew.atRest)

        val all = Crews.of(sessions, activity, emptySet(), emptyMap(), emptySet(), now, max = 6)
        assertEquals(0, all.overflow)
        assertEquals(now - 7_000, all.members.first { it.sessionId == "s3" }.sinceMs)
        assertEquals(now - 50_000, all.members.first { it.sessionId == "s1" }.sinceMs)
        assertEquals(Mood.WORKING, all.moodOf("s3"))
    }

    @Test
    fun aJustCompletedSessionKeepsItsDoneMomentBesideTheOthers() {
        val sessions = listOf(session("a", SessionState.COMPLETED, created = now - 9_000), session("b", SessionState.RUNNING, created = now - 5_000))
        val crew = Crews.of(sessions, emptyMap(), emptySet(), mapOf("a" to now - 1_000), emptySet(), now)
        assertEquals(listOf(Mood.DONE, Mood.THINKING), crew.members.map { it.mood })
        val later = Crews.of(sessions, emptyMap(), emptySet(), mapOf("a" to now - 1_000), emptySet(), now + Crews.DONE_MS)
        assertEquals(listOf("b"), later.members.map { it.sessionId })
    }

    private fun record(seq: Long, event: SessionEvent, ts: Long = now + seq * 1_000) = EventRecord(seq, ts, event)

    @Test
    fun theFeedJoinsToolCallsToTheirResultsAndSkipsRoutine() {
        val records = listOf(
            record(1, SessionEvent.StateChanged(SessionState.CREATED, SessionState.RUNNING)),
            record(2, SessionEvent.UserMessage("Tidy up the phone\nplease")),
            record(3, SessionEvent.ToolCall("c1", "bash", "omp", buildJsonObject { put("command", JsonPrimitive("echo holding; sleep 60")) })),
            record(4, SessionEvent.Approval("r0", "auto_allowed", "Read a file")),
            record(5, SessionEvent.ToolResult("c1", "bash", "ok", "held", durationMs = 60_200)),
            record(6, SessionEvent.Notice("info", "Model switched")),
            record(7, SessionEvent.AgentMessage("\nI will remove the SIM toolkit next.")),
            record(8, SessionEvent.ToolCall("c2", "android_shell", "host", buildJsonObject { put("command", JsonPrimitive("pm uninstall --user 0 com.android.stk")) })),
            record(9, SessionEvent.Approval("r1", "denied", "Run: pm uninstall --user 0 com.android.stk")),
            record(10, SessionEvent.ToolResult("c2", "android_shell", "denied", "The user denied it.")),
            record(11, SessionEvent.Failure("guest", "Lost the agent process")),
            record(12, SessionEvent.ToolCall("c3", "read", "omp", buildJsonObject { put("path", JsonPrimitive("/workspace/notes.md")) })),
        )
        val feed = Feed.of(records, keep = 10)
        assertEquals(
            listOf(
                Triple(FeedEntry.Kind.YOU, null, "Tidy up the phone"),
                Triple(FeedEntry.Kind.TOOL, "bash", "echo holding; sleep 60"),
                Triple(FeedEntry.Kind.SAID, null, "I will remove the SIM toolkit next."),
                Triple(FeedEntry.Kind.TOOL, "android_shell", "pm uninstall --user 0 com.android.stk"),
                Triple(FeedEntry.Kind.APPROVAL, null, "Run: pm uninstall --user 0 com.android.stk"),
                Triple(FeedEntry.Kind.PROBLEM, null, "Lost the agent process"),
                Triple(FeedEntry.Kind.TOOL, "read", "/workspace/notes.md"),
            ),
            feed.map { Triple(it.kind, it.tool, it.text) },
        )
        assertEquals(
            listOf(FeedEntry.Status.NEUTRAL, FeedEntry.Status.OK, FeedEntry.Status.NEUTRAL, FeedEntry.Status.WARN, FeedEntry.Status.FAILED, FeedEntry.Status.FAILED, FeedEntry.Status.RUNNING),
            feed.map { it.status },
        )
        assertEquals(60_200L, feed[1].durationMs)
        assertEquals(now + 3_000, feed[1].atMs)
        assertTrue(Feed.toolRunning(feed))
        assertEquals(3, Feed.of(records, keep = 3).size)
        assertEquals("read", Feed.of(records, keep = 3).last().tool)
        assertFalse(Feed.toolRunning(Feed.of(records.dropLast(1))))
    }

    @Test
    fun aTodoStepNamesItsTaskRatherThanItsOperation() {
        val init = buildJsonObject {
            put("op", JsonPrimitive("init"))
            put("list", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("phase", JsonPrimitive("Work")); put("items", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("Inspect the phone")); add(JsonPrimitive("Verify")) }) })
            })
        }
        val done = buildJsonObject { put("op", JsonPrimitive("done")); put("task", JsonPrimitive("Inspect the phone")) }
        val feed = Feed.of(
            listOf(
                record(1, SessionEvent.ToolCall("t1", "todo", "omp", init)),
                record(2, SessionEvent.ToolResult("t1", "todo", "ok", "Remaining items (2)")),
                record(3, SessionEvent.ToolCall("t2", "todo", "omp", done)),
                record(4, SessionEvent.ToolCall("t3", "todo", "omp", buildJsonObject { put("op", JsonPrimitive("list")) })),
            ),
        )
        assertEquals(listOf("Inspect the phone · Verify", "Inspect the phone", "list"), feed.map { it.text })
    }

    @Test
    fun theRunStartsAtTheLastStartOrRestartNotAtAResumeOrAnApproval() {
        val records = listOf(
            record(1, SessionEvent.StateChanged(SessionState.CREATED, SessionState.RUNNING)),
            record(2, SessionEvent.StateChanged(SessionState.RUNNING, SessionState.COMPLETED)),
            record(3, SessionEvent.StateChanged(SessionState.COMPLETED, SessionState.RUNNING)),
            record(4, SessionEvent.StateChanged(SessionState.RUNNING, SessionState.WAITING_APPROVAL)),
            record(5, SessionEvent.StateChanged(SessionState.WAITING_APPROVAL, SessionState.RUNNING)),
            record(6, SessionEvent.StateChanged(SessionState.RUNNING, SessionState.PAUSED)),
            record(7, SessionEvent.StateChanged(SessionState.PAUSED, SessionState.RUNNING)),
        )
        assertEquals(now + 3_000, Feed.runStart(records))
        assertNull(Feed.runStart(records.drop(3)))
    }

    @Test
    fun aCompletionAndANewRequestAreSignalledOnce() {
        val before = mapOf("a" to SessionState.RUNNING, "b" to SessionState.WAITING_APPROVAL, "c" to SessionState.RUNNING, "d" to SessionState.COMPLETED)
        val after = listOf(
            session("a", SessionState.COMPLETED),
            session("b", SessionState.COMPLETED),
            session("c", SessionState.STOPPED),
            session("d", SessionState.COMPLETED),
            session("e", SessionState.COMPLETED),
        )
        assertEquals(listOf("a", "b"), Signals.completed(before, after))
        assertTrue(Signals.arrived(setOf("r1"), setOf("r1", "r2")))
        assertFalse(Signals.arrived(setOf("r1", "r2"), setOf("r2")))
        assertFalse(Signals.arrived(setOf("r1"), setOf("r1")))
    }

    @Test
    fun timesReadLikeAClock() {
        assertEquals("0:00", elapsedText(-5))
        assertEquals("0:42", elapsedText(42_900))
        assertEquals("12:05", elapsedText((12 * 60 + 5) * 1000L))
        assertEquals("1:02:03", elapsedText((3600 + 2 * 60 + 3) * 1000L))
        assertEquals("0.4", shortSeconds(400))
        assertEquals("12", shortSeconds(12_300))
        assertNull(shortSeconds(61_000))
    }

    @Test
    fun onlyLivingStatesKeepTheFrameLoopRunning() {
        assertEquals(setOf(Mood.IDLE, Mood.THINKING, Mood.WORKING, Mood.COMPACTING, Mood.WAITING), Mood.entries.filter(Crews::moves).toSet())
    }
}
