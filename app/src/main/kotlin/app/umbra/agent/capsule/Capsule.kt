package app.umbra.agent.capsule

import app.umbra.agent.R
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect as AndroidRect
import android.graphics.RectF
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import app.umbra.agent.AppContainer
import app.umbra.agent.MainActivity
import app.umbra.core.model.Point
import app.umbra.platform.android.UmbraAccessibilityService
import app.umbra.platform.android.UmbraOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The Material Capsule (architecture spec §3.1): a capsule fixed at the camera cutout — it never drifts along the
 * screen's edge — showing the orb and the task, or a mini island at rest. Tapping it expands Session and Step as
 * Previous / Current / Next, approvals answered in place, and controls; tapping it at rest opens the Smart Composer
 * without forcing the keyboard. Snapshot selection lassoes part of a screenshot with its accessibility text, returns
 * to the app underneath and attaches the selection to the bottom Quick Composer, which shares the draft and context
 * with Home.
 *
 * With Umbra's accessibility service on, the capsule is an accessibility overlay: it can sit at the camera above the
 * status bar and still be touched. Without it, it is an ordinary overlay just under the status bar (the status bar
 * would otherwise take its touches). Every window carries the title the accessibility backend skips, so agents never
 * see or press it.
 */
class Capsule(private val context: Context, private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val screen = ScreenContext(context, container)

    internal val expanded = MutableStateFlow(false)
    internal val composerFocused = MutableStateFlow(false)
    internal val notice = MutableStateFlow<String?>(null)
    internal val busy = MutableStateFlow<String?>(null)
    internal val dismissedFailures = MutableStateFlow<Set<String>>(emptySet())
    /** The camera hole the collapsed capsule wraps, when it sits at the camera; null otherwise. */
    internal val hole = MutableStateFlow<Hole?>(null)

    /** What the companions and the activity feed show (docs/design/capsule-companion.md). */
    internal val watch = CapsuleWatch(container, scope, ::feel)

    /**
     * [gapPx] is the room left for the camera (the hole and a margin each side), [heightPx] the capsule's height so it
     * encloses the hole, [contentLeft] whether the orb and the task go left of the camera (the side with more room, so
     * the status bar's icons stay visible), [belowPx] how far down an expanded capsule starts its content, and
     * [labelMaxPx] how wide the task may be so the capsule keeps off the screen's edge and the clock or icons there.
     */
    internal data class Hole(val gapPx: Int, val heightPx: Int, val contentLeft: Boolean, val belowPx: Int, val labelMaxPx: Int)

    private var capsuleWindow: OverlayWindow? = null
    private var capsuleType = 0
    private var composerWindow: OverlayWindow? = null
    private var selectionWindow: OverlayWindow? = null
    private var selecting = false
    private var composerBeforeSelection = false
    private var job: Job? = null
    private var enabled = false

    private val density get() = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val rotation = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) { place() }
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    /** Shows the capsule while the user has it on; called by the foreground service that keeps the process alive. */
    fun start() {
        if (job?.isActive == true) return
        context.registerComponentCallbacks(rotation)
        watch.start()
        job = scope.launch {
            launch {
                combine(container.settings.flow.map { it.overlayEnabled }.distinctUntilChanged(), UmbraAccessibilityService.connected) { on, _ -> on }
                    .collect { on -> enabled = on; sync() }
            }
            // A decision or a failure peeks in the capsule by itself; nothing switches the workspace (§3.1, Attention Focus).
            launch {
                var seen = emptySet<String>()
                container.approvals.pending.collect { list ->
                    val ids = list.map { it.id }.toSet()
                    // Inside Umbra the Inbox shows it; the peek is for when the user is in another app.
                    if ((ids - seen).isNotEmpty() && capsuleWindow != null && !container.ui.appVisible.value) expanded.value = true
                    seen = ids
                }
            }
            launch { expanded.collect { place() } }
            // Opening Umbra folds the capsule away from its own screen, and (below the status bar) takes it off.
            launch { container.ui.appVisible.collect { visible -> if (visible) expanded.value = false; sync() } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        watch.stop()
        runCatching { context.unregisterComponentCallbacks(rotation) }
        closeCapsule()
        closeComposer()
        selectionWindow?.close(); selectionWindow = null
        selecting = false
    }

    /**
     * Without the accessibility service the capsule can only sit below the status bar, where it would cover the app
     * bar of the app in front; over Umbra itself that is Umbra's own header, which already shows all it would.
     */
    private fun canShow() = enabled && (UmbraAccessibilityService.connected.value || Settings.canDrawOverlays(context)) &&
        !(container.ui.appVisible.value && UmbraAccessibilityService.instance == null)

    /** Creates, moves to the right window type, or removes the capsule window to match the settings and the services. */
    private fun sync() {
        if (!canShow()) { closeCapsule(); closeComposer(); return }
        if (selecting) return
        val service = UmbraAccessibilityService.instance
        val type = if (service != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        if (capsuleWindow != null && capsuleType == type) return
        closeCapsule()
        val host: Context = service ?: context
        val wm = host.getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = UmbraOverlay.WINDOW_TITLE
        }
        capsuleType = type
        capsuleWindow = runCatching {
            OverlayWindow(wm, params, host, onOutside = { if (expanded.value) expanded.value = false }) { CapsuleView(this, container) }
        }.onFailure { notice.value = context.getString(R.string.cap_notice_capsule, it.message.orEmpty()) }.getOrNull()
        place()
    }

    /**
     * Anchors the capsule on the camera (or the top centre when there is none), expanded or not. Collapsed at the
     * camera it wraps the hole: the hole sits in a gap at one end, the orb and the task beside it.
     */
    private fun place() {
        val window = capsuleWindow ?: return
        val wm = (UmbraAccessibilityService.instance ?: context).getSystemService(WindowManager::class.java)
        val metrics = wm.currentWindowMetrics
        val width = metrics.bounds.width()
        val statusTop = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        val cutout = metrics.windowInsets.displayCutout
        val top = cutout?.boundingRectTop?.takeIf { !it.isEmpty }
        // The exact hole (the path), within the top cutout's reserved rectangle; the rectangle when there is no path.
        val camera = top?.let { reserved ->
            cutout.cutoutPath?.let { path ->
                val f = RectF().also { path.computeBounds(it, true) }
                AndroidRect(floor(f.left).toInt(), floor(f.top).toInt(), ceil(f.right).toInt(), ceil(f.bottom).toInt()).takeIf { it.intersect(reserved) }
            } ?: reserved
        }
        val overStatusBar = capsuleType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val margin = dp(6)
        val pill = if (overStatusBar && camera != null) maxOf(dp(PILL_DP), camera.height() + dp(10)) else dp(PILL_DP)
        val y = when {
            !overStatusBar -> statusTop + dp(4)
            camera != null -> (camera.centerY() - pill / 2).coerceAtLeast(0)
            else -> ((statusTop - pill) / 2).coerceAtLeast(0)
        }
        val wrap = if (overStatusBar && camera != null) {
            val contentLeft = camera.left > width - camera.right
            // Room on the content's side, less a fifth of the screen kept clear for the status bar's corner, less the
            // capsule's own padding, orb and chevron.
            val room = (if (contentLeft) camera.left else width - camera.right) - margin - width / 5 - dp(68)
            Hole(camera.width() + 2 * margin, pill, contentLeft, camera.bottom - y + dp(6), room.coerceAtLeast(dp(48)))
        } else null
        hole.value = wrap
        window.update { p ->
            p.y = y
            when {
                expanded.value -> { p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; p.width = width - dp(24); p.x = 0 }
                wrap != null && camera != null -> {
                    p.width = WindowManager.LayoutParams.WRAP_CONTENT
                    if (wrap.contentLeft) { p.gravity = Gravity.TOP or Gravity.END; p.x = width - (camera.right + margin) }
                    else { p.gravity = Gravity.TOP or Gravity.START; p.x = camera.left - margin }
                }
                else -> { p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; p.width = WindowManager.LayoutParams.WRAP_CONTENT; p.x = (top?.centerX() ?: (width / 2)) - width / 2 }
            }
        }
    }

    /**
     * A light haptic for a new request or a completed task, felt through the capsule's window (so the system's
     * touch-feedback setting applies). Only while the capsule is on screen and Umbra itself is not in front: the app
     * shows the same thing, and a person looking at it needs no nudge.
     */
    private fun feel(signal: CapsuleWatch.Signal) {
        val window = capsuleWindow ?: return
        if (container.ui.appVisible.value) return
        window.haptic(
            when (signal) {
                CapsuleWatch.Signal.REQUEST -> HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                CapsuleWatch.Signal.DONE -> HapticFeedbackConstants.CONFIRM
            },
        )
    }

    private fun closeCapsule() {
        capsuleWindow?.close()
        capsuleWindow = null
        expanded.value = false
    }

    // ---- the bottom Quick Composer ----

    /** Opens the Smart Composer at the bottom. The keyboard opens only when the user taps the field (§3.1). */
    fun openComposer(target: String? = container.ui.composerTarget.value) {
        container.ui.composerTarget.value = target
        expanded.value = false
        if (composerWindow != null) return
        // Apps that guard against tapjacking (Settings, installers, permission screens) hide ordinary overlays; an
        // accessibility overlay is not hidden, so the composer uses one whenever Umbra's service is on.
        val service = UmbraAccessibilityService.instance
        if (service == null && !Settings.canDrawOverlays(context)) {
            open(page = null, session = null)
            return
        }
        val host: Context = service ?: context
        val wm = host.getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (service != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            title = UmbraOverlay.WINDOW_TITLE
        }
        val metrics = wm.currentWindowMetrics
        val screenHeight = metrics.bounds.height()
        val navigation = metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
        composerWindow = runCatching {
            OverlayWindow(wm, params, host, onBack = { if (composerFocused.value) focusComposer(false) else closeComposer(); true }) {
                QuickComposerView(this, container, screenHeight, navigation)
            }
        }.onFailure { notice.value = context.getString(R.string.cap_notice_composer, it.message.orEmpty()) }.getOrNull()
    }

    /** The composer takes the keyboard only while the user is typing; otherwise it never steals focus from the app. */
    fun focusComposer(on: Boolean) {
        val window = composerWindow ?: return
        composerFocused.value = on
        window.update { p ->
            p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (!on) window.windowToken?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it, 0) }
    }

    fun closeComposer() {
        composerWindow?.let { w -> w.windowToken?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it, 0) } }
        composerWindow?.close()
        composerWindow = null
        composerFocused.value = false
        notice.value = null
    }

    /** Sends the shared draft and context to the composer's target. */
    fun send(text: String, projectId: String?) {
        if (busy.value != null) return
        val target = container.ui.composerTarget.value
        busy.value = context.getString(R.string.cap_sending)
        scope.launch {
            try {
                screen.send(text, target, projectId)
                notice.value = null
                closeComposer()
            } catch (e: Exception) {
                notice.value = e.message ?: e::class.simpleName
            } finally {
                busy.value = null
            }
        }
    }

    // ---- Snapshot selection ----

    /**
     * Hides Umbra's windows, takes one screenshot with the accessibility tree, and lets the user lasso part of it.
     * One snapshot, not live (§3.1). [delayMs] lets the app that asked get out of the way first.
     */
    fun startSelection(delayMs: Long = 0) {
        if (selecting) return
        selecting = true
        scope.launch {
            val composerWasOpen = composerWindow != null
            composerBeforeSelection = composerWasOpen
            closeComposer()
            closeCapsule()
            // Let the windows and the keyboard leave the screen before the picture is taken.
            delay(delayMs + 350)
            val capture = try {
                screen.capture()
            } catch (e: Exception) {
                selecting = false
                sync()
                if (composerWasOpen || Settings.canDrawOverlays(context)) openComposer()
                notice.value = e.message ?: e::class.simpleName
                return@launch
            }
            val service = UmbraAccessibilityService.instance
            val host: Context = service ?: context
            val type = if (service != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
                title = UmbraOverlay.WINDOW_TITLE
            }
            val insets = host.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
            selectionWindow = runCatching {
                OverlayWindow(host.getSystemService(WindowManager::class.java), params, host, onBack = { finishSelection(null, null); true }) {
                    SelectionView(capture, insets.top, insets.bottom, onDone = { stroke -> finishSelection(capture, stroke) }, onCancel = { finishSelection(null, null) })
                }
            }.onFailure {
                selecting = false
                sync()
                notice.value = context.getString(R.string.cap_notice_selection, it.message.orEmpty())
            }.getOrNull()
        }
    }

    /** Done: the selection becomes a context chip on the shared composer, and the app underneath is back. */
    private fun finishSelection(capture: ScreenContext.Capture?, stroke: List<Point>?) {
        scope.launch {
            val chip = if (capture != null && !stroke.isNullOrEmpty()) runCatching { screen.select(capture, stroke) }.onFailure { notice.value = context.getString(R.string.cap_notice_read, it.message.orEmpty()) }.getOrNull() else null
            selectionWindow?.close()
            selectionWindow = null
            selecting = false
            chip?.let { container.ui.addContext(it) }
            sync()
            if (chip != null || notice.value != null || composerBeforeSelection) openComposer()
        }
    }

    // ---- links into the app ----

    fun open(page: String?, session: String?) {
        expanded.value = false
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).apply {
                session?.let { putExtra(MainActivity.EXTRA_SESSION, it) }
                page?.let { putExtra(MainActivity.EXTRA_PAGE, it) }
            },
        )
    }

    internal fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch {
        try { block() } catch (e: Exception) { notice.value = e.message ?: e::class.simpleName }
    }

    private companion object {
        const val PILL_DP = 34
    }
}
