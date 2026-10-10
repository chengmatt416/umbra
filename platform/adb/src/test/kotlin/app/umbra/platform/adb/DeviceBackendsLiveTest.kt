package app.umbra.platform.adb

import app.umbra.core.model.action.LogFilter
import app.umbra.core.model.action.SysInfoQuery
import app.umbra.core.model.observation.LogEvent
import app.umbra.platform.api.AppLaunch
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.VirtualDisplaySpec
import app.umbra.platform.api.exec
import app.umbra.platform.shell.OverlayDisplays
import app.umbra.platform.shell.ShellApps
import app.umbra.platform.shell.ShellFiles
import app.umbra.platform.shell.ShellLogs
import app.umbra.platform.shell.ShellPackages
import app.umbra.platform.shell.ShellProcesses
import app.umbra.platform.shell.ShellScreenshots
import app.umbra.platform.shell.ShellSysInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.MethodOrderer

/**
 * The shell-based backends against a real Android device (the Android 16 emulator in CI-less
 * development, or a phone). Everything here runs the real `am`, `pm`, `dumpsys`, `logcat`, `screencap`
 * and `cmd display` on the device and checks real effects.
 */
@TestMethodOrder(MethodOrderer.MethodName::class)
class DeviceBackendsLiveTest {
    private val adb get() = DeviceTestSupport.requireDevice()
    private val shell get() = adb.shell()

