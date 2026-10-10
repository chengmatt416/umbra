package app.umbra.platform.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import app.umbra.core.model.Clock
import app.umbra.core.model.Point
import app.umbra.core.model.Rect
import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.action.GlobalAction
import app.umbra.core.model.capability.UiBackendKind
import app.umbra.core.model.observation.NodeAction
import app.umbra.core.model.observation.NodeFlag
import app.umbra.core.model.observation.UiNode
import app.umbra.core.model.observation.UiSnapshot
import app.umbra.core.model.observation.UiTree
import app.umbra.platform.api.AndroidKeys
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.CapturedImage
import app.umbra.platform.api.NodeActionArgs
import app.umbra.platform.api.ScreenshotBackend
import app.umbra.platform.api.SnapshotOptions
import app.umbra.platform.api.UiBackend
import app.umbra.platform.api.UiFeature
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Reads and drives the screen through [UmbraAccessibilityService]: the semantic tree of every window of
 * every display, node actions (click, set text, scroll), touch gestures on any display, global actions,
 * and screenshots of any display (spec §6, §7, §9).
 *
 * Nodes handed out in a [UiSnapshot] are remembered so [performAction] can find the live
 * `AccessibilityNodeInfo` again. Only the latest snapshot is kept: ids from older ones are stale, which
 * the pipeline detects by fingerprint before it acts.
 */
