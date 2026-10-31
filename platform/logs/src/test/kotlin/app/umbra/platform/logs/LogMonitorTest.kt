package app.umbra.platform.logs

import app.umbra.core.model.Clock
import app.umbra.core.model.action.LogFilter
import app.umbra.core.model.observation.LogEvent
import app.umbra.core.model.observation.LogKind
import app.umbra.platform.api.LogBackend
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LogMonitorTest {
    private class FakeLog : LogBackend {
        override val name = "fake_log"
        val bus = MutableSharedFlow<LogEvent>(extraBufferCapacity = 1024)
        val streamsOpened = AtomicInteger()
        var failNext = false
        var closeDelayMs = 0L

        override suspend fun isAvailable() = true

        override suspend fun read(filter: LogFilter, maxLines: Int): List<LogEvent> = emptyList()

        override fun stream(filter: LogFilter): Flow<LogEvent> = flow {
            streamsOpened.incrementAndGet()
            if (failNext) {
                failNext = false
                throw java.io.IOException("logcat died")
            }
            try { bus.collect { emit(it) } }
            finally { if (closeDelayMs > 0) withContext(NonCancellable) { delay(closeDelayMs) } }
        }

        override suspend fun clear() {}
    }

    private class Harness(scope: CoroutineScope) {
        var now = 1_000_000L
        val clock = Clock { now }
        val backend = FakeLog()
        val journal = ActionJournal(clock)
        val monitor = LogMonitor(backend, journal, scope, clock, LogMonitorConfig(restartDelayMs = 100, maxRestartDelayMs = 400))
    }

    private fun ev(t: Long, level: String, tag: String, message: String, pid: Int = 100) =
        LogEvent(t, level, tag, pid, message = message, kind = LogcatParser.classify(level, tag, message))

    private fun TestScope.harness(): Harness = Harness(CoroutineScope(StandardTestDispatcher(testScheduler)))

    @Test
    fun aCrashIsAssembledAttributedAndPublished() = runTest {
        val h = harness()
        val published = mutableListOf<MonitorEvent>()
        val collector = launch(StandardTestDispatcher(testScheduler)) { h.monitor.events.collect { published += it } }
        h.monitor.start()
        runCurrent()

        h.backend.bus.emit(ev(1_000_000, "I", "ActivityManager", "Start proc 4321:com.example.crashy/u0a123 for next-top-activity {com.example.crashy/.LoginActivity}", pid = 800))
        h.backend.bus.emit(ev(1_000_100, "E", "AndroidRuntime", "FATAL EXCEPTION: main", pid = 4321))
        h.backend.bus.emit(ev(1_000_101, "E", "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 4321))
        runCurrent()

        val crash = published.filterIsInstance<MonitorEvent.CrashDetected>()
        assertTrue(crash.first().isNew)
        val report = crash.last().report
        assertEquals("com.example.crashy", report.packageName, "the package comes from the pid learned at process start")
        assertEquals("java.lang.NullPointerException", report.exception)
        assertTrue(h.monitor.hasCrashed("com.example.crashy", sinceMs = 999_000))
        assertFalse(h.monitor.hasCrashed("com.example.crashy", sinceMs = 1_001_000), "crashes before the check do not count")
        assertFalse(h.monitor.hasCrashed("com.other", sinceMs = 0))
        collector.cancel()
        h.monitor.stop()
    }

    @Test
    fun crashesAreTiedToTheActionThatWasRunning() = runTest {
        val h = harness()
        h.monitor.start()
        runCurrent()

        h.now = 1_000_000
        h.journal.begin("act-7", "s1", "tap", displayId = 0, packageName = "com.example.crashy")
        h.now = 1_000_080
        h.journal.end("act-7")

        h.backend.bus.emit(ev(1_000_150, "E", "AndroidRuntime", "FATAL EXCEPTION: main", pid = 4321))
        h.backend.bus.emit(ev(1_000_151, "E", "AndroidRuntime", "Process: com.example.crashy, PID: 4321", pid = 4321))
        runCurrent()

        val report = h.monitor.crashes().single()
        assertEquals("act-7", report.actionId, "a crash a moment after the tap is attributed to the tap")
        assertTrue("[during action act-7]" in report.summary())
        h.monitor.stop()
    }

    @Test
    fun summaryCountsErrorsAndListsTheMostFrequent() = runTest {
        val h = harness()
        h.monitor.start()
        runCurrent()
        repeat(3) { h.backend.bus.emit(ev(1_000_000L + it, "E", "Net", "request failed: 500", pid = 5)) }
        h.backend.bus.emit(ev(1_000_010, "E", "Db", "locked", pid = 5))
        repeat(2) { h.backend.bus.emit(ev(1_000_020L + it, "W", "Slow", "frame skipped", pid = 5)) }
        runCurrent()

        val summary = h.monitor.summary()
        assertEquals(4, summary.errorCount)
        assertEquals(2, summary.warningCount)
        assertEquals("Net: request failed: 500" to 3, summary.topErrors.first())
        assertFalse(summary.isClean)
        assertTrue("Most frequent errors: Net: request failed: 500 (x3)" in summary.text(), summary.text())
        h.monitor.stop()
    }

    @Test
    fun aCleanLogReportsNoProblems() = runTest {
        val h = harness()
        h.monitor.start()
        runCurrent()
        h.backend.bus.emit(ev(1_000_000, "I", "Boring", "all fine"))
        runCurrent()
        assertTrue(h.monitor.summary().isClean)
        assertTrue(h.monitor.summary().text().startsWith("No crashes or ANRs"))
        h.monitor.stop()
    }

    @Test
    fun watchesFireOnlyForMatchingLines() = runTest {
        val h = harness()
        val hits = mutableListOf<MonitorEvent.WatchHit>()
        val collector = launch(StandardTestDispatcher(testScheduler)) { h.monitor.events.collect { if (it is MonitorEvent.WatchHit) hits += it } }
        h.monitor.start()
        h.monitor.watch("login", LogFilter(tags = listOf("Login"), regex = "token"))
        runCurrent()

        h.backend.bus.emit(ev(1_000_000, "I", "Login", "starting"))
        h.backend.bus.emit(ev(1_000_001, "I", "Other", "token refreshed"))
        h.backend.bus.emit(ev(1_000_002, "I", "Login", "token rejected"))
        runCurrent()

        assertEquals(listOf("token rejected"), hits.map { it.event.message })
        assertEquals("login", hits.single().watch)
        assertTrue(h.monitor.stopWatch("login"))
        assertFalse(h.monitor.stopWatch("login"))
        collector.cancel()
        h.monitor.stop()
    }

    @Test
    fun queryReadsTheBufferedLinesThroughAFilter() = runTest {
        val h = harness()
        h.monitor.start()
        runCurrent()
        h.backend.bus.emit(ev(1_000_000, "I", "A", "one"))
        h.backend.bus.emit(ev(1_000_001, "E", "B", "two"))
        h.backend.bus.emit(ev(1_000_002, "E", "B", "three"))
        runCurrent()
        val errors = h.monitor.query(LogFilter(minLevel = "E"), maxLines = 10)
        assertEquals(listOf("two", "three"), errors.map { it.message })
        assertEquals(listOf("three"), h.monitor.query(LogFilter(minLevel = "E"), maxLines = 1).map { it.message })
        assertEquals(listOf("three"), h.monitor.query(LogFilter(minLevel = "E"), maxLines = 10, sinceMs = 1_000_002).map { it.message })
        h.monitor.stop()
    }

    @Test
    fun theMonitorRestartsWhenLogcatDiesAndBacksOff() = runTest {
        val h = harness()
        h.backend.failNext = true
        h.monitor.start()
        runCurrent()
        assertEquals(1, h.backend.streamsOpened.get())
        assertEquals("logcat died", h.monitor.lastError)

        advanceTimeBy(150)
        runCurrent()
        assertEquals(2, h.backend.streamsOpened.get(), "restarted after the first delay")

        h.backend.bus.emit(ev(1_000_000, "E", "T", "after restart"))
        runCurrent()
        assertEquals(1, h.monitor.query(LogFilter(), 10).size, "events flow again")
        h.monitor.stop()
        assertFalse(h.monitor.isRunning)
    }

    @Test
    fun restartingAsksTheBackendAgainAndLeavesOnlyOneStream() = runTest {
        val h = harness()
        h.monitor.start()
        runCurrent()
        assertEquals(1, h.backend.streamsOpened.get())
        assertEquals(1, h.backend.bus.subscriptionCount.value, "one reader of the log")

        h.monitor.restart()
        runCurrent()
        assertEquals(2, h.backend.streamsOpened.get(), "the backend was asked again")
        assertEquals(1, h.backend.bus.subscriptionCount.value, "the old stream was closed, not left running next to the new one")
        assertTrue(h.monitor.isRunning)

        h.backend.bus.emit(ev(1_000_000, "E", "T", "after the restart"))
        runCurrent()
        assertEquals(1, h.monitor.query(LogFilter(), 10).size, "events still flow once")
        h.monitor.stop()
    }

    @Test
    fun stoppingAndStartingKeepsEvidenceAndWaitsForTheOldStreamToClose() = runTest {
        val h = harness()
        h.backend.closeDelayMs = 100
        h.monitor.start()
        runCurrent()
        h.backend.bus.emit(ev(1_000_000, "E", "AndroidRuntime", "FATAL EXCEPTION: main", pid = 4321))
        h.backend.bus.emit(ev(1_000_001, "E", "AndroidRuntime", "Process: com.example.crashy, PID: 4321", pid = 4321))
        runCurrent()
        val recent = h.monitor.query(LogFilter(), 10)
        val summary = h.monitor.summary()
        h.monitor.stop()
        h.monitor.stop()
        assertFalse(h.monitor.isRunning)
        assertEquals(recent, h.monitor.query(LogFilter(), 10))
        assertEquals(summary, h.monitor.summary())
        h.monitor.start()
        runCurrent()
        h.monitor.restart()
        h.monitor.stop()
        h.monitor.start()
        runCurrent()
        assertEquals(1, h.backend.streamsOpened.get(), "cancellation must finish before another logcat opens")
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, h.backend.streamsOpened.get())
        assertEquals(1, h.backend.bus.subscriptionCount.value)
        assertEquals(recent, h.monitor.query(LogFilter(), 10))
        assertEquals(summary, h.monitor.summary())
        h.backend.closeDelayMs = 0
        h.monitor.stop()
    }

    @Test
    fun startIsIdempotent() = runTest {
        val h = harness()
        h.monitor.start()
        h.monitor.start()
        runCurrent()
        assertEquals(1, h.backend.streamsOpened.get())
        h.monitor.stop()
    }

    @Test
    fun theBuffersAreBounded() = runTest {
        val h = Harness(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val small = LogMonitor(h.backend, h.journal, CoroutineScope(StandardTestDispatcher(testScheduler)), h.clock, LogMonitorConfig(recentCapacity = 10, notableCapacity = 5))
        small.start()
        runCurrent()
        repeat(50) { h.backend.bus.emit(ev(1_000_000L + it, "E", "T", "line $it")) }
        runCurrent()
        val kept = small.query(LogFilter(), 100)
        assertEquals(10, kept.size)
        assertEquals("line 49", kept.last().message)
        assertEquals(5, small.summary().errorCount)
        small.stop()
    }

    @Test
    fun registeredPidsResolvePackagesForLaterEvents() = runTest {
        val h = harness()
        h.monitor.start()
        h.monitor.registerPid(900, "com.example.app")
        runCurrent()
        h.backend.bus.emit(ev(1_000_000, "E", "T", "bad", pid = 900))
        runCurrent()
        assertEquals("com.example.app", h.monitor.query(LogFilter(), 5).single().packageName)
        assertEquals("com.example.app", h.monitor.packageOf(900))
        assertNull(h.monitor.packageOf(901))
        h.monitor.stop()
    }
}
