package app.umbra.core.model.capability

import kotlinx.serialization.Serializable

enum class UiBackendKind { ACCESSIBILITY, UI_AUTOMATION, ROOT_SHELL, ATSPI }

/** How the shell-identity helper is started (ADR 0004). */
enum class PrivilegedLaunchMode { SHIZUKU, ROOT_AS_SHELL }

enum class DisplayMode { BACKGROUND_VIRTUAL, FOREGROUND_ONLY }

enum class LinuxProviderKind { ROOT_CHROOT, TERMUX_PREFIX, PROOT, NONE }

enum class GuiProviderKind { TERMUX_X11, XVFB, NONE }

enum class DecisionBackendKind { LAYA_HTTP, HEURISTIC }

/** The concrete choices the agent runs with, derived from detected capabilities (spec §29). */
@Serializable
data class ExecutionStrategy(
    val uiBackend: UiBackendKind? = null,
    /** Shell-identity helper for displays, input injection, UiAutomation and logs. */
    val shellHelper: PrivilegedLaunchMode? = null,
    /** True when a uid-0 helper may be used for root operations. */
    val rootHelper: Boolean = false,
    val displayMode: DisplayMode = DisplayMode.FOREGROUND_ONLY,
    val linuxProvider: LinuxProviderKind = LinuxProviderKind.NONE,
    val guiProvider: GuiProviderKind = GuiProviderKind.NONE,
    val decisionBackend: DecisionBackendKind = DecisionBackendKind.HEURISTIC,
    /** Human-readable reasons for degraded or surprising choices, shown in the capability screen. */
    val notes: List<String> = emptyList(),
)

/** Pure mapping from a capability snapshot to an [ExecutionStrategy]. */
object StrategySelector {
    fun select(snapshot: CapabilitySnapshot): ExecutionStrategy {
        val notes = mutableListOf<String>()

        val uiBackend = when {
            snapshot.isUsable(CapabilityId.ACCESSIBILITY_SERVICE) -> UiBackendKind.ACCESSIBILITY
            snapshot.isUsable(CapabilityId.UI_AUTOMATION) -> {
                if (snapshot.state(CapabilityId.ADVANCED_PROTECTION) == CapabilityState.AVAILABLE) {
                    notes += "Advanced Protection blocks the accessibility service; using UiAutomation."
                } else {
                    notes += "Accessibility service is not enabled; using UiAutomation."
                }
                UiBackendKind.UI_AUTOMATION
            }
            snapshot.isUsable(CapabilityId.ROOT_PERMISSION) -> {
                notes += "No accessibility or UiAutomation; falling back to root shell input and uiautomator dumps."
                UiBackendKind.ROOT_SHELL
            }
            else -> {
                notes += "No UI control backend is available."
                null
            }
        }

        // Prefer Shizuku (a long-lived Binder service); root dropped to uid 2000 otherwise. Shell
        // identity is what virtual displays need, even when root is available (ADR 0004).
        val shellHelper = when {
            snapshot.isUsable(CapabilityId.SHIZUKU_PERMISSION) -> PrivilegedLaunchMode.SHIZUKU
            snapshot.isUsable(CapabilityId.ROOT_PERMISSION) -> PrivilegedLaunchMode.ROOT_AS_SHELL
            else -> null
        }
        val rootHelper = snapshot.isUsable(CapabilityId.ROOT_PERMISSION)

        val displayMode = if (shellHelper != null && snapshot.isUsable(CapabilityId.VIRTUAL_DISPLAY)) {
            DisplayMode.BACKGROUND_VIRTUAL
        } else {
            if (shellHelper == null) {
                notes += "Background workspace needs Shizuku or root; actions run on the primary display."
            }
            DisplayMode.FOREGROUND_ONLY
        }

        val linuxProvider = if (snapshot.isUsable(CapabilityId.LINUX_RUNTIME)) {
            parseLinuxProvider(snapshot.evidence(CapabilityId.LINUX_RUNTIME, "provider"))
        } else {
            LinuxProviderKind.NONE
        }
        val guiProvider = if (snapshot.isUsable(CapabilityId.X11)) {
            parseGuiProvider(snapshot.evidence(CapabilityId.X11, "provider"))
        } else {
            GuiProviderKind.NONE
        }
        if (linuxProvider == LinuxProviderKind.PROOT) {
            notes += "PRoot is in use; it can hang on some Android 15/16 devices."
        }

        val decision = if (snapshot.isUsable(CapabilityId.LAYA_RUNTIME)) {
            DecisionBackendKind.LAYA_HTTP
        } else {
            notes += "Laya sidecar unavailable; using heuristic decisions."
            DecisionBackendKind.HEURISTIC
        }

        return ExecutionStrategy(
            uiBackend = uiBackend,
            shellHelper = shellHelper,
            rootHelper = rootHelper,
            displayMode = displayMode,
            linuxProvider = linuxProvider,
            guiProvider = guiProvider,
            decisionBackend = decision,
            notes = notes,
        )
    }

    private fun parseLinuxProvider(value: String?): LinuxProviderKind = when (value?.lowercase()) {
        "root_chroot" -> LinuxProviderKind.ROOT_CHROOT
        "termux_prefix" -> LinuxProviderKind.TERMUX_PREFIX
        "proot" -> LinuxProviderKind.PROOT
        else -> LinuxProviderKind.NONE
    }

    private fun parseGuiProvider(value: String?): GuiProviderKind = when (value?.lowercase()) {
        "termux_x11" -> GuiProviderKind.TERMUX_X11
        "xvfb" -> GuiProviderKind.XVFB
        else -> GuiProviderKind.NONE
    }
}
