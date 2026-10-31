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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import app.umbra.agent.AppContainer
import app.umbra.agent.MainActivity
import app.umbra.core.model.Point
import app.umbra.platform.android.UmbraAccessibilityService
import app.umbra.platform.android.UmbraOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.awaitClose
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
    /** The card is folding back into the pill: its window stays until the card says it is done (or a moment passes). */
    internal val collapsing = MutableStateFlow(false)
    /** The pill's place on the screen (its layout bounds), where the card grows from and folds back into. */
    internal val pillBounds = MutableStateFlow<AndroidRect?>(null)
    /** The card's growing shape covers the pill: the pill draws nothing (and the card's window takes the touches). */
    internal val pillHidden = MutableStateFlow(false)
    /** Another capsule app is expanded over the camera: Umbra's pill fades out of its way and takes no touches. */
    internal val yielding = MutableStateFlow(false)
    /**
     * Which side of the pill's window stays put ([Gravity.START], [Gravity.END] or [Gravity.CENTER_HORIZONTAL]): the
     * pill keeps to it while its window is wider than the pill for a moment (see SteadyPill).
     */
    internal val edge = MutableStateFlow(Gravity.CENTER_HORIZONTAL)
    /** The other capsule app Umbra is making room for (Material Capsule, ...), or null. */
    val neighbourName = MutableStateFlow<String?>(null)
    /** In the top corner instead of around the camera (see [CapsuleNeighbours]). */
    @Volatile private var corner = false
    /** A plain pill centred in the status-bar row, independent of the camera (see [CapsuleNeighbours.centre]). */
    @Volatile private var centre = false
    /** The CUSTOM anchor the capsule was last placed for. */
    @Volatile private var placedAnchor: app.umbra.agent.data.CapsuleAnchor? = null

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
    internal val isStarted: Boolean get() = job?.isActive == true
    @Volatile internal var projectedSuggestions: Int = -1
    @Volatile internal var suggestionExpirySignals: Long = 0
    @Volatile internal var debugTapPoint: Point? = null
    internal val debugSuggestionTargets = java.util.concurrent.ConcurrentHashMap<String, Point>()
    internal fun noteDebugTarget(id: String?, point: Point) {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            if (id == null) debugTapPoint = point else debugSuggestionTargets[id] = point
        }
    }
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
                container.capsuleSignals.revision.collect { enabled = container.settings.value.overlayEnabled; sync() }
            }
            // A decision peeks in the capsule by itself, tucked or not; nothing switches the workspace (§3.1).
            launch {
                var seen = emptySet<String>()
                container.capsuleSignals.read(container.approvals.pending).collect { list ->
                    val ids = list.map { it.id }.toSet()
                    if ((ids - seen).isNotEmpty()) tucked.value = false
                    if ((ids - seen).isNotEmpty() && capsuleWindow != null && !container.ui.appVisible.value) expanded.value = true
                    seen = ids
                }
            }
            // News peeks too: a question waits open; a task that finished or failed shows for a moment.
            launch {
                var seen = emptySet<String>()
                container.capsuleSignals.read(container.questions.pending).collect { list ->
                    val ids = list.map { "${it.sessionId}:${it.askedAtMs}" }.toSet()
                    list.lastOrNull { "${it.sessionId}:${it.askedAtMs}" !in seen }?.let { container.ui.focus(it.sessionId); peek(hold = true) }
                    seen = ids
                }
            }
            launch {
                val last = HashMap<String, app.umbra.core.session.SessionState>()
                container.sessionStore.list().forEach { last[it.id] = it.state }
                container.capsuleSignals.revision.collect {
                    container.sessionStore.list().forEach { session ->
                        val before = last.put(session.id, session.state)
                        val ended = session.state == app.umbra.core.session.SessionState.COMPLETED || session.state == app.umbra.core.session.SessionState.FAILED
                        if (before != null && before != session.state && ended &&
                            session.kind != app.umbra.core.session.SessionKind.AGENT && !container.ui.appVisible.value) {
                            container.ui.focus(session.id)
                            peek(hold = false)
                        }
                    }
                }
            }
            // The card has a window of its own, laid out once at its full size, above the pill's: it grows out of the
            // pill and folds back into it while the pill stays drawn underneath until covered, so no frame is ever empty
            // and no window is resized or moved while anything animates (that is what made the expansion flicker).
            launch {
                expanded.collectLatest { open ->
                    if (open) { collapsing.value = false; openCard() }
                    else {
                        focusCard(false)
                        if (cardWindow != null) {
                            collapsing.value = true
                            kotlinx.coroutines.withTimeoutOrNull(COLLAPSE_MS) { collapsing.first { !it } }
                            collapsing.value = false
                            pillHidden.value = false
                            // The pill draws again under the folded card before the card's window goes.
                            delay(HANDOFF_MS)
                        }
                        closeCard()
                    }
                }
            }
            launch { neighbours() }
            launch { tucked.collect { place() } }
            launch { container.capsuleSignals.read(container.ui.appVisible).collect { visible -> if (visible) expanded.value = false; sync() } }
        }
    }

    /** Starts a screen recording: the card folds away so it is not in the picture, the capsule becomes its stop button. */
    fun startRecording() {
        if (container.demonstrations.active.value != null) return
        expanded.value = false
        container.recorder.request()
    }

    fun startTeaching(agentId: String) {
        expanded.value = false
        container.scope.launch {
            try { container.demonstrations.start(agentId) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { android.widget.Toast.makeText(context, e.message ?: "Teaching could not start", android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    /** A recording became context: the card opens with it and its field, for what to do with it. */
    fun showRecording() {
        tucked.value = false
        if (capsuleWindow == null || container.ui.appVisible.value) return
        peekJob?.cancel()
        expanded.value = true
    }

    private var peekJob: Job? = null
    private var replyJob: Job? = null

    /**
     * After the Capsule asked an agent: the agent's next finished reply (not a quiet turn) opens the card for a moment,
     * where it shows the agent's latest words, the way a finished task peeks. Waits at most ten minutes.
     */
    internal fun awaitAgentReply(agentId: String) {
        val sid = container.agents.store.get(agentId)?.sessionId ?: return
        val since = System.currentTimeMillis()
        replyJob?.cancel()
        replyJob = scope.launch {
            val flow = runCatching { container.supervisor.conversation(sid) }.getOrNull() ?: return@launch
            withTimeoutOrNull(10 * 60_000L) {
                flow.first { state ->
                    state.items.any { it is app.umbra.core.orchestrator.ChatItem.Assistant && it.timestamp >= since && !it.streaming &&
                        it.text.isNotBlank() && !it.text.startsWith(app.umbra.agent.agents.AgentRuntime.QUIET) }
                }
            } ?: return@launch
            if (!container.ui.appVisible.value) { feel(CapsuleWatch.Signal.DONE); peek(hold = false) }
        }
    }

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

    /**
     * Keeps [neighbourName], [corner] and [yielding] current: from the enabled accessibility services (another capsule
     * app's) and from the windows over the camera (with Umbra's own service on). Re-places the capsule when it moves.
     */
    private suspend fun neighbours() {
        val servicesChanged = kotlinx.coroutines.flow.callbackFlow {
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
            trySend(Unit)
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }
        val placement = container.settings.flow.map { it.capsulePlacement to it.capsuleAnchor }.distinctUntilChanged()
        kotlinx.coroutines.flow.combine(servicesChanged, UmbraAccessibilityService.topOverlays, placement, expanded) { _, overlays, chosen, open -> Triple(overlays, chosen, open) }
            .collect { (overlays, chosenAndAnchor, open) ->
                val chosen = chosenAndAnchor.first
                val pm = context.packageManager
                val services = runCatching {
                    CapsuleNeighbours.services(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), context.packageName) { pkg ->
                        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
                    }
                }.getOrDefault(emptyList())
                val wm = (UmbraAccessibilityService.instance ?: context).getSystemService(WindowManager::class.java)
                val metrics = wm.currentWindowMetrics
                val statusTop = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
                val island = CapsuleNeighbours.island(overlays, metrics.bounds.width(), statusTop, camera(metrics)?.centerX())
                val windowOwner = overlays.firstNotNullOfOrNull { o -> o.packageName?.let { pkg -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull() } }
                neighbourName.value = services.firstOrNull()?.second ?: windowOwner.takeIf { island != CapsuleNeighbours.Island.NONE }
                val nextCorner = CapsuleNeighbours.corner(chosen, neighbourName.value != null)
                val yield = island == CapsuleNeighbours.Island.EXPANDED && !open
                if (yield != yielding.value) {
                    yielding.value = yield
                    capsuleWindow?.update { p ->
                        p.flags = if (yield) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    }
                }
                val nextCentre = CapsuleNeighbours.centre(chosen, neighbourName.value != null)
                val nextAnchor = chosenAndAnchor.second.takeIf { chosen == app.umbra.agent.data.CapsulePlacement.CUSTOM }
                if (nextCorner != corner || nextCentre != centre || nextAnchor != placedAnchor) { corner = nextCorner; centre = nextCentre; placedAnchor = nextAnchor; place() }
            }
    }

    fun stop() {
        projectedSuggestions = -1
        debugTapPoint = null
        debugSuggestionTargets.clear()
        peekJob?.cancel()
        peekJob = null
        job?.cancel()
        job = null
        watch.stop()
        runCatching { context.unregisterComponentCallbacks(rotation) }
        closeCapsule()
        closeComposer()
        selectionWindow?.close(); selectionWindow = null
        previewWindow?.close(); previewWindow = null
        selecting = false
    }

    /**
     * Without the accessibility service the capsule can only sit below the status bar, where it would cover the app
     * bar of the app in front; over Umbra itself that is Umbra's own header, which already shows all it would.
     */
    private fun canShow() = enabled && (UmbraAccessibilityService.connected.value || Settings.canDrawOverlays(context)) &&
        !(container.ui.appVisible.value && UmbraAccessibilityService.instance == null)

    /** While Umbra's accessibility service is away for a moment: the capsule waits for it instead of being replaced. */
    private var serviceGrace: Job? = null
    private var graceOver = false
    /** The service connection the capsule's window belongs to: Android removes a service's windows with its connection. */
    private var capsuleHost: UmbraAccessibilityService? = null

    /** Creates, moves to the right window type, or removes the capsule window to match the settings and the services. */
    private fun sync() {
        val service = UmbraAccessibilityService.instance
        // The service drops out for a moment now and then (Android rebinds it, a dump suspends it): replacing the capsule
        // with an ordinary overlay and then back flashed it twice. A capsule on the service waits up to SERVICE_GRACE_MS
        // for it; a service that comes back is a new connection, which gets a new window (the old one went with it).
        if (service != null) { serviceGrace?.cancel(); serviceGrace = null; graceOver = false }
        else if (capsuleWindow != null && capsuleType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY && enabled && !graceOver) {
            if (serviceGrace?.isActive != true) serviceGrace = scope.launch { delay(SERVICE_GRACE_MS); graceOver = true; sync() }
            return
        }
        if (!canShow()) { closeCapsule(); closeComposer(); return }
        if (selecting) return
        val type = if (service != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        if (capsuleWindow != null && capsuleType == type && service === capsuleHost) return
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
        capsuleHost = service
        capsuleWindow = runCatching {
            OverlayWindow(
                wm, params, host,
                // A touch on the card's window is outside this one too: only the card's window decides about those.
                onOutside = { if (expanded.value && !cardFocused.value && cardWindow == null) expanded.value = false },
                onBack = { if (cardFocused.value) focusCard(false) else expanded.value = false; true },
            ) { CapsuleView(this, container) }
        }.onFailure { notice.value = context.getString(R.string.cap_notice_capsule, it.message.orEmpty()) }.getOrNull()
        place()
    }

    /**
     * The camera hole: where the user placed it (Size and position, on this screen size), else as the screen reports it
     * (the exact path within the top cutout's rectangle), or null.
     */
    private fun camera(metrics: android.view.WindowMetrics): AndroidRect? {
        custom(metrics)?.let { return it }
        val cutout = metrics.windowInsets.displayCutout
        val top = cutout?.boundingRectTop?.takeIf { !it.isEmpty } ?: return null
        return cutout.cutoutPath?.let { path ->
            val f = RectF().also { path.computeBounds(it, true) }
            AndroidRect(floor(f.left).toInt(), floor(f.top).toInt(), ceil(f.right).toInt(), ceil(f.bottom).toInt()).takeIf { it.intersect(top) }
        } ?: top
    }

    /** The user's own camera rectangle (CUSTOM placement, or the one being edited), when it was set on this screen size. */
    private fun custom(metrics: android.view.WindowMetrics): AndroidRect? {
        draftAnchor?.let { return it.on(metrics.bounds.width(), metrics.bounds.height()) }
        val prefs = container.settings.value
        if (prefs.capsulePlacement != app.umbra.agent.data.CapsulePlacement.CUSTOM) return null
        return prefs.capsuleAnchor?.on(metrics.bounds.width(), metrics.bounds.height())
    }

    /** Size and position is open: the anchor being edited, which the capsule follows live. */
    @Volatile private var draftAnchor: app.umbra.agent.data.CapsuleAnchor? = null
    private var previewWindow: OverlayWindow? = null

    /**
     * Size and position: the capsule wraps [anchor] as the user moves it, and a red circle marks it exactly; when the
     * circle disappears behind the camera, the capsule fits. Null ends the preview (main thread).
     */
    fun previewAnchor(anchor: app.umbra.agent.data.CapsuleAnchor?) {
        draftAnchor = anchor
        place()
        if (anchor == null) { previewWindow?.close(); previewWindow = null; return }
        val service = UmbraAccessibilityService.instance
        val type = if (service != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        previewWindow?.let { window ->
            if (window.params.type == type) {
                window.update { p -> p.x = anchor.x; p.y = anchor.y; p.width = anchor.width; p.height = anchor.height }
                return
            }
            window.close()
            previewWindow = null
        }
        if (service == null && !Settings.canDrawOverlays(context)) return
        val host: Context = service ?: context
        val params = WindowManager.LayoutParams(
            anchor.width, anchor.height, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchor.x
            y = anchor.y
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = UmbraOverlay.WINDOW_TITLE
        }
        previewWindow = runCatching {
            OverlayWindow(host.getSystemService(WindowManager::class.java), params, host) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0xFFFF5F57)),
                )
            }
        }.getOrNull()
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
        // Over the status bar only with the accessibility window: an ordinary overlay is drawn under the status bar's own
        // window, which takes every touch in that band, so there it would show but never answer a tap. Without the
        // service a placement set by hand keeps its column (centred on the camera), just below the status bar.
        val overStatusBar = capsuleType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        if (centre) {
            // The user's choice: a plain pill in the middle of the status-bar row, between the clock and the icons; the
            // card unfolds from it. Over the status bar only with the accessibility window, else just below it.
            hole.value = null
            val pill = dp(PILL_DP)
            val rowY = if (overStatusBar) (maxOf(statusTop, pill) - pill) / 2 else statusTop + dp(4)
            window.update { p ->
                p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                p.x = 0
                p.y = rowY
                p.width = WindowManager.LayoutParams.WRAP_CONTENT
            }
            edge.value = Gravity.CENTER_HORIZONTAL
            cardY = rowY
            return
        }
        if (corner) {
            // Beside another capsule app: a plain pill just under the status bar, at the end away from the thumb's menu.
            hole.value = null
            window.update { p ->
                p.y = statusTop + dp(6)
                p.gravity = Gravity.TOP or (if (container.settings.value.leftHanded) Gravity.START else Gravity.END); p.width = WindowManager.LayoutParams.WRAP_CONTENT; p.x = dp(10)
            }
            edge.value = if (container.settings.value.leftHanded) Gravity.START else Gravity.END
            cardY = statusTop + dp(6)
            return
        }
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
        // The card's first row is the camera's row: the camera sits in the card's top edge, between its controls.
        cardY = if (overStatusBar && camera != null) (camera.centerY() - dp(CARD_ROW_DP) / 2 - dp(10)).coerceAtLeast(0) else y
        window.update { p ->
            when {
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
        edge.value = when {
            wrap != null && camera != null && !wrap.centered -> if (wrap.contentLeft) Gravity.END else Gravity.START
            else -> Gravity.CENTER_HORIZONTAL
        }
    }

    /** The card's field takes the keyboard only while the user types in it; otherwise the app keeps focus (§3.1). */
    fun focusCard(on: Boolean) {
        val window = cardWindow ?: capsuleWindow ?: return
        if (cardFocused.value == on) return
        cardFocused.value = on
        window.update { p ->
            p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (!on) window.windowToken?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it, 0) }
    }

    /** Tapped: expand (a tucked capsule comes back first). */
    fun tap() {
        if (container.demonstrations.active.value != null) { container.scope.launch { container.demonstrations.stop() }; return }
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

    internal fun haptic(feedback: Int) = (cardWindow ?: capsuleWindow)?.haptic(feedback)

    /** Where the card's window starts (the camera's row, or the pill's row in the corner and centre placements). */
    @Volatile private var cardY = 0
    private var cardWindow: OverlayWindow? = null

    /**
     * Opens the card's window above the pill's, at its full size from the first frame: as wide as the screen less
     * 4 dp each side, from the camera's row to the bottom. Its empty part is the outside: a tap there folds the card.
     */
    private fun openCard() {
        val pill = capsuleWindow ?: return
        if (cardWindow != null) return
        place()
        val host: Context = UmbraAccessibilityService.instance?.takeIf { capsuleType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY } ?: context
        val wm = host.getSystemService(WindowManager::class.java)
        val bounds = wm.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(
            bounds.width() - dp(8), (bounds.height() - cardY).coerceAtLeast(dp(200)), capsuleType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = cardY
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            title = UmbraOverlay.WINDOW_TITLE
        }
        cardWindow = runCatching {
            OverlayWindow(
                wm, params, host,
                onOutside = { if (expanded.value && !cardFocused.value) expanded.value = false },
                onBack = { if (cardFocused.value) focusCard(false) else expanded.value = false; true },
            ) { CapsuleView(this, container, card = true) }
        }.onFailure { notice.value = context.getString(R.string.cap_notice_capsule, it.message.orEmpty()) }.getOrNull()
        if (cardWindow == null) { expanded.value = false; return }
        // The card's window is on top now; the pill under it takes no touches until it is back.
        pill.update { p -> p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE }
    }

    private fun closeCard() {
        val window = cardWindow ?: return
        cardWindow = null
        cardFocused.value = false
        window.windowToken?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it, 0) }
        window.close()
        pillHidden.value = false
        capsuleWindow?.update { p -> if (!yielding.value) p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() }
    }

    private fun closeCapsule() {
        closeCard()
        capsuleWindow?.close()
        capsuleWindow = null
        projectedSuggestions = -1
        debugTapPoint = null
        debugSuggestionTargets.clear()
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
        /** The longest the card's window waits for its fold-away animation before it goes anyway. */
        const val COLLAPSE_MS = 420L
        /** About three frames: the pill is drawn again before the card's window that covered it goes. */
        const val HANDOFF_MS = 50L
        /** How long a capsule on the accessibility service waits for the service to come back before it moves. */
        const val SERVICE_GRACE_MS = 1_500L
        /** At most this wide on each side of the camera. */
        const val ZONE_DP = 104
        /** The card's first row, the one the camera sits in. */
        const val CARD_ROW_DP = 40
    }
}
