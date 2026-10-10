package app.umbra.tools

import app.umbra.core.model.Point
import app.umbra.core.model.Rect
import app.umbra.core.model.action.ActionRequest
import app.umbra.core.model.action.ClipboardAction
import app.umbra.core.model.action.Direction
import app.umbra.core.model.action.DisplayAction
import app.umbra.core.model.action.ElementQuery
import app.umbra.core.model.action.ElementRef
import app.umbra.core.model.action.Endpoint
import app.umbra.core.model.action.Environment
import app.umbra.core.model.action.Expectation
import app.umbra.core.model.action.FileAction
import app.umbra.core.model.action.GlobalAction
import app.umbra.core.model.action.LogAction
import app.umbra.core.model.action.LogFilter
import app.umbra.core.model.action.NotificationAction
import app.umbra.core.model.action.ObserveLevel
import app.umbra.core.model.action.Operation
import app.umbra.core.model.action.PackageAction
import app.umbra.core.model.action.Privilege
import app.umbra.core.model.action.ProcessAction
import app.umbra.core.model.action.SysInfoQuery
import app.umbra.core.model.action.Target
import app.umbra.core.model.action.Verification
import app.umbra.core.model.action.WindowAction
import app.umbra.runtime.omp.SchemaBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a decoder knows about the call it is decoding. */
internal class DecodeContext(val state: SessionToolState, val sessionId: String?, val callId: String)

/** A decoded call: normally an action for the pipeline, occasionally something the host answers itself. */
internal sealed interface DecodedCall {
    data class Action(val request: ActionRequest, val observeAfter: Boolean = false) : DecodedCall

    /** The `decide` tool: a question for the fast decision layer. */
    data class Decide(val question: String, val context: String, val kind: String, val options: List<Pair<String, String>>) : DecodedCall
}

internal class ToolSpec(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val decode: (ToolArgs, DecodeContext) -> DecodedCall,
) {
    val keys: Set<String> = (schema["properties"] as? JsonObject)?.keys.orEmpty()
}

/**
 * The tools offered to the agent (spec §14). Each is a thin, forgiving front for one [Operation]:
 * flat arguments the model gets right on the first try, defaults that match what a person would
 * expect, and a description that says when to use it and what comes back.
 */
internal object HostToolRegistry {
    val all: List<ToolSpec> by lazy { android() + privileged() + desktop() + helpers() }
    val byName: Map<String, ToolSpec> by lazy { all.associateBy { it.name } }

    // ---- shared argument groups ----

    private fun SchemaBuilder.targeting(what: String = "the element") {
        string("element", "Element id from an observation, e.g. \"101:0.3\" (the id in brackets). The most reliable way to name $what.")
        string("label", "Visible text or content description of $what (exact, ignoring case). Use when you have no id.")
        string("contains", "Part of the text or description of $what.")
        string("resource_id", "Resource id, with or without the package prefix, e.g. \"login_button\".")
        string("class_name", "Widget class, e.g. \"EditText\" or \"Button\".")
        integer("index", "Which match to use when several elements fit (0 = first in reading order, top-left first).")
        integer("mark", "Mark number from the latest annotated screenshot.")
        integer("x", "Screen x in display pixels. Use only when nothing above identifies the target.")
        integer("y", "Screen y in display pixels.")
    }

    private fun SchemaBuilder.display() =
        integer("display", "Display id. Default: the display you last used (0 is the phone screen). Use display_id from android_display to work on a background display.")

    private fun SchemaBuilder.expectation() {
        obj("expect", "What should be true afterwards. When given, the action only counts as done if it is observed (and is retried another way if safe).") {
            string("text_visible", "This text appears on screen.")
            string("text_gone", "This text disappears from the screen.")
            string("element_visible", "An element with this label appears.")
            string("element_gone", "The element with this label disappears.")
            string("package", "This app comes to the foreground.")
            string("activity", "This activity (pkg/.Activity or just .Activity) is showing.")
            string("no_crash", "This package has not crashed or hit an ANR.")
            string("log_regex", "A log line matching this regular expression appears.")
            boolean("changed", "The screen changed.")
        }
        integer("expect_timeout_ms", "How long to wait for the expectation (default 3000).")
    }

    private fun SchemaBuilder.common(timeoutMs: Long) {
        integer("timeout_ms", "Give up after this many milliseconds (default $timeoutMs).")
        string("reason", "One sentence on why you are doing this; shown to the user if approval is needed.")
        boolean("observe_after", "Also return the screen tree after the action.")
    }

    private const val TARGET_KEYS_NOTE = "Name the target by element id, label, resource_id, mark or x/y."

    // ---- decoding helpers ----

