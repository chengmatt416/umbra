package app.umbra.platform.logs

import app.umbra.core.model.observation.LogKind
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feeds real-format logcat transcripts through the parser and detector together. */
class CrashDetectorTest {
    private fun feed(detector: CrashDetector, transcript: String): List<CrashDetector.Update> {
        val now = 1_791_203_700_000L
        return transcript.trimIndent().lines().mapNotNull { line ->
            LogcatParser.parse(line, now, ZoneOffset.UTC)?.let(detector::accept)
        }
    }

    private val javaCrash = """
        10-05 12:34:56.700  4321  4321 E AndroidRuntime: Shutting down VM
        10-05 12:34:56.701  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main
        10-05 12:34:56.701  4321  4321 E AndroidRuntime: Process: com.example.crashy, PID: 4321
        10-05 12:34:56.701  4321  4321 E AndroidRuntime: java.lang.NullPointerException: Attempt to invoke virtual method 'android.text.Editable android.widget.EditText.getText()' on a null object reference
        10-05 12:34:56.701  4321  4321 E AndroidRuntime: 	at com.example.crashy.LoginActivity.onLoginClicked(LoginActivity.kt:42)
        10-05 12:34:56.701  4321  4321 E AndroidRuntime: 	at android.view.View.performClick(View.java:8000)
        10-05 12:34:56.701  4321  4321 E AndroidRuntime: 	at android.view.View${'$'}PerformClick.run(View.java:31000)
        10-05 12:34:56.701  4321  4321 E AndroidRuntime: Caused by: java.lang.IllegalStateException: no email field
        10-05 12:34:56.701  4321  4321 E AndroidRuntime: 	... 12 more
    """

    @Test
    fun aJavaCrashBecomesOneReportWithPackageExceptionAndFrames() {
        val detector = CrashDetector()
        val updates = feed(detector, javaCrash)
        assertTrue(updates.first().isNew)
        assertEquals(1, updates.count { it.isNew }, "one crash, however many lines")

        val report = updates.last().report
        assertEquals(LogKind.CRASH, report.kind)
        assertEquals("com.example.crashy", report.packageName)
        assertEquals(4321, report.pid)
        assertEquals("main", report.thread)
        assertEquals("java.lang.NullPointerException", report.exception)
        assertTrue(report.message!!.startsWith("Attempt to invoke virtual method"), report.message)
        assertEquals("com.example.crashy.LoginActivity.onLoginClicked(LoginActivity.kt:42)", report.stack.first())
        assertEquals(3, report.stack.size)
        assertEquals("java.lang.IllegalStateException: no email field", report.cause)
        assertTrue(report.summary().startsWith("CRASH in com.example.crashy: java.lang.NullPointerException"), report.summary())
        assertTrue("LoginActivity.kt:42" in report.summary())
    }

    @Test
    fun theReportExistsAfterTheFirstLineAndGainsDetail() {
        val detector = CrashDetector()
        val first = feed(detector, javaCrash).first()
        assertNull(first.report.packageName, "the package arrives on the next line")
        assertEquals("main", first.report.thread)
    }

    @Test
    fun noiseAroundACrashIsIgnored() {
        val detector = CrashDetector()
        val updates = feed(
            detector,
            """
            10-05 12:34:56.000  4321  4321 D AndroidRuntime: Calling main entry com.android.commands.Foo
            10-05 12:34:56.100  1000  1000 I ActivityManager: Start proc 4321:com.example/u0a123 for activity
            10-05 12:34:56.200  4321  4321 E SomeTag: unrelated error
            """,
        )
        assertTrue(updates.isEmpty(), "AndroidRuntime lines without FATAL EXCEPTION are not crashes: $updates")
    }

    @Test
    fun twoProcessesCrashingTogetherAreTrackedSeparately() {
        val detector = CrashDetector()
        val updates = feed(
            detector,
            """
            10-05 12:00:00.000  100  100 E AndroidRuntime: FATAL EXCEPTION: main
            10-05 12:00:00.001  200  200 E AndroidRuntime: FATAL EXCEPTION: Thread-2
            10-05 12:00:00.002  100  100 E AndroidRuntime: Process: com.a, PID: 100
            10-05 12:00:00.003  200  200 E AndroidRuntime: Process: com.b, PID: 200
            10-05 12:00:00.004  100  100 E AndroidRuntime: java.lang.RuntimeException: a failed
            10-05 12:00:00.005  200  200 E AndroidRuntime: java.lang.OutOfMemoryError: b failed
            """,
        )
        val finals = updates.map { it.report }.groupBy { it.id }.mapValues { it.value.last() }.values
        assertEquals(setOf("com.a" to "java.lang.RuntimeException", "com.b" to "java.lang.OutOfMemoryError"), finals.map { it.packageName!! to it.exception!! }.toSet())
    }

