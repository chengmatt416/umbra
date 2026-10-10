package app.umbra.tools

import app.umbra.platform.adb.JvmImageCodec
import app.umbra.platform.api.exec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * The tool stack against a real Android 16 device, exactly as omp calls it: host-tool arguments in,
 * rendered results out. The Settings app is the subject because every Android image has it. The same
 * scenarios run twice: with the tool stack on the development machine (reaching the device through
 * adb) and inside the installed app (through its tool bridge). Skipped when no device is attached.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName::class)
abstract class PipelineScenarios {
    protected abstract fun createRig(): ToolRig?

    private var rig: ToolRig? = null

    @BeforeAll
    fun connect() {
        rig = createRig()
        org.junit.jupiter.api.Assumptions.assumeTrue(rig != null, "no device available; skipping the live-device scenarios")
    }

    @AfterAll
    fun disconnect() {
        rig?.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { rig?.reset() }
    }

    protected val r get() = rig!!

    /** The element id printed for the first observation line that contains [fragment]. */
    private fun idOf(observation: String, fragment: String): String? =
        observation.lineSequence().firstOrNull { fragment in it }?.let { Regex("""\[(\d+:[\d.]+)]""").find(it)?.groupValues?.get(1) }

    private suspend fun openSettings() {
        val launched = r.call("android_launch", """{"package":"com.android.settings"}""")
        assertFalse(launched.isError, launched.text)
    }

    @Test
    fun a_launchObserveFindAndTapByLabelOpensTheNextScreen(): Unit = runBlocking {
        openSettings()

        val observation = r.call("android_observe").text
        assertTrue("com.android.settings" in observation, observation)
        assertTrue("Search Settings" in observation && "Network & internet" in observation, observation)
        assertTrue(Regex("""\[-?\d+:0(\.\d+)*]""").containsMatchIn(observation), "element ids are shown: $observation")

        val found = r.call("android_find", """{"contains":"Network"}""").text
        assertTrue("match(es)" in found && "Network & internet" in found, found)

        // The label may sit in a non-clickable child of a clickable row: the tap must still land on the row.
        val tap = r.call("android_tap", """{"label":"Network & internet","expect":{"text_visible":"Airplane mode"},"expect_timeout_ms":20000}""")
        assertFalse(tap.isError, tap.text)
        assertTrue("Tapped" in tap.text && "Verification passed" in tap.text, tap.text)

        val back = r.call("android_key", """{"key":"BACK","expect":{"text_visible":"Search Settings"},"expect_timeout_ms":20000}""")
        assertFalse(back.isError, back.text)
    }

    @Test
    fun b_elementIdsFromAnObservationCanBeUsedAndGoStaleWhenTheScreenChanges(): Unit = runBlocking {
        openSettings()
        val observation = r.call("android_observe").text
        val devices = assertNotNull(idOf(observation, "\"Connected devices\""), observation)
        val network = assertNotNull(idOf(observation, "\"Network & internet\""), observation)

        val opened = r.call("android_tap", """{"element":"$devices","expect":{"text_visible":"Pair new device"},"expect_timeout_ms":20000}""")
        assertFalse(opened.isError, opened.text)

        // The screen has changed: the id from the old observation must not be applied to the new screen.
        val stale = r.call("android_tap", """{"element":"$network"}""")
        assertTrue(stale.isError, stale.text)
        assertTrue("stale_element" in stale.text || "no longer exists" in stale.text, stale.text)
        assertTrue("Observe again" in stale.text, stale.text)
    }

    @Test
    fun c_scrollingUntilAnElementIsVisibleFindsItAndItCanBeOpened(): Unit = runBlocking {
        openSettings()
        // A row below the fold. (Not "About": that page never goes idle, which `uiautomator dump` needs to read it.)
        val scroll = r.call("android_scroll", """{"direction":"down","until_contains":"Accessibility","max_scrolls":10}""")
        assertFalse(scroll.isError, scroll.text)
        assertTrue("found" in scroll.text && "Accessibility" in scroll.text, scroll.text)

        // Opening a sub-screen replaces the main page, which is the only one with the search bar.
        val page = r.call("android_tap", """{"contains":"Accessibility","expect":{"text_gone":"Search Settings"},"expect_timeout_ms":20000}""")
        assertFalse(page.isError, page.text)
    }

