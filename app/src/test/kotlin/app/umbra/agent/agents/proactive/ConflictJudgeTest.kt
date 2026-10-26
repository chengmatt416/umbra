package app.umbra.agent.agents.proactive

import app.umbra.core.decision.Answer
import app.umbra.core.decision.Routed
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ConflictJudgeTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L
    private val work = CalendarItem("1", "Design review", now + hour, now + 2 * hour, calendar = "Work", account = "mei@corp.example", organizer = "lead@corp.example", uid = "abc@corp")
    private val personal = CalendarItem("2", "Design review", now + hour, now + 2 * hour, calendar = "mei@gmail.example", account = "mei@gmail.example", organizer = "lead@corp.example", uid = "abc@corp")
    private val dentist = CalendarItem("3", "Dentist", now + hour + 30 * 60_000L, now + 3 * hour, location = "Main St 4")

    private fun layaSays(choice: String?, confidence: Double = 0.9, fallback: Boolean = false): suspend (app.umbra.core.decision.DecisionRequest) -> Routed<Map<String, Answer>>? = {
        if (choice == null) null
        else Routed(mapOf("verdict" to Answer.ChoiceAnswer(choice, mapOf(choice to confidence), confidence)), confidence, if (fallback) "heuristic" else "laya", fallback, confidence < 0.6, "")
    }

    @Test
    fun theTimeScanOnlyProposesPairs() {
        val pairs = ConflictDetector.candidates(listOf(work, personal, dentist), now)
        assertEquals(3, pairs.size)
        // Nothing judged: nothing is called a conflict, only a possible one that may be the same event.
        val alerts = ConflictDetector.alerts(pairs.map { ConflictReading(it, null, null) })
        assertTrue(alerts.all { it.severity == ConflictJudge.POSSIBLE && "same event" in it.description })
    }

    @Test
    fun layaDecidesWhenItIsSure() = runTest {
        val judge = ConflictJudge(layaSays("same"), zone = ZoneId.of("UTC"))
        val readings = judge.judge(listOf(ConflictCandidate(work, personal, ConflictJudge.OVERLAP))) { error("the model is not asked") }
        assertEquals(ConflictVerdict.SAME_EVENT, readings.single().verdict)
        assertEquals("laya", readings.single().by)
        assertTrue(ConflictDetector.alerts(readings).isEmpty())
    }

    @Test
    fun theBuiltInRulesAndUnsureAnswersGoToTheModel() = runTest {
        for (laya in listOf(layaSays("conflict", fallback = true), layaSays("conflict", confidence = 0.62), layaSays(null))) {
            var asked: String? = null
            val judge = ConflictJudge(laya, zone = ZoneId.of("UTC"))
            val pairs = listOf(ConflictCandidate(work, personal, ConflictJudge.OVERLAP), ConflictCandidate(work, dentist, ConflictJudge.OVERLAP))
            val readings = judge.judge(pairs) { prompt ->
                asked = prompt
                "Sure. {\"pairs\":[{\"id\":\"p1\",\"verdict\":\"same\",\"why\":\"one invite on two accounts\"},{\"id\":\"p2\",\"verdict\":\"conflict\"},{\"id\":\"p9\",\"verdict\":\"conflict\"}]}"
            }
            val prompt = checkNotNull(asked)
            assertTrue(prompt.contains("calendar Work, mei@corp.example") && prompt.contains("iCalendar UID abc@corp") && prompt.contains("untrusted"))
            assertEquals(listOf(ConflictVerdict.SAME_EVENT, ConflictVerdict.CONFLICT), readings.map { it.verdict })
            assertEquals(listOf("model", "model"), readings.map { it.by })
            val alerts = ConflictDetector.alerts(readings)
            assertEquals(listOf("Design review overlaps Dentist."), alerts.map { it.description })
            assertEquals(ConflictJudge.OVERLAP, alerts.single().severity)
        }
    }

    @Test
    fun anUnreadableReplyLeavesThePairUnjudged() = runTest {
        val judge = ConflictJudge(layaSays(null))
        val readings = judge.judge(listOf(ConflictCandidate(work, dentist, ConflictJudge.OVERLAP))) { "I think they conflict." }
        assertNull(readings.single().verdict)
    }

    @Test
    fun verdictsAreKeptUntilAnEntryChanges() = runTest {
        val file = Files.createTempDirectory("verdicts").resolve("v.json").toFile()
        var calls = 0
        val model: suspend (String) -> String? = { calls++; "{\"pairs\":[{\"id\":\"p1\",\"verdict\":\"compatible\"}]}" }
        ConflictJudge(layaSays(null), file).judge(listOf(ConflictCandidate(work, dentist, ConflictJudge.OVERLAP)), model)
        // A new judge reads the kept verdict (an app restart): no second request.
        val again = ConflictJudge(layaSays(null), file).judge(listOf(ConflictCandidate(work, dentist, ConflictJudge.OVERLAP)), model)
        assertEquals(ConflictVerdict.COMPATIBLE, again.single().verdict)
        assertEquals(1, calls)
        // The dentist moved: ask again.
        ConflictJudge(layaSays(null), file).judge(listOf(ConflictCandidate(work, dentist.copy(startMs = dentist.startMs + 60_000), ConflictJudge.OVERLAP)), model)
        assertEquals(2, calls)
    }

    @Test
    fun theBriefListsOneMeetingOnTwoCalendarsOnceAndCountsOnlyJudgedConflicts() {
        val store = ProactiveStore(Files.createTempDirectory("brief").toFile())
        val day = java.time.LocalDate.of(2027, 1, 15).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val a = work.copy(startMs = day + 9 * hour, endMs = day + 10 * hour)
        val b = personal.copy(startMs = day + 9 * hour, endMs = day + 10 * hour)
        val c = dentist.copy(startMs = day + 9 * hour + 1_800_000, endMs = day + 11 * hour)
        val readings = listOf(
            ConflictReading(ConflictCandidate(a, b, ConflictJudge.OVERLAP), ConflictVerdict.SAME_EVENT, "laya"),
            ConflictReading(ConflictCandidate(a, c, ConflictJudge.OVERLAP), ConflictVerdict.CONFLICT, "model"),
            ConflictReading(ConflictCandidate(b, c, ConflictJudge.OVERLAP), null, null),
        )
        val brief = DailyBriefEngine(store).generate("agent", DailyBrief.Kind.MORNING, day + 7 * hour, listOf(a, b, c), conflicts = readings)
        assertEquals(listOf("Design review", "Dentist"), brief.scheduleHighlights.map { it.title })
        assertEquals(2, brief.conflictsDetected.size)
        assertTrue(brief.headline.contains("1 conflict") || brief.headline.contains("1 個衝突"))
    }
}
