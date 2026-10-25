package app.umbra.agent.agents.proactive

import kotlin.test.*

class BriefTextTest {
    private val decision = OpportunityCard("card", "agent", OpportunityCard.Kind.FOLLOW_UP, "Review renewal", "Approve the draft",
        listOf("email:1"), .8, .9, PreparedNextStep("Review", "agent_turn", "{}"), createdAtMs = 1)
    private val brief = DailyBrief("brief", "2026-10-24", DailyBrief.Kind.MORNING, "Today’s readout",
        scheduleHighlights = listOf(BriefItem("Meeting", "Office")),
        conflictsDetected = listOf(ConflictAlert("conflict", "overlap", "Two meetings overlap", emptyList())),
        overnightCompleted = listOf(BriefItem("Report", "Verified and saved")), attentionNeeded = listOf(decision),
        lowPriorityDigest = "Parcel arrives tomorrow", generatedAtMs = 1)

    @Test fun channelReadoutIncludesDecisionsConflictsScheduleCompletionsAndDigest() {
        val text = BriefText.format(brief)
        for (content in listOf("2026-10-24", "Today’s readout", "Review renewal: Approve the draft", "Two meetings overlap",
            "Meeting: Office", "Report: Verified and saved", "Parcel arrives tomorrow")) assertTrue(content in text, content)
        assertTrue(text.indexOf("Needs your attention") < text.indexOf("Schedule"))
        assertTrue(text.indexOf("Conflicts") < text.indexOf("Schedule"))
    }

    @Test fun emptySectionsStayAbsentAndLargeBriefsStayBounded() {
        val empty = DailyBrief("brief", "2026-10-24", DailyBrief.Kind.EVENING, "No changes", generatedAtMs = 1)
        assertEquals("2026-10-24 · EVENING\nNo changes", BriefText.format(empty))
        val large = BriefText.format(brief.copy(attentionNeeded = List(50) { decision.copy(summary = "x".repeat(1000)) },
            scheduleHighlights = List(50) { BriefItem("Meeting", "x".repeat(1000)) }, lowPriorityDigest = "x".repeat(20_000)))
        assertTrue(large.length <= 16_000)
        assertTrue("Review renewal" in large)
        assertTrue("Completed" in large && "Report: Verified and saved" in large)
        assertTrue("Schedule" in large && "Updates" in large)
    }
}
