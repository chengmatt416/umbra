package app.umbra.agent.capsule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class CapsuleContentTest {
    @Test
    fun contentIsReadOnlyAfterASignalAndDoesNotPollWhileIdle() = runTest {
        val signals = MutableStateFlow(0L)
        var content = "before"
        var reads = 0
        val shown = mutableListOf<String>()
        val job = backgroundScope.launch {
            capsuleContent(signals) { reads++; content }.collect { shown += it }
        }
        runCurrent()
        assertEquals(0, reads)
        content = "received"
        signals.value++
        runCurrent()
        assertEquals(listOf("received"), shown)
        content = "waiting for broadcast"
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, reads)
        assertEquals(listOf("received"), shown)
        signals.value++
        runCurrent()
        assertEquals(listOf("received", "waiting for broadcast"), shown)
        job.cancel()
    }
}
