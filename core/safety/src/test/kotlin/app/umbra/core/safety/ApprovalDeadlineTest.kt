package app.umbra.core.safety

import app.umbra.core.model.Clock
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ApprovalDeadlineTest {
    @Test fun elapsedWallClockRejectsApprovalBeforeCoroutineTimeoutRuns() = runTest {
        var now = 100L
        val broker = ApprovalBroker(Clock { now })
        val result = async { broker.request(ApprovalRequest("a", "s", ApprovalSource.HOST_TOOL, "Send", "Send the draft", createdAtMs = now, timeoutMs = 1000)) }
        runCurrent()
        now = 1100
        assertFalse(broker.resolve("a", ApprovalDecision.Approved()))
        assertEquals(ApprovalDecision.TimedOut, result.await())
        assertTrue(broker.pending.value.isEmpty())
    }
    @Test fun sessionTtlNeverExtendsAnExplicitShorterDeadline() = runTest {
        var now = 100L
        val broker = ApprovalBroker(Clock { now })
        broker.setSessionTimeout("s", 600_000)
        val result = async { broker.request(ApprovalRequest("a", "s", ApprovalSource.HOST_TOOL, "Send", "Send the draft", createdAtMs = now, timeoutMs = 50)) }
        runCurrent()
        assertEquals(50L, broker.pending.value.single().timeoutMs)
        now += 51
        assertFalse(broker.resolve("a", ApprovalDecision.Approved()))
        assertEquals(ApprovalDecision.TimedOut, result.await())
    }
}
