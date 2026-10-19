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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The Material Capsule (architecture spec §3.1, docs/design/redesign-2026-10.md §5): centred on the camera cutout,
 * left zone | camera slot | right zone, the slot as wide as the camera plus a margin each side (read from the
 * DisplayCutout), so the camera never covers what the capsule shows. Tapping it expands a card in place, under the
 * camera — the task's Previous / Current / Next, what it waits for, a field to add instructions, and its controls;
 * at rest the card is the Smart Composer. Swiping it toward the camera tucks it away (the task goes on); a long
 * press selects on the screen, and the selection's controls are in a card at the top too.
 *
 * With Umbra's accessibility service on, the capsule is an accessibility overlay and sits at the camera above the
 * status bar; without it, an ordinary overlay just under the status bar. Every window carries the title the
 * accessibility backend skips, so agents never see or press it.
 */
class Capsule(internal val context: Context, private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val screen = ScreenContext(context, container)

    internal val expanded = MutableStateFlow(false)
    internal val tucked = MutableStateFlow(false)
    /** The card's field has the keyboard (the window is focusable only then). */
    internal val cardFocused = MutableStateFlow(false)
    internal val composerFocused = MutableStateFlow(false)
    internal val notice = MutableStateFlow<String?>(null)
    internal val busy = MutableStateFlow<String?>(null)
    internal val dismissedFailures = MutableStateFlow<Set<String>>(emptySet())
    /** Where the capsule sits relative to the camera; null when there is no camera to wrap. */
    internal val hole = MutableStateFlow<Hole?>(null)

    /** What the companions and the activity feed show (docs/design/capsule-companion.md). */
    internal val watch = CapsuleWatch(container, scope, ::feel)

    /**
     * [centered]: the camera is near the top centre and the capsule is centred on it, [zonePx] wide on each side of
     * the [gapPx] slot. Otherwise the camera is in a corner and the content goes on the side with more room
     * ([contentLeft]), [labelMaxPx] wide at most. [heightPx] encloses the camera, [belowPx] is where an expanded
     * card's content starts.
     */
    internal data class Hole(
        val gapPx: Int,
        val heightPx: Int,
        val contentLeft: Boolean,
        val belowPx: Int,
        val labelMaxPx: Int,
        val centered: Boolean = false,
        val zonePx: Int = 0,
    )

    private var capsuleWindow: OverlayWindow? = null
    private var capsuleType = 0
    private var composerWindow: OverlayWindow? = null
    private var selectionWindow: OverlayWindow? = null
    internal val selectionFocused = MutableStateFlow(false)
    private var selecting = false
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
            // A decision peeks in the capsule by itself, tucked or not; nothing switches the workspace (§3.1).
            launch {
                var seen = emptySet<String>()
                container.approvals.pending.collect { list ->
                    val ids = list.map { it.id }.toSet()
                    if ((ids - seen).isNotEmpty()) tucked.value = false
                    if ((ids - seen).isNotEmpty() && capsuleWindow != null && !container.ui.appVisible.value) expanded.value = true
                    seen = ids
                }
            }
            // News peeks too: a question waits open; a task that finished or failed shows for a moment.
            launch {
                var seen = emptySet<String>()
                container.questions.pending.collect { list ->
                    val ids = list.map { "${it.sessionId}:${it.askedAtMs}" }.toSet()
                    list.lastOrNull { "${it.sessionId}:${it.askedAtMs}" !in seen }?.let { container.ui.focus(it.sessionId); peek(hold = true) }
                    seen = ids
                }
            }
            launch {
                val last = HashMap<String, app.umbra.core.session.SessionState>()
                container.sessionStore.list().forEach { last[it.id] = it.state }
                container.sessionStore.changes.collect { change ->
                    val session = (change as? app.umbra.core.session.StoreChange.SessionChanged)?.session ?: return@collect
                    val before = last.put(session.id, session.state)
                    val ended = session.state == app.umbra.core.session.SessionState.COMPLETED || session.state == app.umbra.core.session.SessionState.FAILED
                    if (before != null && before != session.state && ended) {
                        container.ui.focus(session.id)
                        peek(hold = false)
                    }
                }
            }
            launch { expanded.collect { if (!it) focusCard(false); place() } }
            launch { tucked.collect { place() } }
            launch { container.ui.appVisible.collect { visible -> if (visible) expanded.value = false; sync() } }
        }
    }

    /** Starts a screen recording: the card folds away so it is not in the picture, the capsule becomes its stop button. */
    fun startRecording() {
        expanded.value = false
        container.recorder.request()
    }

    /** A recording became context: the card opens with it and its field, for what to do with it. */
    fun showRecording() {
        tucked.value = false
        if (capsuleWindow == null || container.ui.appVisible.value) return
        peekJob?.cancel()
        expanded.value = true
    }

    private var peekJob: Job? = null

    /**
     * Opens the card by itself for news, tucked or not, unless Umbra itself is in front (it shows the news there).
     * [hold]: it waits open (a question); otherwise it folds back after a moment unless the person has touched it.
     */
    private fun peek(hold: Boolean) {
        tucked.value = false
        if (capsuleWindow == null || container.ui.appVisible.value) return
        val wasOpen = expanded.value
        expanded.value = true
        peekJob?.cancel()
        if (hold || wasOpen) return
        peekJob = scope.launch {
            delay(PEEK_MS)
            val waiting = container.approvals.pending.value.isNotEmpty() || container.questions.pending.value.isNotEmpty()
            if (!cardFocused.value && !waiting) expanded.value = false
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
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            title = UmbraOverlay.WINDOW_TITLE
        }
        capsuleType = type
        capsuleWindow = runCatching {
            OverlayWindow(
                wm, params, host,
                onOutside = { if (expanded.value && !cardFocused.value) expanded.value = false },
                onBack = { if (cardFocused.value) focusCard(false) else expanded.value = false; true },
            ) { CapsuleView(this, container) }
        }.onFailure { notice.value = context.getString(R.string.cap_notice_capsule, it.message.orEmpty()) }.getOrNull()
        place()
    }

    /** The camera hole, as the screen reports it (the exact path within the top cutout's rectangle), or null. */
    private fun camera(metrics: android.view.WindowMetrics): AndroidRect? {
        val cutout = metrics.windowInsets.displayCutout
        val top = cutout?.boundingRectTop?.takeIf { !it.isEmpty } ?: return null
        return cutout.cutoutPath?.let { path ->
            val f = RectF().also { path.computeBounds(it, true) }
            AndroidRect(floor(f.left).toInt(), floor(f.top).toInt(), ceil(f.right).toInt(), ceil(f.bottom).toInt()).takeIf { it.intersect(top) }
        } ?: top
    }

    /**
     * Anchors the capsule on the camera (or the top centre when there is none), collapsed, tucked or expanded. A camera
     * near the middle gets the centred capsule: two equal zones beside a slot the camera's width plus 8 dp each side.
     */
    private fun place() {
        val window = capsuleWindow ?: return
        val wm = (UmbraAccessibilityService.instance ?: context).getSystemService(WindowManager::class.java)
        val metrics = wm.currentWindowMetrics
        val width = metrics.bounds.width()
        val statusTop = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        val camera = camera(metrics)
        val overStatusBar = capsuleType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val margin = dp(8)
        val pill = if (overStatusBar && camera != null) maxOf(dp(PILL_DP), camera.height() + dp(10)) else dp(PILL_DP)
        val y = when {
            !overStatusBar -> statusTop + dp(4)
            camera != null -> (camera.centerY() - pill / 2).coerceAtLeast(0)
            else -> ((statusTop - pill) / 2).coerceAtLeast(0)
        }
        val wrap = if (overStatusBar && camera != null) {
            val centered = abs(camera.centerX() - width / 2) < width * 0.15f
            val slot = camera.width() + 2 * margin
            if (centered) {
                // Each zone stops short of the status bar's clock and icons (a sixth of the screen kept clear at each end).
                val room = minOf(camera.left, width - camera.right) - margin - width / 6
                val zone = room.coerceIn(dp(40), dp(ZONE_DP))
                Hole(slot, pill, contentLeft = true, belowPx = camera.bottom - y + dp(6), labelMaxPx = zone - dp(32), centered = true, zonePx = zone)
            } else {
                val contentLeft = camera.left > width - camera.right
                val room = (if (contentLeft) camera.left else width - camera.right) - margin - width / 5 - dp(68)
                Hole(slot, pill, contentLeft, camera.bottom - y + dp(6), room.coerceAtLeast(dp(48)))
            }
        } else null
        hole.value = wrap
        window.update { p ->
            when {
                expanded.value -> {
                    // The card's first row is the camera's row: the camera sits in the card's top edge, between its controls.
                    p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    p.width = width - dp(8)
                    p.x = 0
                    p.y = if (overStatusBar && camera != null) (camera.centerY() - dp(CARD_ROW_DP) / 2 - dp(10)).coerceAtLeast(0) else y
                }
                wrap != null && camera != null && wrap.centered -> {
                    p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    p.width = WindowManager.LayoutParams.WRAP_CONTENT
                    p.x = camera.centerX() - width / 2
                    p.y = y
                }
                wrap != null && camera != null -> {
                    p.width = WindowManager.LayoutParams.WRAP_CONTENT
                    p.y = y
                    if (wrap.contentLeft) { p.gravity = Gravity.TOP or Gravity.END; p.x = width - (camera.right + margin) }
                    else { p.gravity = Gravity.TOP or Gravity.START; p.x = camera.left - margin }
                }
                else -> {
                    p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    p.width = WindowManager.LayoutParams.WRAP_CONTENT
                    p.x = (camera?.centerX() ?: (width / 2)) - width / 2
                    p.y = y
                }
            }
        }
    }

    /** The card's field takes the keyboard only while the user types in it; otherwise the app keeps focus (§3.1). */
    fun focusCard(on: Boolean) {
        val window = capsuleWindow ?: return
        if (cardFocused.value == on) return
        cardFocused.value = on
        window.update { p ->
            p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (!on) window.windowToken?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it, 0) }
    }

    /** Tapped: expand (a tucked capsule comes back first). */
    fun tap() {
        // While the screen is being recorded, the capsule is its stop button.
        if (container.recorder.state.value is ScreenRecorder.State.Recording) { container.recorder.stop(); return }
        if (tucked.value) { tucked.value = false; return }
        expanded.value = !expanded.value
    }

    /**
     * A light haptic for a new request or a completed task, felt through the capsule's window (so the system's
     * touch-feedback setting applies). Only while the capsule is on screen and Umbra itself is not in front.
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

    internal fun haptic(feedback: Int) = capsuleWindow?.haptic(feedback)

    private fun closeCapsule() {
        capsuleWindow?.close()
        capsuleWindow = null
        expanded.value = false
        cardFocused.value = false
    }

    // ---- the bottom Quick Composer (kept for a capsule below the status bar, where the card cannot type) ----

    /** Opens the Smart Composer at the bottom. The keyboard opens only when the user taps the field (§3.1). */
    fun openComposer(target: String? = container.ui.composerTarget.value) {
        container.ui.composerTarget.value = target
        expanded.value = false
        if (composerWindow != null) return
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

    /** Sends the shared draft and context: to [target] (a session), or as new work. Closes what it was typed in. */
    fun send(text: String, projectId: String?, target: String? = container.ui.composerTarget.value, agent: String? = null) {
        if (busy.value != null) return
        busy.value = context.getString(R.string.cap_sending)
        scope.launch {
            try {
                screen.send(text, target, projectId, toAgent = agent)
                notice.value = null
                closeComposer()
                focusCard(false)
                if (target == null) expanded.value = false
            } catch (e: Exception) {
                notice.value = e.message ?: e::class.simpleName
            } finally {
                busy.value = null
            }
        }
    }

    /** The card's field at rest: the words go to the default agent (made on first need); the card stays open for its answer. */
    fun sendToDefaultAgent(text: String) {
        if (busy.value != null) return
        busy.value = context.getString(R.string.cap_sending)
        scope.launch {
            try {
                val agent = container.agents.ensureDefault()
                screen.send(text, null, null, toAgent = agent.id)
                notice.value = null
                focusCard(false)
            } catch (e: Exception) {
                notice.value = e.message ?: e::class.simpleName
            } finally {
                busy.value = null
            }
        }
    }

    // ---- Snapshot selection ----

    /**
     * Hides Umbra's windows, takes one screenshot with the accessibility tree, and lets the user circle, tap or swipe
     * over part of it; the selection's card sits at the top. One snapshot, not live (§3.1). [delayMs] lets the app
     * that asked get out of the way first.
     */
    fun startSelection(delayMs: Long = 0) {
        if (selecting) return
        selecting = true
        scope.launch {
            closeComposer()
            closeCapsule()
            // Let the windows and the keyboard leave the screen before the picture is taken.
            delay(delayMs + 350)
            val capture = try {
                screen.capture()
            } catch (e: Exception) {
                selecting = false
                sync()
                notice.value = e.message ?: e::class.simpleName
                expanded.value = true
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
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                title = UmbraOverlay.WINDOW_TITLE
            }
            val wm = host.getSystemService(WindowManager::class.java)
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
            val cam = camera(metrics)
            selectionWindow = runCatching {
                OverlayWindow(wm, params, host, onBack = { if (selectionFocused.value) focusSelection(false) else cancelSelection(); true }) {
                    SelectionView(this@Capsule, container, capture, insets.top, cam?.centerY(), onCancel = { cancelSelection() })
                }
            }.onFailure {
                selecting = false
                sync()
                notice.value = context.getString(R.string.cap_notice_selection, it.message.orEmpty())
            }.getOrNull()
        }
    }

    /** The selection card's field takes the keyboard only while the user types in it. */
    fun focusSelection(on: Boolean) {
        val window = selectionWindow ?: return
        selectionFocused.value = on
        window.update { p ->
            p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (!on) window.windowToken?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it, 0) }
    }

    /** Reads what was selected into a context chip (a cropped picture and the accessibility text inside it). */
    internal suspend fun selected(capture: ScreenContext.Capture, stroke: List<Point>) = screen.select(capture, stroke)

    /**
     * Sends [text] about the selection as a new task: the selection goes with it as context, the screen comes back,
     * and the capsule shows the new task at once.
     */
    internal fun sendSelection(capture: ScreenContext.Capture, stroke: List<Point>?, text: String) {
        if (busy.value != null) return
        busy.value = context.getString(R.string.cap_sending)
        scope.launch {
            try {
                // The selection is read now (from the frozen picture), then the screen comes back at once and the
                // task starts behind it: the capsule shows it as soon as it exists.
                val chip = if (!stroke.isNullOrEmpty()) selected(capture, stroke) else null
                closeSelection()
                chip?.let { container.ui.addContext(it) }
                // With no task in focus, what was selected goes to the default agent (made on first need).
                val focusedTask = container.ui.focusedSession.value?.let { container.sessionStore.get(it) }?.takeIf { !it.state.isTerminal && it.kind == app.umbra.core.session.SessionKind.CODING }
                if (focusedTask != null || !container.settings.value.capsuleToAgent) screen.send(text, null, container.ui.homeProject.value)
                else screen.send(text, null, null, toAgent = container.agents.ensureDefault().id)
                notice.value = null
            } catch (e: Exception) {
                android.util.Log.w("Capsule", "sending a selection failed", e)
                notice.value = e.message ?: e::class.simpleName
                closeSelection()
                expanded.value = true
            } finally {
                busy.value = null
            }
        }
    }

    fun cancelSelection() {
        scope.launch { closeSelection() }
    }

    private fun closeSelection() {
        selectionWindow?.let { w -> w.windowToken?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it, 0) } }
        selectionWindow?.close()
        selectionWindow = null
        selectionFocused.value = false
        selecting = false
        sync()
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

    internal companion object {
        const val PILL_DP = 34
        /** How long news about a finished task stays open in the card when nobody touches it. */
        const val PEEK_MS = 6_000L
        /** At most this wide on each side of the camera. */
        const val ZONE_DP = 104
        /** The card's first row, the one the camera sits in. */
        const val CARD_ROW_DP = 40
    }
}