class AccessibilityUiBackend(
    private val displayManager: DisplayManager? = null,
    private val clock: Clock = Clock.System,
    /** True while the service is switched on in the system's settings, whether or not it is bound at this moment. */
    private val enabledInSettings: () -> Boolean = { false },
) : UiBackend, ScreenshotBackend {
    override val name = "accessibility"
    override val kind = UiBackendKind.ACCESSIBILITY

    // Raw key events cannot be injected from an accessibility service; the pipeline falls back to shell input for those.
    override val features = setOf(UiFeature.NODE_ACTIONS, UiFeature.GESTURES, UiFeature.GLOBAL_ACTIONS, UiFeature.MULTI_DISPLAY)

    private val main = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor { Thread(it, "umbra-screenshot").apply { isDaemon = true } }

    @Volatile
    private var registry: Map<String, AccessibilityNodeInfo> = emptyMap()

    private fun service(): UmbraAccessibilityService =
        UmbraAccessibilityService.instance ?: throw BackendException(
            ErrorCode.BACKEND_UNAVAILABLE,
            "The Umbra accessibility service is not running. Enable it in Settings > Accessibility, or let the app enable it with root or Shizuku.",
        )

    @Volatile
    private var lastFailedWaitAtMs = 0L

    /**
     * Whether the service is bound. A service the user switched on is unbound for a moment after the app is
     * restarted or after a UiAutomation run (`uiautomator dump`), which suppresses accessibility services while it
     * lasts. Reaching for UiAutomation then would only unbind it again, so a switched-on service is given a few
     * seconds to come back before it counts as missing; after one wait that fails, the next calls do not wait.
     */
    override suspend fun isAvailable(): Boolean {
        if (UmbraAccessibilityService.instance != null) return true
        if (!enabledInSettings()) return false
        val now = clock.nowMs()
        if (now - lastFailedWaitAtMs < RETRY_AFTER_FAILED_WAIT_MS) return false
        val bound = kotlinx.coroutines.withTimeoutOrNull(REBIND_WAIT_MS) { UmbraAccessibilityService.connected.first { it } } != null
        if (!bound) lastFailedWaitAtMs = clock.nowMs()
        return bound
    }

    // ---- reading ----

    override suspend fun snapshot(options: SnapshotOptions): UiSnapshot = withContext(Dispatchers.Default) {
        val svc = service()
        val windows = svc.windowsOnAllDisplays
        val nodes = HashMap<String, AccessibilityNodeInfo>()
        val trees = ArrayList<UiTree>()
        val now = clock.nowMs()
        for (i in 0 until windows.size()) {
            val displayId = windows.keyAt(i)
            if (options.displayId != null && displayId != options.displayId) continue
            for (window in windows.valueAt(i)) {
                if (window.title?.toString() == UmbraOverlay.WINDOW_TITLE) continue
                val root = window.root ?: continue
                trees += NodeMapper.tree(displayId, window, root, options, now, nodes)
            }
        }
        // A service with no retrievable windows (secure keyguard, display off) still has a focused root.
        if (trees.isEmpty() && (options.displayId == null || options.displayId == Display.DEFAULT_DISPLAY)) {
            svc.rootInActiveWindow?.let { root ->
                trees += NodeMapper.tree(Display.DEFAULT_DISPLAY, null, root, options, now, nodes)
            }
        }
        if (trees.isEmpty()) {
            // Every screen has at least a status bar, so "no windows" means the service cannot see, not that nothing is there.
            val flags = svc.serviceInfo?.flags?.let { "0x" + Integer.toHexString(it) } ?: "?"
            throw BackendException(
                ErrorCode.BACKEND_UNAVAILABLE,
                "The accessibility service sees no windows (displays reported: ${windows.size()}, service flags $flags). " +
                    "The screen may be off or locked, or another accessibility client (UiAutomation) is holding the connection.",
                retryable = true,
            )
        }
        registry = nodes
        UiSnapshot(trees.sortedWith(compareBy<UiTree>({ it.displayId }, { -it.layer })), now)
    }

    // ---- node actions ----

    override suspend fun performAction(node: UiNode, action: NodeAction, args: NodeActionArgs): Boolean = withContext(Dispatchers.Default) {
        val info = registry[node.id] ?: throw BackendException(ErrorCode.STALE_ELEMENT, "node ${node.id} is not in the latest snapshot", retryable = true)
        if (!info.refresh()) throw BackendException(ErrorCode.STALE_ELEMENT, "node ${node.id} no longer exists", retryable = true)
        val id = NodeMapper.actionId(action) ?: throw BackendException(ErrorCode.UNSUPPORTED, "action $action has no accessibility equivalent")
        val bundle = Bundle().apply {
            when (action) {
                NodeAction.SET_TEXT -> putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, args.text.orEmpty())
                NodeAction.SET_SELECTION -> {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, args.selectionStart ?: 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, args.selectionEnd ?: 0)
                }
                else -> Unit
            }
        }
        info.performAction(id, bundle)
    }

    // ---- gestures ----

    private fun bounds(displayId: Int?): Rect {
        val dm = displayManager ?: return Rect(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)
        val display = dm.getDisplay(displayId ?: Display.DEFAULT_DISPLAY) ?: throw BackendException(ErrorCode.NOT_FOUND, "no display $displayId")
        val size = android.graphics.Point().also { display.getRealSize(it) }
        return Rect(0, 0, size.x, size.y)
    }

    private fun clamp(p: Point, b: Rect) = Point(p.x.coerceIn(0, b.right - 1), p.y.coerceIn(0, b.bottom - 1))

    private fun path(vararg points: Point) = Path().apply {
        moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        if (points.size == 1) lineTo(points[0].x.toFloat(), points[0].y.toFloat()) else for (p in points.drop(1)) lineTo(p.x.toFloat(), p.y.toFloat())
    }

    private suspend fun dispatch(displayId: Int?, stroke: GestureDescription.StrokeDescription): Boolean {
        val svc = service()
        return suspendCancellableCoroutine { cont ->
            val builder = GestureDescription.Builder().addStroke(stroke)
            if (displayId != null) builder.setDisplayId(displayId)
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }
            if (!svc.dispatchGesture(builder.build(), callback, main)) cont.resume(false)
        }
    }

    private suspend fun gesture(displayId: Int?, what: String, stroke: GestureDescription.StrokeDescription) {
        if (!dispatch(displayId, stroke)) {
            throw BackendException(ErrorCode.NOT_ACTIONABLE, "The system rejected or cancelled the $what gesture (a secure screen or another gesture in progress).", retryable = true)
        }
    }

    override suspend fun tap(displayId: Int?, point: Point) {
        val p = clamp(point, bounds(displayId))
        gesture(displayId, "tap", GestureDescription.StrokeDescription(path(p), 0, 50))
    }

    override suspend fun longPress(displayId: Int?, point: Point, durationMs: Long) {
        val p = clamp(point, bounds(displayId))
        gesture(displayId, "long press", GestureDescription.StrokeDescription(path(p), 0, durationMs.coerceAtLeast(ViewConfigurationMin)))
    }

    override suspend fun swipe(displayId: Int?, from: Point, to: Point, durationMs: Long) {
        val b = bounds(displayId)
        gesture(displayId, "swipe", GestureDescription.StrokeDescription(path(clamp(from, b), clamp(to, b)), 0, durationMs.coerceAtLeast(1)))
    }

    override suspend fun drag(displayId: Int?, from: Point, to: Point, holdMs: Long, durationMs: Long) {
        val b = bounds(displayId)
        val a = clamp(from, b)
        val z = clamp(to, b)
        // Press and hold first (the stroke continues), then move: two dispatches that form one touch.
        val hold = GestureDescription.StrokeDescription(path(a), 0, holdMs.coerceAtLeast(1), true)
        if (!dispatch(displayId, hold)) throw BackendException(ErrorCode.NOT_ACTIONABLE, "The system rejected the drag gesture.", retryable = true)
        val move = hold.continueStroke(path(a, z), 0, durationMs.coerceAtLeast(1), false)
        gesture(displayId, "drag", move)
    }

    override suspend fun key(displayId: Int?, key: String) {
        val global = AndroidKeys.asGlobalAction(key)
        if (global != null && displayId.let { it == null || it == Display.DEFAULT_DISPLAY } && global(global)) return
        throw BackendException(ErrorCode.UNSUPPORTED, "An accessibility service cannot inject the $key key; use shell input.")
    }

    override suspend fun typeText(displayId: Int?, text: String) {
        val svc = service()
        val focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: throw BackendException(ErrorCode.NOT_ACTIONABLE, "No text field has input focus.", retryable = true)
        val current = focused.text?.takeIf { !focused.isShowingHintText }?.toString().orEmpty()
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text) }
        if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw BackendException(ErrorCode.NOT_ACTIONABLE, "The focused field refused the text.", retryable = true)
        }
    }

    override suspend fun global(action: GlobalAction): Boolean = withContext(Dispatchers.Main) {
        val svc = service()
        svc.performGlobalAction(
            when (action) {
                GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
                GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                GlobalAction.POWER_DIALOG -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                GlobalAction.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                GlobalAction.TAKE_SCREENSHOT -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            },
        )
    }

    // ---- screenshots ----

    override suspend fun capture(displayId: Int?): CapturedImage {
        val svc = service()
        repeat(4) { attempt ->
            val result = suspendCancellableCoroutine<Result<CapturedImage>> { cont ->
                svc.takeScreenshot(
                    displayId ?: Display.DEFAULT_DISPLAY, screenshotExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val buffer = screenshot.hardwareBuffer
                            try {
                                val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                    ?: return cont.resume(Result.failure(BackendException(ErrorCode.INTERNAL, "screenshot buffer could not be wrapped")))
                                val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
                                val out = ByteArrayOutputStream()
                                software.compress(Bitmap.CompressFormat.PNG, 100, out)
                                cont.resume(Result.success(CapturedImage(out.toByteArray(), "image/png", software.width, software.height)))
                            } finally {
                                buffer.close()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            val failure = when (errorCode) {
                                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> BackendException(ErrorCode.TIMEOUT, "screenshots are rate limited", retryable = true)
                                AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> BackendException(ErrorCode.NOT_FOUND, "no display $displayId")
                                AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> BackendException(ErrorCode.PERMISSION_DENIED, "the screen is secure and cannot be captured")
                                AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> BackendException(ErrorCode.PERMISSION_DENIED, "the service may not take screenshots")
                                else -> BackendException(ErrorCode.INTERNAL, "screenshot failed with code $errorCode", retryable = true)
                            }
                            cont.resume(Result.failure(failure))
                        }
                    },
                )
            }
            val value = result.getOrNull()
            if (value != null) return value
            val error = result.exceptionOrNull() as BackendException
            // The system allows about three screenshots a second.
            if (error.retryable && error.code == ErrorCode.TIMEOUT && attempt < 3) delay(400) else throw error
        }
        throw BackendException(ErrorCode.TIMEOUT, "screenshot rate limit did not clear", retryable = true)
    }

    private companion object {
        const val ViewConfigurationMin = 400L
        const val REBIND_WAIT_MS = 8_000L
        const val RETRY_AFTER_FAILED_WAIT_MS = 30_000L
    }
}

