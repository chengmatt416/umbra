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
    private val inputMethodPackages by lazy { getSystemService(android.view.inputmethod.InputMethodManager::class.java).inputMethodList.map { it.packageName }.toSet() }
    override fun onServiceConnected() {
        instance = this
        _connected.value = true
        updateInteractionEvents()
    }

    private fun updateInteractionEvents() {
        val info = serviceInfo ?: return
        info.eventTypes = if (interactionListeners.isEmpty()) info.eventTypes and TEACH_EVENTS.inv() else info.eventTypes or TEACH_EVENTS
        serviceInfo = info
    }

    private fun interruptTeaching() {
        if (interactionListeners.isEmpty()) return
        val event = AccessibilityInteraction(System.currentTimeMillis(), packageName, "", null, null,
            app.umbra.core.model.Rect.EMPTY, "INTERRUPTED", "", "", false, 0)
        interactionListeners.forEach { it(event) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The tree is read on demand; events only tell the backend how recently the UI changed.
        if (event != null) {
            if (interactionListeners.isNotEmpty()) publishInteraction(event)
            lastEventAtMs.set(System.currentTimeMillis())
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) lastTextInputAtMs.set(System.currentTimeMillis())
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) publishTopOverlays()
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

    /**
     * Other apps' overlays in the top third of the screen (a "dynamic island" such as Material Capsule draws one around
     * the camera). Only their app and bounds are kept, never their content; Umbra's own windows, System UI and the
     * system itself are left out. The Capsule uses this to stay out of their way.
     */
    private fun publishTopOverlays() {
        runCatching {
            val limit = resources.displayMetrics.heightPixels / 3
            val found = windows.mapNotNull { w ->
                if (w.displayId != 0) return@mapNotNull null
                if (w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && w.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@mapNotNull null
                if (w.title?.toString() == UmbraOverlay.WINDOW_TITLE) return@mapNotNull null
                val r = android.graphics.Rect().also(w::getBoundsInScreen)
                if (r.isEmpty || r.top > limit) return@mapNotNull null
                val pkg = w.root?.packageName?.toString()
                if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return@mapNotNull null
                // An unattributed system window is the status bar or the shade, not another app's capsule.
                if (pkg == null && w.type == AccessibilityWindowInfo.TYPE_SYSTEM) return@mapNotNull null
                TopOverlay(pkg, app.umbra.core.model.Rect(r.left, r.top, r.right, r.bottom))
            }
            if (found != overlays.value) overlays.value = found
        }
    }

    private fun publishInteraction(event: AccessibilityEvent) {
        val action = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICK"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "LONG_CLICK"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLL"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW"
            else -> return
        }
        runCatching {
            val node = event.source
            val packageName = node?.packageName?.toString() ?: event.packageName?.toString() ?: return
            val window = node?.window
            if (packageName == this.packageName || packageName in inputMethodPackages || window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return
            val rect = android.graphics.Rect()
            node?.getBoundsInScreen(rect)
            val secret = node?.isPassword == true
            val input = action == "TYPE" || node?.isEditable == true
            val item = AccessibilityInteraction(System.currentTimeMillis(), packageName, window?.title?.toString().orEmpty().take(200),
                node?.viewIdResourceName?.take(200), node?.className?.toString()?.take(200),
                app.umbra.core.model.Rect(rect.left, rect.top, rect.width(), rect.height()), action,
                if (secret) "[SECRET_INPUT_HANDOFF]" else if (input) "[INPUT_PARAMETER]" else node?.text?.toString().orEmpty().take(200),
                if (secret || input) "" else node?.contentDescription?.toString().orEmpty().take(200), secret, window?.displayId ?: 0)
            interactionListeners.forEach { it(item) }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        interruptTeaching()
        if (instance === this) instance = null
        _connected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        interruptTeaching()
        if (instance === this) instance = null
        _connected.value = false
        super.onDestroy()
    }

    companion object {
        private val interactionListeners = java.util.concurrent.CopyOnWriteArrayList<(AccessibilityInteraction) -> Unit>()
        private const val TEACH_EVENTS = AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or AccessibilityEvent.TYPE_VIEW_SCROLLED
        fun observeInteractions(listener: (AccessibilityInteraction) -> Unit): AutoCloseable {
            interactionListeners += listener
            instance?.updateInteractionEvents()
            return AutoCloseable { interactionListeners -= listener; instance?.updateInteractionEvents() }
        }
        @Volatile
        var instance: UmbraAccessibilityService? = null
            private set

        private val overlays = MutableStateFlow<List<TopOverlay>>(emptyList())
        /** Other apps' windows near the top of the screen; see [publishTopOverlays]. */
        val topOverlays: StateFlow<List<TopOverlay>> = overlays.asStateFlow()

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
        /** Timing only; typed content is never retained for attention ranking. */
        val lastTextInputAtMs = AtomicLong(0)

        /** The component name used with `settings put secure enabled_accessibility_services`. */
        fun componentName(packageName: String) = "$packageName/${UmbraAccessibilityService::class.java.name}"
    }
}

/** Another app's window near the top of the screen: which app (when known) and where, nothing else. */
data class TopOverlay(val packageName: String?, val bounds: app.umbra.core.model.Rect)
