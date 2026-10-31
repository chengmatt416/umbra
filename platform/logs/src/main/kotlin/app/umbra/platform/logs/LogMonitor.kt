package app.umbra.platform.logs

import app.umbra.core.model.Clock
import app.umbra.core.model.action.LogFilter
import app.umbra.core.model.observation.LogEvent
import app.umbra.core.model.observation.LogKind
import app.umbra.platform.api.LogBackend
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface MonitorEvent {
    /** A crash, ANR or native crash appeared ([isNew]) or gained detail. */
    data class CrashDetected(val report: CrashReport, val isNew: Boolean) : MonitorEvent

    /** A line matched a watch the agent registered. */
    data class WatchHit(val watch: String, val event: LogEvent) : MonitorEvent
}

data class LogMonitorConfig(
    /**
     * Warnings and above from everyone, plus the tags that carry crash evidence and process starts at
     * lower levels. Cheap enough to leave running for a whole session.
     */
    val filter: LogFilter = LogFilter(
        minLevel = "W",
        tagLevels = mapOf("AndroidRuntime" to "V", "ActivityManager" to "I", "DEBUG" to "I", "libc" to "F"),
        buffers = listOf("main", "system", "crash"),
    ),
    val recentCapacity: Int = 4_000,
    val notableCapacity: Int = 1_000,
    val restartDelayMs: Long = 500,
    val maxRestartDelayMs: Long = 10_000,
)

data class LogSummary(
    val crashes: List<CrashReport>,
    val errorCount: Int,
    val warningCount: Int,
    /** Most frequent error messages as `tag: text` with their counts. */
    val topErrors: List<Pair<String, Int>>,
) {
    val isClean: Boolean get() = crashes.isEmpty() && errorCount == 0

    fun text(): String = buildString {
        if (crashes.isEmpty()) append("No crashes or ANRs") else append(crashes.size).append(" crash event(s):\n").append(crashes.joinToString("\n") { "- " + it.summary() })
        append("\n$errorCount error and $warningCount warning log line(s)")
        if (topErrors.isNotEmpty()) append(".\nMost frequent errors: ").append(topErrors.joinToString("; ") { "${it.first} (x${it.second})" })
    }
}

/**
 * Watches the device log for the whole session (spec §18): collects warnings, errors and crash
 * evidence, assembles crash reports, learns which pid belongs to which package, and ties events to
 * the agent action that was running ([ActionJournal]). The agent reads it through the `logs` tool and
 * the orchestrator pushes [events] into the session as they happen.
 */
