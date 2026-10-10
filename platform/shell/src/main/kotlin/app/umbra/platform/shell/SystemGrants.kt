package app.umbra.platform.shell

import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellInput.quote
import app.umbra.platform.api.exec

/** One thing the app needs from the system, whether it is in place, and how to put it in place. */
data class GrantStatus(
    val id: String,
    val title: String,
    val granted: Boolean,
    /** What was observed, for the capability screen and bug reports. */
    val detail: String = "",
)

/**
 * Turns on, with shell or root access, the permissions that would otherwise send the user hunting
 * through system settings (spec §23 onboarding): the accessibility service, notification access, the
 * "restricted settings" gate for sideloaded apps, overlay and notification permissions, battery
 * exemption and the phantom-process killer that would kill the Linux runtime's children. Every check
 * reads real system state, and every grant is verified by reading it back.
 */
class SystemGrants(
    /** A shell with at least shell-user rights (or root). */
    private val shell: ShellBackend,
    private val packageName: String,
    private val accessibilityService: String,
    private val notificationListener: String,
) {
    private suspend fun run(command: String): String = shell.exec(command, timeoutMs = 20_000).let { if (it.ok) it.stdout.trim() else "" }

    private val accessibilityComponent get() = "$packageName/$accessibilityService"
    private val listenerComponent get() = "$packageName/$notificationListener"

    // ---- accessibility ----

    suspend fun accessibilityEnabled(): Boolean {
        val services = run("settings get secure enabled_accessibility_services")
        val on = run("settings get secure accessibility_enabled") == "1"
        return on && services.split(':').any { it.trim().equals(accessibilityComponent, ignoreCase = true) || it.trim().equals(shortComponent(accessibilityComponent), ignoreCase = true) }
    }

    /** Adds the service to the enabled list (keeping other services) and switches accessibility on. */
    suspend fun enableAccessibility(): Boolean {
        val current = run("settings get secure enabled_accessibility_services").takeIf { it != "null" && it.isNotBlank() }
        val entries = current?.split(':').orEmpty().filter { it.isNotBlank() }
        if (entries.none { it.equals(accessibilityComponent, ignoreCase = true) }) {
            val updated = (entries + accessibilityComponent).joinToString(":")
            shell.exec("settings put secure enabled_accessibility_services ${quote(updated)}")
        }
        shell.exec("settings put secure accessibility_enabled 1")
        return accessibilityEnabled()
    }

    suspend fun disableAccessibility(): Boolean {
        val current = run("settings get secure enabled_accessibility_services").takeIf { it != "null" }
        val remaining = current?.split(':').orEmpty().filter { it.isNotBlank() && !it.equals(accessibilityComponent, ignoreCase = true) }
        if (remaining.isEmpty()) {
            shell.exec("settings delete secure enabled_accessibility_services")
            shell.exec("settings put secure accessibility_enabled 0")
        } else {
            shell.exec("settings put secure enabled_accessibility_services ${quote(remaining.joinToString(":"))}")
        }
        return !accessibilityEnabled()
    }

    // ---- notification access ----

    suspend fun notificationListenerAllowed(): Boolean =
        run("settings get secure enabled_notification_listeners").split(':').any { it.trim().equals(listenerComponent, ignoreCase = true) }

    suspend fun allowNotificationListener(): Boolean {
        shell.exec("cmd notification allow_listener ${quote(listenerComponent)}")
        return notificationListenerAllowed()
    }

    // ---- runtime permissions and app ops ----

    suspend fun permissionGranted(permission: String): Boolean =
        run("dumpsys package ${quote(packageName)} | grep -E ${quote("$permission: granted=")}").contains("granted=true")

    suspend fun grantPermission(permission: String): Boolean {
        shell.exec("pm grant ${quote(packageName)} ${quote(permission)}")
        return permissionGranted(permission)
    }

    suspend fun appOpAllowed(op: String): Boolean = run("appops get ${quote(packageName)} ${quote(op)}").lowercase().let { "allow" in it && "deny" !in it }

    suspend fun allowAppOp(op: String): Boolean {
        shell.exec("appops set ${quote(packageName)} ${quote(op)} allow")
        return appOpAllowed(op)
    }

    // ---- battery and background ----

    suspend fun batteryExempt(): Boolean = run("cmd deviceidle whitelist").lines().any { it.contains(packageName) }

    suspend fun exemptFromBatteryOptimization(): Boolean {
        shell.exec("cmd deviceidle whitelist +${quote(packageName)}")
        return batteryExempt()
    }

    /**
     * Android 12+ kills background child processes beyond a small cap, which would take the Linux
     * runtime's processes with it. Both switches are needed; the device-config value is re-synced by
     * the system unless syncing is disabled.
     */
    suspend fun phantomProcessGuardOff(): Boolean {
        val monitor = run("settings get global settings_enable_monitor_phantom_procs")
        val max = run("device_config get activity_manager max_phantom_processes")
        return monitor == "false" && (max.toLongOrNull() ?: 0) >= 2_147_483_647L
    }

    suspend fun disablePhantomProcessKiller(): Boolean {
        shell.exec("settings put global settings_enable_monitor_phantom_procs false")
        shell.exec("device_config set_sync_disabled_for_tests persistent")
        shell.exec("device_config put activity_manager max_phantom_processes 2147483647")
        return phantomProcessGuardOff()
    }

    // ---- everything ----

    /** Reads every grant without changing anything. */
    suspend fun status(): List<GrantStatus> = listOf(
        GrantStatus("accessibility", "Accessibility service", accessibilityEnabled()),
        GrantStatus("notification_listener", "Notification access", notificationListenerAllowed()),
        GrantStatus("write_secure_settings", "Write secure settings", permissionGranted("android.permission.WRITE_SECURE_SETTINGS")),
        GrantStatus("read_logs", "Read system logs", permissionGranted("android.permission.READ_LOGS")),
        GrantStatus("post_notifications", "Post notifications", permissionGranted("android.permission.POST_NOTIFICATIONS")),
        GrantStatus("overlay", "Display over other apps", appOpAllowed("SYSTEM_ALERT_WINDOW")),
        GrantStatus("restricted_settings", "Restricted settings", appOpAllowed("ACCESS_RESTRICTED_SETTINGS")),
        GrantStatus("battery", "Unrestricted battery", batteryExempt()),
        GrantStatus("phantom_process_guard", "Background process limit lifted", phantomProcessGuardOff()),
    )

    /** Grants everything that can be granted from a shell and returns the state afterwards. */
    suspend fun grantAll(): List<GrantStatus> {
        allowAppOp("ACCESS_RESTRICTED_SETTINGS") // before accessibility: the system refuses sideloaded services otherwise
        grantPermission("android.permission.WRITE_SECURE_SETTINGS")
        grantPermission("android.permission.READ_LOGS")
        grantPermission("android.permission.POST_NOTIFICATIONS")
        allowAppOp("SYSTEM_ALERT_WINDOW")
        enableAccessibility()
        allowNotificationListener()
        exemptFromBatteryOptimization()
        disablePhantomProcessKiller()
        return status()
    }

    private fun shortComponent(component: String): String {
        val pkg = component.substringBefore('/')
        val cls = component.substringAfter('/')
        return if (cls.startsWith("$pkg.")) "$pkg/.${cls.removePrefix("$pkg.")}" else component
    }
}