/** Translates between `AccessibilityNodeInfo` and the platform-independent [UiNode]. */
internal object NodeMapper {
    private val ACTIONS: List<Pair<Int, NodeAction>> by lazy {
        listOf(
            AccessibilityNodeInfo.ACTION_CLICK to NodeAction.CLICK,
            AccessibilityNodeInfo.ACTION_LONG_CLICK to NodeAction.LONG_CLICK,
            AccessibilityNodeInfo.ACTION_FOCUS to NodeAction.FOCUS,
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS to NodeAction.CLEAR_FOCUS,
            AccessibilityNodeInfo.ACTION_SELECT to NodeAction.SELECT,
            AccessibilityNodeInfo.ACTION_SET_TEXT to NodeAction.SET_TEXT,
            AccessibilityNodeInfo.ACTION_SET_SELECTION to NodeAction.SET_SELECTION,
            AccessibilityNodeInfo.ACTION_COPY to NodeAction.COPY,
            AccessibilityNodeInfo.ACTION_CUT to NodeAction.CUT,
            AccessibilityNodeInfo.ACTION_PASTE to NodeAction.PASTE,
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD to NodeAction.SCROLL_FORWARD,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD to NodeAction.SCROLL_BACKWARD,
            AccessibilityNodeInfo.ACTION_EXPAND to NodeAction.EXPAND,
            AccessibilityNodeInfo.ACTION_COLLAPSE to NodeAction.COLLAPSE,
            AccessibilityNodeInfo.ACTION_DISMISS to NodeAction.DISMISS,
            AccessibilityAction.ACTION_SCROLL_UP.id to NodeAction.SCROLL_UP,
            AccessibilityAction.ACTION_SCROLL_DOWN.id to NodeAction.SCROLL_DOWN,
            AccessibilityAction.ACTION_SCROLL_LEFT.id to NodeAction.SCROLL_LEFT,
            AccessibilityAction.ACTION_SCROLL_RIGHT.id to NodeAction.SCROLL_RIGHT,
            AccessibilityAction.ACTION_SHOW_ON_SCREEN.id to NodeAction.SHOW_ON_SCREEN,
        )
    }

