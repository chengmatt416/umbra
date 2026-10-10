package app.umbra.tools

import app.umbra.platform.adb.Adb
import app.umbra.platform.api.exec
import app.umbra.platform.shell.SystemGrants
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Runs the scenarios against the app installed on the device: installs the debug APK, applies the
 * system grants with a real shell, starts the app, switches its tool bridge on through the debug
 * receiver and talks to it over `adb forward`. Everything the tools do happens inside the app, with
 * the real accessibility service, the real notification listener and the app's own shells.
 */
class DeviceRig(private val adb: Adb, apk: File) : ToolRig {
    override val shell = adb.shell()
    private val client: BridgeClient
    private val port = 47000 + SecureRandom().nextInt(1000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val approve = AtomicBoolean(true)
    private val approvalIds = java.util.concurrent.CopyOnWriteArraySet<String>()
    private val running = AtomicBoolean(true)

    init {
        runBlocking {
            val install = adb.install(apk)
            check(install.ok) { "install failed: ${install.stdout} ${install.stderr}" }
            // Force-stopping an app disables its accessibility service, so start from a stopped app and enable afterwards.
            shell.exec("am force-stop $PACKAGE")
            val grants = SystemGrants(shell, PACKAGE, "app.umbra.platform.android.UmbraAccessibilityService", "app.umbra.platform.android.UmbraNotificationListener")
            grants.grantAll()
            shell.exec("am start -n $PACKAGE/.MainActivity")
            startHelper()
            // The app needs a moment to start its process and service on a slow device.
            waitForAccessibility()
        }
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val reply = runBlocking { broadcast("BRIDGE_START", "--es token $token --ei port $port") }
        check(reply == port.toString()) { "the tool bridge did not start: $reply" }
        runBlocking { adb.forward(port, port).also { check(it.ok) { "adb forward failed: ${it.stderr}" } } }
        client = BridgeClient("127.0.0.1", port, token)
        scope.launch {
            // Stands in for the user at the approval dialog.
            while (isActive && running.get()) {
                val id = try {
                    broadcast("RESOLVE_APPROVAL", "--es decision ${if (approve.get()) "approve" else "deny"}")
                } catch (_: Exception) {
                    "none"
                }
                if (id != "none" && id.isNotBlank()) approvalIds += id
                delay(600)
            }
        }
    }

    /**
     * Starts the privileged helper the way a user would: as the shell user over adb, or (with
     * -Dumbra.helper.root=true) as root that drops to the shell user, which is how a rooted phone starts it.
     */
    private suspend fun startHelper() {
        val port = 41000 + SecureRandom().nextInt(1000)
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val apk = shell.exec("pm path $PACKAGE").stdout.trim().removePrefix("package:")
        shell.exec("pkill -f app.umbra.platform.server.ServerMain; rm -f /data/local/tmp/umbra-helper.log")
        val viaRoot = System.getProperty("umbra.helper.root") == "true"
        val command = "CLASSPATH=$apk setsid nohup app_process /system/bin app.umbra.platform.server.ServerMain --port $port --token $token${if (viaRoot) " --uid 2000" else ""} >/data/local/tmp/umbra-helper.log 2>&1 &"
        shell.exec(if (viaRoot) "su 0 sh -c '$command'" else command)
        repeat(60) {
            if ("listening" in shell.exec("cat /data/local/tmp/umbra-helper.log").stdout) {
                val reply = broadcast("HELPER_CONFIGURE", "--es token $token --ei port $port")
                check(reply == "configured") { "the app did not accept the helper settings: '$reply'" }
                return
            }
            delay(500)
        }
        error("the privileged helper did not start:\n" + shell.exec("cat /data/local/tmp/umbra-helper.log").stdout)
    }

    private suspend fun waitForAccessibility() {
        repeat(60) {
            val dump = shell.exec("dumpsys accessibility").stdout
            if (dump.substringAfter("Bound services", "").take(500).contains("UmbraAccessibilityService")) return
            delay(500)
        }
        error("the accessibility service did not bind")
    }

    private suspend fun broadcast(action: String, extras: String): String {
        val out = shell.exec("am broadcast -a app.umbra.agent.dev.$action $extras -p $PACKAGE", timeoutMs = 30_000).stdout
        return Regex("""data="([^"]*)"""").find(out)?.groupValues?.get(1) ?: ""
    }

    override suspend fun call(tool: String, args: String, session: String): ToolReply = withContext(Dispatchers.IO) {
        val r = client.call(tool, Json.parseToJsonElement(args).jsonObject, session)
        ToolReply(r.isError, r.text, r.images)
    }

    override suspend fun reset() {
        shell.exec("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; settings put system screen_off_timeout 1800000")
        shell.exec("settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0")
        shell.exec("am force-stop com.android.settings; am force-stop com.google.android.settings.intelligence; input keyevent KEYCODE_HOME")
        for (session in listOf("live", "agent-a", "agent-b")) call("android_display", """{"action":"release","display_id":0}""", session)
        approvalIds.clear()
        answerApprovals(true)
    }

    override fun answerApprovals(approve: Boolean) {
        this.approve.set(approve)
    }

    override fun approvalCount(): Int = approvalIds.size

    override fun close() {
        running.set(false)
        scope.cancel()
        runCatching { client.close() }
        runBlocking { broadcast("BRIDGE_STOP", "") }
    }

    companion object {
        const val PACKAGE = "app.umbra.agent"
    }
}
