package app.umbra.core.model.capability

import app.umbra.core.model.action.Privilege
import app.umbra.core.model.capability.CapabilityId.ACCESSIBILITY_SERVICE
import app.umbra.core.model.capability.CapabilityId.ADVANCED_PROTECTION
import app.umbra.core.model.capability.CapabilityId.LAYA_RUNTIME
import app.umbra.core.model.capability.CapabilityId.LINUX_RUNTIME
import app.umbra.core.model.capability.CapabilityId.ROOT_PERMISSION
import app.umbra.core.model.capability.CapabilityId.SHIZUKU_PERMISSION
import app.umbra.core.model.capability.CapabilityId.UI_AUTOMATION
import app.umbra.core.model.capability.CapabilityId.VIRTUAL_DISPLAY
import app.umbra.core.model.capability.CapabilityId.X11
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrategySelectorTest {
    private fun cap(id: CapabilityId, state: CapabilityState = CapabilityState.AVAILABLE, vararg evidence: Pair<String, String>) =
        Capability(id, state, evidence = evidence.toMap())

    private fun snapshot(vararg caps: Capability) = CapabilitySnapshot().with(*caps)

    @Test
    fun nothingAvailableDegradesGracefully() {
        val s = StrategySelector.select(CapabilitySnapshot())
        assertNull(s.uiBackend)
        assertNull(s.shellHelper)
        assertFalse(s.rootHelper)
        assertEquals(DisplayMode.FOREGROUND_ONLY, s.displayMode)
        assertEquals(LinuxProviderKind.NONE, s.linuxProvider)
        assertEquals(DecisionBackendKind.HEURISTIC, s.decisionBackend)
        assertTrue(s.notes.isNotEmpty())
    }

    @Test
    fun rootOnlyUsesRootShellAndShellIdentityForDisplays() {
        val s = StrategySelector.select(snapshot(cap(ROOT_PERMISSION), cap(VIRTUAL_DISPLAY)))
        assertEquals(UiBackendKind.ROOT_SHELL, s.uiBackend)
        assertEquals(PrivilegedLaunchMode.ROOT_AS_SHELL, s.shellHelper)
        assertTrue(s.rootHelper)
        assertEquals(DisplayMode.BACKGROUND_VIRTUAL, s.displayMode)
    }

    @Test
    fun advancedProtectionFallsBackToUiAutomation() {
        val s = StrategySelector.select(
            snapshot(
                cap(ACCESSIBILITY_SERVICE, CapabilityState.UNAVAILABLE),
                cap(ADVANCED_PROTECTION),
                cap(UI_AUTOMATION),
                cap(SHIZUKU_PERMISSION),
            ),
        )
        assertEquals(UiBackendKind.UI_AUTOMATION, s.uiBackend)
        assertEquals(PrivilegedLaunchMode.SHIZUKU, s.shellHelper)
        assertTrue(s.notes.any { "Advanced Protection" in it }, s.notes.toString())
    }

    @Test
    fun accessibilityIsPreferredWhenUsable() {
        val s = StrategySelector.select(snapshot(cap(ACCESSIBILITY_SERVICE), cap(UI_AUTOMATION), cap(ROOT_PERMISSION)))
        assertEquals(UiBackendKind.ACCESSIBILITY, s.uiBackend)
    }

    @Test
    fun shizukuIsPreferredOverRootForShellIdentity() {
        val s = StrategySelector.select(snapshot(cap(SHIZUKU_PERMISSION), cap(ROOT_PERMISSION)))
        assertEquals(PrivilegedLaunchMode.SHIZUKU, s.shellHelper)
        assertTrue(s.rootHelper)
    }

    @Test
    fun degradedCapabilitiesStillCountAsUsable() {
        val s = StrategySelector.select(snapshot(cap(ACCESSIBILITY_SERVICE, CapabilityState.DEGRADED)))
        assertEquals(UiBackendKind.ACCESSIBILITY, s.uiBackend)
    }

    @Test
    fun runtimeProvidersComeFromProbeEvidence() {
        val s = StrategySelector.select(
            snapshot(
                cap(LINUX_RUNTIME, CapabilityState.AVAILABLE, "provider" to "root_chroot"),
                cap(X11, CapabilityState.AVAILABLE, "provider" to "xvfb"),
                cap(LAYA_RUNTIME),
            ),
        )
        assertEquals(LinuxProviderKind.ROOT_CHROOT, s.linuxProvider)
        assertEquals(GuiProviderKind.XVFB, s.guiProvider)
        assertEquals(DecisionBackendKind.LAYA_HTTP, s.decisionBackend)
    }

    @Test
    fun prootProducesAWarning() {
        val s = StrategySelector.select(snapshot(cap(LINUX_RUNTIME, CapabilityState.DEGRADED, "provider" to "proot")))
        assertEquals(LinuxProviderKind.PROOT, s.linuxProvider)
        assertTrue(s.notes.any { "PRoot" in it })
    }

    @Test
    fun canProvideMapsPrivilegesToCapabilities() {
        val none = CapabilitySnapshot()
        assertTrue(none.canProvide(Privilege.NONE))
        assertFalse(none.canProvide(Privilege.ACCESSIBILITY))
        assertFalse(none.canProvide(Privilege.SHELL))
        assertFalse(none.canProvide(Privilege.ROOT))

        val root = snapshot(cap(ROOT_PERMISSION))
        assertTrue(root.canProvide(Privilege.SHELL))
        assertTrue(root.canProvide(Privilege.ROOT))
        assertFalse(root.canProvide(Privilege.ACCESSIBILITY))

        val uiAutomation = snapshot(cap(UI_AUTOMATION))
        assertTrue(uiAutomation.canProvide(Privilege.ACCESSIBILITY))
    }
}
