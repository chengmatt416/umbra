package app.umbra.platform.adb

import app.umbra.platform.api.AppLaunch
import app.umbra.platform.api.ShellEvent
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.VirtualDisplaySpec
import app.umbra.platform.api.exec
import app.umbra.platform.privileged.PrivilegedClient
import app.umbra.platform.privileged.ServerDisplays
import app.umbra.platform.privileged.ServerException
import app.umbra.platform.privileged.ServerScreenshots
import app.umbra.platform.privileged.ServerShell
import app.umbra.platform.privileged.Wire
import app.umbra.platform.shell.OverlayDisplays
import app.umbra.platform.shell.ShellApps
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int

/**
 * The privileged helper, started on the device the two ways users start it (as the shell user over
 * adb; as root that drops to the shell user) and driven through the same client the app uses.
 * The display test is the point: a real virtual display, a real app launched on it, a real picture of it.
 */
class PrivilegedHelperLiveTest {
    private suspend fun startHelper(adb: Adb, viaRoot: Boolean): Pair<PrivilegedClient, Int> {
        val shell = adb.shell()
        val port = 42000 + SecureRandom().nextInt(2000)
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val apk = shell.exec("pm path app.umbra.agent").stdout.trim().removePrefix("package:")
        shell.exec("pkill -f app.umbra.platform.server.ServerMain; rm -f /data/local/tmp/umbra-helper-test.log")
        val command = "CLASSPATH=$apk setsid nohup app_process /system/bin app.umbra.platform.server.ServerMain --port $port --token $token${if (viaRoot) " --uid 2000" else ""} >/data/local/tmp/umbra-helper-test.log 2>&1 &"
        shell.exec(if (viaRoot) "su 0 sh -c '$command'" else command)
        repeat(60) {
            if ("listening" in shell.exec("cat /data/local/tmp/umbra-helper-test.log").stdout) {
                assertTrue(adb.forward(port, port).ok)
                return PrivilegedClient(port, token) to port
            }
            delay(500)
        }
        error("the helper did not start:\n" + shell.exec("cat /data/local/tmp/umbra-helper-test.log").stdout)
    }

    private suspend fun prepare(): Adb {
        val adb = DeviceTestSupport.requireDevice()
        val apk = DeviceTestSupport.requireDebugApk()
        assertTrue(adb.install(apk).ok)
        return adb
    }

