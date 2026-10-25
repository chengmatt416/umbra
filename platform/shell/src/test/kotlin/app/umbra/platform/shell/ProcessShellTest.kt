package app.umbra.platform.shell

import app.umbra.platform.api.BackendException
import app.umbra.platform.api.ShellEvent
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.exec
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Runs real child processes (`sh` on the machine running the tests); nothing is simulated. */
class ProcessShellTest {
    private val sh = ProcessShell.app()

    @Test
    fun capturesStdoutStderrAndTheExitCode(): Unit = runBlocking {
        val r = sh.exec("echo out; echo err >&2; exit 3")
        assertEquals(3, r.exitCode)
        assertEquals("out\n", r.stdout)
        assertEquals("err", r.stderr)
        assertFalse(r.ok)
        assertEquals("out\n\nerr", r.combined)
    }

    @Test
    fun aSuccessfulCommandIsOk(): Unit = runBlocking {
        val r = sh.exec("true")
        assertTrue(r.ok)
        assertEquals("", r.stdout)
    }

    @Test
    fun stdinReachesTheCommandAndIsClosedAfterwards(): Unit = runBlocking {
        val r = sh.exec(ShellRequest("cat", stdin = "héllo\nwörld 日本語"))
        assertEquals("héllo\nwörld 日本語", r.stdout)
        // A command that waits for input must not hang when none was given.
        assertTrue(sh.exec(ShellRequest("cat", timeoutMs = 5_000)).ok)
    }

    @Test
    fun cwdAndEnvironmentAreAppliedByTheShell(): Unit = runBlocking {
        val dir = Files.createTempDirectory("umbra-shell").toFile()
        try {
            val r = sh.exec(ShellRequest("pwd; echo \"\$GREETING\"", cwd = dir.path, env = mapOf("GREETING" to "it's \"quoted\" \$HOME")))
            val lines = r.stdout.lines()
            assertEquals(dir.canonicalPath, File(lines[0]).canonicalPath)
            assertEquals("it's \"quoted\" \$HOME", lines[1], "values are quoted, not expanded")
            assertEquals(1, sh.exec(ShellRequest("true", cwd = "/definitely/not/here")).exitCode)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun badEnvironmentNamesAreRejectedBeforeAnythingRuns(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { sh.exec(ShellRequest("true", env = mapOf("A B" to "x"))) }
        assertFailsWith<IllegalArgumentException> { sh.exec(ShellRequest("true", env = mapOf("A=B; rm -rf /" to "x"))) }
    }

    @Test
    fun aCommandThatRunsTooLongIsStoppedAndFlagged(): Unit = runBlocking {
        val started = System.nanoTime()
        val r = sh.exec(ShellRequest("echo before; sleep 20.5", timeoutMs = 400))
        assertTrue(r.timedOut)
        assertEquals(ProcessShell.TIMEOUT_EXIT_CODE, r.exitCode)
        assertEquals("before\n", r.stdout, "output produced before the timeout is kept")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "the timeout actually stops the wait")
    }

    @Test
    fun outputBeyondTheLimitIsDroppedAndReported(): Unit = runBlocking {
        val r = sh.exec(ShellRequest("yes abcdefghij | head -c 200000", maxOutputBytes = 1_000))
        assertTrue(r.truncated)
        assertEquals(1_000, r.stdout.length)
        assertTrue(r.ok, "draining the rest keeps the child from blocking on a full pipe")
    }

    @Test
    fun binaryOutputSurvivesUntouched(): Unit = runBlocking {
        val r = sh.execBytes(ShellRequest("printf '\\000\\001\\377\\n\\r\\200'"))
        assertTrue(r.ok)
        assertContentEquals(byteArrayOf(0, 1, 0xFF.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), 0x80.toByte()), r.stdout)
    }

    @Test
    fun aStreamEmitsLinesAndEndsWithTheExitCode(): Unit = runBlocking {
        val events = withTimeout(10_000) { sh.stream(ShellRequest("printf 'a\\nb\\n'; echo oops >&2; exit 2")).toList() }
        assertEquals(listOf("a", "b"), events.filterIsInstance<ShellEvent.Out>().map { it.line })
        assertEquals(listOf("oops"), events.filterIsInstance<ShellEvent.Err>().map { it.line })
        assertEquals(ShellEvent.Exit(2), events.last())
    }

    @Test
    fun streamingOutputIsBoundedBeforeANewlineArrives(): Unit = runBlocking {
        val events = withTimeout(5_000) {
            sh.stream(ShellRequest("head -c 200000 /dev/zero | tr '\\000' x; sleep 20", maxOutputBytes = 1024)).toList()
        }
        assertTrue(events.filterIsInstance<ShellEvent.Out>().all { it.line.toByteArray().size <= 1024 })
        assertTrue(events.filterIsInstance<ShellEvent.Err>().any { "1024" in it.line }, events.toString())
        assertTrue((events.last() as ShellEvent.Exit).code != 0)
    }

    @Test
    fun aStreamHonorsItsDeadlineWhileAnExplicitLogMonitorMayStayOpen(): Unit = runBlocking {
        val events = withTimeout(5_000) { sh.stream(ShellRequest("echo started; sleep 20", timeoutMs = 150)).toList() }
        assertEquals(ShellEvent.Out("started"), events.first())
        assertEquals(ShellEvent.Exit(ProcessShell.TIMEOUT_EXIT_CODE), events.last())
        val first = withTimeout(5_000) { sh.stream(ShellRequest("echo alive; sleep 20", timeoutMs = Long.MAX_VALUE)).first() }
        assertEquals(ShellEvent.Out("alive"), first)
    }

    @Test
    fun cancellingAStreamStopsTheCommandAndItsChildren(): Unit = runBlocking {
        val marker = "sleep 31.${System.nanoTime() % 1000}"
        val first = withTimeout(10_000) { sh.stream(ShellRequest("echo started; $marker")).first() }
        assertEquals(ShellEvent.Out("started"), first)
        // `first()` cancelled the collector; the sleep must be gone shortly after.
        var alive = true
        repeat(30) {
            if (alive) {
                alive = sh.exec("pgrep -f '$marker' | grep -v \$\$ | head -1").stdout.isNotBlank()
                if (alive) Thread.sleep(100)
            }
        }
        assertFalse(alive, "the child of the shell was left running")
    }

    @Test
    fun aMissingExecutableIsReportedAsUnavailable(): Unit = runBlocking {
        val broken = ProcessShell(ShellIdentity.APP, listOf("/definitely/not/a/shell", "-c"), "broken")
        assertFailsWith<BackendException> { broken.exec("true") }
        assertFalse(ProcessShell.root("/definitely/not/su").isAvailable(), "a missing su means no root")
    }

    @Test
    fun quotingKeepsAnyStringAsOneWord(): Unit = runBlocking {
        for (value in listOf("plain", "with space", "it's", "\$(touch /tmp/x)", "a;b", "`id`", "", "*", "line\nbreak")) {
            val r = sh.exec("printf %s ${ProcessShell.quote(value)}")
            assertEquals(value, r.stdout, "round trip of <$value>")
        }
    }

    @Test
    fun cancellingAStreamWhoseCommandIsBlockedLeavesNothingUncaught(): Unit = runBlocking {
        // The helper process outlives its streams: an exception escaping a reader thread would end it.
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            repeat(3) {
                withTimeout(20_000) { sh.stream(ShellRequest("echo ready; sleep 30")).first() }
            }
            kotlinx.coroutines.delay(1_500) // the killed commands' readers wake up and finish
            assertTrue(uncaught.isEmpty(), "uncaught: $uncaught")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
