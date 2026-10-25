package app.umbra.agent.data

import java.time.Instant
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class UsageCountsTest {
    private val id = "1a25995e-76ee-45cb-ab59-b232650c8438"
    private var time = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli()
    private var state = UsageCountState()
    private var enabled = true
    private val sent = mutableListOf<String>()
    private fun counts(send: suspend (String) -> Boolean = { sent += it; true }) = UsageCounts({ enabled }, { state }, { state = it }, send, { time }, { id })

    @Test fun optOutProducesNoIdentifierWritesOrNetworkCalls(): Unit = runTest {
        enabled = false
        counts().recordOpen()
        assertEquals(UsageCountState(), state)
        assertTrue(sent.isEmpty())
    }

    @Test fun concurrentOpensAndRestartsSendOnlyOncePerUtcMonthWithTheSameInstallId(): Unit = runTest {
        val counter = counts()
        coroutineScope { repeat(20) { launch { counter.recordOpen() } } }
        assertEquals(listOf(id), sent)
        counts().recordOpen()
        assertEquals(1, sent.size)
        time = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
        counter.recordOpen()
        assertEquals(listOf(id, id), sent)
        assertEquals("2026-11", state.sentMonth)
    }

    @Test fun failuresAreRetriedAfterBackoffWithoutLosingTheInstallationIdentity(): Unit = runTest {
        val failed = counts { sent += it; false }
        failed.recordOpen()
        assertNull(state.sentMonth)
        repeat(20) { failed.recordOpen() }
        assertEquals(1, sent.size)
        time += 6 * 3_600_000L
        counts().recordOpen()
        assertEquals(listOf(id, id), sent)
        assertEquals("2026-10", state.sentMonth)
    }

    @Test fun corruptStoredValuesCannotBeTransmittedAsTheInstallIdentifier(): Unit = runTest {
        state = UsageCountState(installId = "private task text")
        counts().recordOpen()
        assertEquals(listOf(id), sent)
        assertEquals(id, state.installId)
    }

    @Test fun disabledQueuedOpensNeverStartAnotherRequest(): Unit = runTest {
        val inFlight = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val counts = counts { sent += it; inFlight.complete(Unit); finish.await(); false }
        val first = launch { counts.recordOpen() }
        inFlight.await()
        val queued = launch { counts.recordOpen() }
        enabled = false
        finish.complete(Unit)
        first.join(); queued.join()
        time += 10 * 3_600_000L
        counts.recordOpen()
        assertEquals(listOf(id), sent)
    }

    @Test fun storageOrNetworkFailureCannotPreventOpeningTheAppAndCancellationStillPropagates(): Unit = runTest {
        UsageCounts({ true }, { state }, { error("storage unavailable") }, { error("must not send") }, { time }, { id }).recordOpen()
        counts { error("offline") }.recordOpen()
        assertNull(state.sentMonth)
        time += 6 * 3_600_000L
        assertFailsWith<CancellationException> { counts { throw CancellationException("cancelled") }.recordOpen() }
    }
}
