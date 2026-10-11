package app.umbra.agent.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import app.umbra.agent.AppContainer
import app.umbra.agent.data.GuestManager

enum class CapabilityStatus { OK, WARN, MISSING }

/** What the user can do about a capability from the setup screen. */
enum class CapabilityAction(val label: String) {
    ENABLE_ACCESSIBILITY("Enable"),
    START_HELPER("Start"),
    INSTALL_LINUX("Install"),
    ADD_PROVIDER("Add"),
    GRANT_PERMISSIONS("Grant"),
    OPEN_NOTIFICATION_SETTINGS("Open settings"),
    OPEN_OVERLAY_SETTINGS("Open settings"),
    OPEN_BATTERY_SETTINGS("Open settings"),
    OPEN_ACCESSIBILITY_SETTINGS("Open settings"),
    OPEN_SHIZUKU("Open Shizuku"),
    REQUEST_SHIZUKU("Allow"),
    START_HELPER_WITH_SHIZUKU("Start"),
}

data class CapabilityRow(
    val id: String,
    val title: String,
    val status: CapabilityStatus,
    val detail: String,
    val essential: Boolean,
    val action: CapabilityAction? = null,
)

/**
 * Looks at the device the way the agent will rely on it, and reports plainly what works, what is
 * missing and what the user can do about it (spec §29). Every row comes from asking the real
 * component, not from remembered state.
 */
class CapabilityProbe(private val container: AppContainer) {
    private val context = container.context

    suspend fun probe(): List<CapabilityRow> {
        val rows = ArrayList<CapabilityRow>()

        val serviceRunning = container.accessibility.isAvailable()
        val serviceEnabled = serviceRunning || container.grants.accessibilityEnabled()
        rows += when {
            serviceRunning -> CapabilityRow("accessibility", "Screen control", CapabilityStatus.OK, "The accessibility service is running.", true)
            serviceEnabled -> CapabilityRow("accessibility", "Screen control", CapabilityStatus.WARN, "Enabled but not connected. Turn it off and on again in Accessibility settings.", true, CapabilityAction.OPEN_ACCESSIBILITY_SETTINGS)
            else -> CapabilityRow("accessibility", "Screen control", CapabilityStatus.MISSING, "Needed to see and operate apps. Enable the Umbra accessibility service. If its switch is greyed out: Umbra's App info, ⋮ menu, Allow restricted settings.", true, CapabilityAction.ENABLE_ACCESSIBILITY)
        }

        val root = container.rootShell.isAvailable()
        rows += if (root) CapabilityRow("root", "Root", CapabilityStatus.OK, "Root access works.", false)
        else CapabilityRow("root", "Root", CapabilityStatus.MISSING, "No root access. Linux runs without it, under proot (slower, and not kept apart from the app as with root); the Android tools still work.", false)

        val helperUp = container.helper.client.isUp()
        val shizuku = container.shizuku.state()
        rows += when {
            helperUp -> CapabilityRow("helper", "Privileged helper", CapabilityStatus.OK, "Running as the shell user. Enables background displays and system settings.", false)
            root -> CapabilityRow("helper", "Privileged helper", CapabilityStatus.WARN, "Not running. Start it to use background displays and shell-level tools.", false, CapabilityAction.START_HELPER)
            shizuku == app.umbra.agent.ShizukuLauncher.State.READY -> CapabilityRow("helper", "Privileged helper", CapabilityStatus.WARN, "Not running. Shizuku can start it.", false, CapabilityAction.START_HELPER_WITH_SHIZUKU)
            else -> CapabilityRow("helper", "Privileged helper", CapabilityStatus.WARN, "Not running. Start it with Shizuku, or over adb (see Settings).", false)
        }

        rows += when (shizuku) {
            app.umbra.agent.ShizukuLauncher.State.NOT_INSTALLED -> CapabilityRow("shizuku", "Shizuku", CapabilityStatus.WARN, "Optional: gives shell-level tools without root. Not installed.", false)
            app.umbra.agent.ShizukuLauncher.State.NOT_RUNNING -> CapabilityRow("shizuku", "Shizuku", CapabilityStatus.WARN, "Installed but not running. Open Shizuku and start it.", false, CapabilityAction.OPEN_SHIZUKU)
            app.umbra.agent.ShizukuLauncher.State.NEEDS_PERMISSION -> CapabilityRow("shizuku", "Shizuku", CapabilityStatus.WARN, "Running. Allow Umbra to use it.", false, CapabilityAction.REQUEST_SHIZUKU)
            app.umbra.agent.ShizukuLauncher.State.READY -> CapabilityRow("shizuku", "Shizuku", CapabilityStatus.OK, if (container.shizuku.uid == 0) "Ready, running as root." else "Ready: shell-level access (not root).", false)
        }

        rows += if (container.displays.isAvailable()) CapabilityRow("displays", "Background displays", CapabilityStatus.OK, "The agent can work in an invisible display while you use the phone.", false)
        else CapabilityRow("displays", "Background displays", CapabilityStatus.WARN, "Needs the privileged helper. Without it the agent works on the main screen.", false)

        rows += when (val g = container.guest.state.value) {
            GuestManager.State.Ready, GuestManager.State.Installed -> CapabilityRow(
                "linux", "Linux environment", CapabilityStatus.OK,
                "Debian is installed. The agent can use bash, git and developer tools." + if (container.guest.mode == app.umbra.runtime.linux.LinuxMode.PROOT) " Runs without root (proot)." else "",
                true,
            )
            GuestManager.State.NoRoot -> CapabilityRow("linux", "Linux environment", CapabilityStatus.MISSING, "Neither root nor proot can run it on this phone.", true)
            is GuestManager.State.Installing, is GuestManager.State.Downloading -> CapabilityRow("linux", "Linux environment", CapabilityStatus.WARN, "Installing…", true)
            is GuestManager.State.Failed -> CapabilityRow("linux", "Linux environment", CapabilityStatus.MISSING, g.message, true, CapabilityAction.INSTALL_LINUX)
            else -> CapabilityRow(
                "linux", "Linux environment", CapabilityStatus.MISSING,
                if (container.guest.bundle != null) "Not installed yet. It comes with the app: install it below, no download needed." else "Not installed. The agent runs inside it.",
                true, CapabilityAction.INSTALL_LINUX,
            )
        }

        val prefs = container.settings.value
        val model = prefs.defaultModel
        val providerOk = model != null && prefs.providers.any { p -> p.id == model.provider && (p.apiKeyEnv == null || container.secrets.has(p.apiKeyEnv)) }
        rows += if (providerOk) CapabilityRow("model", "AI model", CapabilityStatus.OK, "${model.provider}/${model.modelId}", true)
        else CapabilityRow("model", "AI model", CapabilityStatus.MISSING, "Add a provider and choose a model.", true, CapabilityAction.ADD_PROVIDER)

        val notificationsOn = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        rows += if (notificationsOn) CapabilityRow("notifications", "Notifications", CapabilityStatus.OK, "Approvals can reach you outside the app.", false)
        else CapabilityRow("notifications", "Notifications", CapabilityStatus.WARN, "Turned off: approval requests would only show inside the app.", false, CapabilityAction.OPEN_NOTIFICATION_SETTINGS)

        val listener = container.grants.notificationListenerAllowed()
        rows += if (listener) CapabilityRow("notification-access", "Notification access", CapabilityStatus.OK, "The agent can read and answer notifications.", false)
        else CapabilityRow("notification-access", "Notification access", CapabilityStatus.WARN, "Lets the agent read notifications (e.g. codes, messages) when a task needs it.", false, CapabilityAction.GRANT_PERMISSIONS)

        val battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        rows += if (battery) CapabilityRow("battery", "Background running", CapabilityStatus.OK, "Exempt from battery optimisation.", false)
        else CapabilityRow("battery", "Background running", CapabilityStatus.WARN, "Android may stop long tasks. Exempt Umbra from battery optimisation.", false, CapabilityAction.GRANT_PERMISSIONS)

        val overlay = Settings.canDrawOverlays(context)
        rows += if (overlay) CapabilityRow("overlay", "Floating controls", CapabilityStatus.OK, "The overlay can show progress and approvals over other apps.", false)
        else CapabilityRow("overlay", "Floating controls", CapabilityStatus.WARN, "Optional: draw over other apps to show a stop button and approvals.", false, CapabilityAction.OPEN_OVERLAY_SETTINGS)

        return rows
    }