    private fun decodeExpect(a: ToolArgs): Expectation? {
        val e = a.obj("expect") ?: return null
        val items = buildList<Expectation> {
            e.str("text_visible")?.let { add(Expectation.TextVisible(it)) }
            e.str("text_gone")?.let { add(Expectation.TextGone(it)) }
            e.str("element_visible")?.let { add(Expectation.ElementVisible(ElementQuery(text = it))) }
            e.str("element_gone")?.let { add(Expectation.ElementGone(ElementQuery(text = it))) }
            e.str("package")?.let { add(Expectation.PackageForeground(it)) }
            e.str("activity")?.let { add(Expectation.ActivityIs(it)) }
            e.str("no_crash")?.let { add(Expectation.NoCrash(it)) }
            e.str("log_regex")?.let { add(Expectation.LogMatches(it)) }
            if (e.bool("changed") == true) add(Expectation.UiChanged)
        }
        if (items.isEmpty()) throw ArgumentException("'expect' is empty; give at least one of: text_visible, text_gone, element_visible, element_gone, package, activity, no_crash, log_regex, changed")
        return if (items.size == 1) items.single() else Expectation.AllOf(items)
    }

    /** The verification for an action: the agent's `expect` if given (required), else a quiet "did the screen change". */
    private fun verification(a: ToolArgs, default: Expectation = Expectation.None, defaultTimeoutMs: Long = 1_500): Verification {
        val expect = decodeExpect(a)
        return if (expect != null) {
            Verification(expect, timeoutMs = a.long("expect_timeout_ms") ?: 3_000, pollMs = 150, settleMs = 150, required = true)
        } else {
            Verification(default, timeoutMs = defaultTimeoutMs, pollMs = 150, settleMs = if (default is Expectation.None) 0 else 200, required = false)
        }
    }

    private fun decodeTarget(a: ToolArgs, ctx: DecodeContext, env: Environment, required: Boolean, what: String): Target {
        val display = a.int("display")
        val element = a.str("element")
        val mark = a.int("mark")
        val query = ElementQuery(
            text = a.str("label"), textContains = a.str("contains"), resourceId = a.str("resource_id"),
            className = a.str("class_name"), index = a.int("index") ?: 0,
        )
        val x = a.int("x")
        val y = a.int("y")
        if ((x == null) != (y == null)) throw ArgumentException("Give both x and y, or neither.")
        return when {
            element != null -> Target(env, display, element = ElementRef(id = element))
            mark != null -> {
                val shown = display ?: ctx.state.activeDisplay
                val m = ctx.state.memory.mark(shown, mark)
                    ?: throw ArgumentException("There is no mark $mark. Marks exist only for the latest android_screenshot with annotate=true on display $shown; take one first.")
                if (m.nodeId != null) Target(env, display, element = ElementRef(id = m.nodeId)) else Target(env, display, point = m.bounds.center)
            }
            !query.isEmpty -> Target(env, display, element = ElementRef(query = query))
            x != null && y != null -> Target(env, display, point = Point(x, y))
            required -> throw ArgumentException("No target given for $what. $TARGET_KEYS_NOTE")
            else -> Target(env, display)
        }
    }

    private fun endpoint(a: ToolArgs, prefix: String): Endpoint? {
        val element = a.str("${prefix}_element")
        val x = a.int("${prefix}_x")
        val y = a.int("${prefix}_y")
        return when {
            element != null -> Endpoint(element = ElementRef(id = element))
            x != null && y != null -> Endpoint(point = Point(x, y))
            x != null || y != null -> throw ArgumentException("Give both ${prefix}_x and ${prefix}_y.")
            else -> null
        }
    }

    private fun request(
        a: ToolArgs,
        ctx: DecodeContext,
        op: Operation,
        target: Target,
        verify: Verification = Verification(),
        defaultTimeoutMs: Long = 10_000,
        requires: Privilege = Privilege.NONE,
    ): DecodedCall.Action = DecodedCall.Action(
        ActionRequest(
            id = ctx.callId, operation = op, target = target, requires = requires, verify = verify,
            timeoutMs = (a.long("timeout_ms") ?: defaultTimeoutMs).coerceIn(500, 30 * 60_000L),
            rationale = a.str("reason"), sessionId = ctx.sessionId,
        ),
        observeAfter = a.bool("observe_after") == true,
    )

    private fun spec(name: String, description: String, schema: SchemaBuilder.() -> Unit, decode: (ToolArgs, DecodeContext) -> DecodedCall): ToolSpec {
        val built = SchemaBuilder().apply(schema).build()
        val keys = (built["properties"] as JsonObject).keys
        return ToolSpec(name, description, built) { a, ctx ->
            a.rejectUnknown(keys)
            decode(a, ctx)
        }
    }

    private fun direction(a: ToolArgs, name: String = "direction", default: String? = null): Direction =
        when (a.choice(name, listOf("up", "down", "left", "right"), default)) {
            "up" -> Direction.UP; "down" -> Direction.DOWN; "left" -> Direction.LEFT; else -> Direction.RIGHT
        }

    // ---- Android ----

