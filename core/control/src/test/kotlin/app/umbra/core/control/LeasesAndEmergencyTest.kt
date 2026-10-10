package app.umbra.core.control

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.io.TempDir

class LeasesAndEmergencyTest {
    @TempDir
    lateinit var dir: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun anAgentNeverTakesInputFromTheUserAndTakeBackFencesTheOldGeneration() {
        val leases = InputLeases(ControlStore(dir))
        val agent = Principal.agent("s1")
        val first = leases.acquire("terminal:t1", agent).getOrThrow()
        assertIs<LeaseCheck.Allowed>(leases.check("terminal:t1", agent, first.generation))

        val taken = leases.takeBack("terminal:t1")
        assertTrue(taken.generation > first.generation)
        assertIs<LeaseCheck.Fenced>(leases.check("terminal:t1", agent), "the agent is fenced the moment the user takes over")
        assertTrue(leases.acquire("terminal:t1", agent).isFailure, "and cannot take input back from the user")

        leases.grant("terminal:t1", agent)
        assertIs<LeaseCheck.Fenced>(leases.check("terminal:t1", agent, first.generation), "an old generation stays fenced")
        assertIs<LeaseCheck.Allowed>(leases.check("terminal:t1", agent))
        assertEquals(listOf("acquire", "takeback", "blocked", "restore"), ControlStore(dir).journal("control-timeline").readAll().map { it.kind })
    }

    @Test
    fun aLockdownFencesEveryLeaseAndSurvivesARestartUntilLifted() {
        val store = ControlStore(dir)
        val emergency = EmergencyControl(store, scope)
        val leases = InputLeases(store) { emergency.lockdownReason() }
        leases.acquire("display:0", Principal.agent("s1"))
        emergency.lockdown("test lockdown")
        assertIs<LeaseCheck.Fenced>(leases.check("display:0", Principal.agent("s1")))

        // AC-006: a restart (a new process reading the same store) keeps the lockdown.
        val again = EmergencyControl(ControlStore(dir), scope)
        assertEquals(EmergencyLevel.LOCKDOWN, again.current().level)
        assertEquals("test lockdown", again.lockdownReason())

        again.lift()
        assertNull(EmergencyControl(ControlStore(dir), scope).lockdownReason())
        assertTrue(again.current().generation >= 2, "lockdown and lift each move the fencing generation")
    }

    @Test
    fun anUncertainLeaseIsNotTrustedUntilReacquired() {
        val leases = InputLeases(ControlStore(dir))
        val user = Principal.User
        leases.acquire("terminal:t2", user)
        leases.markUncertain("terminal:t2", "server restarted")
        assertIs<LeaseCheck.Fenced>(leases.check("terminal:t2", user))
        leases.takeBack("terminal:t2")
        assertIs<LeaseCheck.Allowed>(leases.check("terminal:t2", user))
    }
}