    @Test
    fun a_theAdbShellIsTheShellUserOnAnAndroid16Device(): Unit = runBlocking {
        val sh = shell
        assertEquals(ShellIdentity.SHELL, sh.identity)
        assertTrue(sh.isAvailable())
        assertEquals("2000", sh.exec("id -u").stdout.trim())
        assertEquals("16", sh.exec("getprop ro.build.version.release").stdout.trim())
        // A failing command reports its exit code and stderr, and a missing one is not a crash.
        val missing = sh.exec("definitely-not-a-command")
        assertTrue(!missing.ok && missing.exitCode != 0)
        // Binary output is intact: a PNG signature and no CRLF translation.
        val bytes = sh.execBytes(ShellRequest("printf '\\211PNG\\r\\n\\032\\n'"))
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), bytes.stdout.map { it.toInt() and 0xFF })
    }

    @Test
    fun b_rootIsAvailableOnAUserdebugImage(): Unit = runBlocking {
        val root = adb.root()
        if (!root.isAvailable()) return@runBlocking // a production phone: nothing to assert
        assertEquals("0", root.exec("id -u").stdout.trim())
        assertTrue(root.exec("ls /data/data | head -3").ok, "root can list app data, shell cannot")
        assertTrue(!shell.exec("ls /data/data").ok)
    }

    @Test
    fun c_packagesAreListedAndInspected(): Unit = runBlocking {
        val pm = ShellPackages(shell)
        val all = pm.list(filter = "com.android.settings", includeSystem = true)
        val settings = assertNotNull(all.firstOrNull { it.packageName == "com.android.settings" })
        assertTrue(settings.system)
        assertEquals(1000, settings.uid)
        assertTrue(pm.list(includeSystem = false).none { it.packageName == "com.android.settings" }, "user list excludes system apps")

        val details = assertNotNull(pm.details("com.android.settings"))
        assertEquals("16", details.summary.versionName)
        assertNotNull(details.launcherActivity)
        assertTrue(details.requestedPermissions.isNotEmpty())
        assertNull(pm.details("com.not.installed.anywhere"))
    }

    @Test
    fun d_anAppLaunchesComesToTheForegroundAndStops(): Unit = runBlocking {
        shell.exec("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
        val apps = ShellApps(shell)
        val outcome = apps.launch(AppLaunch(packageName = "com.android.settings", displayId = 0))
        assertTrue(outcome.started, outcome.message)
        assertEquals("com.android.settings/.Settings", outcome.component)

        assertEquals("com.android.settings", waitForForeground(apps, 0, "com.android.settings"))

        val unknown = apps.launch(AppLaunch(packageName = "com.bogus.app.nowhere"))
        assertTrue(!unknown.started, "an uninstalled package is not launched: ${unknown.message}")
        val badActivity = apps.launch(AppLaunch(packageName = "com.android.settings", activity = ".DoesNotExist"))
        assertTrue(!badActivity.started && "does not exist" in badActivity.message, badActivity.message)

        apps.stop("com.android.settings", force = true)
        delay(1_000)
        val after = apps.foreground(0).singleOrNull()
        assertTrue(after?.packageName != "com.android.settings", "settings was force-stopped, foreground is ${after?.packageName}")
    }

    private suspend fun waitForForeground(apps: ShellApps, display: Int, pkg: String): String? {
        repeat(40) {
            val fg = apps.foreground(display).firstOrNull()?.packageName
            if (fg == pkg) return fg
            delay(250)
        }
        return apps.foreground(display).firstOrNull()?.packageName
    }

    @Test
    fun e_systemInformationComesFromTheRealTools(): Unit = runBlocking {
        val info = ShellSysInfo(shell)
        assertEquals("16", info.query(SysInfoQuery.GETPROP, "ro.build.version.release").text)
        assertTrue("level" in info.query(SysInfoQuery.BATTERY).text)
        assertTrue("MemTotal" in info.query(SysInfoQuery.MEMORY).text)
        assertTrue("/data" in info.query(SysInfoQuery.STORAGE).text)
        assertTrue("Display id 0" in info.query(SysInfoQuery.DISPLAYS).text)
        assertTrue(info.query(SysInfoQuery.SETTINGS, "global/airplane_mode_on").text in listOf("0", "1"))
        assertTrue("package" in info.query(SysInfoQuery.DUMPSYS, "-l").text.lowercase())

        val state = info.deviceState()
        assertTrue(state.batteryPct in 0..100, "battery ${state.batteryPct}")
        assertTrue(state.memTotalMb!! > 1_000 && state.memAvailMb!! > 0)
        assertTrue(state.storageFreeMb!! > 100)
        assertNotNull(state.thermalStatus)
    }

    @Test
    fun f_processesAreListedAndTheCallerCanKillItsOwn(): Unit = runBlocking {
        val ps = ShellProcesses(shell)
        assertTrue(ps.isRunning("system_server"))
        assertTrue(ps.list("system_server").isNotEmpty())
        assertEquals("init", ps.info(1)?.name)
        assertTrue(!ps.isRunning("no_such_process_xyz"))

        // Start a real sleeper owned by the shell user and kill it through the backend.
        shell.exec("nohup sleep 345 >/dev/null 2>&1 &")
        delay(500)
        val sleeper = ps.list("sleep 345").firstOrNull { it.command?.startsWith("sleep 345") == true }
        assertNotNull(sleeper, "the sleeper process is listed")
        ps.kill(sleeper.pid, 9)
        delay(300)
        assertTrue(ps.list("sleep 345").none { it.command?.startsWith("sleep 345") == true })
    }

    @Test
    fun g_filesRoundTripThroughTheShell(): Unit = runBlocking {
        val fs = ShellFiles(shell, privileged = adb.root().takeIf { it.isAvailable() })
        val dir = "/data/local/tmp/umbra-live-test"
        fs.delete(dir, recursive = true)
        fs.mkdir("$dir/sub")
        fs.write("$dir/a.txt", "héllo wörld 日本語\nline two")
        fs.write("$dir/a.txt", "\nappended", append = true)
        assertEquals("héllo wörld 日本語\nline two\nappended", fs.read("$dir/a.txt", 1_000))
        assertEquals("héllo", fs.read("$dir/a.txt", 6).take(5), "reads are limited to maxBytes")

        val stat = assertNotNull(fs.stat("$dir/a.txt"))
        assertEquals(false, stat.isDirectory)
        assertTrue(stat.sizeBytes > 20)
        assertTrue(assertNotNull(fs.stat("$dir/sub")).isDirectory)
        assertNull(fs.stat("$dir/nope"))

        val listing = fs.list(dir)
        assertEquals(listOf("sub", "a.txt"), listing.map { it.name }, "directories first")
        assertEquals("$dir/a.txt", listing.last().path)

        fs.move("$dir/a.txt", "$dir/sub/b.txt")
        assertNull(fs.stat("$dir/a.txt"))
        assertEquals(1, fs.list("$dir/sub").size)
        assertFailsWith<app.umbra.platform.api.BackendException> { fs.read("$dir/missing", 10) }

        fs.delete(dir, recursive = true)
        assertNull(fs.stat(dir))
    }

    @Test
    fun h_theLogIsReadAndStreamedWithAttribution(): Unit = runBlocking {
        val logs = ShellLogs(shell)
        val marker = "umbra-marker-${System.nanoTime()}"
        val seen = CompletableDeferred<LogEvent>()
        val collector = launch(Dispatchers.Default) {
            seen.complete(logs.stream(LogFilter(tags = listOf("UmbraLiveTest"))).first { it.message == marker })
        }
        delay(2_000) // let logcat attach before writing
        shell.exec("log -p w -t UmbraLiveTest $marker")
        val streamed = withTimeout(15_000) { seen.await() }
        collector.cancel()
        assertEquals("UmbraLiveTest", streamed.tag)
        assertEquals("W", streamed.level)
        assertEquals(marker, streamed.message)
        assertEquals(2000, streamed.uid, "written by the shell user")

        val read = logs.read(LogFilter(tags = listOf("UmbraLiveTest"), minLevel = "V"), 10)
        assertTrue(read.any { it.message == marker }, "a buffered read finds it too")
        assertTrue(read.all { it.tag == "UmbraLiveTest" })
        val none = logs.read(LogFilter(tags = listOf("UmbraLiveTest"), regex = "no-such-text-anywhere"), 10)
        assertTrue(none.isEmpty())

        // By package: the shell user's lines belong to com.android.shell (uid 2000); another app's do not.
        val byPackage = logs.read(LogFilter(packageName = "com.android.shell", tags = listOf("UmbraLiveTest")), 10)
        assertTrue(byPackage.any { it.message == marker }, "found through the package's uid")
        assertTrue(logs.read(LogFilter(packageName = "com.android.settings", tags = listOf("UmbraLiveTest")), 10).isEmpty())
    }

    @Test
    fun i_screenshotsAreRealPngsOfTheDisplay(): Unit = runBlocking {
        val shot = ShellScreenshots(shell).capture(null)
        assertEquals("image/png", shot.mime)
        val wm = shell.exec("wm size").stdout
        val size = Regex("""Physical size: (\d+)x(\d+)""").find(wm)!!
        assertEquals(size.groupValues[1].toInt(), shot.width)
        assertEquals(size.groupValues[2].toInt(), shot.height)
        val decoded = JvmImageCodec().decode(shot.bytes)
        assertEquals(shot.width, decoded.width)
        assertTrue(shot.bytes.size > 2_000, "a real screen compresses to more than a blank image")
    }

    @Test
    fun j_anOverlayDisplayCanBeCreatedUsedAndDestroyed(): Unit = runBlocking {
        val displays = OverlayDisplays(shell)
        val apps = ShellApps(shell)
        displays.ownedDisplays().forEach { displays.destroy(it.id) }
        val before = displays.list()
        assertTrue(before.any { it.isDefault })

        val created = displays.create(VirtualDisplaySpec("umbra-test", 720, 1280, 280))
        try {
            assertTrue(created.id != 0 && created.isVirtual)
            assertEquals(720, created.width)
            assertEquals(1280, created.height)
            assertTrue(displays.list().any { it.id == created.id })
            assertFailsWith<app.umbra.platform.api.BackendException>("only one overlay at a time") {
                displays.create(VirtualDisplaySpec("second"))
            }

            shell.exec("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
            val launched = apps.launch(AppLaunch(packageName = "com.android.settings", displayId = created.id))
            assertTrue(launched.started, launched.message)
            assertEquals("com.android.settings", waitForForeground(apps, created.id, "com.android.settings"), "the app runs on the new display")
            assertTrue(apps.foreground(0).none { it.packageName == "com.android.settings" }, "and not on the main display")
        } finally {
            apps.stop("com.android.settings")
            displays.destroy(created.id)
        }
        assertTrue(displays.list().none { it.id == created.id }, "the display is gone")
        assertTrue(displays.ownedDisplays().isEmpty())
    }
}