    private fun android(): List<ToolSpec> {
        val env = Environment.ANDROID
        return listOf(
            spec(
                "android_observe",
                "Look at the Android screen. level=tree (default) lists the visible elements; the id in brackets, like [101:0.3], is what the other android_* tools accept as `element`. level=brief gives only the foreground app and counts; level=full adds every display and device state. Look before you act and again after anything that changes the screen. Add screenshot=true for a picture.",
                {
                    enum("level", listOf("brief", "tree", "full"), "How much to return (default tree).")
                    display()
                    boolean("interactive_only", "List only elements you can tap, type into or scroll.")
                    integer("max_nodes", "Limit on listed elements (default 250).")
                    boolean("screenshot", "Attach a screenshot.")
                    boolean("force", "Return the full tree even if the screen is unchanged since your last observation.")
                },
            ) { a, ctx ->
                val level = when (a.choice("level", listOf("brief", "tree", "full"), "tree")) { "brief" -> ObserveLevel.BRIEF; "full" -> ObserveLevel.FULL; else -> ObserveLevel.TREE }
                if (a.bool("force") == true) ctx.state.lastObservation.clear()
                request(
                    a, ctx,
                    Operation.Observe(level, a.bool("screenshot") ?: false, a.int("max_nodes") ?: 250, a.bool("interactive_only") ?: false),
                    Target(env, a.int("display")),
                )
            },
            spec(
                "android_find",
                "Search the current screen for elements by label, text fragment, resource id or class, without acting. Returns each match with its id, label, state and position, so you can pick one for android_tap or android_type.",
                {
                    string("label", "Visible text or content description (exact, ignoring case).")
                    string("contains", "Part of the text or description.")
                    string("resource_id", "Resource id, with or without the package prefix.")
                    string("class_name", "Widget class, e.g. \"EditText\".")
                    integer("limit", "Maximum matches to list (default 20).")
                    display(); common(5_000)
                },
            ) { a, ctx ->
                val q = ElementQuery(text = a.str("label"), textContains = a.str("contains"), resourceId = a.str("resource_id"), className = a.str("class_name"))
                if (q.isEmpty) throw ArgumentException("Give at least one of label, contains, resource_id, class_name.")
                request(a, ctx, Operation.Find(q, a.int("limit") ?: 20), Target(env, a.int("display")), defaultTimeoutMs = 5_000)
            },
            spec(
                "android_tap",
                "Tap an element or a point. $TARGET_KEYS_NOTE Prefer ids and labels over coordinates; they survive layout changes. Returns what happened and whether the screen changed. Pass `expect` (e.g. text_visible) to make the call fail unless the effect really happens.",
                { targeting("the element to tap"); display(); expectation(); common(10_000) },
            ) { a, ctx ->
                request(a, ctx, Operation.Tap, decodeTarget(a, ctx, env, true, "android_tap"), verification(a, Expectation.UiChanged))
            },
            spec(
                "android_double_tap",
                "Double-tap an element or a point (zoom, select a word). $TARGET_KEYS_NOTE",
                { targeting("the element"); display(); expectation(); common(10_000) },
            ) { a, ctx -> request(a, ctx, Operation.DoubleTap, decodeTarget(a, ctx, env, true, "android_double_tap"), verification(a, Expectation.UiChanged)) },
            spec(
                "android_long_press",
                "Press and hold an element or a point (context menus, drag handles, selection). $TARGET_KEYS_NOTE",
                { targeting("the element"); display(); integer("duration_ms", "Hold time (default 600)."); expectation(); common(10_000) },
            ) { a, ctx ->
                request(a, ctx, Operation.LongPress(a.long("duration_ms") ?: 600), decodeTarget(a, ctx, env, true, "android_long_press"), verification(a, Expectation.UiChanged))
            },
            spec(
                "android_swipe",
                "Swipe with a finger. direction is the way the FINGER moves (swipe up reveals content below). Starts at the target element or the screen centre; or give an explicit end with to_element / to_x+to_y. To scroll a list, prefer android_scroll.",
                {
                    enum("direction", listOf("up", "down", "left", "right"), "Finger direction.")
                    number("distance", "Fraction of the area to cross, 0.1-0.95 (default 0.5).")
                    integer("duration_ms", "Swipe time (default 300).")
                    targeting("where the swipe starts"); string("to_element", "Element id to swipe to."); integer("to_x"); integer("to_y")
                    display(); expectation(); common(10_000)
                },
            ) { a, ctx ->
                val to = endpoint(a, "to")
                if (to == null && !a.has("direction")) throw ArgumentException("Give a direction, or an end point with to_element or to_x and to_y.")
                request(
                    a, ctx,
                    Operation.Swipe(if (a.has("direction")) direction(a) else null, a.float("distance") ?: 0.5f, a.long("duration_ms") ?: 300, to),
                    decodeTarget(a, ctx, env, false, "android_swipe"), verification(a, Expectation.UiChanged),
                )
            },
            spec(
                "android_scroll",
                "Scroll a list or page. direction is which content to reveal (down = see what is below). With `until_label` it keeps scrolling until an element with that text is visible (up to max_scrolls) and returns it. Name the list with element/label, or leave it out to scroll the main scrollable area.",
                {
                    enum("direction", listOf("up", "down", "left", "right"), "Which content to reveal (default down).")
                    number("amount", "Fraction of the list per scroll, 0.1-0.95 (default 0.7).")
                    string("until_label", "Keep scrolling until an element with this text or description is visible.")
                    string("until_contains", "Keep scrolling until an element whose text contains this is visible.")
                    integer("max_scrolls", "Limit for until_* (default 8).")
                    targeting("the scrollable list"); display(); common(30_000)
                },
            ) { a, ctx ->
                val until = if (a.has("until_label") || a.has("until_contains")) ElementQuery(text = a.str("until_label"), textContains = a.str("until_contains")) else null
                request(
                    a, ctx,
                    Operation.Scroll(direction(a, default = "down"), a.float("amount") ?: 0.7f, until, a.int("max_scrolls") ?: 8),
                    decodeTarget(a, ctx, env, false, "android_scroll"), verification(a, Expectation.None), defaultTimeoutMs = 30_000,
                )
            },
            spec(
                "android_drag",
                "Press at the target, hold, and drag to another element or point (reorder lists, move sliders, drag and drop). $TARGET_KEYS_NOTE End with to_element or to_x+to_y.",
                {
                    targeting("the element to drag"); string("to_element", "Element id to drop on."); integer("to_x"); integer("to_y")
                    integer("hold_ms", "Hold before moving (default 300)."); integer("duration_ms", "Move time (default 500).")
                    display(); expectation(); common(10_000)
                },
            ) { a, ctx ->
                val to = endpoint(a, "to") ?: throw ArgumentException("android_drag needs to_element or to_x and to_y.")
                request(a, ctx, Operation.Drag(to, a.long("duration_ms") ?: 500, a.long("hold_ms") ?: 300), decodeTarget(a, ctx, env, true, "android_drag"), verification(a, Expectation.UiChanged))
            },
            spec(
                "android_type",
                "Type text into a field. Name the field with element/label/resource_id, or leave it out to use the focused field (or the only text field on screen). clear=true replaces the existing text; submit=true presses Enter afterwards. Set secret=true for passwords and tokens: the value is never logged or echoed back.",
                {
                    string("text", "The text to type.", required = true)
                    boolean("clear", "Replace existing text instead of appending."); boolean("submit", "Press Enter after typing.")
                    boolean("secret", "The text is a secret (password, token); never log or repeat it.")
                    targeting("the text field"); display(); expectation(); common(10_000)
                },
            ) { a, ctx ->
                val text = a.str("text") ?: throw ArgumentException("'text' is required")
                request(
                    a, ctx, Operation.TypeText(text, a.bool("clear") ?: false, a.bool("submit") ?: false, a.bool("secret") ?: false),
                    decodeTarget(a, ctx, env, false, "android_type"), verification(a, Expectation.None),
                )
            },
            spec(
                "android_select_text",
                "Select text in a field (all of it, or a character range) so it can be copied, cut or replaced.",
                { boolean("all", "Select everything."); integer("start"); integer("end"); targeting("the text field"); display(); common(10_000) },
            ) { a, ctx ->
                request(a, ctx, Operation.SelectText(a.bool("all") ?: false, a.int("start"), a.int("end")), decodeTarget(a, ctx, env, false, "android_select_text"))
            },
            spec(
                "android_clipboard",
                "Read or write the clipboard, or copy/cut/paste in a text field. action=get returns the clipboard text (Android may hide it from background apps); set stores `text`; copy, cut and paste act on the named or focused field.",
                {
                    enum("action", listOf("get", "set", "copy", "cut", "paste"), "What to do.", required = true)
                    string("text", "Text for action=set."); targeting("the text field"); display(); common(10_000)
                },
            ) { a, ctx ->
                val action = when (a.choice("action", listOf("get", "set", "copy", "cut", "paste"))) {
                    "get" -> ClipboardAction.GET; "set" -> ClipboardAction.SET; "copy" -> ClipboardAction.COPY; "cut" -> ClipboardAction.CUT; else -> ClipboardAction.PASTE
                }
                request(a, ctx, Operation.Clipboard(action, a.str("text")), decodeTarget(a, ctx, env, false, "android_clipboard"))
            },
            spec(
                "android_key",
                "Press a key: ENTER, BACK, HOME, TAB, DEL (backspace), ESCAPE, SPACE, DPAD_UP/DOWN/LEFT/RIGHT, VOLUME_UP, VOLUME_DOWN, POWER, APP_SWITCH (recents), or any KEYCODE_* name. Also: global=notifications|quick_settings|lock_screen|power_dialog for system panels.",
                {
                    string("key", "Key name, e.g. \"ENTER\" or \"KEYCODE_BACK\".")
                    enum("global", listOf("notifications", "quick_settings", "lock_screen", "power_dialog", "recents", "back", "home"), "A system action instead of a key.")
                    display(); expectation(); common(10_000)
                },
            ) { a, ctx ->
                val global = a.str("global")
                val op = if (global != null) {
                    Operation.Global(
                        when (a.choice("global", listOf("notifications", "quick_settings", "lock_screen", "power_dialog", "recents", "back", "home"))) {
                            "notifications" -> GlobalAction.NOTIFICATIONS; "quick_settings" -> GlobalAction.QUICK_SETTINGS; "lock_screen" -> GlobalAction.LOCK_SCREEN
                            "power_dialog" -> GlobalAction.POWER_DIALOG; "recents" -> GlobalAction.RECENTS; "back" -> GlobalAction.BACK; else -> GlobalAction.HOME
                        },
                    )
                } else {
                    Operation.PressKey(a.requireStr("key"))
                }
                request(a, ctx, op, decodeTarget(a, ctx, env, false, "android_key"), verification(a, Expectation.UiChanged))
            },
            spec(
                "android_launch",
                "Open an app by package name (find it with android_packages). Optionally a specific activity, an intent action/uri (e.g. a deep link) and extras. Waits until the app is in the foreground, or reports that it crashed on start. display launches it on a background display.",
                {
                    string("package", "Package name, e.g. com.android.settings.", required = true)
                    string("activity", "Activity class, e.g. .MainActivity."); string("action", "Intent action, e.g. android.intent.action.VIEW.")
                    string("uri", "Data URI."); stringMap("extras", "String extras for the intent.")
                    boolean("wait", "Wait for the app to reach the foreground (default true)."); display(); expectation(); common(30_000)
                },
            ) { a, ctx ->
                request(
                    a, ctx,
                    Operation.LaunchApp(a.requireStr("package"), a.str("activity"), a.str("action"), a.str("uri"), a.strMap("extras"), a.bool("wait") ?: true),
                    Target(env, a.int("display")), verification(a), defaultTimeoutMs = 30_000,
                )
            },
            spec("android_stop", "Force-stop an app.", { string("package", required = true); boolean("force"); common(10_000) }) { a, ctx ->
                request(a, ctx, Operation.StopApp(a.requireStr("package"), a.bool("force") ?: true), Target(env), verification(a))
            },
            spec("android_switch", "Bring an already running app to the front (launches it if it is not running).", { string("package", required = true); display(); common(10_000) }) { a, ctx ->
                request(a, ctx, Operation.SwitchApp(a.requireStr("package")), Target(env, a.int("display")), verification(a))
            },
            spec(
                "android_notifications",
                "List notifications, or open, dismiss, press an action button of, or reply to one (by key from the list).",
                {
                    enum("action", listOf("list", "open", "dismiss", "press_action", "reply"), required = true)
                    string("key", "Notification key from the list."); integer("index", "Action button index for press_action."); string("text", "Reply text.")
                    common(10_000)
                },
            ) { a, ctx ->
                val action = when (a.choice("action", listOf("list", "open", "dismiss", "press_action", "reply"))) {
                    "list" -> NotificationAction.LIST; "open" -> NotificationAction.OPEN; "dismiss" -> NotificationAction.DISMISS; "press_action" -> NotificationAction.PRESS_ACTION; else -> NotificationAction.REPLY
                }
                request(a, ctx, Operation.Notifications(action, a.str("key"), a.int("index"), a.str("text")), Target(env))
            },
            spec(
                "android_screenshot",
                "Take a screenshot. annotate=true draws numbered boxes on every tappable element and lists them; tap one with mark=N in the other android_* tools. Use it when the element list is empty or wrong (games, canvases, web views) or when you need to see the screen. If nothing can be marked it draws a coordinate grid so you can read x/y positions.",
                {
                    boolean("annotate", "Draw numbered marks on tappable elements."); boolean("grid", "Draw a coordinate grid (display pixels).")
                    integer("max_edge", "Longest side of the returned image in pixels (default 1280)."); display()
                    obj("region", "Crop to this area, in display pixels.") { integer("left"); integer("top"); integer("right"); integer("bottom") }
                    common(15_000)
                },
            ) { a, ctx ->
                val region = a.obj("region")?.let { r -> Rect(r.int("left") ?: 0, r.int("top") ?: 0, r.int("right") ?: throw ArgumentException("region needs right"), r.int("bottom") ?: throw ArgumentException("region needs bottom")) }
                request(a, ctx, Operation.Screenshot(a.bool("annotate") ?: false, a.int("max_edge") ?: 1280, region, a.bool("grid") ?: false), Target(env, a.int("display")), defaultTimeoutMs = 15_000)
            },
            spec(
                "android_wait_for",
                "Wait until something is true: text appears or disappears, an element shows up, an app or activity comes to the front, a log line matches, or the screen changes. Prefer this to sleeping. Fails with what was actually seen if it times out.",
                {
                    obj("expect", "The condition.", required = true) {
                        string("text_visible"); string("text_gone"); string("element_visible"); string("element_gone"); string("package"); string("activity")
                        string("no_crash"); string("log_regex"); boolean("changed")
                    }
                    display(); integer("timeout_ms", "How long to wait (default 10000).")
                    string("reason")
                },
            ) { a, ctx ->
                val expect = decodeExpect(a) ?: throw ArgumentException("'expect' is required")
                request(a, ctx, Operation.WaitFor(expect), Target(env, a.int("display")), defaultTimeoutMs = 10_000)
            },
            spec(
                "android_shell",
                "Run a shell command on the phone with shell privileges (like adb shell): pm, am, dumpsys, input, settings, getprop, logcat, ls, cat, ps and the rest of toybox. Not a Linux environment: for builds, git, python and compilers use your own bash tool. Output is trimmed; exit code is reported.",
                {
                    string("command", "The command line.", required = true); string("cwd", "Working directory."); string("stdin", "Text to feed on standard input.")
                    integer("max_output_bytes"); common(30_000)
                },
            ) { a, ctx ->
                request(
                    a, ctx, Operation.Shell(a.requireStr("command"), a.str("cwd"), emptyMap(), a.str("stdin"), asRoot = false, maxOutputBytes = a.int("max_output_bytes") ?: (64 * 1024)),
                    Target(env), defaultTimeoutMs = 30_000,
                )
            },
            spec(
                "android_packages",
                "List, inspect, install, uninstall, enable, disable, clear the data of, and grant or revoke permissions for apps. install takes the path of an APK: on the phone, or a /workspace path for an app you built in Linux. list shows user-installed apps; filter searches all.",
                {
                    enum("action", listOf("list", "info", "install", "uninstall", "clear_data", "enable", "disable", "grant", "revoke"), required = true)
                    string("package"); string("apk_path", "APK file for install, e.g. /workspace/projects/app/build/app.apk."); string("permission", "e.g. android.permission.POST_NOTIFICATIONS."); string("filter", "Substring of the package name for list.")
                    common(60_000)
                },
            ) { a, ctx ->
                val action = PackageAction.valueOf(a.choice("action", PackageAction.entries.map { it.name.lowercase() }).uppercase())
                request(a, ctx, Operation.Packages(action, a.str("package"), a.str("apk_path"), a.str("permission"), 0, a.str("filter")), Target(env), defaultTimeoutMs = 60_000, requires = Privilege.SHELL)
            },
            spec(
                "android_sysinfo",
                "Query system state: battery, memory, storage, network, thermal, displays, processes, services, a getprop key, a settings value, or a dumpsys service (argument = the key or service name).",
                {
                    enum("query", SysInfoQuery.entries.map { it.name.lowercase() }, required = true); string("argument", "Property, setting or service name."); common(15_000)
                },
            ) { a, ctx ->
                val q = SysInfoQuery.valueOf(a.choice("query", SysInfoQuery.entries.map { it.name.lowercase() }).uppercase())
                request(a, ctx, Operation.SysInfo(q, a.str("argument")), Target(env), defaultTimeoutMs = 15_000, requires = Privilege.SHELL)
            },
            spec(
                "android_logs",
                "Read the device log or watch it. query returns recent lines (filter by package, tags, minimum level, regex); summary reports crashes, ANRs and the most frequent errors since this session started; watch reports matching lines to you as they happen; stop_watch ends a watch. A crash or ANR in any app is reported automatically while the monitor runs.",
                {
                    enum("action", listOf("query", "summary", "watch", "stop_watch"), required = true)
                    string("package", "Only this app's lines."); stringArray("tags", "Only these log tags."); enum("level", listOf("V", "D", "I", "W", "E", "F"), "Minimum level (default V).")
                    string("regex", "Only lines matching this regular expression."); integer("max_lines", "Default 200."); string("name", "Name of the watch.")
                    common(15_000)
                },
            ) { a, ctx ->
                val action = LogAction.valueOf(a.choice("action", listOf("query", "summary", "watch", "stop_watch")).uppercase())
                val filter = LogFilter(a.str("package"), null, null, a.strList("tags"), (a.str("level") ?: "V").uppercase(), emptyMap(), a.str("regex"))
                request(a, ctx, Operation.Logs(action, filter, a.int("max_lines") ?: 200, a.str("name")), Target(env), defaultTimeoutMs = 15_000, requires = Privilege.SHELL)
            },
            spec(
                "android_display",
                "Background workspaces: create a virtual display, list displays, launch an app onto one, lease or release one, destroy one. Work on a created display by passing display=<id> to the android_* tools; it runs in the background without taking over the phone screen. Needs Shizuku or root.",
                {
                    enum("action", DisplayAction.entries.map { it.name.lowercase() }, required = true)
                    integer("display_id"); integer("width"); integer("height"); integer("dpi"); string("package"); string("activity"); common(15_000)
                },
            ) { a, ctx ->
                val action = DisplayAction.valueOf(a.choice("action", DisplayAction.entries.map { it.name.lowercase() }).uppercase())
                request(a, ctx, Operation.Display(action, a.int("width"), a.int("height"), a.int("dpi"), a.int("display_id"), a.str("package"), a.str("activity")), Target(env), defaultTimeoutMs = 15_000, requires = Privilege.SHELL)
            },
        )
    }