    @Test
    fun a_theHelperRunsAsTheShellUserAndRunsCommands(): Unit = runBlocking {
        val adb = prepare()
        val (client, port) = startHelper(adb, viaRoot = false)
        try {
            assertTrue(client.isUp())
            assertEquals(2000, client.serverInfo?.get("uid")?.let { (it as kotlinx.serialization.json.JsonPrimitive).int })
            val shell = ServerShell(client)
            assertEquals(ShellIdentity.SHELL, shell.identity)
            assertEquals("2000", shell.exec("id -u").stdout.trim())

            // Everything ProcessShell supports works through the helper: stdin, exit codes, binary, streams, timeouts.
            assertEquals("héllo", shell.exec(ShellRequest("cat", stdin = "héllo")).stdout)
            val failing = shell.exec("echo oops >&2; exit 4")
            assertEquals(4, failing.exitCode)
            assertEquals("oops", failing.stderr)
            assertEquals(listOf(0, 1, 255), shell.execBytes(ShellRequest("printf '\\000\\001\\377'")).stdout.map { it.toInt() and 0xFF })
            assertTrue(shell.exec(ShellRequest("sleep 20.7", timeoutMs = 500)).timedOut)

            val events = withTimeout(20_000) { shell.stream(ShellRequest("printf 'a\\nb\\n'; exit 3")).toList() }
            assertEquals(listOf("a", "b"), events.filterIsInstance<ShellEvent.Out>().map { it.line })
            assertEquals(ShellEvent.Exit(3), events.last())

            // Cancelling a stream stops the command on the device.
            val marker = "sleep 33.${System.nanoTime() % 1000}"
            withTimeout(20_000) { shell.stream(ShellRequest("echo go; $marker")).first() }
            delay(1_000)
            assertTrue(adb.shell().exec("pgrep -f '$marker' | grep -v \$\$ | head -1").stdout.isBlank(), "the streamed command was left running")
            // Ending a stream closes pipes under a blocked read, which must not take the helper down with it.
            assertEquals("2000", shell.exec("id -u").stdout.trim(), "the helper survived the cancelled stream")

            // A client with the wrong token is refused.
            val intruder = PrivilegedClient(port, "not-the-token")
            assertFailsWith<ServerException> { intruder.call("hello") }
            intruder.close()
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun b_aVirtualDisplayHostsARealAppAndCanBePhotographed(): Unit = runBlocking {
        val adb = prepare()
        val (client, _) = startHelper(adb, viaRoot = false)
        try {
            val shell = adb.shell()
            shell.exec("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
            val displays = ServerDisplays(client) { OverlayDisplays(shell).list() }
            assertTrue(displays.isAvailable())

            val created = displays.create(VirtualDisplaySpec("umbra-live", 720, 1280, 280))
            try {
                assertTrue(created.id > 0 && created.isVirtual)
                val system = displays.list().firstOrNull { it.id == created.id }
                assertTrue(system != null, "the system lists the new display: ${displays.list()}")
                assertEquals(720, system!!.width)
                assertEquals(1280, system.height)
                assertEquals(listOf(created.id), displays.ownedDisplays().map { it.id })

                val apps = ShellApps(shell)
                val launched = apps.launch(AppLaunch(packageName = "com.android.settings", displayId = created.id))
                assertTrue(launched.started, launched.message)
                var onDisplay = false
                repeat(40) {
                    if (!onDisplay) {
                        onDisplay = apps.foreground(created.id).any { it.packageName == "com.android.settings" }
                        if (!onDisplay) delay(500)
                    }
                }
                assertTrue(onDisplay, "Settings did not come up on display ${created.id}")
                assertTrue(apps.foreground(0).none { it.packageName == "com.android.settings" }, "and not on the main display")

                // A real picture of the invisible display: the right size, and it changes as the app finishes drawing.
                val screenshots = ServerScreenshots(client)
                val splash = screenshots.capture(created.id)
                assertEquals("image/png", splash.mime)
                assertEquals(720, splash.width)
                assertEquals(1280, splash.height)
                val codec = JvmImageCodec()
                val firstImage = codec.decode(splash.bytes)
                assertTrue(firstImage.pixels.toSet().size > 1, "the first frame is not a blank surface")

                // Wait until the accessibility tree shows the app's real UI on this display.
                val ui = app.umbra.platform.shell.UiAutomatorUi(shell)
                var drawn = false
                repeat(40) {
                    if (!drawn) {
                        val tree = ui.snapshot(app.umbra.platform.api.SnapshotOptions(displayId = created.id)).windows.firstOrNull { it.packageName == "com.android.settings" }
                        drawn = tree?.nodes?.any { it.text == "Search Settings" } == true
                        if (!drawn) delay(1_000)
                    }
                }
                assertTrue(drawn, "Settings never showed its UI on display ${created.id}")

                delay(1_000)
                val settled = screenshots.capture(created.id)
                java.io.File("build/artifacts").also { it.mkdirs() }.resolve("virtual-display.png").writeBytes(settled.bytes)
                val after = codec.decode(settled.bytes)
                val changed = app.umbra.platform.vision.ImageOps.diffRatio(firstImage, after)
                assertTrue(changed > 0.05f, "the picture follows what the app draws: only ${"%.1f".format(changed * 100)}% of it changed since the splash")
            } finally {
                ShellApps(shell).stop("com.android.settings")
                displays.destroy(created.id)
            }
            delay(1_000)
            assertTrue(OverlayDisplays(shell).list().none { it.id == created.id }, "the display is gone")
            assertTrue(displays.ownedDisplays().isEmpty())
            assertFailsWith<app.umbra.platform.api.BackendException> { displays.destroy(created.id) }
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun c_aHelperStartedAsRootDropsToTheShellUser(): Unit = runBlocking {
        val adb = prepare()
        if (!adb.root().isAvailable()) return@runBlocking // a production phone: root is the user's to grant
        val (client, _) = startHelper(adb, viaRoot = true)
        try {
            assertTrue(client.isUp())
            assertEquals(2000, client.serverInfo?.get("uid")?.let { (it as kotlinx.serialization.json.JsonPrimitive).int })
            assertEquals("2000", ServerShell(client).exec("id -u").stdout.trim())
            // It can still create a display: the point of dropping to uid 2000.
            val displays = ServerDisplays(client) { OverlayDisplays(adb.shell()).list() }
            val d = displays.create(VirtualDisplaySpec("from-root", 720, 1280, 280))
            displays.destroy(d.id)
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun d_aProcessKeepsItsInputOpenAndDiesWithItsConnection(): Unit = runBlocking {
        val adb = prepare()
        val (client, _) = startHelper(adb, viaRoot = false)
        try {
            // Two-way: what is written to its input comes back from its output, and closing the input ends it.
            val (id, events) = client.openProc("cat")
            suspend fun readUntil(text: String): String = withTimeout(15_000) {
                var seen = ""
                while (text !in seen) {
                    val event = events.receive()
                    (event["out"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { seen += String(Wire.unb64(it), Charsets.UTF_8) }
                }
                seen
            }
            client.writeProc(id, "first line\n".toByteArray())
            assertTrue("first line" in readUntil("first line"))
            client.writeProc(id, "héllo\n".toByteArray())
            assertTrue("héllo" in readUntil("héllo"), "UTF-8 survives the round trip")
            client.closeProcInput(id)
            val exit = withTimeout(15_000) {
                var code: Int? = null
                while (code == null) code = (events.receive()["exit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                code
            }
            assertEquals(0, exit, "cat ends when its input does")

            // A process belongs to the connection that started it.
            val (_, _) = client.openProc("exec sleep 3141")
            suspend fun running() = adb.shell().exec("ps -A -o ARGS | grep -c '[s]leep 3141'").stdout.trim()
            withTimeout(10_000) { while (running() == "0") delay(200) }
            client.close()
            withTimeout(15_000) { while (running() != "0") delay(300) }
        } finally {
            runCatching { client.shutdown() }
        }
    }

    @Test
    fun e_aLargeInputStreamsToACommandThroughTheHelper(): Unit = runBlocking {
        val adb = prepare()
        val (client, _) = startHelper(adb, viaRoot = false)
        try {
            val shell = ServerShell(client)
            // 6 MB of a known pattern: far more than one message holds, so it has to be fed in pieces.
            val size = 6 * 1024 * 1024
            val data = ByteArray(size) { (it * 31 + (it shr 8)).toByte() }
            val expected = java.security.MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
            val file = "/data/local/tmp/umbra-stream-test.bin"
            val result = shell.exec(ShellRequest("cat > $file; wc -c < $file; md5sum < $file; rm -f $file", stdinSource = { data.inputStream() }, timeoutMs = 120_000))
            assertTrue(result.ok, result.combined)
            val lines = result.stdout.lines().filter { it.isNotBlank() }
            assertEquals(size.toString(), lines[0].trim(), "every byte arrived")
            assertTrue(lines[1].startsWith(expected), "and unchanged: ${lines[1]} instead of $expected")

            // A source that fails midway ends the command with what it got, instead of leaving it waiting for more.
            val failing = object : java.io.InputStream() {
                var served = 0
                override fun read(): Int = if (served++ < 1000) 65 else throw java.io.IOException("the source failed")
            }
            val broken = shell.exec(ShellRequest("wc -c", stdinSource = { failing }, timeoutMs = 30_000))
            assertEquals("1000", broken.stdout.trim())

            // A command that never reads its input does not hang the caller either.
            val ignoring = shell.exec(ShellRequest("echo done", stdinSource = { data.inputStream() }, timeoutMs = 30_000))
            assertEquals("done", ignoring.stdout.trim())
        } finally {
            client.shutdown()
        }
    }
}
