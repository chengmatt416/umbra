package app.umbra.platform.shell

import app.umbra.core.model.observation.NodeFlag
import app.umbra.platform.logs.LogcatParser
import java.io.File
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parsers run over output captured from a real Android 16 (API 36.1) emulator, kept in
 * src/test/resources/golden. These are recordings of the real tools, not hand-written imitations:
 * when Android changes a format, re-record them (scripts/record-golden.sh) and the tests say what broke.
 */
class ParsersGoldenTest {
    private fun golden(name: String): String =
        (javaClass.getResource("/golden/$name") ?: error("missing golden file $name")).readText()

    // ---- pm ----

    @Test
    fun theFullPackageListParsesWithPathsVersionsAndUids() {
        val packages = PmParsers.parseList(golden("pm_list_all.txt"))
        assertTrue(packages.size > 100, "an emulator image has hundreds of packages, got ${packages.size}")
        val settings = assertNotNull(packages.firstOrNull { it.packageName == "com.android.settings" })
        assertEquals(1000, settings.uid)
        assertEquals(36L, settings.versionCode)
        assertTrue(settings.apkPath!!.endsWith(".apk"), settings.apkPath)
        assertTrue(packages.all { it.packageName.isNotBlank() && it.packageName.matches(Regex("[A-Za-z0-9_.]+")) }, "no path fragments leaked into names")
        assertTrue(packages.all { it.uid != null }, "-U gives every package a uid")
    }

    @Test
    fun systemAndDisabledSetsComeFromTheirOwnListings() {
        val system = PmParsers.names(golden("pm_list_system.txt"))
        val disabled = PmParsers.names(golden("pm_list_disabled.txt"))
        assertTrue("com.android.settings" in system)
        assertTrue("com.google.android.gms.supervision" in disabled && disabled.size >= 1, disabled.toString())
        val packages = PmParsers.parseList(golden("pm_list_all.txt"), disabled = disabled)
        assertEquals(disabled.size, packages.count { !it.enabled }, "every disabled package is in the full list")
        assertEquals(false, packages.first { it.packageName == "com.google.android.gms.supervision" }.enabled)
    }

    @Test
    fun packagePathsContainingEqualsSignsAreSplitAtTheLastOne() {
        val line = "package:/data/app/~~Xy9Qw==/com.example.app-abc123==/base.apk=com.example.app versionCode:42 uid:10300"
        val p = PmParsers.parseList(line).single()
        assertEquals("com.example.app", p.packageName)
        assertEquals("/data/app/~~Xy9Qw==/com.example.app-abc123==/base.apk", p.apkPath)
        assertEquals(42L, p.versionCode)
        assertEquals(10300, p.uid)
        assertEquals("com.plain", PmParsers.parseList("package:com.plain").single().packageName)
    }

    @Test
    fun installerRepliesAreSuccessOrFailure() {
        assertTrue(PmParsers.resultOf("Success\n").isSuccess)
        assertTrue(PmParsers.resultOf("Performing Streamed Install\nSuccess").isSuccess)
        val failure = PmParsers.resultOf("Performing Streamed Install\nFailure [INSTALL_FAILED_ALREADY_EXISTS: Attempt to re-install]")
        assertTrue(failure.isFailure)
        assertTrue("INSTALL_FAILED_ALREADY_EXISTS" in failure.exceptionOrNull()!!.message!!)
        assertTrue(PmParsers.resultOf("").isFailure)
    }

    @Test
    fun theFullPackageDumpYieldsVersionFlagsAndPermissions() {
        val d = assertNotNull(PmParsers.parseDetails("com.android.settings", golden("dumpsys_package_settings_trimmed.txt")))
        assertEquals("16", d.summary.versionName)
        assertEquals(36L, d.summary.versionCode)
        assertTrue(d.summary.system)
        assertEquals(false, d.summary.debuggable)
        assertEquals(1000, d.uid)
        assertNull(d.summary.installer)
        assertNotNull(d.firstInstallTimeMs)
        assertTrue("android.permission.QUERY_ADVANCED_PROTECTION_MODE" in d.grantedPermissions, d.grantedPermissions.toString())
        assertTrue(d.requestedPermissions.isNotEmpty() && d.requestedPermissions.all { it.contains('.') })
        assertNull(PmParsers.parseDetails("x.y", "Unable to find package: x.y"))
    }

    // ---- am / activities / displays ----