    // ---- privileged ----

    private fun privileged(): List<ToolSpec> {
        val env = Environment.PRIVILEGED
        return listOf(
            spec(
                "priv_exec",
                "Run a shell command as ROOT. Only for what shell access cannot do (editing system files, mounts, kernel settings). Anything destructive or irreversible is shown to the user for approval first. Fails if the device is not rooted.",
                { string("command", required = true); string("cwd"); string("stdin"); integer("max_output_bytes"); common(30_000) },
            ) { a, ctx ->
                request(a, ctx, Operation.Shell(a.requireStr("command"), a.str("cwd"), emptyMap(), a.str("stdin"), asRoot = true, maxOutputBytes = a.int("max_output_bytes") ?: (64 * 1024)), Target(env), defaultTimeoutMs = 30_000, requires = Privilege.ROOT)
            },
            spec(
                "priv_fs",
                "File operations with root or shell privileges: read, write, append, list, stat, mkdir, delete, move. Use for paths the app cannot reach (/data/data, /system, /proc).",
                {
                    enum("action", FileAction.entries.map { it.name.lowercase() }, required = true); string("path", required = true)
                    string("content", "Text for write/append."); string("destination", "Target path for move."); integer("max_bytes"); common(30_000)
                },
            ) { a, ctx ->
                val action = FileAction.valueOf(a.choice("action", FileAction.entries.map { it.name.lowercase() }).uppercase())
                request(a, ctx, Operation.File(action, a.requireStr("path"), a.str("content"), a.str("destination"), privileged = true, maxBytes = a.int("max_bytes") ?: (256 * 1024)), Target(env), defaultTimeoutMs = 30_000, requires = Privilege.ROOT)
            },
            spec(
                "priv_proc",
                "List, inspect or signal (kill) system processes by pid or name.",
                { enum("action", listOf("list", "info", "kill"), required = true); integer("pid"); string("name", "Process name for list/kill."); integer("signal", "Default 15 (TERM)."); common(15_000) },
            ) { a, ctx ->
                val action = ProcessAction.valueOf(a.choice("action", listOf("list", "info", "kill")).uppercase())
                request(a, ctx, Operation.Process(action, a.int("pid"), a.str("name"), a.int("signal")), Target(env), defaultTimeoutMs = 15_000, requires = Privilege.SHELL)
            },
        )
    }

