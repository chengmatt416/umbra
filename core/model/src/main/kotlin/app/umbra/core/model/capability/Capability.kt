package app.umbra.core.model.capability

import app.umbra.core.model.action.Privilege
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Everything spec §29 asks the product to detect, plus the probes the runtime design needs. */
@Serializable
enum class CapabilityId {
    /** Enabled, connected, and not revoked by Advanced Protection Mode. */
    @SerialName("accessibility_service") ACCESSIBILITY_SERVICE,
    /** UiAutomation connection hosted in a privileged process. */
    @SerialName("ui_automation") UI_AUTOMATION,
    @SerialName("shizuku_available") SHIZUKU_AVAILABLE,
    @SerialName("shizuku_permission") SHIZUKU_PERMISSION,
    @SerialName("root_available") ROOT_AVAILABLE,
    @SerialName("root_permission") ROOT_PERMISSION,
    /** A privileged helper is running with shell identity (Shizuku, or root dropped to uid 2000). */
    @SerialName("shell_helper") SHELL_HELPER,
    @SerialName("virtual_display") VIRTUAL_DISPLAY,
    @SerialName("input_methods") INPUT_METHODS,
    @SerialName("linux_runtime") LINUX_RUNTIME,
    @SerialName("debian_runtime") DEBIAN_RUNTIME,
    @SerialName("x11") X11,
    @SerialName("laya_runtime") LAYA_RUNTIME,
    @SerialName("omp_runtime") OMP_RUNTIME,
    @SerialName("model_providers") MODEL_PROVIDERS,
    /** exec() of a binary in app data works (targetSdk 28 SELinux domain). */
    @SerialName("exec_from_app_data") EXEC_FROM_APP_DATA,
    /** Phantom-process killer disabled (or not applicable) so child processes survive. */
    @SerialName("phantom_process_guard") PHANTOM_PROCESS_GUARD,
    @SerialName("overlay_permission") OVERLAY_PERMISSION,
    @SerialName("notification_permission") NOTIFICATION_PERMISSION,
    @SerialName("battery_unrestricted") BATTERY_UNRESTRICTED,
    /** Informational: Advanced Protection Mode is on and will block non-accessibility-tool services. */
    @SerialName("advanced_protection") ADVANCED_PROTECTION,
}

@Serializable
enum class CapabilityState {
    AVAILABLE,

    /** Works, but with a known limitation described in [Capability.detail]. */
    DEGRADED,
    UNAVAILABLE,
    UNKNOWN,
}

@Serializable
data class Capability(
    val id: CapabilityId,
    val state: CapabilityState,
    val detail: String = "",
    /** Raw facts behind the verdict (versions, paths, measured latency, chosen provider...). */
    val evidence: Map<String, String> = emptyMap(),
    val measuredAtMs: Long = 0,
    /** What the user can do to fix it, shown in onboarding and the capability screen. */
    val userAction: String? = null,
) {
    val isUsable: Boolean get() = state == CapabilityState.AVAILABLE || state == CapabilityState.DEGRADED
}

@Serializable
data class CapabilitySnapshot(
    val capabilities: Map<CapabilityId, Capability> = emptyMap(),
    val takenAtMs: Long = 0,
) {
    operator fun get(id: CapabilityId): Capability? = capabilities[id]

    fun state(id: CapabilityId): CapabilityState = capabilities[id]?.state ?: CapabilityState.UNKNOWN

    fun isUsable(id: CapabilityId): Boolean = capabilities[id]?.isUsable == true

    fun evidence(id: CapabilityId, key: String): String? = capabilities[id]?.evidence?.get(key)

    fun with(vararg updates: Capability): CapabilitySnapshot =
        copy(capabilities = capabilities + updates.associateBy { it.id })

    /**
     * Whether the device can currently supply [privilege]. Accessibility-level access is also
     * satisfied by UiAutomation, which the privileged helper provides.
     */
    fun canProvide(privilege: Privilege): Boolean = when (privilege) {
        Privilege.NONE -> true
        Privilege.ACCESSIBILITY -> isUsable(CapabilityId.ACCESSIBILITY_SERVICE) || isUsable(CapabilityId.UI_AUTOMATION)
        Privilege.SHELL -> isUsable(CapabilityId.SHELL_HELPER) || isUsable(CapabilityId.ROOT_PERMISSION)
        Privilege.ROOT -> isUsable(CapabilityId.ROOT_PERMISSION)
    }
}
