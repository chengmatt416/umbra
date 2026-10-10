package app.umbra.core.recovery

import app.umbra.core.model.Clock
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class HealthSupervisorTest {
    /** A subsystem whose behaviour the test scripts. */
    private class Fake(
        override val id: String,
        override val dependsOn: Set<String> = emptySet(),
    ) : Subsystem {
        var health: Health = Health.Healthy
        var restartResults = ArrayDeque<RestartResult>()
        var onRestart: (Fake) -> Unit = {}
        var restarts = 0
        val restartTimes = mutableListOf<Long>()
        var probeDelayMs = 0L
        var probeThrows = false
        var restartDelayMs = 0L
        lateinit var now: () -> Long

        override suspend fun probe(): Health {
            if (probeDelayMs > 0) delay(probeDelayMs)
            if (probeThrows) error("binder transaction failed")
            return health
        }

        override suspend fun restart(): RestartResult {
            restarts++
            restartTimes += now()
            if (restartDelayMs > 0) delay(restartDelayMs)
            val result = restartResults.removeFirstOrNull() ?: RestartResult.Restarted
            onRestart(this)
            return result
        }
    }

    private val policy = RecoveryPolicy(
        probeIntervalMs = 1_000, baseBackoffMs = 1_000, maxBackoffMs = 8_000, maxConsecutiveFailures = 4,
        circuitOpenMs = 30_000, jitterRatio = 0.0, probeTimeoutMs = 2_000, restartTimeoutMs = 5_000,
    )

    private fun TestScope.supervisor(vararg subs: Fake, p: RecoveryPolicy = policy): HealthSupervisor {
        subs.forEach { it.now = { currentTime } }
        return HealthSupervisor(subs.toList(), p, Clock { currentTime }, Random(1))
    }

    private fun TestScope.collect(sup: HealthSupervisor): MutableList<RecoveryEvent> {
        val events = mutableListOf<RecoveryEvent>()
        backgroundScope.launch { sup.events.toList(events) }
        return events
    }

    private fun List<RecoveryEvent>.names() = map { it::class.simpleName }

    @Test
    fun aHealthySubsystemProducesNoEventsAndNoRestarts() = runTest {
        val a = Fake("shizuku")
        val sup = supervisor(a)
        val events = collect(sup)
        runCurrent()
        repeat(3) { sup.checkNow() }
        assertTrue(events.isEmpty())
        assertEquals(0, a.restarts)
    }

    @Test
    fun aFailureIsRestartedImmediatelyAndReportedAsRecovered() = runTest {
        val a = Fake("shizuku")
        a.health = Health.Failed("binder died")
        a.onRestart = { it.health = Health.Healthy }
        val sup = supervisor(a)
        val events = collect(sup)
        runCurrent()

        sup.checkNow()
        runCurrent()
        assertEquals(listOf("ProbeFailed", "RestartStarted", "Recovered"), events.names())
        assertEquals(1, a.restarts)
        val s = sup.state("shizuku")
        assertEquals(0, s.consecutiveFailures)
        assertEquals(1, s.totalRestarts)
        assertTrue(s.health.isHealthy)
    }

    @Test
    fun failedRestartsBackOffExponentiallyAndThenOpenTheCircuit() = runTest {
        val a = Fake("omp")
        a.health = Health.Failed("crashed")
        a.restartResults = ArrayDeque(List(10) { RestartResult.Failed("still broken") })
        val sup = supervisor(a)
        val events = collect(sup)
        val loop = sup.start(backgroundScope)
        runCurrent()

        advanceTimeBy(60_000)
        // attempt 1 at t=0, then waits 1s, 2s, 4s: attempts at 0, 1000, 3000, 7000.
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), a.restartTimes.take(4), a.restartTimes.toString())
        val opened = events.filterIsInstance<RecoveryEvent.CircuitOpened>().first()
        assertEquals(7_000, opened.atMs)
        assertEquals(37_000, opened.untilMs)

        // While open nothing is restarted.
        val restartsWhileOpen = a.restartTimes.count { it in 7_001..36_999 }
        assertEquals(0, restartsWhileOpen)
        loop.cancel()
    }

    @Test
    fun aHalfOpenCircuitGetsOneTrialAndClosesWhenItWorks() = runTest {
        val a = Fake("omp")
        a.health = Health.Failed("crashed")
        a.restartResults = ArrayDeque(List(4) { RestartResult.Failed("still broken") })
        a.onRestart = { if (it.restarts > 4) it.health = Health.Healthy }
        val sup = supervisor(a)
        val events = collect(sup)
        val loop = sup.start(backgroundScope)
        runCurrent()

        advanceTimeBy(45_000)
        assertEquals(5, a.restarts, "four failures, then exactly one trial after the circuit window")
        assertTrue(events.any { it is RecoveryEvent.CircuitHalfOpen })
        assertTrue(events.any { it is RecoveryEvent.Recovered })
        assertEquals(CircuitState.CLOSED, sup.state("omp").circuit)
        assertEquals(0, sup.state("omp").consecutiveFailures)
        loop.cancel()
    }

    @Test
    fun aFailedTrialReopensTheCircuit() = runTest {
        val a = Fake("omp")
        a.health = Health.Failed("crashed")
        a.restartResults = ArrayDeque(List(20) { RestartResult.Failed("nope") })
        val sup = supervisor(a)
        val events = collect(sup)
        val loop = sup.start(backgroundScope)
        runCurrent()
        advanceTimeBy(80_000)
        assertTrue(events.filterIsInstance<RecoveryEvent.CircuitOpened>().size >= 2, events.names().toString())
        loop.cancel()
    }

    @Test
    fun backoffIsCappedAndJitterStaysWithinBounds() {
        val sup = HealthSupervisor(
            emptyList(),
            RecoveryPolicy(baseBackoffMs = 1_000, maxBackoffMs = 8_000, jitterRatio = 0.2),
            random = Random(42),
        )
        for (attempt in 1..30) {
            val expected = minOf(1_000L shl minOf(attempt - 1, 20), 8_000L)
            repeat(50) {
                val d = sup.backoffMs(attempt)
                assertTrue(d >= (expected * 0.8).toLong() - 1 && d <= (expected * 1.2).toLong() + 1, "attempt $attempt: $d not within 20% of $expected")
            }
        }
    }

    @Test
    fun anUnsafeFailureIsSurfacedNotRestarted() = runTest {
        val a = Fake("runtime")
        a.health = Health.Failed("filesystem busy: build in progress", safeToRestart = false)
        val sup = supervisor(a)
        val events = collect(sup)
        runCurrent()
        repeat(3) { sup.checkNow() }
        runCurrent()
        assertEquals(0, a.restarts)
        assertEquals(1, events.filterIsInstance<RecoveryEvent.NeedsUser>().size, "reported once, not on every probe")
        assertTrue(sup.state("runtime").needsUser!!.contains("not safe to restart"))

        // When it heals by itself the flag clears.
        a.health = Health.Healthy
        sup.checkNow()
        runCurrent()
        assertEquals(null, sup.state("runtime").needsUser)
        assertTrue(events.any { it is RecoveryEvent.Recovered })
    }

    @Test
    fun aRestartThatNeedsTheUserStopsFurtherAttemptsUntilReset() = runTest {
        val a = Fake("accessibility")
        a.health = Health.Failed("service disabled")
        a.restartResults = ArrayDeque(listOf(RestartResult.NeedsUser("Enable Umbra in Accessibility settings")))
        val sup = supervisor(a)
        val events = collect(sup)
        runCurrent()
        sup.checkNow(); sup.checkNow(); sup.checkNow()
        runCurrent()
        assertEquals(1, a.restarts)
        assertEquals("Enable Umbra in Accessibility settings", sup.state("accessibility").needsUser)
        assertEquals(1, events.filterIsInstance<RecoveryEvent.NeedsUser>().size)

        sup.reset("accessibility")
        a.onRestart = { it.health = Health.Healthy }
        sup.checkNow()
        assertEquals(2, a.restarts, "reset allows a fresh attempt")
        assertTrue(sup.state("accessibility").health.isHealthy)
    }

    @Test
    fun dependenciesAreRecoveredFirstAndTheWaitIsReportedOnce() = runTest {
        val shizuku = Fake("shizuku")
        val display = Fake("display", dependsOn = setOf("shizuku"))
        shizuku.health = Health.Failed("binder died")
        display.health = Health.Failed("virtual display lost")
        shizuku.restartResults = ArrayDeque(listOf(RestartResult.Failed("not yet")))
        shizuku.onRestart = { if (it.restarts >= 2) it.health = Health.Healthy }
        display.onRestart = { it.health = Health.Healthy }
        val sup = supervisor(shizuku, display)
        val events = collect(sup)
        val loop = sup.start(backgroundScope)
        runCurrent()

        advanceTimeBy(10_000)
        assertEquals(1, events.filterIsInstance<RecoveryEvent.Skipped>().count { it.subsystem == "display" }, "waiting is reported once")
        val shizukuRecovered = events.indexOfFirst { it is RecoveryEvent.Recovered && it.subsystem == "shizuku" }
        val displayRestart = events.indexOfFirst { it is RecoveryEvent.RestartStarted && it.subsystem == "display" }
        assertTrue(shizukuRecovered in 0 until displayRestart, "dependency first: ${events.names()}")
        assertTrue(display.health.isHealthy)
        loop.cancel()
    }

    @Test
    fun aDegradedSubsystemIsReportedButNeverRestarted() = runTest {
        val a = Fake("laya")
        a.health = Health.Degraded("heuristic fallback in use")
        val sup = supervisor(a)
        val events = collect(sup)
        runCurrent()
        repeat(3) { sup.checkNow() }
        runCurrent()
        assertEquals(0, a.restarts)
        assertEquals(1, events.filterIsInstance<RecoveryEvent.Degraded>().size)
    }

    @Test
    fun aProbeThatThrowsOrHangsCountsAsAFailure() = runTest {
        val thrower = Fake("a").apply { probeThrows = true }
        val hanger = Fake("b").apply { probeDelayMs = 60_000 }
        thrower.restartResults = ArrayDeque(listOf(RestartResult.Failed("x")))
        hanger.restartResults = ArrayDeque(listOf(RestartResult.Failed("y")))
        val sup = supervisor(thrower, hanger)
        val events = collect(sup)
        runCurrent()
        sup.checkNow()
        runCurrent()
        val failures = events.filterIsInstance<RecoveryEvent.ProbeFailed>()
        assertTrue(failures.any { "binder transaction failed" in it.reason })
        assertTrue(failures.any { "timed out" in it.reason })
        assertEquals(1, thrower.restarts)
        assertEquals(1, hanger.restarts)
    }

    @Test
    fun aRestartThatHangsIsTimedOut() = runTest {
        val a = Fake("omp").apply { health = Health.Failed("down"); restartDelayMs = 60_000 }
        val sup = supervisor(a)
        val events = collect(sup)
        runCurrent()
        sup.checkNow()
        runCurrent()
        assertTrue(events.filterIsInstance<RecoveryEvent.RestartFailed>().any { "timed out" in it.reason }, events.names().toString())
        assertEquals(5_000, currentTime)
    }

    @Test
    fun aRestartThatLeavesItUnhealthyIsAFailureNotARecovery() = runTest {
        val a = Fake("omp").apply { health = Health.Failed("down") } // restart "succeeds" but nothing changes
        val sup = supervisor(a)
        val events = collect(sup)
        runCurrent()
        sup.checkNow()
        runCurrent()
        val failed = events.filterIsInstance<RecoveryEvent.RestartFailed>().single()
        assertTrue("still unhealthy" in failed.reason, failed.reason)
        assertTrue(events.none { it is RecoveryEvent.Recovered })
    }

    @Test
    fun aSubsystemThatHealsOnItsOwnClearsItsFailureHistory() = runTest {
        val a = Fake("net").apply { health = Health.Failed("offline") }
        a.restartResults = ArrayDeque(List(3) { RestartResult.Failed("no route") })
        val sup = supervisor(a)
        val events = collect(sup)
        val loop = sup.start(backgroundScope)
        runCurrent()
        advanceTimeBy(1_500)
        a.health = Health.Healthy
        advanceTimeBy(5_000)
        assertEquals(0, sup.state("net").consecutiveFailures)
        assertEquals(CircuitState.CLOSED, sup.state("net").circuit)
        assertTrue(events.any { it is RecoveryEvent.Recovered })
        loop.cancel()
    }

    @Test
    fun theSupervisorRejectsUnknownDependencies() {
        val a = Fake("a", dependsOn = setOf("ghost"))
        assertFailsWith<IllegalArgumentException> { HealthSupervisor(listOf(a)) }
    }

    @Test
    fun subsystemsAreSupervisedIndependently() = runTest {
        val slow = Fake("slow").apply { health = Health.Failed("down"); restartDelayMs = 4_000 }
        val fast = Fake("fast").apply { health = Health.Failed("down") }
        fast.onRestart = { it.health = Health.Healthy }
        slow.onRestart = { it.health = Health.Healthy }
        val sup = supervisor(slow, fast)
        val loop = sup.start(backgroundScope)
        runCurrent()
        advanceTimeBy(100)
        assertTrue(fast.health.isHealthy, "a slow restart must not delay the other subsystem")
        advanceTimeBy(5_000)
        assertTrue(slow.health.isHealthy)
        loop.cancel()
    }

    @Test
    fun resetForgetsFailuresButKeepsTheRestartCount() = runTest {
        val a = Fake("omp").apply { health = Health.Failed("down") }
        a.restartResults = ArrayDeque(List(2) { RestartResult.Failed("x") })
        val sup = supervisor(a)
        runCurrent()
        sup.checkNow()
        assertEquals(1, sup.state("omp").consecutiveFailures)
        sup.reset("omp")
        val s = sup.state("omp")
        assertEquals(0, s.consecutiveFailures)
        assertEquals(CircuitState.CLOSED, s.circuit)
        assertEquals(1, s.totalRestarts)
        assertIs<Health.Healthy>(s.health)
    }

    @Test
    fun aGateHoldsRecoveryBackAndLearnsHowAttemptsWent() = runTest {
        val sub = Fake("linux").apply { health = Health.Failed("not mounted") }
        sub.now = { currentTime }
        val outcomes = mutableListOf<Pair<Long, Boolean>>()
        val observed = mutableListOf<Boolean>()
        var refusal: String? = "no consumer needs it"
        val gate = object : RecoveryGate {
            var epoch = 0L
            override fun admit(subsystem: String, owner: String): RecoveryPermit =
                refusal?.let { RecoveryPermit.Refused(it) } ?: RecoveryPermit.Granted(subsystem, ++epoch)
            override fun finished(permit: RecoveryPermit.Granted, ok: Boolean, reason: String?) { outcomes += permit.epoch to ok }
            override fun observed(subsystem: String, healthy: Boolean) { observed += healthy }
        }
        val sup = HealthSupervisor(listOf(sub), policy, Clock { currentTime }, Random(1), gate)
        val events = mutableListOf<RecoveryEvent>()
        val collector = launch { sup.events.collect { events += it } }
        runCurrent()
        sup.checkNow()
        sup.checkNow()
        runCurrent()
        assertEquals(0, sub.restarts, "held back: no restart")
        assertEquals("no consumer needs it", sup.state("linux").heldBy)
        assertEquals(1, events.count { it is RecoveryEvent.Held }, "the reason is said once, not on every probe")

        refusal = null
        sub.onRestart = { it.health = Health.Healthy }
        sup.checkNow()
        assertEquals(1, sub.restarts)
        assertEquals(listOf(1L to true), outcomes)
        assertEquals(null, sup.state("linux").heldBy)
        assertEquals(listOf(false, false, false), observed, "every probe reaches the gate")
        collector.cancel()
    }
}
