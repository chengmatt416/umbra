package app.umbra.platform.logs

import app.umbra.core.model.action.LogFilter
import app.umbra.core.model.observation.LogEvent
import app.umbra.core.model.observation.LogKind
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogParsingTest {
    private val utc = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 10, 5, 12, 40, 0).toInstant(utc).toEpochMilli()

    private fun parse(line: String) = LogcatParser.parse(line, now, utc)

    @Test
    fun threadtimeLinesParseIntoAllFields() {
        val e = assertNotNull(parse("10-05 12:34:56.789  4321  4350 E AndroidRuntime: FATAL EXCEPTION: main"))
        assertEquals(LocalDateTime.of(2026, 10, 5, 12, 34, 56, 789_000_000).toInstant(utc).toEpochMilli(), e.timestampMs)
        assertEquals(4321, e.pid)
        assertEquals("E", e.level)
        assertEquals("AndroidRuntime", e.tag)
        assertEquals("FATAL EXCEPTION: main", e.message)
        assertEquals(LogKind.CRASH, e.kind)
    }

    @Test
    fun paddedTagsAndColonsInMessagesSurvive() {
        val e = assertNotNull(parse("10-05 12:34:56.789   500   500 I chatty  : uid=1000(system) expire 5 lines"))
        assertEquals("chatty", e.tag)
        assertEquals("uid=1000(system) expire 5 lines", e.message)

        val c = assertNotNull(parse("10-05 12:34:56.789  1234  1234 D MyTag  : request failed: code=500: server error"))
        assertEquals("MyTag", c.tag)
        assertEquals("request failed: code=500: server error", c.message)
    }

    @Test
    fun theYearAndEpochModifiersAreUnderstood() {
        val withYear = assertNotNull(parse("2025-12-31 23:59:59.001  100  100 W Tag: hi"))
        assertEquals(LocalDateTime.of(2025, 12, 31, 23, 59, 59, 1_000_000).toInstant(utc).toEpochMilli(), withYear.timestampMs)

        val epoch = assertNotNull(parse("1791203696.789  4321  4350 E Tag: boom"))
        assertEquals(1791203696789L, epoch.timestampMs)
        assertEquals(LogKind.ERROR, epoch.kind)
    }

    @Test
    fun aDecemberLineSeenInJanuaryBelongsToLastYear() {
        val january = LocalDateTime.of(2027, 1, 1, 0, 0, 5).toInstant(utc).toEpochMilli()
        val e = assertNotNull(LogcatParser.parse("12-31 23:59:58.000  1  1 I T: m", january, utc))
        assertEquals(LocalDateTime.of(2026, 12, 31, 23, 59, 58).toInstant(utc).toEpochMilli(), e.timestampMs)
    }

    @Test
    fun theUidColumnIsReadAsANumberOrAName() {
        // Lines as printed by `logcat -v threadtime -v epoch -v uid` on an Android 16 emulator.
        val numeric = assertNotNull(parse("         1791180604.486  1000  3231  3421 I HalDevMgr: bestIfaceCreationProposal is null"))
        assertEquals(1000, numeric.uid)
        assertEquals(3231, numeric.pid)
        assertEquals("HalDevMgr", numeric.tag)

        val named = assertNotNull(parse("         1791180605.429 radio  3655  3776 D SatelliteController: satellite is disabled"))
        assertEquals(1001, named.uid)
        assertEquals(3655, named.pid)

        val app = assertNotNull(parse("10-05 10:07:36.314 u0_a221   742  1057 D NetworkStats: registered"))
        assertEquals(10221, app.uid)
        assertEquals("NetworkStats", app.tag)

        assertEquals(1_010_221, Uids.parse("u10_a221"))
        assertEquals(99_005, Uids.parse("u0_i5"), "isolated processes start at 99000")
        assertEquals(2000, Uids.parse("shell"))
        assertNull(Uids.parse("not_a_user"))
    }

    @Test
    fun nonLogLinesAreIgnored() {
        assertNull(parse("--------- beginning of main"))
        assertNull(parse(""))
        assertNull(parse("random garbage"))
        assertNull(parse("13-45 99:99:99.999  1  1 I T: invalid date"))
    }

    @Test
    fun kindsAreClassifiedFromLevelAndKnownSignatures() {
        fun kind(level: String, tag: String, msg: String) = LogcatParser.classify(level, tag, msg)
        assertEquals(LogKind.CRASH, kind("E", "AndroidRuntime", "FATAL EXCEPTION: main"))
        assertEquals(LogKind.ERROR, kind("E", "AndroidRuntime", "Shutting down VM"), "only FATAL EXCEPTION opens a crash")
        assertEquals(LogKind.ANR, kind("E", "ActivityManager", "ANR in com.example (com.example/.Main)"))
        assertEquals(LogKind.NATIVE_CRASH, kind("F", "libc", "Fatal signal 11 (SIGSEGV), code 1"))
        assertEquals(LogKind.NATIVE_CRASH, kind("F", "DEBUG", "*** *** *** *** ***"))
        assertEquals(LogKind.ERROR, kind("E", "Anything", "bad"))
        assertEquals(LogKind.ERROR, kind("F", "Anything", "worse"))
        assertEquals(LogKind.WARNING, kind("W", "Anything", "hmm"))
        assertEquals(LogKind.NORMAL, kind("I", "Anything", "ok"))
    }

    private fun event(level: String, tag: String, message: String = "m", pid: Int = 1, pkg: String? = null) =
        LogEvent(0, level, tag, pid, packageName = pkg, message = message)

    @Test
    fun levelsAreOrderedAndFilteredByMinimum() {
        val matcher = LogFilterMatcher(LogFilter(minLevel = "W"))
        assertFalse(matcher.matches(event("D", "T")))
        assertFalse(matcher.matches(event("I", "T")))
        assertTrue(matcher.matches(event("W", "T")))
        assertTrue(matcher.matches(event("E", "T")))
        assertTrue(matcher.matches(event("F", "T")))
        assertTrue(LogLevels.atLeast("E", "W"))
        assertFalse(LogLevels.atLeast("D", "I"))
    }

    @Test
    fun perTagLevelsOverrideTheGlobalMinimum() {
        val matcher = LogFilterMatcher(LogFilter(minLevel = "W", tagLevels = mapOf("AndroidRuntime" to "V", "DEBUG" to "I")))
        assertTrue(matcher.matches(event("V", "AndroidRuntime")))
        assertTrue(matcher.matches(event("I", "DEBUG")))
        assertFalse(matcher.matches(event("D", "DEBUG")))
        assertFalse(matcher.matches(event("I", "Other")))
        assertTrue(matcher.matches(event("W", "Other")))
    }

    @Test
    fun tagPidPackageAndRegexFiltersCombine() {
        val matcher = LogFilterMatcher(
            LogFilter(tags = listOf("Login"), packageName = "com.example", regex = "fail(ed|ure)"),
            packageOf = { pid -> if (pid == 7) "com.example" else null },
        )
        assertTrue(matcher.matches(event("E", "Login", "login failed", pid = 7)))
        assertFalse(matcher.matches(event("E", "Login", "login ok", pid = 7)), "regex")
        assertFalse(matcher.matches(event("E", "Other", "login failed", pid = 7)), "tag")
        assertFalse(matcher.matches(event("E", "Login", "login failed", pid = 8)), "package from pid")
        assertTrue(matcher.matches(event("E", "Login", "failure", pid = 9, pkg = "com.example")), "explicit package wins")
        assertTrue(LogFilterMatcher(LogFilter(pid = 7)).matches(event("I", "T", pid = 7)))
        assertFalse(LogFilterMatcher(LogFilter(pid = 7)).matches(event("I", "T", pid = 8)))
    }
}