    @Test
    fun d_textIsTypedIntoTheSearchField(): Unit = runBlocking {
        openSettings()
        val open = r.call("android_tap", """{"label":"Search Settings"}""")
        assertFalse(open.isError, open.text)
        val screen = r.call("android_observe").text
        assertTrue("EditText" in screen || "edit" in screen, "the search page did not open with a text field:\n$screen")

        val typed = r.call("android_type", """{"text":"wifi","expect":{"text_visible":"wifi"},"expect_timeout_ms":20000}""")
        assertFalse(typed.isError, typed.text)
        assertTrue("Typed \"wifi\"" in typed.text, typed.text)
    }

    @Test
    fun e_anAnnotatedScreenshotNumbersTheElementsAndMarksCanBeTapped(): Unit = runBlocking {
        openSettings()
        val shot = r.call("android_screenshot", """{"annotate":true}""")
        assertFalse(shot.isError, shot.text)
        val jpeg = shot.images.single()
        val decoded = JvmImageCodec().decode(jpeg)
        assertTrue(decoded.width in 300..1280 && decoded.height in 300..1280, "${decoded.width}x${decoded.height}")
        assertTrue(jpeg.size < 600_000, "image fits an RPC frame: ${jpeg.size} bytes")

        assertTrue("Marks:" in shot.text, shot.text)
        val mark = Regex("""(\d+) "Network & internet"""").find(shot.text)?.groupValues?.get(1)
        assertNotNull(mark, "the Network row is marked by its title: ${shot.text}")

        val tap = r.call("android_tap", """{"mark":$mark,"expect":{"text_visible":"Airplane mode"},"expect_timeout_ms":20000}""")
        assertFalse(tap.isError, tap.text)
    }

    @Test
    fun f_waitingSucceedsWhenTrueAndTimesOutWithEvidenceWhenNot(): Unit = runBlocking {
        openSettings()
        val ok = r.call("android_wait_for", """{"expect":{"text_visible":"Search Settings"},"timeout_ms":30000}""")
        assertFalse(ok.isError, ok.text)

        val started = System.currentTimeMillis()
        val timeout = r.call("android_wait_for", """{"expect":{"text_visible":"zzz-text-that-is-not-there"},"timeout_ms":3000}""")
        assertTrue(timeout.isError, timeout.text)
        assertTrue("Timed out" in timeout.text && "zzz-text-that-is-not-there" in timeout.text, timeout.text)
        assertTrue(System.currentTimeMillis() - started >= 3_000)

        val verify = r.call("verify", """{"expect":{"package":"com.android.settings","no_crash":"com.android.settings"},"timeout_ms":20000}""")
        assertFalse(verify.isError, verify.text)
    }

    @Test
    fun g_shellPackagesSysinfoAndLogsToolsReturnRealDeviceData(): Unit = runBlocking {
        val sh = r.call("android_shell", """{"command":"echo umbra; id -u"}""").text
        assertTrue("umbra" in sh && "Exit 0" in sh, sh)

        val failing = r.call("android_shell", """{"command":"ls /definitely/not/here"}""")
        assertTrue(failing.isError && ("Exit 1" in failing.text || "No such file" in failing.text), failing.text)

        val pkgs = r.call("android_packages", """{"action":"list","filter":"com.android.settings"}""").text
        assertTrue("com.android.settings" in pkgs, pkgs)

        val info = r.call("android_packages", """{"action":"info","package":"com.android.settings"}""").text
        assertTrue("com.android.settings 16" in info && "Launcher activity" in info, info)

        assertEquals("16", r.call("android_sysinfo", """{"query":"getprop","argument":"ro.build.version.release"}""").text.trim())

        r.shell.exec("log -p e -t UmbraLiveTool tool-marker-1")
        // logd makes a line visible a moment after `log` returns; a busy device can take seconds.
        var logs = ""
        repeat(30) {
            if ("tool-marker-1" !in logs) {
                logs = r.call("android_logs", """{"action":"query","tags":["UmbraLiveTool"],"max_lines":5}""").text
                if ("tool-marker-1" !in logs) delay(500)
            }
        }
        assertTrue("tool-marker-1" in logs, logs)

        val summary = r.call("android_logs", """{"action":"summary"}""").text
        assertTrue("crash" in summary.lowercase() || "No crashes" in summary, summary)
    }

    @Test
    fun h_dangerousActionsAreAskedAboutAndDeniedOnesDoNotRun(): Unit = runBlocking {
        r.answerApprovals(false)
        val marker = "/data/local/tmp/umbra-deny-marker"
        r.shell.exec("echo keep > $marker")
        val denied = r.call("android_shell", """{"command":"rm -rf /sdcard/Download $marker"}""")
        assertTrue(denied.isError, denied.text)
        assertTrue("denied" in denied.text.lowercase(), denied.text)
        assertEquals("keep", r.shell.exec("cat $marker").stdout.trim(), "a denied command must not run")
        assertTrue(r.approvalCount() >= 1, "the user was asked")

        val before = r.approvalCount()
        val own = r.call("android_stop", """{"package":"app.umbra.agent"}""")
        assertTrue(own.isError, own.text)
        assertEquals(before, r.approvalCount(), "stopping the agent's own app is refused outright, not offered for approval")

        r.shell.exec("rm -f $marker")
    }

    @Test
    fun i_aBackgroundDisplayCanBeCreatedWorkedOnAndDestroyed(): Unit = runBlocking {
        val created = r.call("android_display", """{"action":"create","width":720,"height":1280,"dpi":280,"package":"com.android.settings"}""")
        assertFalse(created.isError, created.text)
        val id = assertNotNull(Regex("""Created display (\d+)""").find(created.text)?.groupValues?.get(1)?.toInt(), created.text)
        try {
            delay(2_000)
            val onDisplay = r.call("android_observe", """{"display":$id}""").text
            assertTrue("Search Settings" in onDisplay, "Settings runs on display $id:\n$onDisplay")

            val tap = r.call("android_tap", """{"label":"Network & internet","display":$id,"expect":{"text_visible":"Airplane mode"},"expect_timeout_ms":25000}""")
            assertFalse(tap.isError, tap.text)

            val main = r.call("android_observe", """{"display":0,"level":"brief"}""").text
            assertFalse("Airplane mode" in main, "the main display is untouched:\n$main")

            val listing = r.call("android_display", """{"action":"list"}""").text
            assertTrue("$id" in listing && "leased by live" in listing, listing)
        } finally {
            r.call("android_stop", """{"package":"com.android.settings"}""")
            val destroyed = r.call("android_display", """{"action":"destroy","display_id":$id}""")
            assertFalse(destroyed.isError, destroyed.text)
        }
    }

    @Test
    fun j_twoSessionsCannotDriveTheSameDisplayAtOnce(): Unit = runBlocking {
        val launched = r.call("android_launch", """{"package":"com.android.settings"}""", session = "agent-a")
        assertFalse(launched.isError, launched.text)
        val first = r.call("android_tap", """{"x":360,"y":60}""", session = "agent-a")
        assertFalse(first.isError, first.text)

        val second = r.call("android_tap", """{"x":360,"y":60}""", session = "agent-b")
        assertTrue(second.isError && "lease_unavailable" in second.text, second.text)
        assertTrue("agent-a" in second.text, second.text)

        // Releasing the display (what stopping or pausing a session does) lets the other session in.
        assertFalse(r.call("android_display", """{"action":"release","display_id":0}""", session = "agent-a").isError)
        val third = r.call("android_tap", """{"x":360,"y":60}""", session = "agent-b")
        assertFalse(third.isError, third.text)
    }
}
