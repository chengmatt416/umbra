package app.umbra.core.model.action

import app.umbra.core.model.Rect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Direction {
    @SerialName("up") UP,
    @SerialName("down") DOWN,
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
}

@Serializable
enum class GlobalAction {
    @SerialName("back") BACK,
    @SerialName("home") HOME,
    @SerialName("recents") RECENTS,
    @SerialName("notifications") NOTIFICATIONS,
    @SerialName("quick_settings") QUICK_SETTINGS,
    @SerialName("power_dialog") POWER_DIALOG,
    @SerialName("lock_screen") LOCK_SCREEN,
    @SerialName("take_screenshot") TAKE_SCREENSHOT,
}

@Serializable
enum class ClipboardAction {
    @SerialName("get") GET,
    @SerialName("set") SET,
    @SerialName("copy") COPY,
    @SerialName("cut") CUT,
    @SerialName("paste") PASTE,
}

@Serializable
enum class NotificationAction {
    @SerialName("list") LIST,
    @SerialName("open") OPEN,
    @SerialName("dismiss") DISMISS,
    @SerialName("press_action") PRESS_ACTION,
    @SerialName("reply") REPLY,
}

@Serializable
enum class ObserveLevel {
    /** Foreground package/activity, display list and a one-line screen summary. */
    @SerialName("brief") BRIEF,
    /** Brief plus the accessibility tree of the target display. */
    @SerialName("tree") TREE,
    /** Tree for every window of every display, plus device state. */
    @SerialName("full") FULL,
}

@Serializable
enum class PackageAction {
    @SerialName("list") LIST,
    @SerialName("info") INFO,
    @SerialName("install") INSTALL,
    @SerialName("uninstall") UNINSTALL,
    @SerialName("clear_data") CLEAR_DATA,
    @SerialName("enable") ENABLE,
    @SerialName("disable") DISABLE,
    @SerialName("grant") GRANT,
    @SerialName("revoke") REVOKE,
}

@Serializable
enum class SysInfoQuery {
    @SerialName("dumpsys") DUMPSYS,
    @SerialName("getprop") GETPROP,
    @SerialName("settings") SETTINGS,
    @SerialName("processes") PROCESSES,
    @SerialName("services") SERVICES,
    @SerialName("displays") DISPLAYS,
    @SerialName("battery") BATTERY,
    @SerialName("memory") MEMORY,
    @SerialName("storage") STORAGE,
    @SerialName("network") NETWORK,
    @SerialName("thermal") THERMAL,
}

@Serializable
enum class LogAction {
    /** One-shot read of the buffered log. */
    @SerialName("query") QUERY,
    @SerialName("watch") WATCH,
    @SerialName("stop_watch") STOP_WATCH,
    /** Crashes/ANRs/warnings seen since the session started, correlated with agent actions. */
    @SerialName("summary") SUMMARY,
}

@Serializable
enum class DisplayAction {
    @SerialName("create") CREATE,
    @SerialName("list") LIST,
    @SerialName("destroy") DESTROY,
    @SerialName("launch") LAUNCH,
    @SerialName("lease") LEASE,
    @SerialName("release") RELEASE,
}

@Serializable
enum class FileAction {
    @SerialName("read") READ,
    @SerialName("write") WRITE,
    @SerialName("append") APPEND,
    @SerialName("list") LIST,
    @SerialName("stat") STAT,
    @SerialName("mkdir") MKDIR,
    @SerialName("delete") DELETE,
    @SerialName("move") MOVE,
}

@Serializable
enum class ProcessAction {
    @SerialName("list") LIST,
    @SerialName("info") INFO,
    @SerialName("kill") KILL,
}

@Serializable
enum class WindowAction {
    @SerialName("list") LIST,
    @SerialName("focus") FOCUS,
    @SerialName("close") CLOSE,
    @SerialName("move") MOVE,
    @SerialName("resize") RESIZE,
    @SerialName("minimize") MINIMIZE,
    @SerialName("maximize") MAXIMIZE,
}

