package app.umbra.platform.logs

import app.umbra.core.model.observation.LogEvent
import app.umbra.core.model.observation.LogKind
import kotlinx.serialization.Serializable

/** A crash, ANR or native crash assembled from several log lines. Updated in place as lines arrive. */
@Serializable
data class CrashReport(
    val id: Int,
    val kind: LogKind,
    val timestampMs: Long,
    val pid: Int,
    val packageName: String? = null,
    val thread: String? = null,
    /** `java.lang.NullPointerException`. */
    val exception: String? = null,
    val message: String? = null,
    /** First stack frames, innermost first, without the leading `at`. */
    val stack: List<String> = emptyList(),
    /** Deepest `Caused by:` line. */
    val cause: String? = null,
    /** `SIGSEGV` for native crashes. */
    val signal: String? = null,
    /** ANR reason. */
    val reason: String? = null,
    val actionId: String? = null,
) {
    /** One paragraph for the agent: what died, why, and where. */
    fun summary(): String = buildString {
        val who = packageName ?: "pid $pid"
        when (kind) {
            LogKind.CRASH -> {
                append("CRASH in $who")
                exception?.let { append(": ").append(it) }
                message?.let { append(": ").append(it.take(200)) }
                cause?.let { append(" (").append(it.take(160)).append(')') }
                stack.firstOrNull()?.let { append(" at ").append(it) }
            }
            LogKind.ANR -> {
                append("ANR in $who")
                reason?.let { append(": ").append(it.take(200)) }
            }
            LogKind.NATIVE_CRASH -> {
                append("NATIVE CRASH in $who")
                signal?.let { append(" (").append(it).append(')') }
                stack.firstOrNull()?.let { append(" at ").append(it) }
            }
            else -> append("event in $who")
        }
        actionId?.let { append(" [during action ").append(it).append(']') }
    }
}

/**
 * Turns the log lines that make up a crash into one [CrashReport] (spec §18).
 *
 * A Java crash is a run of `AndroidRuntime` lines from one process starting at `FATAL EXCEPTION`,
 * an ANR is a few `ActivityManager` lines starting at `ANR in`, and a native crash is a
 * `libc: Fatal signal` line plus the `DEBUG` tombstone. The report is created at the first line, so
 * a watcher learns about it immediately, and refined by the lines that follow.
 */
class CrashDetector(private val maxFrames: Int = 12) {
    class Update(val report: CrashReport, val isNew: Boolean)

    private var nextId = 1
    private val open = HashMap<Key, CrashReport>()
    private val nativeByPid = HashMap<Int, CrashReport>()

    private data class Key(val kind: LogKind, val pid: Int)

    /** Feeds one event; returns the report it created or changed, or null if it is unrelated. */
    fun accept(event: LogEvent): Update? = when {
        event.tag == "AndroidRuntime" -> javaCrash(event)
        event.tag == "ActivityManager" -> anr(event)
        event.tag == "libc" && event.message.startsWith("Fatal signal") -> nativeSignal(event)
        event.tag == "DEBUG" -> tombstone(event)
        else -> null
    }

    private fun javaCrash(e: LogEvent): Update? {
        val key = Key(LogKind.CRASH, e.pid)
        val text = e.message
        if (text.startsWith("FATAL EXCEPTION")) {
            val thread = text.substringAfter(':', "").trim().ifEmpty { null }
            val report = CrashReport(nextId++, LogKind.CRASH, e.timestampMs, e.pid, thread = thread, actionId = e.actionId)
            open[key] = report
            return Update(report, isNew = true)
        }
        val current = open[key] ?: return null
        val trimmed = text.trim()
        val updated = when {
            trimmed.startsWith("Process:") -> {
                val pkg = trimmed.removePrefix("Process:").substringBefore(',').trim().ifEmpty { null }
                current.copy(packageName = pkg)
            }
            trimmed.startsWith("at ") || trimmed.startsWith("\tat ") ->
                if (current.stack.size < maxFrames) current.copy(stack = current.stack + trimmed.removePrefix("at ").trim()) else current
            trimmed.startsWith("Caused by:") -> current.copy(cause = trimmed.removePrefix("Caused by:").trim())
            trimmed.startsWith("...") || trimmed.isEmpty() -> current
            current.exception == null -> {
                val cls = trimmed.substringBefore(':').trim()
                if (looksLikeThrowable(cls)) {
                    current.copy(exception = cls, message = trimmed.substringAfter(':', "").trim().ifEmpty { null })
                } else {
                    current
                }
            }
            // Messages can span several lines; keep the first few.
            current.stack.isEmpty() && current.cause == null && (current.message?.length ?: 0) < 400 ->
                current.copy(message = listOfNotNull(current.message, trimmed).joinToString(" "))
            else -> current
        }
        if (updated == current) return null
        open[key] = updated
        return Update(updated, isNew = false)
    }