class LogMonitor(
    private val backend: LogBackend,
    private val journal: ActionJournal,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val config: LogMonitorConfig = LogMonitorConfig(),
) {
    private val lock = Any()
    // A new stream waits for cancellation to close the previous logcat, even across rapid stop/start calls.
    private val streamLock = Mutex()
    private val recent = ArrayDeque<LogEvent>()
    private val notable = ArrayDeque<LogEvent>()
    private val reports = LinkedHashMap<Int, CrashReport>()
    private val detector = CrashDetector()
    private val pidPackages = ConcurrentHashMap<Int, String>()
    private val watches = ConcurrentHashMap<String, Pair<LogFilterMatcher, LogFilter>>()
    private val flow = MutableSharedFlow<MonitorEvent>(extraBufferCapacity = 256)
    @Volatile
    private var job: Job? = null

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var linesSeen: Long = 0
        private set

    val events: SharedFlow<MonitorEvent> = flow.asSharedFlow()

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch { streamLock.withLock { collectForever() } }
        }
    }

    /** Pauses collection to save battery; buffered evidence still belongs to the sessions that collected it. */
    fun stop() {
        synchronized(lock) {
            job?.cancel()
        }
    }

    /**
     * Ends the running log stream and starts a new one, which asks the backend again. A stream started early, before
     * root or the helper was available, can be running as the app itself; this moves it to the better one.
     */
    fun restart() {
        synchronized(lock) {
            job?.cancel()
            job = scope.launch { streamLock.withLock { collectForever() } }
        }
    }

    private suspend fun collectForever() {
        var delayMs = config.restartDelayMs
        while (currentCoroutineContext().isActive) {
            try {
                backend.stream(config.filter).collect { event ->
                    delayMs = config.restartDelayMs
                    ingest(event)
                }
                lastError = "log stream ended"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e::class.simpleName
            }
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(config.maxRestartDelayMs)
        }
    }

    /** Public so a one-shot `logcat -d` read can be fed through the same pipeline, and for tests. */
    fun ingest(raw: LogEvent) {
        linesSeen++
        learnPid(raw)
        val pkg = raw.packageName ?: pidPackages[raw.pid]
        val actionId = raw.actionId ?: journal.actionAt(raw.timestampMs, packageName = pkg)?.actionId
        val event = raw.copy(packageName = pkg, actionId = actionId)

        var update: CrashDetector.Update? = null
        synchronized(lock) {
            recent.addLast(event)
            while (recent.size > config.recentCapacity) recent.removeFirst()
            if (event.kind != LogKind.NORMAL || event.level == "E" || event.level == "W") {
                notable.addLast(event)
                while (notable.size > config.notableCapacity) notable.removeFirst()
            }
            update = detector.accept(event)?.let { u ->
                // Attribute to a package through the pid when the lines themselves did not name one.
                val withPkg = if (u.report.packageName == null) pidPackages[u.report.pid]?.let { u.report.copy(packageName = it) } ?: u.report else u.report
                reports[withPkg.id] = withPkg
                withPkg.packageName?.let { if (withPkg.pid != 0) pidPackages[withPkg.pid] = it }
                CrashDetector.Update(withPkg, u.isNew)
            }
        }
        update?.let { flow.tryEmit(MonitorEvent.CrashDetected(it.report, it.isNew)) }
        for ((name, pair) in watches) {
            if (pair.first.matches(event)) flow.tryEmit(MonitorEvent.WatchHit(name, event))
        }
    }

    private fun learnPid(e: LogEvent) {
        if (e.tag != "ActivityManager" || !e.message.startsWith("Start proc")) return
        Regex("""Start proc (\d+):([\w.]+)/""").find(e.message)?.let { m ->
            pidPackages[m.groupValues[1].toInt()] = m.groupValues[2]
        }
    }

    /** Teach the monitor a pid→package mapping the app learned elsewhere (process list, own launches). */
    fun registerPid(pid: Int, packageName: String) {
        pidPackages[pid] = packageName
    }

    fun packageOf(pid: Int): String? = pidPackages[pid]

    // ---- queries ----

    fun crashes(sinceMs: Long = 0, packageName: String? = null): List<CrashReport> = synchronized(lock) {
        reports.values.filter { it.timestampMs >= sinceMs && (packageName == null || it.packageName == packageName) }
    }

    /** True when [packageName] crashed or hit an ANR at or after [sinceMs]. */
    fun hasCrashed(packageName: String, sinceMs: Long): Boolean = crashes(sinceMs, packageName).isNotEmpty()

    /** Buffered events (newest last) that match [filter], for `log_matches` checks and quick reads. */
    fun query(filter: LogFilter, maxLines: Int, sinceMs: Long = 0): List<LogEvent> {
        val matcher = LogFilterMatcher(filter, ::packageOf)
        return synchronized(lock) { recent.filter { it.timestampMs >= sinceMs && matcher.matches(it) }.takeLast(maxLines) }
    }

    fun summary(sinceMs: Long = 0, packageName: String? = null): LogSummary {
        val (events, found) = synchronized(lock) {
            notable.filter { it.timestampMs >= sinceMs && (packageName == null || it.packageName == packageName) } to
                reports.values.filter { it.timestampMs >= sinceMs && (packageName == null || it.packageName == packageName) }
        }
        val errors = events.filter { it.level == "E" || it.level == "F" }
        val top = errors.groupingBy { "${it.tag}: ${it.message.trim().take(80)}" }.eachCount().entries
            .sortedByDescending { it.value }.take(5).map { it.key to it.value }
        return LogSummary(found, errorCount = errors.size, warningCount = events.count { it.level == "W" }, topErrors = top)
    }

    // ---- watches ----

    fun watch(name: String, filter: LogFilter) {
        watches[name] = LogFilterMatcher(filter, ::packageOf) to filter
    }

    fun stopWatch(name: String): Boolean = watches.remove(name) != null

    fun watchNames(): Set<String> = watches.keys.toSet()
}
