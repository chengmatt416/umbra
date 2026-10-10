package app.umbra.platform.shell

import app.umbra.core.model.Clock
import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.action.LogFilter
import app.umbra.core.model.observation.LogEvent
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.CapturedImage
import app.umbra.platform.api.LogBackend
import app.umbra.platform.api.ScreenshotBackend
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellEvent
import app.umbra.platform.api.ShellInput.quote
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.exec
import app.umbra.platform.logs.LogFilterMatcher
import app.umbra.platform.logs.LogcatParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull

/**
 * The device log through `logcat`. Lines carry an absolute (epoch) timestamp and the uid of the
 * writer, so they can be ordered against agent actions and attributed to apps without guessing at
 * time zones or pids.
 */
class ShellLogs(private val shell: ShellBackend, private val clock: Clock = Clock.System) : LogBackend {
    override val name = "shell_logcat"

    override suspend fun isAvailable() = shell.isAvailable()

    /** `Tag:Level` filter specs; with explicit tags everything else is silenced (`*:S`). */
    internal fun spec(f: LogFilter): String {
        val parts = mutableListOf<String>()
        for ((tag, level) in f.tagLevels) parts += "$tag:${level.uppercase()}"
        if (f.tags.isNotEmpty()) {
            for (t in f.tags) if (t !in f.tagLevels) parts += "$t:${f.minLevel.uppercase()}"
            parts += "*:S"
        } else {
            parts += "*:${f.minLevel.uppercase()}"
        }
        return parts.joinToString(" ") { quote(it) }
    }

    private fun options(f: LogFilter): String = buildString {
        append("-v threadtime -v epoch -v uid")
        if (f.buffers.isNotEmpty()) append(" -b ").append(f.buffers.joinToString(","))
        f.pid?.let { append(" --pid=$it") }
    }

    /**
     * logcat names the writer by uid, not by package, and a crashed process is gone, so neither a pid lookup nor a
     * running-process lookup can find an app's lines. The package's uid is stable for as long as it is installed.
     */
    private suspend fun uidOf(packageName: String): Int? {
        val r = shell.exec("pm list packages -U ${quote(packageName)}", timeoutMs = 15_000)
        if (!r.ok) return null
        return Regex("""package:${Regex.escape(packageName)}\s+uid:(\d+)""").find(r.stdout)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun read(filter: LogFilter, maxLines: Int): List<LogEvent> {
        val uid = filter.packageName?.takeIf { filter.uid == null }?.let { uidOf(it) }
        val effective = if (uid != null) filter.copy(packageName = null, uid = uid) else filter
        // -t counts lines after logcat's own filtering but before ours (regex, package), so over-fetch.
        val fetch = (maxLines * 4).coerceIn(maxLines, 20_000)
        val r = shell.exec("logcat -d ${options(effective)} -t $fetch ${spec(effective)}", timeoutMs = 30_000, maxOutputBytes = 16 * 1024 * 1024)
        if (r.timedOut) throw BackendException(ErrorCode.TIMEOUT, "logcat timed out", retryable = true)
        if (!r.ok) throw BackendException(ErrorCode.COMMAND_FAILED, "logcat failed: ${r.combined.take(200)}")
        val now = clock.nowMs()
        val matcher = LogFilterMatcher(effective)
        return r.stdout.lineSequence().mapNotNull { LogcatParser.parse(it, now) }.filter(matcher::matches).toList().takeLast(maxLines)
    }

    override fun stream(filter: LogFilter): Flow<LogEvent> {
        val matcher = LogFilterMatcher(filter)
        return shell.stream(ShellRequest("logcat ${options(filter)} -T 1 ${spec(filter)}", timeoutMs = Long.MAX_VALUE))
            .mapNotNull { e -> (e as? ShellEvent.Out)?.let { LogcatParser.parse(it.line, clock.nowMs()) } }
            .filter(matcher::matches)
    }

    override suspend fun clear() {
        val r = shell.exec("logcat -c")
        if (!r.ok) throw BackendException(ErrorCode.COMMAND_FAILED, "logcat -c failed: ${r.combined.take(200)}")
    }
}

/** `screencap`: the default display and other physical displays. Virtual displays need another backend. */
class ShellScreenshots(private val shell: ShellBackend, private val displays: OverlayDisplays = OverlayDisplays(shell)) : ScreenshotBackend {
    override val name = "shell_screencap"

    override suspend fun isAvailable() = shell.isAvailable()

    override suspend fun capture(displayId: Int?): CapturedImage {
        val flag = if (displayId == null || displayId == 0) "" else {
            val info = displays.list().firstOrNull { it.id == displayId }
                ?: throw BackendException(ErrorCode.NOT_FOUND, "no display $displayId")
            // screencap addresses physical displays by the number in their unique id (`local:4619...`).
            val physical = info.uniqueId?.substringAfter("local:", "")?.takeIf { it.isNotEmpty() }
                ?: throw BackendException(ErrorCode.UNSUPPORTED, "screencap cannot capture display $displayId (${info.name}): it is not a physical display")
            " -d $physical"
        }
        val r = shell.execBytes(ShellRequest("screencap -p$flag", timeoutMs = 20_000))
        if (!r.ok) throw BackendException(ErrorCode.COMMAND_FAILED, "screencap failed (exit ${r.exitCode}): ${r.stderr.take(200)}")
        val png = r.stdout
        val (w, h) = pngSize(png) ?: throw BackendException(ErrorCode.INTERNAL, "screencap did not return a PNG (${png.size} bytes)")
        return CapturedImage(png, "image/png", w, h)
    }

    internal companion object {
        private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

        /** Width and height from the IHDR chunk, without decoding the image. */
        fun pngSize(png: ByteArray): Pair<Int, Int>? {
            if (png.size < 24 || !png.copyOfRange(0, 4).contentEquals(SIGNATURE)) return null
            fun int(at: Int) = ((png[at].toInt() and 0xFF) shl 24) or ((png[at + 1].toInt() and 0xFF) shl 16) or ((png[at + 2].toInt() and 0xFF) shl 8) or (png[at + 3].toInt() and 0xFF)
            return int(16) to int(20)
        }
    }
}
