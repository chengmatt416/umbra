package app.umbra.agent.agents.proactive

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class CalendarDraftTest {
    private val draft = CalendarDraft("event", "session", "Review", 1_800_000_000_000, 1_800_003_600_000, "Asia/Taipei", "Office", "Bring the agenda", 0)
    @Test fun validatesBoundsAndShowsExactEventForApproval() {
        draft.validate()
        assertContains(draft.review(), "Asia/Taipei")
        assertContains(draft.review(), "Bring the agenda")
        assertFailsWith<IllegalArgumentException> { draft.copy(title = "").validate() }
        assertFailsWith<IllegalArgumentException> { draft.copy(endMs = draft.startMs).validate() }
        assertFailsWith<IllegalArgumentException> { draft.copy(endMs = draft.startMs + 367 * 86_400_000L).validate() }
        assertFailsWith<java.time.DateTimeException> { draft.copy(zoneId = "invalid").validate() }
    }
}