    /** Applies [action]; returns an intent to start when the user has to finish it in system settings. */
    suspend fun perform(action: CapabilityAction): Intent? = when (action) {
        // Root, the helper or a granted WRITE_SECURE_SETTINGS can switch the service on; otherwise the user does it in Settings.
        CapabilityAction.ENABLE_ACCESSIBILITY -> if (container.grants.enableAccessibility()) null else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        CapabilityAction.OPEN_ACCESSIBILITY_SETTINGS -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        CapabilityAction.START_HELPER -> {
            container.helper.startWithRoot(container.rootShell)
            null
        }
        CapabilityAction.OPEN_SHIZUKU -> context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        CapabilityAction.REQUEST_SHIZUKU -> {
            container.shizuku.requestPermission()
            null
        }
        CapabilityAction.START_HELPER_WITH_SHIZUKU -> {
            container.shizuku.startHelper()
            null
        }
        CapabilityAction.GRANT_PERMISSIONS -> {
            container.grants.grantAll()
            null
        }
        CapabilityAction.OPEN_NOTIFICATION_SETTINGS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        CapabilityAction.OPEN_OVERLAY_SETTINGS -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        CapabilityAction.OPEN_BATTERY_SETTINGS -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        CapabilityAction.INSTALL_LINUX, CapabilityAction.ADD_PROVIDER -> null // handled by the screen (navigation / pickers)
    }
}

/** True when everything the agent cannot work without is in place. */
fun List<CapabilityRow>.essentialsReady() = filter { it.essential }.all { it.status == CapabilityStatus.OK }
