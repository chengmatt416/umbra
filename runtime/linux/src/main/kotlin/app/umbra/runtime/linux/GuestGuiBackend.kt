package app.umbra.runtime.linux

import app.umbra.core.model.Point
import app.umbra.core.model.Rect
import app.umbra.core.model.action.Direction
import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.action.GlobalAction
import app.umbra.core.model.action.WindowAction
import app.umbra.core.model.capability.UiBackendKind
import app.umbra.core.model.observation.GuiWindowInfo
import app.umbra.core.model.observation.NodeAction
import app.umbra.core.model.observation.NodeFlag
import app.umbra.core.model.observation.UiNode
import app.umbra.core.model.observation.UiSnapshot
import app.umbra.core.model.observation.UiTree
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.CapturedImage
import app.umbra.platform.api.LinuxGuiBackend
import app.umbra.platform.api.NodeActionArgs
import app.umbra.platform.api.SnapshotOptions
import app.umbra.platform.api.UiFeature
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A graphical Linux session in the guest (spec §12, §13), driven by `umbra-gui` (guest/bin): a private
 * Xvfb with a window manager, AT-SPI for the semantic tree, xdotool for input and ImageMagick for
 * pictures. Every call is one short process in the guest, run as the app user.
 *
 * Node ids are `<windowId>:<AT-SPI path>`; the window id is the index of the window on the
 * accessibility bus, so an id is valid until the next snapshot (the same rule as on Android).
 */
