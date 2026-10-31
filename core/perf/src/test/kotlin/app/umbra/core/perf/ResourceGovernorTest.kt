package app.umbra.core.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ResourceGovernorTest {
    private val good = ResourceSample(0, memAvailMb = 3000, batteryPct = 80, charging = false, storageFreeMb = 20_000)
    private val low = good.copy(batteryPct = 10) // HIGH: battery low, not charging

    private class Script(var sample: ResourceSample) : ResourceSampler {
        var failNext = false
        var calls = 0
        override suspend fun sample(): ResourceSample {
            calls++
            if (failNext) { failNext = false; error("sensor unavailable") }
            return sample
        }
    }

    @Test
    fun idleSamplingWakesImmediatelyWhenASessionStarts() = runTest {
        val script = Script(good)
        val g = ResourceGovernor(script, intervalMs = 1_000)
        val loop = g.start(backgroundScope)
        runCurrent()
        assertEquals(1, script.calls)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(1, script.calls)
        assertTrue(g.tryStartSession())
        runCurrent()
        assertEquals(2, script.calls)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, script.calls)
        g.endSession()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(4, script.calls)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(4, script.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(5, script.calls)
        loop.cancel()
    }

    @Test
    fun underPressureSamplingKeepsItsPaceAndRestsOnlyOnceNormal() = runTest {
        val script = Script(low)
        val g = ResourceGovernor(script, intervalMs = 1_000)
        val loop = g.start(backgroundScope)
        runCurrent()
        assertEquals(1, script.calls)
        val work = async { g.withAdmission(WorkClass.EXPENSIVE) { error("pressure should hold this work") } }
        runCurrent()
        // Short of power: work waits, and every interval a sample decides whether it may run.
        repeat(2) { advanceTimeBy(1_000); runCurrent() }
        assertEquals(3, script.calls)
        work.cancel()
        runCurrent()
        // Nothing waits any more, but the pressure stays: the normal pace holds, since the next sample decides when a
        // new task may start again.
        repeat(2) { advanceTimeBy(1_000); runCurrent() }
        assertEquals(5, script.calls)
        // Pressure gone: once the decision is back to normal and nothing runs or waits, sampling rests.
        script.sample = good
        repeat(30) { if (g.decision.value.level != PressureLevel.NORMAL) { advanceTimeBy(1_000); runCurrent() } }
        assertEquals(PressureLevel.NORMAL, g.decision.value.level)
        val calls = script.calls
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(calls, script.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(calls + 1, script.calls)
        loop.cancel()
    }

    @Test
    fun publishesTheDecisionFromTheLatestSample() = runTest {
        val script = Script(good)
        val g = ResourceGovernor(script, intervalMs = 1_000)
        assertEquals(PressureLevel.NORMAL, g.decision.value.level)
        script.sample = low
        g.refreshNow()
        assertEquals(PressureLevel.HIGH, g.decision.value.level)
        assertEquals(low, g.latestSample)
    }

    @Test
    fun theLoopKeepsRunningWhenTheSamplerFails() = runTest {
        val script = Script(low)
        val g = ResourceGovernor(script, intervalMs = 1_000, idleIntervalMs = 1_000)
        val job = g.start(backgroundScope)
        runCurrent()
        assertEquals(PressureLevel.HIGH, g.decision.value.level)
        script.failNext = true
        advanceTimeBy(1_001)
        assertEquals(PressureLevel.HIGH, g.decision.value.level, "keeps the last decision")
        script.sample = good
        advanceTimeBy(10_000)
        assertTrue(job.isActive)
        assertTrue(g.decision.value.level < PressureLevel.HIGH, "recovers once samples are good again")
        job.cancel()
    }

    @Test
    fun expensiveWorkRunsImmediatelyWhenTheDeviceIsHealthy() = runTest {
        val g = ResourceGovernor(Script(good))
        val result = g.withAdmission(WorkClass.EXPENSIVE) { currentTime }
        assertEquals(0, result)
    }

    @Test
    fun expensiveConcurrencyIsCapped() = runTest {
        val g = ResourceGovernor(Script(good)) // normal: 2 expensive at a time
        var running = 0
        var peak = 0
        val jobs = (1..5).map {
            async {
                g.withAdmission(WorkClass.EXPENSIVE) {
                    running++; peak = maxOf(peak, running)
                    delay(100)
                    running--
                }
            }
        }
        advanceUntilIdle()
        jobs.forEach { it.await() }
        assertEquals(2, peak)
        assertEquals(0, g.activeExpensive)
        assertEquals(300, currentTime, "5 jobs, 2 at a time, 100ms each = 3 waves")
    }

    @Test
    fun expensiveWorkWaitsOutThePressureInsteadOfFailing() = runTest {
        val script = Script(low)
        val g = ResourceGovernor(script, intervalMs = 1_000)
        g.refreshNow()
        assertFalse(g.decision.value.allows(WorkClass.EXPENSIVE))

        var ranAt = -1L
        val work = async { g.withAdmission(WorkClass.EXPENSIVE) { ranAt = currentTime } }
        runCurrent()
        assertEquals(-1, ranAt, "held while the battery is low")

        script.sample = good
        val loop = g.start(backgroundScope)
        advanceUntilIdle()
        work.await()
        assertTrue(ranAt > 0, "resumed after pressure relaxed (three good samples, one level at a time)")
        assertTrue(g.decision.value.allows(WorkClass.EXPENSIVE))
        loop.cancel()
    }

    @Test
    fun lightWorkContinuesUnderHighPressureButNotWhenCritical() = runTest {
        val script = Script(low)
        val g = ResourceGovernor(script)
        g.refreshNow()
        assertEquals("ran", g.withAdmission(WorkClass.LIGHT) { "ran" })

        script.sample = good.copy(batteryPct = 3)
        g.refreshNow()
        assertEquals(PressureLevel.CRITICAL, g.decision.value.level)
        var lightRan = false
        val light = async { g.withAdmission(WorkClass.LIGHT) { lightRan = true } }
        runCurrent()
        assertFalse(lightRan)
        assertEquals("user", g.withAdmission(WorkClass.INTERACTIVE) { "user" }, "the user is never blocked")
        light.cancel()
    }

    @Test
    fun sessionsAreLimitedAndFreedWhenTheyEnd() = runTest {
        val g = ResourceGovernor(Script(good)) // normal: 3 sessions
        assertTrue(g.tryStartSession() && g.tryStartSession() && g.tryStartSession())
        assertFalse(g.tryStartSession(), "fourth session is refused")
        assertEquals(3, g.activeSessions)
        g.endSession()
        assertTrue(g.canStartSession())
        assertTrue(g.tryStartSession())
        repeat(10) { g.endSession() }
        assertEquals(0, g.activeSessions, "never negative")
    }

    @Test
    fun underHighPressureOneTaskStartsAtATime() = runTest {
        val script = Script(low)
        val g = ResourceGovernor(script)
        g.refreshNow()
        assertEquals(PressureLevel.HIGH, g.decision.value.level)
        assertTrue(g.canStartSession(), "a task the person asks for still starts")
        assertTrue(g.tryStartSession())
        assertFalse(g.tryStartSession(), "but only one at a time")
        assertEquals(1, g.activeSessions)
    }

    @Test
    fun aWorkBlockThatThrowsStillReleasesItsSlot() = runTest {
        val g = ResourceGovernor(Script(good))
        runCatching { g.withAdmission(WorkClass.EXPENSIVE) { error("build failed") } }
        assertEquals(0, g.activeExpensive)
        assertEquals("next", g.withAdmission(WorkClass.EXPENSIVE) { "next" })
    }
}
