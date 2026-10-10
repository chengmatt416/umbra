package app.umbra.core.control

import app.umbra.core.model.Clock
import app.umbra.core.recovery.RecoveryPermit
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SharedServicesTest {
    @TempDir
    lateinit var dir: Path

    private var now = 1_000_000L
    private val clock = Clock { now }
    private val policy = ServicePolicy(restartBudget = 3, windowMs = 600_000, cooldownMs = 60_000, maxCooldownMs = 600_000, jitterRatio = 0.0, stability = 2, ownerLeaseMs = 120_000, firstStage = 1, stageMs = 10_000)

    private fun services() = SharedServices(ControlStore(dir, clock), policy, Random(1)).apply { declare("linux"); declare("tmux.user") }

    private fun granted(p: RecoveryPermit) = assertIs<RecoveryPermit.Granted>(p, "$p")

    @Test
    fun recoveryHappensOnlyOnDemandAndUnderOneOwner() {
        val s = services()
        assertEquals(RecoveryPermit.Refused("no consumer needs it now"), s.admit("linux", "supervisor"))
        s.sync("linux", setOf("session-a", "session-b"))
        assertEquals(2, s.referencesOf("linux").size)

        val first = granted(s.admit("linux", "supervisor"))
        val second = s.admit("linux", "another")
        assertTrue(second is RecoveryPermit.Refused && "supervisor is recovering it" in second.reason, "$second")

        // An owner that never reports back loses the lease; the late outcome of its epoch is ignored.
        now += 121_000
        val takeover = granted(s.admit("linux", "another"))
        assertEquals(first.epoch + 1, takeover.epoch)
        s.finished(first, ok = true, reason = null)
        assertEquals("another", s.record("linux")!!.recoveryOwner, "a stale outcome does not end the current owner's recovery")
        s.finished(takeover, ok = true, reason = null)
        assertNull(s.record("linux")!!.recoveryOwner)
        assertEquals("recovered", s.record("linux")!!.lastOutcome)

        // Undeclared subsystems are not governed.
        assertIs<RecoveryPermit.Granted>(s.admit("accessibility", "supervisor"))
    }

    @Test
    fun theRestartBudgetOpensAPersistedBreakerThatOnlyStabilityOrTheUserCloses() {
        val s = services()
        s.sync("linux", setOf("session-a"))
        repeat(3) {
            s.finished(granted(s.admit("linux", "supervisor")), ok = false, reason = "mount failed")
            now += 1_000
        }
        val refused = s.admit("linux", "supervisor")
        assertTrue(refused is RecoveryPermit.Refused && "restart budget" in refused.reason, "$refused")
        assertEquals(Breaker.OPEN, s.record("linux")!!.breaker)
        assertEquals(now + 60_000, s.record("linux")!!.openUntilMs)
        assertNotNull(s.admitConsumer("linux"), "an open breaker takes no new work")

        // A new instance of the manager (the app restarted, the screen reopened) sees the same breaker.
        val again = services()
        assertEquals(Breaker.OPEN, again.record("linux")!!.breaker)
        assertIs<RecoveryPermit.Refused>(again.admit("linux", "supervisor"))

        // After the cooldown: one trial (half-open); a failed trial reopens with a doubled cooldown.
        now += 61_000
        val trial = granted(again.admit("linux", "supervisor"))
        assertEquals(Breaker.HALF_OPEN, again.record("linux")!!.breaker)
        again.finished(trial, ok = false, reason = "still failing")
        assertEquals(Breaker.OPEN, again.record("linux")!!.breaker)
        assertEquals(now + 120_000, again.record("linux")!!.openUntilMs)

        // A trial that works closes the breaker only after enough healthy probes in a row.
        now += 121_000
        again.finished(granted(again.admit("linux", "supervisor")), ok = true, reason = null)
        again.observed("linux", true)
        assertEquals(Breaker.HALF_OPEN, again.record("linux")!!.breaker)
        again.observed("linux", false)
        again.observed("linux", true)
        assertEquals(Breaker.HALF_OPEN, again.record("linux")!!.breaker, "an unhealthy probe restarts the count")
        again.observed("linux", true)
        assertEquals(Breaker.CLOSED, again.record("linux")!!.breaker)
        assertEquals(0, again.record("linux")!!.openings)
    }

    @Test
    fun aResetIsExplicitAndJournaled() {
        val s = services()
        s.sync("linux", setOf("session-a"))
        repeat(3) { s.finished(granted(s.admit("linux", "supervisor")), ok = false, reason = "x") }
        assertIs<RecoveryPermit.Refused>(s.admit("linux", "supervisor"))
        s.resetBreaker("linux", by = "user", reason = "fixed the mount by hand")
        assertEquals(Breaker.CLOSED, s.record("linux")!!.breaker)
        assertTrue(s.recent.value.any { it.kind == "service.breaker_reset" })
        granted(s.admit("linux", "supervisor"))
    }

    @Test
    fun drainingClosesAdmissionFirstAndReportsWhatStillHoldsIt() {
        val s = services()
        s.sync("tmux.user", setOf("term-1", "term-2"))
        val report = s.drain("tmux.user", "moving terminals to another group")
        assertEquals(listOf("term-1", "term-2"), report.references.map { it.consumer }.sorted())
        assertTrue("draining" in s.admitConsumer("tmux.user").orEmpty())
        s.sync("tmux.user", setOf("term-1", "term-2"))
        assertIs<RecoveryPermit.Refused>(s.admit("tmux.user", "supervisor"), "a draining service is not recovered")
        s.resume("tmux.user")
        assertNull(s.admitConsumer("tmux.user"))
    }

    @Test
    fun afterARecoveryNewWorkIsLetInInStages() {
        val s = services()
        s.sync("linux", setOf("session-a"))
        s.finished(granted(s.admit("linux", "supervisor")), ok = true, reason = null)
        assertTrue(s.reattaching(s.record("linux")!!))
        assertNull(s.admitConsumer("linux", count = false), "a check that does not count lets no one in")
        assertNull(s.admitConsumer("linux"), "stage 0 lets one in")
        assertTrue("in stages" in s.admitConsumer("linux").orEmpty())
        now += 10_000
        assertNull(s.admitConsumer("linux"), "stage 1 lets two in, in all")
        assertTrue("in stages" in s.admitConsumer("linux").orEmpty())
        now += 10_000
        assertNull(s.admitConsumer("linux"))
        assertNull(s.admitConsumer("linux"))
        now += 200_000
        repeat(5) { assertNull(s.admitConsumer("linux")) }
        assertNull(s.record("linux")!!.reattachFromMs, "the ramp ends")
        assertTrue(!s.reattaching(s.record("linux")!!))
    }

    @Test
    fun aNewProcessInstanceIsJournaledButTheLogicalServiceStays() {
        val s = services()
        val h1 = ServiceHealth(HealthLevel.entries.associateWith { LevelProbe(ProbeResult.PASS, "ok") }, now, instance = "pid 100")
        s.recordHealth("tmux.user", h1)
        s.recordHealth("tmux.user", h1.copy(instance = "pid 200"))
        assertEquals("pid 200", s.record("tmux.user")!!.instance)
        assertEquals(2, s.recent.value.count { it.kind == "service.instance" })
        assertTrue(s.health.value.getValue("tmux.user").ready)
        val notReady = ServiceHealth(mapOf(HealthLevel.L1 to LevelProbe(ProbeResult.PASS, "alive"), HealthLevel.L2 to LevelProbe(ProbeResult.FAIL, "no answer")), now)
        assertTrue(!notReady.ready, "alive is not ready")
    }
}
