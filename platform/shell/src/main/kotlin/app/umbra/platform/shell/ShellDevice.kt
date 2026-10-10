package app.umbra.platform.shell

import app.umbra.core.model.Clock
import app.umbra.platform.api.ClipboardBackend
import app.umbra.platform.api.DisplayBackend
import app.umbra.platform.api.LinuxGuiBackend
import app.umbra.platform.api.NotificationBackend
import app.umbra.platform.api.ScreenshotBackend
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.StaticBackends
import app.umbra.platform.api.UiBackend

/** Wires the shell-based backends into a [StaticBackends] for one shell identity (and optionally root). */
object ShellDevice {
    /**
     * @param shell the least-privileged shell that can run `am`, `pm` and `dumpsys` (shell user, or root)
     * @param root a root shell for privileged file access and `priv_exec`, if the device has one
     * @param app the app's own unprivileged shell, the last resort for `android_shell`
     * @param ui extra, preferred UI backends (the accessibility service); the uiautomator backend is always the last resort
     */
    fun backends(
        shell: ShellBackend,
        root: ShellBackend? = null,
        app: ShellBackend? = null,
        ui: List<UiBackend> = emptyList(),
        displays: DisplayBackend = OverlayDisplays(shell),
        screenshots: List<ScreenshotBackend> = emptyList(),
        notifications: NotificationBackend? = null,
        clipboard: ClipboardBackend? = null,
        linuxGui: LinuxGuiBackend? = null,
        clock: Clock = Clock.System,
    ): StaticBackends {
        val apps = ShellApps(shell)
        return StaticBackends(
            androidUi = ui + UiAutomatorUi(shell, clock),
            shells = listOfNotNull(shell, root, app),
            androidScreenshots = screenshots + ShellScreenshots(shell, displays as? OverlayDisplays ?: OverlayDisplays(shell)),
            apps = apps,
            packages = ShellPackages(shell, apps),
            sysinfo = ShellSysInfo(shell),
            processes = ShellProcesses(shell),
            files = ShellFiles(shell, root),
            displays = displays,
            logs = ShellLogs(shell, clock),
            notifications = notifications,
            clipboard = clipboard,
            linuxGui = linuxGui,
        )
    }
}