    @Test
    fun anAnrIsReportedWithPackagePidAndReason() {
        val detector = CrashDetector()
        val updates = feed(
            detector,
            """
            10-05 12:40:00.100   800   830 E ActivityManager: ANR in com.example.crashy (com.example.crashy/.LoginActivity)
            10-05 12:40:00.100   800   830 E ActivityManager: PID: 4321
            10-05 12:40:00.100   800   830 E ActivityManager: Reason: Input dispatching timed out (com.example.crashy/.LoginActivity is not responding. Waited 5001ms for MotionEvent)
            10-05 12:40:00.100   800   830 E ActivityManager: Load: 4.2 / 3.9 / 3.1
            """,
        )
        val report = updates.last().report
        assertEquals(LogKind.ANR, report.kind)
        assertEquals("com.example.crashy", report.packageName)
        assertEquals(4321, report.pid)
        assertTrue(report.reason!!.startsWith("Input dispatching timed out"))
        assertTrue(report.summary().startsWith("ANR in com.example.crashy: Input dispatching timed out"), report.summary())
    }

    @Test
    fun aNativeCrashCombinesTheSignalLineAndTheTombstone() {
        val detector = CrashDetector()
        val updates = feed(
            detector,
            """
            10-05 12:41:00.001  4400  4400 F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 4400 (crashy), pid 4400 (crashy)
            10-05 12:41:00.100  4500  4500 F DEBUG   : *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
            10-05 12:41:00.100  4500  4500 F DEBUG   : Build fingerprint: 'google/sdk_gphone64_arm64/emu64a:16/BE2A/1:userdebug/dev-keys'
            10-05 12:41:00.100  4500  4500 F DEBUG   : pid: 4400, tid: 4400, name: crashy  >>> com.example.crashy <<<
            10-05 12:41:00.100  4500  4500 F DEBUG   : signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
            10-05 12:41:00.100  4500  4500 F DEBUG   :       #00 pc 0000000000012345  /data/app/com.example.crashy/lib/arm64/libnative.so (Java_com_example_crashy_Native_boom+16)
            10-05 12:41:00.100  4500  4500 F DEBUG   :       #01 pc 00000000000abcde  /apex/com.android.art/lib64/libart.so (art_quick_generic_jni_trampoline+144)
            """,
        )
        assertEquals(1, updates.count { it.isNew })
        val report = updates.last().report
        assertEquals(LogKind.NATIVE_CRASH, report.kind)
        assertEquals("SIGSEGV", report.signal)
        assertEquals(4400, report.pid)
        assertEquals("com.example.crashy", report.packageName)
        assertEquals(2, report.stack.size)
        assertTrue("Java_com_example_crashy_Native_boom" in report.stack.first())
        assertTrue(report.summary().startsWith("NATIVE CRASH in com.example.crashy (SIGSEGV)"), report.summary())
    }

    @Test
    fun aTombstoneWithoutTheEarlierSignalLineStillProducesAReport() {
        val detector = CrashDetector()
        val updates = feed(
            detector,
            "10-05 12:41:00.100  4500  4500 F DEBUG   : pid: 777, tid: 777, name: foo  >>> com.example.foo <<<",
        )
        val report = assertNotNull(updates.singleOrNull()).report
        assertEquals("com.example.foo", report.packageName)
        assertEquals(LogKind.NATIVE_CRASH, report.kind)
    }

    @Test
    fun framesAreCappedSoAHugeStackDoesNotGrowWithoutBound() {
        val detector = CrashDetector(maxFrames = 5)
        val frames = (1..50).joinToString("\n") { "10-05 12:00:00.001  100  100 E AndroidRuntime: \tat com.a.F$it.run(F.kt:$it)" }
        val updates = feed(
            detector,
            "10-05 12:00:00.000  100  100 E AndroidRuntime: FATAL EXCEPTION: main\n10-05 12:00:00.000  100  100 E AndroidRuntime: java.lang.Error: x\n$frames",
        )
        assertEquals(5, updates.last().report.stack.size)
    }
}