    // ---- Linux desktop ----

    private fun desktop(): List<ToolSpec> {
        val env = Environment.LINUX_GUI
        fun SchemaBuilder.guiDisplay() = integer("display", "X display number (default: the running session).")
        return listOf(
            spec(
                "gui_observe",
                "Look at the Linux desktop: windows and the accessibility tree of the focused one (AT-SPI). Element ids in brackets work as `element` in the other gui_* tools. Start desktop apps with gui_launch.",
                { enum("level", listOf("brief", "tree", "full")); guiDisplay(); boolean("interactive_only"); integer("max_nodes"); boolean("screenshot"); common(15_000) },
            ) { a, ctx ->
                val level = when (a.choice("level", listOf("brief", "tree", "full"), "tree")) { "brief" -> ObserveLevel.BRIEF; "full" -> ObserveLevel.FULL; else -> ObserveLevel.TREE }
                request(a, ctx, Operation.Observe(level, a.bool("screenshot") ?: false, a.int("max_nodes") ?: 250, a.bool("interactive_only") ?: false), Target(env, a.int("display")), defaultTimeoutMs = 15_000)
            },
            spec(
                "gui_click",
                "Click an element or a point on the Linux desktop. button=right opens context menus; double=true double-clicks. $TARGET_KEYS_NOTE",
                { targeting("the element"); enum("button", listOf("left", "right")); boolean("double"); guiDisplay(); expectation(); common(10_000) },
            ) { a, ctx ->
                val op = when {
                    a.choice("button", listOf("left", "right"), "left") == "right" -> Operation.RightClick
                    a.bool("double") == true -> Operation.DoubleTap
                    else -> Operation.Tap
                }
                request(a, ctx, op, decodeTarget(a, ctx, env, true, "gui_click"), verification(a, Expectation.UiChanged))
            },
            spec(
                "gui_type",
                "Type text on the Linux desktop into the named or focused element.",
                { string("text", required = true); boolean("clear"); boolean("submit"); boolean("secret"); targeting("the field"); guiDisplay(); expectation(); common(10_000) },
            ) { a, ctx ->
                request(a, ctx, Operation.TypeText(a.str("text") ?: throw ArgumentException("'text' is required"), a.bool("clear") ?: false, a.bool("submit") ?: false, a.bool("secret") ?: false), decodeTarget(a, ctx, env, false, "gui_type"), verification(a))
            },
            spec(
                "gui_key",
                "Press a key or chord on the Linux desktop, in xdotool syntax: \"Return\", \"ctrl+l\", \"alt+F4\", \"super\".",
                { string("key", required = true); guiDisplay(); expectation(); common(10_000) },
            ) { a, ctx -> request(a, ctx, Operation.PressKey(a.requireStr("key")), Target(env, a.int("display")), verification(a, Expectation.UiChanged)) },
            spec(
                "gui_scroll",
                "Scroll with the mouse wheel over an element or point (direction = which content to reveal).",
                { enum("direction", listOf("up", "down", "left", "right")); number("amount"); targeting("where to scroll"); guiDisplay(); common(10_000) },
            ) { a, ctx ->
                request(a, ctx, Operation.Scroll(direction(a, default = "down"), a.float("amount") ?: 0.7f, null, 1), decodeTarget(a, ctx, env, false, "gui_scroll"))
            },
            spec(
                "gui_drag",
                "Drag with the mouse from the target to another element or point.",
                { targeting("where to start"); string("to_element"); integer("to_x"); integer("to_y"); guiDisplay(); common(10_000) },
            ) { a, ctx ->
                val to = endpoint(a, "to") ?: throw ArgumentException("gui_drag needs to_element or to_x and to_y.")
                request(a, ctx, Operation.Drag(to), decodeTarget(a, ctx, env, true, "gui_drag"), verification(a, Expectation.UiChanged))
            },
            spec(
                "gui_window",
                "List desktop windows, or focus, close, move, resize, minimize or maximize one (by id or title fragment).",
                {
                    enum("action", WindowAction.entries.map { it.name.lowercase() }, required = true); string("id"); string("title")
                    integer("left"); integer("top"); integer("right"); integer("bottom"); guiDisplay(); common(10_000)
                },
            ) { a, ctx ->
                val action = WindowAction.valueOf(a.choice("action", WindowAction.entries.map { it.name.lowercase() }).uppercase())
                val rect = if (a.has("right") && a.has("bottom")) Rect(a.int("left") ?: 0, a.int("top") ?: 0, a.int("right")!!, a.int("bottom")!!) else null
                request(a, ctx, Operation.Window(action, a.str("id"), a.str("title"), rect), Target(env, a.int("display")))
            },
            spec(
                "gui_launch",
                "Start a graphical program on the Linux desktop (firefox, code, a game, an IDE). Then call gui_observe.",
                { string("command", required = true); stringArray("args"); string("cwd"); stringMap("env"); guiDisplay(); common(15_000) },
            ) { a, ctx ->
                request(a, ctx, Operation.LaunchCommand(a.requireStr("command"), a.strList("args"), a.str("cwd"), a.strMap("env")), Target(env, a.int("display")), defaultTimeoutMs = 15_000)
            },
            spec(
                "gui_clipboard",
                "Read or set the Linux desktop clipboard.",
                { enum("action", listOf("get", "set"), required = true); string("text"); common(10_000) },
            ) { a, ctx ->
                val action = if (a.choice("action", listOf("get", "set")) == "get") ClipboardAction.GET else ClipboardAction.SET
                request(a, ctx, Operation.Clipboard(action, a.str("text")), Target(env))
            },
            spec(
                "gui_screenshot",
                "Screenshot of the Linux desktop. annotate=true numbers the clickable elements; use mark=N in gui_click.",
                { boolean("annotate"); boolean("grid"); integer("max_edge"); guiDisplay(); common(15_000) },
            ) { a, ctx ->
                request(a, ctx, Operation.Screenshot(a.bool("annotate") ?: false, a.int("max_edge") ?: 1280, null, a.bool("grid") ?: false), Target(env, a.int("display")), defaultTimeoutMs = 15_000)
            },
        )
    }