class GuestGuiBackend(
    private val runtime: LinuxRuntime,
    private val size: String = "1280x800",
    private val display: Int = 99,
) : LinuxGuiBackend {
    override val name = "linux_gui_atspi"
    override val kind = UiBackendKind.ATSPI
    override val features = setOf(UiFeature.NODE_ACTIONS, UiFeature.GESTURES, UiFeature.KEY_EVENTS, UiFeature.TEXT_INJECTION)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Runs one `umbra-gui` command and returns its JSON answer; a refusal becomes a [BackendException].
     * The desktop session starts itself the first time something needs it.
     */
    private suspend fun call(vararg args: String, timeoutMs: Long = 40_000): JsonObject {
        val command = args.firstOrNull()
        if (command != "start" && command != "stop" && command != "status" && !sessionKnownUp) {
            val status = rawCall("status", timeoutMs = 20_000)
            if ((status["running"] as? JsonPrimitive)?.content != "true") rawCall("start", "--size", size, "--display", display.toString(), timeoutMs = 90_000)
            sessionKnownUp = true
        }
        return try {
            rawCall(*args, timeoutMs = timeoutMs)
        } catch (e: BackendException) {
            // The session died since the last call (the X server crashed, the guest was restarted): start it again once.
            if (command == "start" || command == "stop" || command == "status" || !e.message.orEmpty().contains("no graphical session")) throw e
            sessionKnownUp = false
            rawCall("start", "--size", size, "--display", display.toString(), timeoutMs = 90_000)
            sessionKnownUp = true
            rawCall(*args, timeoutMs = timeoutMs)
        }
    }

    @Volatile
    private var sessionKnownUp = false

    private suspend fun rawCall(vararg args: String, timeoutMs: Long): JsonObject {
        val result = runtime.exec(listOf("umbra-gui", *args), timeoutMs = timeoutMs, maxOutputBytes = 16 * 1024 * 1024, cwd = RuntimeLayout.GUEST_HOME)
        if (result.timedOut) throw BackendException(ErrorCode.TIMEOUT, "umbra-gui ${args.firstOrNull()} timed out", retryable = true)
        val line = result.stdout.lineSequence().lastOrNull { it.startsWith("{") }
            ?: throw BackendException(ErrorCode.BACKEND_UNAVAILABLE, "umbra-gui gave no answer: ${result.combined.take(300).ifBlank { "exit ${result.exitCode}" }}")
        val obj = json.parseToJsonElement(line).jsonObject
        if ((obj["ok"] as? JsonPrimitive)?.content != "true") {
            val message = (obj["error"] as? JsonPrimitive)?.content ?: "failed"
            throw BackendException(
                when {
                    message == "stale_element" -> ErrorCode.STALE_ELEMENT
                    message.contains("no graphical session") -> ErrorCode.BACKEND_UNAVAILABLE
                    message.contains("not installed") -> ErrorCode.UNSUPPORTED
                    else -> ErrorCode.COMMAND_FAILED
                },
                message,
                retryable = message == "stale_element",
            )
        }
        return obj
    }

    @Volatile
    private var usableUntilMs = 0L

    /**
     * Whether a desktop can be used: the Linux environment is up and the `gui` pack is in it. The session itself
     * starts the first time something needs it, so a desktop that is not running yet still counts. The answer is
     * kept for a few seconds because the pipeline asks before every operation.
     */
    override suspend fun isAvailable(): Boolean {
        if (System.currentTimeMillis() < usableUntilMs) return true
        val usable = try {
            runtime.isMounted() && runtime.exec(listOf("test", "-x", "/usr/bin/Xvfb"), timeoutMs = 15_000).ok
        } catch (_: BackendException) {
            false
        }
        if (usable) usableUntilMs = System.currentTimeMillis() + USABLE_CACHE_MS
        return usable
    }

    /** Starts the desktop session if it is not running (installing nothing: the `gui` pack must already be in the guest). */
    suspend fun start(): Boolean {
        runtime.mount()
        val ok = (call("start", "--size", size, "--display", display.toString(), timeoutMs = 90_000)["ok"] as? JsonPrimitive)?.content == "true"
        sessionKnownUp = ok
        return ok
    }

    suspend fun stop() {
        sessionKnownUp = false
        call("stop", timeoutMs = 20_000)
    }

    // ---- reading ----

    override suspend fun snapshot(options: SnapshotOptions): UiSnapshot {
        val answer = call("tree", "--max", options.maxNodes.coerceAtMost(1_500).toString(), timeoutMs = 60_000)
        val now = System.currentTimeMillis()
        val windows = (answer["windows"] as? JsonArray).orEmpty().mapNotNull { element ->
            val w = element as? JsonObject ?: return@mapNotNull null
            val rawId = str(w, "id") ?: return@mapNotNull null
            val (appIndex, windowIndex) = rawId.split('.').map { it.toInt() }
            val windowId = appIndex * 100 + windowIndex
            val nodes = (w["nodes"] as? JsonArray).orEmpty().mapNotNull { n -> (n as? JsonObject)?.let { toNode(windowId, it, str(w, "app")) } }
            UiTree(
                displayId = display,
                windowId = windowId,
                packageName = str(w, "app"),
                title = str(w, "title"),
                isActive = (w["active"] as? JsonPrimitive)?.content == "true",
                isFocused = (w["active"] as? JsonPrimitive)?.content == "true",
                nodes = nodes,
                capturedAtMs = now,
                truncated = (w["truncated"] as? JsonPrimitive)?.content == "true",
                source = "atspi",
            )
        }
        return UiSnapshot(windows, now)
    }

    private fun toNode(windowId: Int, n: JsonObject, app: String?): UiNode? {
        val path = str(n, "id") ?: return null
        val b = (n["bounds"] as? JsonArray)?.map { (it as JsonPrimitive).content.toInt() } ?: listOf(0, 0, 0, 0)
        val flags = (n["flags"] as? JsonArray).orEmpty().mapNotNull { f -> runCatching { NodeFlag.valueOf((f as JsonPrimitive).content) }.getOrNull() }.toMutableSet()
        val actions = (n["actions"] as? JsonArray).orEmpty().mapNotNull { a ->
            when ((a as JsonPrimitive).content.lowercase()) {
                "click", "press", "activate", "jump" -> NodeAction.CLICK
                "toggle" -> NodeAction.CLICK
                "expand or contract", "expand" -> NodeAction.EXPAND
                "select" -> NodeAction.SELECT
                else -> null
            }
        }.toMutableSet()
        val role = str(n, "role") ?: "element"
        if (role in EDITABLE_ROLES) {
            flags += NodeFlag.EDITABLE
            actions += NodeAction.SET_TEXT
        }
        return UiNode(
            id = "$windowId:$path",
            depth = (n["depth"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            parentId = str(n, "parent")?.let { "$windowId:$it" },
            className = role,
            packageName = app,
            text = str(n, "text") ?: str(n, "name").takeIf { role in LABELLED_ROLES },
            contentDescription = str(n, "name").takeIf { role !in LABELLED_ROLES } ?: str(n, "desc"),
            bounds = Rect(b[0], b[1], b[2], b[3]),
            flags = flags,
            actions = actions,
            childCount = (n["children"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
        )
    }

    override suspend fun performAction(node: UiNode, action: NodeAction, args: NodeActionArgs): Boolean {
        val path = node.id.substringAfter(':')
        val answer = when (action) {
            NodeAction.CLICK -> call("act", path, "click")
            NodeAction.FOCUS -> call("act", path, "focus")
            NodeAction.SET_TEXT -> call("act", path, "set_text", args.text.orEmpty())
            NodeAction.SELECT -> call("act", path, "select")
            NodeAction.EXPAND -> call("act", path, "expand")
            NodeAction.COLLAPSE -> call("act", path, "collapse")
            else -> return false
        }
        return (answer["performed"] as? JsonPrimitive)?.content == "true"
    }

    // ---- input ----

    override suspend fun tap(displayId: Int?, point: Point) {
        call("click", point.x.toString(), point.y.toString())
    }

    override suspend fun longPress(displayId: Int?, point: Point, durationMs: Long) {
        // Desktop long-press is a held button; the closest honest equivalent is a right click.
        call("click", point.x.toString(), point.y.toString(), "--right")
    }

    override suspend fun swipe(displayId: Int?, from: Point, to: Point, durationMs: Long) {
        call("drag", from.x.toString(), from.y.toString(), to.x.toString(), to.y.toString(), "--hold-ms", "0", "--duration-ms", durationMs.toString())
    }

    override suspend fun drag(displayId: Int?, from: Point, to: Point, holdMs: Long, durationMs: Long) {
        call("drag", from.x.toString(), from.y.toString(), to.x.toString(), to.y.toString(), "--hold-ms", holdMs.toString(), "--duration-ms", durationMs.toString())
    }

    override suspend fun key(displayId: Int?, key: String) {
        call("key", key)
    }

    override suspend fun typeText(displayId: Int?, text: String) {
        call("type", text, timeoutMs = 120_000)
    }

    override suspend fun global(action: GlobalAction): Boolean = when (action) {
        GlobalAction.BACK -> { call("key", "alt+Left"); true }
        GlobalAction.HOME -> { call("key", "super+d"); true }
        else -> false
    }

    override suspend fun rightClick(display: Int?, point: Point) {
        call("click", point.x.toString(), point.y.toString(), "--right")
    }

    override suspend fun wheel(display: Int?, point: Point, direction: Direction, clicks: Int) {
        val name = when (direction) { Direction.UP -> "up"; Direction.DOWN -> "down"; Direction.LEFT -> "left"; Direction.RIGHT -> "right" }
        call("scroll", point.x.toString(), point.y.toString(), name, clicks.toString())
    }

    // ---- windows, launching, clipboard, pictures ----

    override suspend fun windows(display: Int?): List<GuiWindowInfo> =
        (call("windows")["windows"] as? JsonArray).orEmpty().mapNotNull { element ->
            val w = element as? JsonObject ?: return@mapNotNull null
            val b = (w["bounds"] as? JsonArray)?.map { (it as JsonPrimitive).content.toInt() } ?: listOf(0, 0, 0, 0)
            GuiWindowInfo(
                id = str(w, "id") ?: return@mapNotNull null,
                title = str(w, "title"), appName = str(w, "app"), pid = (w["pid"] as? JsonPrimitive)?.content?.toIntOrNull(),
                bounds = Rect(b[0], b[1], b[2], b[3]), isFocused = (w["focused"] as? JsonPrimitive)?.content == "true", display = ":${this.display}",
            )
        }

    override suspend fun windowAction(action: WindowAction, id: String?, title: String?, rect: Rect?): Boolean {
        val target = id ?: windows(null).firstOrNull { title != null && it.title?.contains(title, ignoreCase = true) == true }?.id ?: return false
        return try {
            when (action) {
                WindowAction.FOCUS -> call("window", "focus", target)
                WindowAction.CLOSE -> call("window", "close", target)
                WindowAction.MINIMIZE -> call("window", "minimize", target)
                WindowAction.MAXIMIZE -> call("window", "maximize", target)
                WindowAction.MOVE -> call("window", "move", target, (rect ?: return false).left.toString(), rect.top.toString())
                WindowAction.RESIZE -> call("window", "resize", target, (rect ?: return false).width.toString(), rect.height.toString())
                else -> return false
            }
            true
        } catch (_: BackendException) {
            false
        }
    }

    override suspend fun launch(command: String, args: List<String>, cwd: String?, env: Map<String, String>): Int? {
        val answer = call("launch", "--", command, *args.toTypedArray(), timeoutMs = 30_000)
        return (answer["pid"] as? JsonPrimitive)?.content?.toIntOrNull()
    }

    override suspend fun clipboardGet(): String? = (call("clip", "get")["text"] as? JsonPrimitive)?.content

    override suspend fun clipboardSet(text: String) {
        call("clip", "set", text)
    }

    override suspend fun capture(displayId: Int?): CapturedImage {
        val answer = call("shot", "--max-edge", "1600", timeoutMs = 60_000)
        return CapturedImage(
            Base64.getDecoder().decode((answer["data"] as JsonPrimitive).content),
            (answer["mime"] as? JsonPrimitive)?.content ?: "image/png",
            (answer["width"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            (answer["height"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
        )
    }

    private fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this?.toList() ?: emptyList()

    private companion object {
        const val USABLE_CACHE_MS = 10_000L
        val EDITABLE_ROLES = setOf("text", "entry", "password text", "terminal", "paragraph", "text entry")
        val LABELLED_ROLES = setOf("label", "push button", "toggle button", "menu item", "check box", "radio button", "page tab", "list item", "link", "heading", "check menu item", "radio menu item", "table cell", "tree item", "combo box", "menu")
    }
}
