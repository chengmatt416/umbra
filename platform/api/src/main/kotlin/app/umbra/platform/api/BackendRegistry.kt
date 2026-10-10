package app.umbra.platform.api

import app.umbra.core.model.action.Environment

/**
 * What the pipeline can reach right now. The Android app implements this dynamically from the
 * detected capabilities (a backend appears when Shizuku connects and disappears when it dies);
 * tests use [StaticBackends].
 *
 * Lookups are cheap and do not check availability: callers use [Backend.isAvailable] so they can
 * report *why* nothing worked.
 */
interface BackendRegistry {
    /** UI backends for [environment], most preferred first. */
    fun ui(environment: Environment): List<UiBackend>

    /** The shell for exactly this identity, or null when the device cannot provide it. */
    fun shell(identity: ShellIdentity): ShellBackend?

    fun screenshots(environment: Environment): List<ScreenshotBackend>

    val apps: AppBackend?
    val packages: PackageBackend?
    val sysinfo: SysInfoBackend?
    val processes: ProcessBackend?
    val files: FileBackend?
    val displays: DisplayBackend?
    val logs: LogBackend?
    val notifications: NotificationBackend?
    val clipboard: ClipboardBackend?
    val linuxGui: LinuxGuiBackend?
}

/** Fixed set of backends; the simplest [BackendRegistry]. */
class StaticBackends(
    private val androidUi: List<UiBackend> = emptyList(),
    private val shells: List<ShellBackend> = emptyList(),
    private val androidScreenshots: List<ScreenshotBackend> = emptyList(),
    override val apps: AppBackend? = null,
    override val packages: PackageBackend? = null,
    override val sysinfo: SysInfoBackend? = null,
    override val processes: ProcessBackend? = null,
    override val files: FileBackend? = null,
    override val displays: DisplayBackend? = null,
    override val logs: LogBackend? = null,
    override val notifications: NotificationBackend? = null,
    override val clipboard: ClipboardBackend? = null,
    override val linuxGui: LinuxGuiBackend? = null,
) : BackendRegistry {
    override fun ui(environment: Environment): List<UiBackend> = when (environment) {
        Environment.ANDROID, Environment.PRIVILEGED -> androidUi
        Environment.LINUX_GUI -> listOfNotNull(linuxGui)
        Environment.LINUX_CLI -> emptyList()
    }

    override fun shell(identity: ShellIdentity): ShellBackend? = shells.firstOrNull { it.identity == identity }

    override fun screenshots(environment: Environment): List<ScreenshotBackend> = when (environment) {
        Environment.ANDROID, Environment.PRIVILEGED -> androidScreenshots
        Environment.LINUX_GUI -> listOfNotNull(linuxGui)
        Environment.LINUX_CLI -> emptyList()
    }
}
