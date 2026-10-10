package app.umbra.platform.adb

import app.umbra.platform.api.exec
import app.umbra.platform.shell.SystemGrants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The onboarding grants, applied with a real shell to the real installed app and read back from the
 * system. The last check is the one that matters: the accessibility service is actually bound.
 */
class SystemGrantsLiveTest {
    @Test
    fun everyGrantIsAppliedAndTheAccessibilityServiceConnects(): Unit = runBlocking {
        val adb = DeviceTestSupport.requireDevice()
        val apk = DeviceTestSupport.requireDebugApk()
        val shell = adb.shell()
        val install = adb.install(apk)
        assertTrue(install.ok && "Success" in install.stdout, install.stdout + install.stderr)
        // After a reinstall the system puts back the accessibility services that were on before it, a little later
        // (longer on a busy emulator). Let that happen first, or it would undo the "start from nothing" below.
        delay(6_000)

        val grants = SystemGrants(shell, "app.umbra.agent", "app.umbra.platform.android.UmbraAccessibilityService", "app.umbra.platform.android.UmbraNotificationListener")
        // Start from nothing so the test proves the grants, not leftovers.
        grants.disableAccessibility()
        shell.exec("cmd notification disallow_listener app.umbra.agent/app.umbra.platform.android.UmbraNotificationListener")
        delay(1_500)
        assertFalse(grants.accessibilityEnabled(), "nothing may turn the service back on by itself")
        assertFalse(grants.notificationListenerAllowed())

        val status = grants.grantAll()
        val missing = status.filter { !it.granted }
        // POST_NOTIFICATIONS is not requestable below the SDK that introduced it; everything else must be in place.
        assertTrue(missing.all { it.id == "post_notifications" }, "not granted: $missing")
        assertTrue(grants.accessibilityEnabled() && grants.notificationListenerAllowed() && grants.batteryExempt() && grants.phantomProcessGuardOff())

        // The system binds the service once it is enabled: wait for it to show up as running.
        var bound = false
        repeat(40) {
            if (!bound) {
                bound = shell.exec("dumpsys accessibility").stdout.let { "app.umbra.agent" in it && "UmbraAccessibilityService" in it && "Bound services" in it && it.substringAfter("Bound services").take(400).contains("UmbraAccessibilityService") }
                if (!bound) delay(500)
            }
        }
        assertTrue(bound, "the accessibility service was enabled but never bound:\n" + shell.exec("dumpsys accessibility | head -40").stdout)

        // Turning it off removes only ours.
        assertTrue(grants.disableAccessibility())
        assertEquals(false, grants.accessibilityEnabled())
        assertTrue(grants.enableAccessibility())
    }
}
