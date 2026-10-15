package app.umbra.agent.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.platform.api.exec
import app.umbra.agent.data.GuestManager

enum class CapabilityStatus { OK, WARN, MISSING }

/** Android's "restricted settings" hold on accessibility: known to be in force, likely (installed from a file), or not. */
enum class Restriction { HELD, LIKELY, NONE }

/** What the user can do about a capability from the setup screen. */
enum class CapabilityAction(@param:androidx.annotation.StringRes val label: Int) {
    ENABLE_ACCESSIBILITY(R.string.cap_act_enable),
    START_HELPER(R.string.cap_act_start),
    INSTALL_LINUX(R.string.cap_act_install),
    ADD_PROVIDER(R.string.cap_act_add),
    GRANT_PERMISSIONS(R.string.cap_act_grant),
    OPEN_NOTIFICATION_SETTINGS(R.string.cap_act_open_settings),
    OPEN_OVERLAY_SETTINGS(R.string.cap_act_open_settings),
    OPEN_BATTERY_SETTINGS(R.string.cap_act_open_settings),
    OPEN_ACCESSIBILITY_SETTINGS(R.string.cap_act_open_settings),
    OPEN_SHIZUKU(R.string.cap_act_open_shizuku),
    REQUEST_SHIZUKU(R.string.cap_act_allow),
    START_HELPER_WITH_SHIZUKU(R.string.cap_act_start),
}

data class CapabilityRow(
    val id: String,
    val title: String,
    val status: CapabilityStatus,
    val detail: String,
    val essential: Boolean,
    val action: CapabilityAction? = null,
    /** Android 13+ holds accessibility back for sideloaded apps until the user allows "restricted settings". */
    val restricted: Restriction = Restriction.NONE,
    /** Works on the development emulator, not yet tried on a real phone (UX report F-04). */
    val unverified: Boolean = false,
)

/**
 * Looks at the device the way the agent will rely on it, and reports plainly what works, what is
 * missing and what the user can do about it (spec §29). Every row comes from asking the real
 * component, not from remembered state.
 */
class CapabilityProbe(private val container: AppContainer) {
    private val context = container.context
    private fun t(id: Int, vararg args: Any) = context.getString(id, *args)