    // ---- helpers: verify and decide ----

    private fun helpers(): List<ToolSpec> = listOf(
        spec(
            "verify",
            "Check whether something is true right now (no waiting): text on screen, element present, app in the foreground, no crash, a log line, or a shell command's output. Use it to confirm a result before reporting success. Each condition is reported with the evidence.",
            {
                obj("expect", "The conditions; all must hold.", required = true) {
                    string("text_visible"); string("text_gone"); string("element_visible"); string("element_gone"); string("package"); string("activity")
                    string("no_crash"); string("log_regex"); boolean("changed")
                }
                enum("environment", listOf("android", "linux_gui"), "Which screen to check (default android)."); display()
                integer("timeout_ms", "How long to keep checking (default 2000)."); string("reason")
            },
        ) { a, ctx ->
            val expect = decodeExpect(a) ?: throw ArgumentException("'expect' is required")
            val env = if (a.choice("environment", listOf("android", "linux_gui"), "android") == "linux_gui") Environment.LINUX_GUI else Environment.ANDROID
            val timeout = (a.long("timeout_ms") ?: 2_000).coerceIn(0, 60_000)
            DecodedCall.Action(
                ActionRequest(
                    id = ctx.callId, operation = Operation.Verify(expect), target = Target(env, a.int("display")),
                    verify = Verification(Expectation.None, timeoutMs = timeout), timeoutMs = timeout + 1_000, rationale = a.str("reason"), sessionId = ctx.sessionId,
                ),
            )
        },
        spec(
            "decide",
            "Ask the fast decision layer to pick among options or answer a yes/no question about a situation, in milliseconds. Use it for quick, well-defined judgements (which button, did it work, retry or give up). It reports its confidence; when confidence is low it says so and you should decide yourself.",
            {
                string("question", "What to decide, e.g. \"which element signs the user in?\" or \"did the login succeed?\"", required = true)
                string("context", "A plain-text description of the situation, e.g. the visible texts of the screen.")
                raw(
                    "options",
                    buildJsonObject {
                        put("type", "array"); put("description", "For a choice: the candidates, as strings or {id, description} objects. Leave out for a yes/no question.")
                        put("items", buildJsonObject {
                            put("anyOf", kotlinx.serialization.json.JsonArray(listOf(
                                buildJsonObject { put("type", "string") },
                                buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject { put("id", buildJsonObject { put("type", "string") }); put("description", buildJsonObject { put("type", "string") }) })
                                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("id"))))
                                },
                            )))
                        })
                    },
                )
            },
        ) { a, _ ->
            val options = a.elements("options").map { e ->
                when (e) {
                    is JsonPrimitive -> e.content.trim() to e.content.trim()
                    is JsonObject -> {
                        val id = (e["id"] as? JsonPrimitive)?.content?.trim() ?: throw ArgumentException("each option object needs an 'id'")
                        id to ((e["description"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: id)
                    }
                    else -> throw ArgumentException("options must be strings or {id, description} objects")
                }
            }
            if (options.size == 1) throw ArgumentException("A choice needs at least two options; leave 'options' out for a yes/no question.")
            if (options.map { it.first }.toSet().size != options.size) throw ArgumentException("Option ids must be unique.")
            DecodedCall.Decide(a.requireStr("question"), a.str("context").orEmpty(), if (options.isEmpty()) "yes_no" else "choice", options)
        },
    )
}