    private fun anr(e: LogEvent): Update? {
        val text = e.message.trim()
        if (text.startsWith("ANR in ")) {
            val pkg = text.removePrefix("ANR in ").substringBefore(' ').trim()
            val report = CrashReport(nextId++, LogKind.ANR, e.timestampMs, pid = 0, packageName = pkg, actionId = e.actionId)
            // The pid arrives on the next line; key by package until then.
            open[Key(LogKind.ANR, 0)] = report
            return Update(report, isNew = true)
        }
        val current = open[Key(LogKind.ANR, 0)] ?: return null
        val updated = when {
            text.startsWith("PID:") -> current.copy(pid = text.removePrefix("PID:").trim().toIntOrNull() ?: 0)
            text.startsWith("Reason:") -> current.copy(reason = text.removePrefix("Reason:").trim())
            else -> return null
        }
        open[Key(LogKind.ANR, 0)] = updated
        return Update(updated, isNew = false)
    }

    private fun nativeSignal(e: LogEvent): Update {
        // "Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 4400 (crashy), pid 4400 (crashy)"
        val m = Regex("""Fatal signal \d+ \((\w+)\)""").find(e.message)
        val pid = Regex("""pid (\d+)""").find(e.message)?.groupValues?.get(1)?.toIntOrNull() ?: e.pid
        val thread = Regex("""tid \d+ \(([^)]*)\)""").find(e.message)?.groupValues?.get(1)
        val report = CrashReport(nextId++, LogKind.NATIVE_CRASH, e.timestampMs, pid, thread = thread, signal = m?.groupValues?.get(1), actionId = e.actionId)
        nativeByPid[pid] = report
        return Update(report, isNew = true)
    }

    private fun tombstone(e: LogEvent): Update? {
        val text = e.message.trim()
        // "pid: 4400, tid: 4400, name: crashy  >>> com.example.crashy <<<"
        Regex("""pid: (\d+), tid: \d+, name: .*?>>> (\S+) <<<""").find(text)?.let { m ->
            val pid = m.groupValues[1].toInt()
            val pkg = m.groupValues[2]
            val existing = nativeByPid[pid]
            return if (existing != null) {
                val updated = existing.copy(packageName = pkg)
                nativeByPid[pid] = updated
                Update(updated, isNew = false)
            } else {
                // Tombstone without the earlier libc line (the crash buffer was cleared in between).
                val report = CrashReport(nextId++, LogKind.NATIVE_CRASH, e.timestampMs, pid, packageName = pkg, actionId = e.actionId)
                nativeByPid[pid] = report
                Update(report, isNew = true)
            }
        }
        Regex("""signal \d+ \((\w+)\)""").find(text)?.let { m ->
            val report = lastNativeReport() ?: return null
            if (report.signal == m.groupValues[1]) return null
            val updated = report.copy(signal = m.groupValues[1])
            nativeByPid[report.pid] = updated
            return Update(updated, isNew = false)
        }
        // Backtrace frame: "#00 pc 0000000000012345  /path/libnative.so (Java_com_example_crash+16)"
        Regex("""^#\d+\s+pc\s+[0-9a-f]+\s+(.*)$""").find(text)?.let { m ->
            val report = lastNativeReport() ?: return null
            if (report.stack.size >= maxFrames) return null
            val updated = report.copy(stack = report.stack + m.groupValues[1].trim())
            nativeByPid[report.pid] = updated
            return Update(updated, isNew = false)
        }
        return null
    }

    private fun lastNativeReport(): CrashReport? = nativeByPid.values.maxByOrNull { it.id }

    private fun looksLikeThrowable(name: String): Boolean =
        name.isNotEmpty() && ' ' !in name && (name.endsWith("Exception") || name.endsWith("Error") || name.endsWith("Throwable") || '.' in name)
}