    /**
     * Whether Android holds back accessibility for this app as a "restricted setting" (Android 13+). An app may not read
     * that op itself, so it is asked with shell-level rights when the helper or root is at hand; without them, an app
     * installed from an APK file is the case Android restricts, so that counts as likely.
     */
    suspend fun restrictedSettings(): Restriction {
        val answer = runCatching { container.shellUser.exec("appops get ${context.packageName} ACCESS_RESTRICTED_SETTINGS", timeoutMs = 5_000) }.getOrNull()
        val out = answer?.stdout.orEmpty()
        if (answer?.exitCode == 0 && "ACCESS_RESTRICTED_SETTINGS" in out) {
            return if (Regex("ACCESS_RESTRICTED_SETTINGS: (deny|ignore)").containsMatchIn(out)) Restriction.HELD else Restriction.NONE
        }
        val source = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).packageSource }.getOrNull()
        return if (source == android.content.pm.PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || source == android.content.pm.PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE) Restriction.LIKELY else Restriction.NONE
    }

    suspend fun probe(): List<CapabilityRow> {
        val rows = ArrayList<CapabilityRow>()

        val serviceRunning = container.accessibility.isAvailable()
        val serviceEnabled = serviceRunning || container.grants.accessibilityEnabled()
        rows += when {
            serviceRunning -> CapabilityRow("accessibility", t(R.string.cap_screen), CapabilityStatus.OK, t(R.string.cap_screen_ok), true)
            serviceEnabled -> CapabilityRow("accessibility", t(R.string.cap_screen), CapabilityStatus.WARN, t(R.string.cap_screen_warn), true, CapabilityAction.OPEN_ACCESSIBILITY_SETTINGS)
            else -> CapabilityRow("accessibility", t(R.string.cap_screen), CapabilityStatus.MISSING, t(R.string.cap_screen_missing), true, CapabilityAction.ENABLE_ACCESSIBILITY, restricted = restrictedSettings())
        }

        val root = container.rootShell.isAvailable()
        rows += if (root) CapabilityRow("root", t(R.string.cap_root), CapabilityStatus.OK, t(R.string.cap_root_ok), false)
        else CapabilityRow("root", t(R.string.cap_root), CapabilityStatus.MISSING, t(R.string.cap_root_missing), false)

        val helperUp = container.helper.client.isUp()
        val shizuku = container.shizuku.state()
        rows += when {
            helperUp -> CapabilityRow("helper", t(R.string.cap_helper), CapabilityStatus.OK, t(R.string.cap_helper_ok), false)
            root -> CapabilityRow("helper", t(R.string.cap_helper), CapabilityStatus.WARN, t(R.string.cap_helper_root), false, CapabilityAction.START_HELPER)
            shizuku == app.umbra.agent.ShizukuLauncher.State.READY -> CapabilityRow("helper", t(R.string.cap_helper), CapabilityStatus.WARN, t(R.string.cap_helper_shizuku), false, CapabilityAction.START_HELPER_WITH_SHIZUKU, unverified = true)
            else -> CapabilityRow("helper", t(R.string.cap_helper), CapabilityStatus.WARN, t(R.string.cap_helper_none), false)
        }

        rows += when (shizuku) {
            app.umbra.agent.ShizukuLauncher.State.NOT_INSTALLED -> CapabilityRow("shizuku", t(R.string.cap_shizuku), CapabilityStatus.WARN, t(R.string.cap_shizuku_absent), false, unverified = true)
            app.umbra.agent.ShizukuLauncher.State.NOT_RUNNING -> CapabilityRow("shizuku", t(R.string.cap_shizuku), CapabilityStatus.WARN, t(R.string.cap_shizuku_stopped), false, CapabilityAction.OPEN_SHIZUKU, unverified = true)
            app.umbra.agent.ShizukuLauncher.State.NEEDS_PERMISSION -> CapabilityRow("shizuku", t(R.string.cap_shizuku), CapabilityStatus.WARN, t(R.string.cap_shizuku_permission), false, CapabilityAction.REQUEST_SHIZUKU, unverified = true)
            app.umbra.agent.ShizukuLauncher.State.READY -> CapabilityRow("shizuku", t(R.string.cap_shizuku), CapabilityStatus.OK, t(if (container.shizuku.uid == 0) R.string.cap_shizuku_root else R.string.cap_shizuku_shell), false, unverified = true)
        }

        rows += if (container.displays.isAvailable()) CapabilityRow("displays", t(R.string.cap_displays), CapabilityStatus.OK, t(R.string.cap_displays_ok), false)
        else CapabilityRow("displays", t(R.string.cap_displays), CapabilityStatus.WARN, t(R.string.cap_displays_missing), false)

        rows += when (val g = container.guest.state.value) {
            GuestManager.State.Ready, GuestManager.State.Installed -> CapabilityRow("linux", t(R.string.cap_linux), CapabilityStatus.OK, t(R.string.cap_linux_ok), true)
            GuestManager.State.NoRoot -> CapabilityRow("linux", t(R.string.cap_linux), CapabilityStatus.MISSING, t(R.string.cap_linux_noroot), true)
            is GuestManager.State.Installing, is GuestManager.State.Downloading -> CapabilityRow("linux", t(R.string.cap_linux), CapabilityStatus.WARN, t(R.string.cap_linux_installing), true)
            is GuestManager.State.Failed -> CapabilityRow("linux", t(R.string.cap_linux), CapabilityStatus.MISSING, g.message, true, CapabilityAction.INSTALL_LINUX)
            else -> CapabilityRow(
                "linux", t(R.string.cap_linux), CapabilityStatus.MISSING,
                t(if (container.guest.bundle != null) R.string.cap_linux_bundled else R.string.cap_linux_missing),
                true, CapabilityAction.INSTALL_LINUX,
            )
        }

        val prefs = container.settings.value
        val model = prefs.defaultModel
        val providerOk = model != null && prefs.providers.any { p -> p.id == model.provider && (p.apiKeyEnv == null || container.secrets.has(p.apiKeyEnv)) }
        rows += if (providerOk) CapabilityRow("model", t(R.string.cap_model), CapabilityStatus.OK, "${model.provider}/${model.modelId}", true)
        else CapabilityRow("model", t(R.string.cap_model), CapabilityStatus.MISSING, t(R.string.cap_model_missing), true, CapabilityAction.ADD_PROVIDER)

        val notificationsOn = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        rows += if (notificationsOn) CapabilityRow("notifications", t(R.string.cap_notifications), CapabilityStatus.OK, t(R.string.cap_notifications_ok), false)
        else CapabilityRow("notifications", t(R.string.cap_notifications), CapabilityStatus.WARN, t(R.string.cap_notifications_off), false, CapabilityAction.OPEN_NOTIFICATION_SETTINGS)

        val listener = container.grants.notificationListenerAllowed()
        rows += if (listener) CapabilityRow("notification-access", t(R.string.cap_listener), CapabilityStatus.OK, t(R.string.cap_listener_ok), false)
        else CapabilityRow("notification-access", t(R.string.cap_listener), CapabilityStatus.WARN, t(R.string.cap_listener_missing), false, CapabilityAction.GRANT_PERMISSIONS)

        val battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        rows += if (battery) CapabilityRow("battery", t(R.string.cap_battery), CapabilityStatus.OK, t(R.string.cap_battery_ok), false)
        else CapabilityRow("battery", t(R.string.cap_battery), CapabilityStatus.WARN, t(R.string.cap_battery_missing), false, CapabilityAction.GRANT_PERMISSIONS)

        val overlay = Settings.canDrawOverlays(context)
        rows += if (overlay) CapabilityRow("overlay", t(R.string.cap_overlay), CapabilityStatus.OK, t(R.string.cap_overlay_ok), false)
        else CapabilityRow("overlay", t(R.string.cap_overlay), CapabilityStatus.WARN, t(R.string.cap_overlay_missing), false, CapabilityAction.OPEN_OVERLAY_SETTINGS)

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
