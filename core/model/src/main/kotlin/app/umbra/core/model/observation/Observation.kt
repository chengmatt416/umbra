package app.umbra.core.model.observation

import app.umbra.core.model.Rect
import kotlinx.serialization.Serializable

@Serializable
data class DisplayInfo(
    val id: Int,
    val name: String? = null,
    val width: Int,
    val height: Int,
    val densityDpi: Int = 0,
    val isDefault: Boolean = false,
    val isVirtual: Boolean = false,
    val ownerPackage: String? = null,
    val state: String? = null,
    /** System unique id, e.g. `local:4619827259835644672` (physical) or `overlay:1`. */
    val uniqueId: String? = null,
    /** Session id currently holding the interactive lease on this display, if any. */
    val leasedBy: String? = null,
)

@Serializable
data class WindowInfo(
    val id: Int,
    val displayId: Int,
    val packageName: String? = null,
    val title: String? = null,
    val type: String? = null,
    val layer: Int = 0,
    val isActive: Boolean = false,
    val isFocused: Boolean = false,
    val bounds: Rect = Rect.EMPTY,
)

@Serializable
data class ForegroundInfo(
    val displayId: Int,
    val packageName: String? = null,
    val activity: String? = null,
    val windowTitle: String? = null,
)

/** Numbered box drawn on an annotated screenshot (set-of-marks), so the agent can pick by index. */
@Serializable
data class Marker(
    val index: Int,
    val nodeId: String? = null,
    val bounds: Rect,
    val label: String? = null,
)

/**
 * Reference to a captured screenshot. [scale] is screenshot pixels per display pixel, so a point
 * the agent reads off the image converts back with `x / scale`.
 */
@Serializable
data class ScreenshotRef(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val mime: String = "image/png",
    val path: String? = null,
    val base64: String? = null,
    val scale: Float = 1f,
    val markers: List<Marker> = emptyList(),
)

@Serializable
data class DeviceState(
    val batteryPct: Int? = null,
    val charging: Boolean? = null,
    val thermalStatus: String? = null,
    val memAvailMb: Long? = null,
    val memTotalMb: Long? = null,
    val storageFreeMb: Long? = null,
    val networkType: String? = null,
    val networkOnline: Boolean? = null,
    val cpuPct: Float? = null,
)

@Serializable
data class AndroidState(
    val foreground: List<ForegroundInfo> = emptyList(),
    val displays: List<DisplayInfo> = emptyList(),
    val windows: List<WindowInfo> = emptyList(),
    val snapshot: UiSnapshot? = null,
    val screenshot: ScreenshotRef? = null,
)

@Serializable
data class ProcessInfo(
    val pid: Int,
    val name: String,
    val user: String? = null,
    val cpuPct: Float? = null,
    val memMb: Long? = null,
    val command: String? = null,
    /** Session or agent that owns the process, if the host started it. */
    val owner: String? = null,
    val state: String? = null,
)

@Serializable
data class GuiWindowInfo(
    val id: String,
    val title: String? = null,
    val appName: String? = null,
    val pid: Int? = null,
    val bounds: Rect = Rect.EMPTY,
    val isFocused: Boolean = false,
    val display: String? = null,
)

@Serializable
enum class LogKind { NORMAL, WARNING, ERROR, CRASH, ANR, NATIVE_CRASH }

@Serializable
data class LogEvent(
    val timestampMs: Long,
    val level: String,
    val tag: String,
    val pid: Int = 0,
    val uid: Int? = null,
    val packageName: String? = null,
    val message: String,
    val kind: LogKind = LogKind.NORMAL,
    /** Id of the agent action running when this was logged (spec §18 correlation). */
    val actionId: String? = null,
)

@Serializable
data class FsChange(
    val path: String,
    /** created | modified | deleted */
    val kind: String,
    val timestampMs: Long,
    val sizeBytes: Long? = null,
)

@Serializable
data class BuildInfo(
    val id: String,
    val command: String,
    val cwd: String? = null,
    /** running | succeeded | failed */
    val state: String,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val exitCode: Int? = null,
    val tail: List<String> = emptyList(),
)

@Serializable
data class SessionBrief(
    val id: String,
    val title: String,
    val state: String,
    val activeAgents: Int = 0,
    val displayIds: List<Int> = emptyList(),
)

/**
 * Normalised view of device and environment state (spec §15). Each backend fills the parts it
 * knows; the agent never needs per-backend reasoning logic.
 */
@Serializable
data class Observation(
    val timestampMs: Long,
    val sessionId: String? = null,
    val device: DeviceState? = null,
    val android: AndroidState? = null,
    val processes: List<ProcessInfo> = emptyList(),
    val guiWindows: List<GuiWindowInfo> = emptyList(),
    val guiTrees: List<UiTree> = emptyList(),
    val shellTail: List<String> = emptyList(),
    val logs: List<LogEvent> = emptyList(),
    val fsChanges: List<FsChange> = emptyList(),
    val builds: List<BuildInfo> = emptyList(),
    val sessions: List<SessionBrief> = emptyList(),
)
