package app.umbra.tools

import app.umbra.core.model.Clock
import app.umbra.platform.api.BinaryShellResult
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellEvent
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.ShellResult
import app.umbra.platform.api.StaticBackends
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/**
 * When the agent may use the screen. The shell here answers `dumpsys` the way a phone does, from a
 * small state machine, so the rules (wake a sleeping screen, ask a swipe lock to go away and give a slow
 * phone time to do it, never touch a PIN) are checked without a device.
 */
class ScreenGuardTest {
    /** A phone whose lock screen goes away after [dismissesNeeded] requests, or never when it is secure. */
    private class Phone(var awake: Boolean = true, var locked: Boolean = false, val secure: Boolean = false, val dismissesNeeded: Int = 1) : ShellBackend {
        var dismissRequests = 0
        var wakeRequests = 0
        override val name = "phone"
        override val identity = ShellIdentity.SHELL

        override suspend fun isAvailable() = true

        override suspend fun exec(request: ShellRequest): ShellResult {
            val c = request.command
            return when {
                "mWakefulness=" in c -> ShellResult(0, "  mWakefulness=${if (awake) "Awake" else "Asleep"}\n")
                "mDreamingLockscreen=" in c -> ShellResult(0, "    mShowingDream=false mDreamingLockscreen=$locked\n")
                "dumpsys window policy" in c -> ShellResult(0, "      secure=$secure\n")
                c.startsWith("input keyevent KEYCODE_WAKEUP") -> { wakeRequests++; awake = true; ShellResult(0, "") }
                c.startsWith("wm dismiss-keyguard") -> {
                    dismissRequests++
                    if (!secure && dismissRequests >= dismissesNeeded) locked = false
                    ShellResult(0, "")
                }
                else -> ShellResult(0, "")
            }
        }

        override suspend fun execBytes(request: ShellRequest) = BinaryShellResult(0, ByteArray(0))

        override fun stream(request: ShellRequest): Flow<ShellEvent> = emptyFlow()
    }

    private var time = 0L
    private fun guard(phone: Phone): ScreenGuard {
        val backends = StaticBackends(shells = listOf(phone))
        val clock = Clock { time }
        val env = PipelineEnv(backends, clock, null, null, null, Verifier(backends, null, clock), DisplayLeaseManager(clock), PipelineConfig(), ElementTargeting(null))
        return ScreenGuard(env)
    }

    @Test
    fun `an unlocked awake phone is usable without touching it`() = runTest {
        val phone = Phone()
        assertNull(guard(phone).ensureUsable())
        assertEquals(0, phone.wakeRequests + phone.dismissRequests)
    }

    @Test
    fun `a sleeping screen is woken`() = runTest {
        val phone = Phone(awake = false)
        assertNull(guard(phone).ensureUsable())
        assertEquals(1, phone.wakeRequests)
    }

    @Test
    fun `a swipe lock is dismissed even when the phone needs several tries`() = runTest {
        val phone = Phone(locked = true, dismissesNeeded = 3)
        assertNull(guard(phone).ensureUsable())
        assertEquals(3, phone.dismissRequests)
    }

    @Test
    fun `a swipe lock that never goes away is reported as such`() = runTest {
        val phone = Phone(locked = true, dismissesNeeded = Int.MAX_VALUE)
        val problem = assertNotNull(guard(phone).ensureUsable())
        assertTrue("swipe it away" in problem, problem)
        assertEquals(6, phone.dismissRequests)
    }

    @Test
    fun `a PIN is the users and is never tried`() = runTest {
        val phone = Phone(locked = true, secure = true)
        val problem = assertNotNull(guard(phone).ensureUsable())
        assertTrue("PIN, pattern or password" in problem, problem)
        assertEquals(0, phone.dismissRequests, "no attempt to get past a credential")
    }
}