    @Test
    fun amStartOutputsFromTheRealTool() {
        val ok = AmParsers.parseStart("Starting: Intent { cmp=com.android.settings/.Settings }\n")
        assertTrue(ok.started)
        assertEquals("com.android.settings/.Settings", ok.component)

        val already = AmParsers.parseStart(
            "Starting: Intent { cmp=com.android.settings/.Settings }\nWarning: Activity not started, intent has been delivered to currently running top-most instance.\n",
        )
        assertTrue(already.started, "an already running app counts as launched")
        assertTrue(already.message.startsWith("Warning:"))

        val bogus = AmParsers.parseStart("Starting: Intent { cmp=com.bogus.app/.Main }\nError type 3\nError: Activity class {com.bogus.app/com.bogus.app.Main} does not exist.\n")
        assertEquals(false, bogus.started)
        assertTrue("does not exist" in bogus.message, bogus.message)

        assertEquals(false, AmParsers.parseStart("").started)
    }

    @Test
    fun launcherResolutionTakesTheLastLine() {
        val out = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\ncom.android.settings/.Settings\n"
        assertEquals("com.android.settings/.Settings", AmParsers.parseResolvedComponent(out))
        assertNull(AmParsers.parseResolvedComponent("No activity found"))
    }

    @Test
    fun theResumedActivityOfEachDisplayComesFromItsOwnBlock() {
        val fg = ActivityParsers.foreground(golden("dumpsys_activity_activities_2displays.txt"))
        assertEquals(listOf(2, 0), fg.map { it.displayId })
        val onTwo = fg.first { it.displayId == 2 }
        assertEquals("com.android.settings", onTwo.packageName)
        assertEquals("com.android.settings/com.android.settings.Settings", onTwo.activity)
        val onZero = fg.first { it.displayId == 0 }
        assertEquals("com.google.android.apps.nexuslauncher", onZero.packageName)
        assertEquals("com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity", onZero.activity)
    }

    @Test
    fun displaysFromCmdDisplay() {
        val displays = DisplayParsers.parse(golden("cmd_display_get_displays_overlay.txt"))
        assertEquals(listOf(0, 2), displays.map { it.id })
        val main = displays[0]
        assertEquals("Built-in Screen", main.name)
        assertEquals(1080, main.width)
        assertEquals(2400, main.height)
        assertEquals(420, main.densityDpi)
        assertTrue(main.isDefault)
        assertEquals(false, main.isVirtual)
        assertTrue(main.uniqueId!!.startsWith("local:"))
        val overlay = displays[1]
        assertEquals("Overlay #1", overlay.name)
        assertTrue(overlay.isVirtual)
        assertEquals("overlay:1", overlay.uniqueId)
        assertEquals("ON", overlay.state)
    }

    // ---- system state ----

    @Test
    fun systemStateFromProcAndDumpsys() {
        val (total, avail) = SystemParsers.memInfo(golden("proc_meminfo.txt"))
        assertTrue(total!! > 1_000 && avail!! in 1..total, "total=$total avail=$avail")
        assertTrue(SystemParsers.dfFreeMb(golden("df_data.txt"))!! > 100)

        val (level, powered) = SystemParsers.battery(golden("dumpsys_battery.txt"))
        assertEquals(100, level)
        assertEquals(true, powered)
        assertEquals("none", SystemParsers.thermal(golden("dumpsys_thermalservice.txt")))

        // The recording was taken while the emulator's Wi-Fi was connected but not yet validated.
        val (type, online) = SystemParsers.network(golden("dumpsys_connectivity_full.txt"))
        assertEquals("wifi", type)
        assertEquals(false, online, "connected is not the same as having validated internet access")
        // A validated Wi-Fi network, as printed by the same tool earlier on the same emulator.
        val validated = "Active default network: 101\n  NetworkAgentInfo{network{101}  handle{437197393933}  ni{WIFI CONNECTED extra: } created=2026-10-05T02:04:29.201Z Score(Policies : TRANSPORT_PRIMARY&EVER_EVALUATED&IS_UNMETERED&EVER_VALIDATED&IS_VALIDATED ; KeepConnected : 0)"
        assertEquals("wifi" to true, SystemParsers.network(validated))
    }

    @Test
    fun theProcessListKeepsPidUserAndMemory() {
        val ps = SystemParsers.ps(golden("ps_all.txt"))
        val init = assertNotNull(ps.firstOrNull { it.pid == 1 })
        assertEquals("root", init.user)
        assertEquals("init", init.name)
        assertTrue(ps.any { it.name == "system_server" || it.name.endsWith("/system_server") })
        assertTrue(ps.any { it.name.startsWith("[") }, "kernel threads are listed in brackets")
    }