    fun actionId(action: NodeAction): Int? = ACTIONS.firstOrNull { it.second == action }?.first

    /** Pre-order tree of one window; every node is also stored in [registry] under its id for later actions. */
    fun tree(
        displayId: Int,
        window: android.view.accessibility.AccessibilityWindowInfo?,
        root: AccessibilityNodeInfo,
        options: SnapshotOptions,
        nowMs: Long,
        registry: MutableMap<String, AccessibilityNodeInfo>,
    ): UiTree {
        val windowId = window?.id ?: -1
        val out = ArrayList<UiNode>()
        var truncated = false
        // Iterative depth-first walk: (node, id, depth, parentId). Deep trees must not overflow the stack.
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, "$windowId:0", 0, null))
        val deadline = System.currentTimeMillis() + WALK_BUDGET_MS
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (out.size >= options.maxNodes || System.currentTimeMillis() > deadline) { truncated = true; break }
            val info = f.info
            registry[f.id] = info
            val visible = info.isVisibleToUser
            if (visible || options.includeInvisible) out += toNode(info, f.id, f.depth, f.parentId)
            // Nothing under a node the user cannot see is visible either (the window root excepted).
            if (!visible && !options.includeInvisible && f.depth > 0) continue
            // Push children in reverse so they pop in order.
            for (i in info.childCount - 1 downTo 0) {
                val child = info.getChild(i) ?: continue
                stack.addLast(Frame(child, "${f.id}.$i", f.depth + 1, f.id))
            }
        }
        // Child counts must describe the emitted tree, so recount from parent links.
        val counts = HashMap<String, Int>()
        out.forEach { n -> n.parentId?.let { counts.merge(it, 1, Int::plus) } }
        val nodes = out.map { it.copy(childCount = counts[it.id] ?: 0) }
        return UiTree(
            displayId = displayId, windowId = windowId, packageName = root.packageName?.toString(),
            title = window?.title?.toString(), layer = window?.layer ?: 0,
            isActive = window?.isActive ?: true, isFocused = window?.isFocused ?: true,
            nodes = nodes, capturedAtMs = nowMs, truncated = truncated, source = "accessibility",
        )
    }

    private class Frame(val info: AccessibilityNodeInfo, val id: String, val depth: Int, val parentId: String?)

    private fun toNode(n: AccessibilityNodeInfo, id: String, depth: Int, parentId: String?): UiNode {
        val bounds = android.graphics.Rect().also(n::getBoundsInScreen)
        val flags = buildSet {
            if (n.isVisibleToUser) add(NodeFlag.VISIBLE)
            if (n.isEnabled) add(NodeFlag.ENABLED)
            if (n.isClickable) add(NodeFlag.CLICKABLE)
            if (n.isLongClickable) add(NodeFlag.LONG_CLICKABLE)
            if (n.isFocusable) add(NodeFlag.FOCUSABLE)
            if (n.isFocused) add(NodeFlag.FOCUSED)
            if (n.isEditable) add(NodeFlag.EDITABLE)
            if (n.isScrollable) add(NodeFlag.SCROLLABLE)
            if (n.isCheckable) add(NodeFlag.CHECKABLE)
            if (n.isChecked) add(NodeFlag.CHECKED)
            if (n.isSelected) add(NodeFlag.SELECTED)
            if (n.isPassword) add(NodeFlag.PASSWORD)
            if (n.isHeading) add(NodeFlag.HEADING)
        }
        val advertised = n.actionList.map { it.id }.toSet()
        val actions = ACTIONS.filter { it.first in advertised }.map { it.second }.toSet()
        return UiNode(
            id = id, depth = depth, parentId = parentId,
            className = n.className?.toString(), packageName = n.packageName?.toString(),
            resourceId = n.viewIdResourceName,
            text = if (n.isPassword) n.text?.toString()?.let { "•".repeat(it.length) } else n.text?.toString()?.takeIf { !n.isShowingHintText },
            contentDescription = n.contentDescription?.toString(),
            hint = n.hintText?.toString(),
            stateDescription = n.stateDescription?.toString(),
            bounds = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom),
            flags = flags, actions = actions,
        )
    }

    private const val WALK_BUDGET_MS = 6_000L
}
