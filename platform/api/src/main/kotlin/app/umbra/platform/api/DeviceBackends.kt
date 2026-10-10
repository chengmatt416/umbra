package app.umbra.platform.api

import app.umbra.core.model.observation.DeviceState
import app.umbra.core.model.observation.DisplayInfo
import app.umbra.core.model.observation.ForegroundInfo
import app.umbra.core.model.observation.LogEvent
import app.umbra.core.model.observation.ProcessInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import app.umbra.core.model.action.LogFilter
import app.umbra.core.model.action.SysInfoQuery

// ---------------------------------------------------------------------------------------------
// Apps and packages
// ---------------------------------------------------------------------------------------------

data class AppLaunch(
    val packageName: String? = null,
    val activity: String? = null,
    val intentAction: String? = null,
    val uri: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val displayId: Int? = null,
)

data class LaunchOutcome(
    val started: Boolean,
    /** Component the system resolved, e.g. `com.example/.MainActivity`. */
    val component: String? = null,
    val message: String = "",
)

interface AppBackend : Backend {
    suspend fun launch(launch: AppLaunch): LaunchOutcome

    suspend fun stop(packageName: String, force: Boolean = true)

    /** Resumed activity per display. A null [displayId] means every display. */
    suspend fun foreground(displayId: Int? = null): List<ForegroundInfo>

    /** Brings an already running app's task to the front on its display. */
    suspend fun bringToFront(packageName: String, displayId: Int? = null): Boolean
}

@Serializable
data class PackageSummary(
    val packageName: String,
    val enabled: Boolean = true,
    val system: Boolean = false,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val apkPath: String? = null,
    val installer: String? = null,
    val debuggable: Boolean? = null,
    /** Linux uid of the app, to attribute log lines and processes to it. */
    val uid: Int? = null,
)

@Serializable
data class PackageDetails(
    val summary: PackageSummary,
    val requestedPermissions: List<String> = emptyList(),
    val grantedPermissions: List<String> = emptyList(),
    val firstInstallTimeMs: Long? = null,
    val lastUpdateTimeMs: Long? = null,
    val launcherActivity: String? = null,
    val uid: Int? = null,
)

interface PackageBackend : Backend {
    suspend fun list(filter: String? = null, includeSystem: Boolean = false, userId: Int = 0): List<PackageSummary>

    suspend fun details(packageName: String, userId: Int = 0): PackageDetails?

    suspend fun install(apkPath: String, replace: Boolean = true, grantAll: Boolean = false, userId: Int = 0): String

    suspend fun uninstall(packageName: String, keepData: Boolean = false, userId: Int = 0): String

    suspend fun clearData(packageName: String, userId: Int = 0): String

    suspend fun setEnabled(packageName: String, enabled: Boolean, userId: Int = 0): String

    suspend fun grant(packageName: String, permission: String, userId: Int = 0): String

    suspend fun revoke(packageName: String, permission: String, userId: Int = 0): String
}

// ---------------------------------------------------------------------------------------------
// System state
// ---------------------------------------------------------------------------------------------

@Serializable
data class SysInfo(
    val query: SysInfoQuery,
    /** Human-readable, already trimmed to a size the agent can read. */
    val text: String,
    val data: JsonElement? = null,
)

interface SysInfoBackend : Backend {
    suspend fun query(query: SysInfoQuery, argument: String? = null, maxChars: Int = 8_000): SysInfo

    suspend fun deviceState(): DeviceState
}

interface ProcessBackend : Backend {
    suspend fun list(nameFilter: String? = null): List<ProcessInfo>

    suspend fun info(pid: Int): ProcessInfo?

    suspend fun kill(pid: Int, signal: Int = 15)

    suspend fun isRunning(name: String): Boolean
}

// ---------------------------------------------------------------------------------------------
// Files
// ---------------------------------------------------------------------------------------------

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0,
    val modifiedMs: Long = 0,
    val mode: String? = null,
)

interface FileBackend : Backend {
    /** Reads at most [maxBytes] of UTF-8 text. */
    suspend fun read(path: String, maxBytes: Int, privileged: Boolean = false): String

    suspend fun write(path: String, content: String, append: Boolean = false, privileged: Boolean = false)

    suspend fun list(path: String, privileged: Boolean = false): List<FileEntry>

    suspend fun stat(path: String, privileged: Boolean = false): FileEntry?

    suspend fun mkdir(path: String, privileged: Boolean = false)

    suspend fun delete(path: String, recursive: Boolean = false, privileged: Boolean = false)

    suspend fun move(from: String, to: String, privileged: Boolean = false)
}

// ---------------------------------------------------------------------------------------------
// Displays and screenshots
// ---------------------------------------------------------------------------------------------

data class VirtualDisplaySpec(
    val name: String,
    val width: Int = 1080,
    val height: Int = 2400,
    val densityDpi: Int = 420,
    val ownerSessionId: String? = null,
)

interface DisplayBackend : Backend {
    suspend fun list(): List<DisplayInfo>

    suspend fun create(spec: VirtualDisplaySpec): DisplayInfo

    suspend fun destroy(displayId: Int)

    /** Virtual displays this app created in an earlier process and that are still alive (spec §9). */
    suspend fun ownedDisplays(): List<DisplayInfo>
}

/** An encoded image (PNG or JPEG). [width] and [height] are the pixel size of the decoded image. */
class CapturedImage(
    val bytes: ByteArray,
    val mime: String,
    val width: Int,
    val height: Int,
) {
    override fun toString() = "CapturedImage($mime ${width}x$height ${bytes.size}B)"
}

interface ScreenshotBackend : Backend {
    suspend fun capture(displayId: Int?): CapturedImage
}

// ---------------------------------------------------------------------------------------------
// Logs, notifications, clipboard
// ---------------------------------------------------------------------------------------------

interface LogBackend : Backend {
    /** The buffered log, newest last. */
    suspend fun read(filter: LogFilter, maxLines: Int): List<LogEvent>

    /** Live entries from now on. The flow ends when the collector cancels or logcat dies. */
    fun stream(filter: LogFilter): Flow<LogEvent>

    suspend fun clear()
}

data class NotificationInfo(
    val key: String,
    val packageName: String,
    val title: String? = null,
    val text: String? = null,
    val postedAtMs: Long = 0,
    val actions: List<String> = emptyList(),
    val ongoing: Boolean = false,
    val clearable: Boolean = true,
    val canReply: Boolean = false,
)

interface NotificationBackend : Backend {
    suspend fun list(): List<NotificationInfo>

    suspend fun open(key: String): Boolean

    suspend fun dismiss(key: String): Boolean

    suspend fun pressAction(key: String, index: Int): Boolean

    suspend fun reply(key: String, text: String): Boolean
}

interface ClipboardBackend : Backend {
    suspend fun get(): String?

    suspend fun set(text: String)
}