@Serializable
data class LogFilter(
    val packageName: String? = null,
    val pid: Int? = null,
    val uid: Int? = null,
    val tags: List<String> = emptyList(),
    /** V, D, I, W, E, F */
    val minLevel: String = "V",
    /** Per-tag minimum level that overrides [minLevel] for that tag (a logcat filterspec such as `AndroidRuntime:V`). */
    val tagLevels: Map<String, String> = emptyMap(),
    val regex: String? = null,
    val buffers: List<String> = listOf("main", "system", "crash"),
)

/**
 * Typed operation of an [ActionRequest] (spec §16 "command or operation"). Android UI and Linux GUI
 * share the UI operations; the [Target.environment] selects the backend.
 */
@Serializable
sealed interface Operation {
    /** Stable name for logs, approval prompts and risk rules. */
    val name: String

    // ---- UI interaction (Android and Linux GUI) ----

    @Serializable @SerialName("tap")
    data object Tap : Operation { override val name get() = "tap" }

    @Serializable @SerialName("double_tap")
    data object DoubleTap : Operation { override val name get() = "double_tap" }

    /** Secondary click: opens context menus on a Linux desktop. Android has no equivalent; use [LongPress]. */
    @Serializable @SerialName("right_click")
    data object RightClick : Operation { override val name get() = "right_click" }

    @Serializable @SerialName("long_press")
    data class LongPress(val durationMs: Long = 600) : Operation { override val name get() = "long_press" }

    /** Swipe from the target (or screen centre) in [direction], or between explicit endpoints. */
    @Serializable @SerialName("swipe")
    data class Swipe(
        val direction: Direction? = null,
        val distanceFraction: Float = 0.5f,
        val durationMs: Long = 300,
        val to: Endpoint? = null,
    ) : Operation { override val name get() = "swipe" }

    /** Scroll the container at the target; optionally until [untilVisible] appears. */
    @Serializable @SerialName("scroll")
    data class Scroll(
        val direction: Direction = Direction.DOWN,
        val amount: Float = 0.7f,
        val untilVisible: ElementQuery? = null,
        val maxScrolls: Int = 8,
    ) : Operation { override val name get() = "scroll" }

    /** Drag from the request target to [to]. */
    @Serializable @SerialName("drag")
    data class Drag(val to: Endpoint, val durationMs: Long = 500, val holdMs: Long = 300) : Operation {
        override val name get() = "drag"
    }

    @Serializable @SerialName("type_text")
    data class TypeText(
        val text: String,
        val clearFirst: Boolean = false,
        val submit: Boolean = false,
        /** Marks the text as a secret so it is never logged or echoed in summaries. */
        val secret: Boolean = false,
    ) : Operation { override val name get() = "type_text" }

    @Serializable @SerialName("select_text")
    data class SelectText(val all: Boolean = false, val start: Int? = null, val end: Int? = null) : Operation {
        override val name get() = "select_text"
    }

    @Serializable @SerialName("clipboard")
    data class Clipboard(val action: ClipboardAction, val text: String? = null) : Operation {
        override val name get() = "clipboard"
    }

    /** Android keycode name (`KEYCODE_ENTER`) or an xdotool key spec (`ctrl+l`). */
    @Serializable @SerialName("press_key")
    data class PressKey(val key: String) : Operation { override val name get() = "press_key" }

    @Serializable @SerialName("global")
    data class Global(val action: GlobalAction) : Operation { override val name get() = "global" }

    @Serializable @SerialName("launch_app")
    data class LaunchApp(
        val packageName: String,
        val activity: String? = null,
        val intentAction: String? = null,
        val uri: String? = null,
        val extras: Map<String, String> = emptyMap(),
        val waitForForeground: Boolean = true,
    ) : Operation { override val name get() = "launch_app" }

    @Serializable @SerialName("stop_app")
    data class StopApp(val packageName: String, val force: Boolean = true) : Operation {
        override val name get() = "stop_app"
    }

    @Serializable @SerialName("switch_app")
    data class SwitchApp(val packageName: String) : Operation { override val name get() = "switch_app" }

