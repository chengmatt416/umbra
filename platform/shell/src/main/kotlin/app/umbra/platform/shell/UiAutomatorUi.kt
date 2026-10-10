package app.umbra.platform.shell

import app.umbra.core.model.Clock
import app.umbra.core.model.Point
import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.action.GlobalAction
import app.umbra.core.model.capability.UiBackendKind
import app.umbra.core.model.observation.NodeAction
import app.umbra.core.model.observation.UiNode
import app.umbra.core.model.observation.UiSnapshot
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.AndroidKeys
import app.umbra.platform.api.NodeActionArgs
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellInput
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.SnapshotOptions
import app.umbra.platform.api.UiBackend
import app.umbra.platform.api.UiFeature
import app.umbra.platform.api.exec
import java.util.concurrent.atomic.AtomicLong
import javax.xml.parsers.ParserConfigurationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xml.sax.SAXException

/**
 * Drives the screen with the tools every Android image ships: `uiautomator dump --windows` for the
 * tree of every window of every display, and `input` for touches, keys and text. It needs shell
 * identity (Shizuku, `su 2000`, root, or adb) but no accessibility service, so it is the way in when
 * accessibility is unavailable (Advanced Protection, restricted settings) and the way a development
 * machine drives an emulator.
 *
 * It is slower than the accessibility service (a dump starts a JVM, ~1 s on a phone) and has no node
 * actions: elements are pressed by their coordinates, which is why the pipeline falls back to gestures.
 */
class UiAutomatorUi(
    private val shell: ShellBackend,
    private val clock: Clock = Clock.System,
    private val workDir: String = "/data/local/tmp/umbra",
) : UiBackend {
    override val name = "uiautomator_shell"
    override val kind = UiBackendKind.ROOT_SHELL
    override val features = setOf(UiFeature.GESTURES, UiFeature.KEY_EVENTS, UiFeature.GLOBAL_ACTIONS, UiFeature.TEXT_INJECTION, UiFeature.MULTI_DISPLAY)

    // Only one UiAutomation connection may exist system-wide at a time.
    private val dumpLock = Mutex()
    private val counter = AtomicLong()

    override suspend fun isAvailable() = shell.isAvailable()

    override suspend fun snapshot(options: SnapshotOptions): UiSnapshot = dumpLock.withLock {
        val file = "$workDir/ui-${System.nanoTime()}-${counter.incrementAndGet()}.xml"
        // The dump goes to a file (it has no stdout mode); read it back and always clean up.
        val command = "mkdir -p $workDir; uiautomator dump --windows $file >/dev/null 2>$file.err; " +
            "if [ -s $file ]; then cat $file; code=0; else cat $file.err >&2; code=3; fi; rm -f $file $file.err; exit \$code"
        var last: Exception? = null
        repeat(3) { attempt ->
            val r = shell.exec(ShellRequest(command, timeoutMs = 40_000, maxOutputBytes = 16 * 1024 * 1024))
            if (r.timedOut) throw BackendException(ErrorCode.TIMEOUT, "uiautomator dump timed out", retryable = true)
            if (r.ok && r.stdout.isNotBlank()) {
                return@withLock try {
                    val parsed = UiAutomatorXml.parse(r.stdout, clock.nowMs(), options.maxNodes)
                    if (options.displayId == null) parsed else parsed.copy(windows = parsed.windows.filter { it.displayId == options.displayId })
                } catch (e: SAXException) {
                    throw BackendException(ErrorCode.INTERNAL, "uiautomator produced unreadable XML: ${e.message}", cause = e)
                } catch (e: ParserConfigurationException) {
                    throw BackendException(ErrorCode.INTERNAL, "XML parser unavailable: ${e.message}", cause = e)
                }
            }
            val reason = r.stderr.ifBlank { r.stdout }.trim()
            last = BackendException(ErrorCode.BACKEND_UNAVAILABLE, "uiautomator dump failed: ${reason.take(200)}", retryable = true)
            // Another UiAutomation client (a test run, Shizuku's server) holds the connection: wait and retry.
            if (attempt < 2) delay(400L * (attempt + 1))
        }
        throw last!!
    }

    override suspend fun performAction(node: UiNode, action: NodeAction, args: NodeActionArgs): Boolean =
        throw BackendException(ErrorCode.UNSUPPORTED, "uiautomator dumps cannot act on nodes; use their coordinates")

    private suspend fun input(command: String) {
        val r = shell.exec(command, timeoutMs = 15_000)
        if (r.timedOut) throw BackendException(ErrorCode.TIMEOUT, "input timed out", retryable = true)
        if (!r.ok) throw BackendException(ErrorCode.COMMAND_FAILED, "'${command.take(80)}' failed: ${r.combined.take(200)}")
    }

    override suspend fun tap(displayId: Int?, point: Point) = input(ShellInput.tap(displayId, point))

    override suspend fun longPress(displayId: Int?, point: Point, durationMs: Long) = input(ShellInput.longPress(displayId, point, durationMs))

    override suspend fun swipe(displayId: Int?, from: Point, to: Point, durationMs: Long) = input(ShellInput.swipe(displayId, from, to, durationMs))

    override suspend fun drag(displayId: Int?, from: Point, to: Point, holdMs: Long, durationMs: Long) = input(ShellInput.drag(displayId, from, to, durationMs))

    override suspend fun key(displayId: Int?, key: String) {
        val code = AndroidKeys.normalize(key) ?: throw BackendException(ErrorCode.INVALID_ARGUMENTS, "unknown key '$key'")
        input(ShellInput.key(displayId, code))
    }

    override suspend fun typeText(displayId: Int?, text: String) {
        if (!ShellInput.canInputText(text)) {
            throw BackendException(ErrorCode.UNSUPPORTED, "input text can only type printable ASCII without '%s'; use set_text or the clipboard for this text")
        }
        input(ShellInput.text(displayId, text))
    }

    override suspend fun global(action: GlobalAction): Boolean {
        val command = when (action) {
            GlobalAction.BACK -> ShellInput.key(null, "KEYCODE_BACK")
            GlobalAction.HOME -> ShellInput.key(null, "KEYCODE_HOME")
            GlobalAction.RECENTS -> ShellInput.key(null, "KEYCODE_APP_SWITCH")
            GlobalAction.NOTIFICATIONS -> "cmd statusbar expand-notifications"
            GlobalAction.QUICK_SETTINGS -> "cmd statusbar expand-settings"
            GlobalAction.POWER_DIALOG -> ShellInput.key(null, "KEYCODE_POWER", longPress = true)
            GlobalAction.LOCK_SCREEN -> ShellInput.key(null, "KEYCODE_SLEEP")
            GlobalAction.TAKE_SCREENSHOT -> return false
        }
        input(command)
        return true
    }
}
