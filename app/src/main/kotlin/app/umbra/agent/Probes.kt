package app.umbra.agent

import android.app.NotificationManager
import android.os.PowerManager
import android.provider.Settings
import app.umbra.agent.data.GuestManager
import app.umbra.agent.data.LayaSidecar
import app.umbra.core.model.capability.Capability
import app.umbra.core.model.capability.CapabilityDetector
import app.umbra.core.model.capability.CapabilityId
import app.umbra.core.model.capability.CapabilityProbe
import app.umbra.core.model.capability.CapabilityState
import app.umbra.platform.api.exec
import java.io.File

/**
 * One real check per capability the product depends on (spec §29): each asks the component itself
 * (runs the command, opens the connection, reads the setting) rather than trusting remembered state.
 * Together they feed the strategy the agent runs with and the capability screen.
 */
internal fun capabilityDetector(container: AppContainer): CapabilityDetector {
    val context = container.context

    fun probe(id: CapabilityId, block: suspend () -> Capability) = object : CapabilityProbe {
        override val id = id
        override suspend fun probe(): Capability = block()
    }

    fun ok(id: CapabilityId, detail: String = "", vararg evidence: Pair<String, String>) = Capability(id, CapabilityState.AVAILABLE, detail, evidence.toMap())
    fun no(id: CapabilityId, detail: String, action: String? = null) = Capability(id, CapabilityState.UNAVAILABLE, detail, userAction = action)
    fun degraded(id: CapabilityId, detail: String, action: String? = null) = Capability(id, CapabilityState.DEGRADED, detail, userAction = action)

    return CapabilityDetector(
        listOf(
            probe(CapabilityId.ACCESSIBILITY_SERVICE) {
                when {
                    container.accessibility.isAvailable() -> ok(CapabilityId.ACCESSIBILITY_SERVICE, "The service is connected.")
                    container.grants.accessibilityEnabled() -> degraded(CapabilityId.ACCESSIBILITY_SERVICE, "Enabled but not connected.", "Turn the service off and on in Accessibility settings.")
                    else -> no(CapabilityId.ACCESSIBILITY_SERVICE, "Not enabled.", "Enable the Umbra accessibility service.")
                }
            },
            probe(CapabilityId.ROOT_AVAILABLE) {
                if (container.rootShell.isAvailable()) ok(CapabilityId.ROOT_AVAILABLE, "su answers with uid 0.") else no(CapabilityId.ROOT_AVAILABLE, "No root on this phone.")
            },
            probe(CapabilityId.ROOT_PERMISSION) {
                if (container.rootShell.isAvailable()) ok(CapabilityId.ROOT_PERMISSION, "Root is granted to Umbra.")
                else no(CapabilityId.ROOT_PERMISSION, "Root is not granted.", "Grant root to Umbra in your root manager.")
            },
            probe(CapabilityId.SHIZUKU_AVAILABLE) {
                when (container.shizuku.state()) {
                    ShizukuLauncher.State.NOT_INSTALLED -> no(CapabilityId.SHIZUKU_AVAILABLE, "Shizuku is not installed.")
                    ShizukuLauncher.State.NOT_RUNNING -> no(CapabilityId.SHIZUKU_AVAILABLE, "Shizuku is installed but not running.", "Open Shizuku and start it.")
                    else -> ok(CapabilityId.SHIZUKU_AVAILABLE, "Shizuku is running as uid ${container.shizuku.uid}.", "uid" to container.shizuku.uid.toString())
                }
            },
            probe(CapabilityId.SHIZUKU_PERMISSION) {
                when (container.shizuku.state()) {
                    ShizukuLauncher.State.READY -> ok(CapabilityId.SHIZUKU_PERMISSION, if (container.shizuku.uid == 0) "Shizuku runs as root." else "Shell-level access through Shizuku (not root).")
                    ShizukuLauncher.State.NEEDS_PERMISSION -> no(CapabilityId.SHIZUKU_PERMISSION, "Umbra is not allowed to use Shizuku yet.", "Allow Umbra in Shizuku.")
                    else -> no(CapabilityId.SHIZUKU_PERMISSION, "Shizuku is not available.")
                }
            },
            probe(CapabilityId.SHELL_HELPER) {
                if (container.helper.client.isUp()) ok(CapabilityId.SHELL_HELPER, "The privileged helper runs as the shell user.")
                else no(CapabilityId.SHELL_HELPER, "The helper is not running.", "Start it from Settings, or over adb once per boot.")
            },
            probe(CapabilityId.VIRTUAL_DISPLAY) {
                if (container.displays.isAvailable()) ok(CapabilityId.VIRTUAL_DISPLAY, "Background displays can be created.")
                else no(CapabilityId.VIRTUAL_DISPLAY, "Needs the privileged helper.", "Start the privileged helper.")
            },
            probe(CapabilityId.LINUX_RUNTIME) {
                when (container.guest.state.value) {
                    GuestManager.State.Ready, GuestManager.State.Installed -> ok(
                        CapabilityId.LINUX_RUNTIME, "The Debian environment is installed.",
                        "provider" to if (container.guest.mode == app.umbra.runtime.linux.LinuxMode.PROOT) "proot" else "root_chroot",
                    )
                    GuestManager.State.NoRoot -> no(CapabilityId.LINUX_RUNTIME, "Neither root nor proot can run it.")
                    else -> no(CapabilityId.LINUX_RUNTIME, "Not installed.", "Install the Linux environment from Set up.")
                }
            },
            probe(CapabilityId.DEBIAN_RUNTIME) {
                val versions = if (container.guest.state.value == GuestManager.State.Ready) container.guest.versions() else null
                if (versions != null) ok(CapabilityId.DEBIAN_RUNTIME, "Debian ${versions["debian"]}", *versions.toList().toTypedArray())
                else no(CapabilityId.DEBIAN_RUNTIME, "The environment is not running.")
            },
            probe(CapabilityId.OMP_RUNTIME) {
                val omp = if (container.guest.state.value == GuestManager.State.Ready) container.guest.versions()?.get("omp") else null
                if (!omp.isNullOrBlank()) ok(CapabilityId.OMP_RUNTIME, "omp $omp", "version" to omp) else no(CapabilityId.OMP_RUNTIME, "omp is not available in the Linux environment.")
            },
            probe(CapabilityId.X11) {
                if (container.guest.desktopInstalled) ok(CapabilityId.X11, "A virtual desktop (Xvfb) is installed.", "provider" to "xvfb")
                else no(CapabilityId.X11, "The desktop add-on is not installed.", "Install Desktop apps in Set up.")
            },
            probe(CapabilityId.LAYA_RUNTIME) {
                if (container.laya.state.value == LayaSidecar.State.RUNNING) ok(CapabilityId.LAYA_RUNTIME, "The Laya sidecar answers.") else no(CapabilityId.LAYA_RUNTIME, "Laya is not running.")
            },
            probe(CapabilityId.MODEL_PROVIDERS) {
                val prefs = container.settings.value
                val configured = prefs.providers.filter { p -> p.apiKeyEnv == null || container.secrets.has(p.apiKeyEnv) }
                val model = prefs.defaultModel
                if (configured.isNotEmpty() && model != null) ok(CapabilityId.MODEL_PROVIDERS, configured.joinToString { it.label }, "default" to "${model.provider}/${model.modelId}")
                else no(CapabilityId.MODEL_PROVIDERS, "No provider with a key and a chosen model.", "Add a provider in Models.")
            },
            probe(CapabilityId.EXEC_FROM_APP_DATA) {
                // Targeting Android 16, SELinux refuses exec() of files in app data (W^X); programs there still run when
                // the system linker maps them, which is how proot's loader starts every program of the Linux
                // environment. Try that for real (root mode does not depend on it).
                val copy = File(context.filesDir, "exec-probe")
                try {
                    File("/system/bin/true").copyTo(copy, overwrite = true)
                    copy.setExecutable(true)
                    val direct = runCatching { ProcessBuilder(copy.path).start().waitFor() }.getOrNull()
                    val linked = ProcessBuilder("/system/bin/linker64", copy.path).start().waitFor()
                    when {
                        linked == 0 && direct == 0 -> ok(CapabilityId.EXEC_FROM_APP_DATA, "Programs in app data run, directly and through the linker.")
                        linked == 0 -> ok(CapabilityId.EXEC_FROM_APP_DATA, "Programs in app data run through the linker (proot's way); direct exec is refused, as Android 16 requires.")
                        else -> no(CapabilityId.EXEC_FROM_APP_DATA, "Programs in app data do not run (linker exit $linked).")
                    }
                } catch (e: Exception) {
                    no(CapabilityId.EXEC_FROM_APP_DATA, "Blocked: ${e.message}")
                } finally {
                    copy.delete()
                }
            },
            probe(CapabilityId.PHANTOM_PROCESS_GUARD) {
                if (container.grants.phantomProcessGuardOff()) ok(CapabilityId.PHANTOM_PROCESS_GUARD, "The phantom-process killer is off.")
                else degraded(CapabilityId.PHANTOM_PROCESS_GUARD, "Android may kill child processes of long tasks.", "Apply the system grants in Set up.")
            },
            probe(CapabilityId.OVERLAY_PERMISSION) {
                if (Settings.canDrawOverlays(context)) ok(CapabilityId.OVERLAY_PERMISSION) else no(CapabilityId.OVERLAY_PERMISSION, "Not allowed.", "Allow drawing over other apps.")
            },
            probe(CapabilityId.NOTIFICATION_PERMISSION) {
                if (context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) ok(CapabilityId.NOTIFICATION_PERMISSION) else no(CapabilityId.NOTIFICATION_PERMISSION, "Notifications are off.", "Turn notifications on for Umbra.")
            },
            probe(CapabilityId.BATTERY_UNRESTRICTED) {
                if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) ok(CapabilityId.BATTERY_UNRESTRICTED)
                else degraded(CapabilityId.BATTERY_UNRESTRICTED, "Battery optimisation may stop long tasks.", "Exempt Umbra from battery optimisation.")
            },
            probe(CapabilityId.ADVANCED_PROTECTION) {
                // Informational: when Advanced Protection is on, Android 16 blocks accessibility services that are not accessibility tools.
                val on = container.shellUser.exec("settings get secure advanced_protection_mode").stdout.trim() == "1"
                if (on) ok(CapabilityId.ADVANCED_PROTECTION, "Advanced Protection is on.") else no(CapabilityId.ADVANCED_PROTECTION, "Off.")
            },
        ),
    )
}