    @Serializable @SerialName("notifications")
    data class Notifications(
        val action: NotificationAction,
        val key: String? = null,
        val actionIndex: Int? = null,
        val text: String? = null,
    ) : Operation { override val name get() = "notifications" }

    @Serializable @SerialName("screenshot")
    data class Screenshot(
        /** Draw numbered set-of-marks boxes over interactive elements and return the index map. */
        val annotate: Boolean = false,
        val maxEdge: Int = 1280,
        val region: Rect? = null,
        /** Draw coordinate ticks along the edges, for screens whose tree exposes nothing to mark. */
        val grid: Boolean = false,
    ) : Operation { override val name get() = "screenshot" }

    @Serializable @SerialName("observe")
    data class Observe(
        val level: ObserveLevel = ObserveLevel.TREE,
        val includeScreenshot: Boolean = false,
        val maxNodes: Int = 250,
        val onlyInteractive: Boolean = false,
    ) : Operation { override val name get() = "observe" }

    /** Lists the on-screen elements matching [query], without acting. */
    @Serializable @SerialName("find")
    data class Find(val query: ElementQuery, val limit: Int = 20) : Operation { override val name get() = "find" }

    @Serializable @SerialName("wait_for")
    data class WaitFor(val expect: Expectation) : Operation { override val name get() = "wait_for" }

    // ---- Linux GUI specific ----

    @Serializable @SerialName("window")
    data class Window(
        val action: WindowAction,
        val id: String? = null,
        val title: String? = null,
        val rect: Rect? = null,
    ) : Operation { override val name get() = "window" }

    @Serializable @SerialName("launch_command")
    data class LaunchCommand(
        val command: String,
        val args: List<String> = emptyList(),
        val cwd: String? = null,
        val env: Map<String, String> = emptyMap(),
    ) : Operation { override val name get() = "launch_command" }

    // ---- System / privileged ----

    @Serializable @SerialName("shell")
    data class Shell(
        val command: String,
        val cwd: String? = null,
        val env: Map<String, String> = emptyMap(),
        val stdin: String? = null,
        val asRoot: Boolean = false,
        val maxOutputBytes: Int = 64 * 1024,
    ) : Operation { override val name get() = "shell" }

    @Serializable @SerialName("packages")
    data class Packages(
        val action: PackageAction,
        val packageName: String? = null,
        val apkPath: String? = null,
        val permission: String? = null,
        val userId: Int = 0,
        val filter: String? = null,
    ) : Operation { override val name get() = "packages" }

    @Serializable @SerialName("sysinfo")
    data class SysInfo(val query: SysInfoQuery, val argument: String? = null) : Operation {
        override val name get() = "sysinfo"
    }

    @Serializable @SerialName("logs")
    data class Logs(
        val action: LogAction,
        val filter: LogFilter = LogFilter(),
        val maxLines: Int = 200,
        /** Names a watch so it can be stopped; defaults to one derived from the filter. */
        val watchName: String? = null,
    ) : Operation { override val name get() = "logs" }

    @Serializable @SerialName("display")
    data class Display(
        val action: DisplayAction,
        val width: Int? = null,
        val height: Int? = null,
        val dpi: Int? = null,
        val displayId: Int? = null,
        val packageName: String? = null,
        val activity: String? = null,
    ) : Operation { override val name get() = "display" }

    @Serializable @SerialName("file")
    data class File(
        val action: FileAction,
        val path: String,
        val content: String? = null,
        val destination: String? = null,
        val privileged: Boolean = false,
        val maxBytes: Int = 256 * 1024,
    ) : Operation { override val name get() = "file" }

    @Serializable @SerialName("process")
    data class Process(
        val action: ProcessAction,
        val pid: Int? = null,
        val name_: String? = null,
        val signal: Int? = null,
    ) : Operation { override val name get() = "process" }

    /** Re-check an expectation without acting (the `verify` host tool). */
    @Serializable @SerialName("verify")
    data class Verify(val expect: Expectation) : Operation { override val name get() = "verify" }
}