    @Test
    fun statRowsBecomeFileEntries() {
        val entries = SystemParsers.stat(golden("stat_list.txt"))
        assertTrue(entries.isNotEmpty())
        val dir = entries.first { it.isDirectory }
        assertTrue(dir.sizeBytes > 0 && dir.modifiedMs > 1_600_000_000_000)
        assertTrue(dir.mode!!.matches(Regex("[0-7]{3,4}")))
        val tricky = SystemParsers.stat("regular file|12|1700000000|644|/sdcard/a|b c.txt").single()
        assertEquals("/sdcard/a|b c.txt", tricky.path, "a pipe in a file name does not split it further")
    }

    // ---- logcat ----

    @Test
    fun everyLineOfARealEpochUidLogParses() {
        val now = 1_791_180_700_000L
        val lines = golden("logcat_epoch_uid.txt").lines().filter { it.isNotBlank() && !it.startsWith("---------") }
        assertTrue(lines.size > 20)
        val events = lines.map { line -> assertNotNull(LogcatParser.parse(line, now, ZoneOffset.UTC), "unparsed: $line") }
        assertTrue(events.all { it.timestampMs in 1_790_000_000_000..1_792_000_000_000 })
        assertTrue(events.any { it.uid == 1000 }, "system uid is preserved")
        assertTrue(events.all { it.tag.isNotBlank() && it.pid > 0 })
        val wifi = events.first { it.tag == "WifiScoreCard" }
        assertTrue(wifi.message.startsWith("BSSID update"), wifi.message)
    }

    // ---- uiautomator ----

    @Test
    fun theMultiWindowDumpBecomesTreesPerWindow() {
        val snapshot = UiAutomatorXml.parse(golden("uiautomator_dump_windows_settings.xml"), capturedAtMs = 42)
        val tree = snapshot.windows.single()
        assertEquals(0, tree.displayId)
        assertEquals(6, tree.windowId)
        assertEquals("Settings", tree.title)
        assertEquals("com.android.settings", tree.packageName)
        assertTrue(tree.isActive && tree.isFocused)
        assertEquals("uiautomator_dump", tree.source)
        assertEquals(66, tree.nodes.size)
        assertEquals(tree.nodes.size, tree.nodes.map { it.id }.toSet().size, "ids are unique")
        val root = tree.nodes.first()
        assertEquals("6:0", root.id)
        assertEquals(0, root.depth)
        assertNull(root.parentId)
        assertTrue(tree.nodes.drop(1).all { it.parentId != null && tree.node(it.parentId!!) != null }, "every parent exists")
        assertEquals(tree.nodes.size - 1, tree.nodes.sumOf { it.childCount }, "child counts add up to every non-root node")
        assertTrue(tree.nodes.any { it.isClickable } && tree.nodes.any { it.isScrollable })
        assertTrue(tree.nodes.all { it.has(NodeFlag.VISIBLE) })
    }

    @Test
    fun thePlainHierarchyDumpIsOneWindow() {
        val snapshot = UiAutomatorXml.parse(golden("uiautomator_dump_settings_home.xml"), 1)
        val tree = snapshot.windows.single()
        assertEquals(0, tree.windowId)
        assertEquals("com.android.settings", tree.packageName)
        assertTrue(tree.nodes.size > 20)
        assertEquals("0:0", tree.nodes.first().id)
    }

    @Test
    fun theNodeLimitTruncatesAndSaysSo() {
        val tree = UiAutomatorXml.parse(golden("uiautomator_dump_windows_settings.xml"), 1, maxNodesPerWindow = 10).windows.single()
        assertEquals(10, tree.nodes.size)
        assertTrue(tree.truncated)
    }

    @Test
    fun anEmptyDumpHasNoWindows() {
        val xml = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><displays />"
        assertTrue(UiAutomatorXml.parse(xml, 1).windows.isEmpty())
    }

    @Test
    fun boundsParseIncludingNegativeCoordinates() {
        assertEquals(app.umbra.core.model.Rect(0, 0, 1080, 2400), UiAutomatorXml.bounds("[0,0][1080,2400]"))
        assertEquals(app.umbra.core.model.Rect(-20, 10, 300, 400), UiAutomatorXml.bounds("[-20,10][300,400]"))
        assertEquals(app.umbra.core.model.Rect.EMPTY, UiAutomatorXml.bounds(null))
        assertEquals(app.umbra.core.model.Rect.EMPTY, UiAutomatorXml.bounds("garbage"))
    }

    @Test
    fun theGoldenFilesAreRealRecordings() {
        // A guard against someone replacing a recording with a hand-written file: real dumps are large.
        val dir = File(javaClass.getResource("/golden/pm_list_all.txt")!!.toURI()).parentFile
        assertTrue(File(dir, "uiautomator_dump_windows_settings.xml").length() > 10_000)
        assertTrue(File(dir, "dumpsys_activity_activities_2displays.txt").length() > 10_000)
    }
}
