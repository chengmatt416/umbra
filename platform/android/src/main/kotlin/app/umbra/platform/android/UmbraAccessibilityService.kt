package app.umbra.platform.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The accessibility service the agent sees and acts through (spec §6). The system creates and binds
 * it once the user (or root, or Shizuku) enables it; [AccessibilityUiBackend] talks to the live
 * [instance]. It declares gesture, screenshot and window-retrieval capabilities and is flagged as an
 * accessibility tool so Advanced Protection Mode does not switch it off.
 */
class UmbraAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The tree is read on demand; events only tell the backend how recently the UI changed.
        if (event != null) {
            lastEventAtMs.set(System.currentTimeMillis())
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                val pkg = windows.firstOrNull { it.displayId == 0 && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root?.packageName?.toString()
                if (pkg != null && pkg != mainPackage.value) {
                    mainPackage.value?.let { leftPackages[it] = System.currentTimeMillis() }
                    mainPackage.value = pkg
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        _connected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        _connected.value = false
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: UmbraAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)
        private val mainPackage = MutableStateFlow<String?>(null)
        val foregroundPackage: StateFlow<String?> = mainPackage.asStateFlow()
        private val leftPackages = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /** Background actions wait until the user has left this app for five seconds. */
        fun userUsing(packageName: String, now: Long = System.currentTimeMillis()): Boolean =
            mainPackage.value == packageName || now - (leftPackages[packageName] ?: 0L) < 5_000

        /** True while the system has the service bound and running. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        /** Wall-clock time of the last accessibility event: how long the UI has been quiet. */
        val lastEventAtMs = AtomicLong(0)

        /** The component name used with `settings put secure enabled_accessibility_services`. */
        fun componentName(packageName: String) = "$packageName/${UmbraAccessibilityService::class.java.name}"
    }
}
