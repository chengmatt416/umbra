package app.umbra.agent

import android.content.Context
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.exec
import app.umbra.platform.privileged.PrivilegedClient
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

/**
 * Knows where the privileged helper listens, starts it when the device allows (root), and hands out
 * the command a user without root runs once over adb. The helper keeps running when the app is
 * closed, so the port and token are remembered across restarts.
 */
class HelperController(private val context: Context) {
    private val prefs = context.getSharedPreferences("helper", Context.MODE_PRIVATE)
    private val endpoint = AtomicReference(load())

    /** Connection to the helper; follows [configure] without being recreated. */
    val client = PrivilegedClient({ endpoint.get() })

    // A second helper that runs as root, for phones where the app cannot call `su` itself (see RootViaHelperShell).
    private val rootEndpoint = AtomicReference(loadRoot())
    val rootClient = PrivilegedClient({ rootEndpoint.get() })

    private fun loadRoot(): PrivilegedClient.Endpoint? {
        val port = prefs.getInt("rootPort", 0)
        val token = prefs.getString("rootToken", null)
        return if (port > 0 && token != null) PrivilegedClient.Endpoint(port, token) else null
    }

    fun configureRoot(port: Int, token: String) {
        prefs.edit().putInt("rootPort", port).putString("rootToken", token).apply()
        rootEndpoint.set(PrivilegedClient.Endpoint(port, token))
    }

    private fun load(): PrivilegedClient.Endpoint? {
        val port = prefs.getInt("port", 0)
        val token = prefs.getString("token", null)
        return if (port > 0 && token != null) PrivilegedClient.Endpoint(port, token) else null
    }

    fun configure(port: Int, token: String) {
        prefs.edit().putInt("port", port).putString("token", token).apply()
        endpoint.set(PrivilegedClient.Endpoint(port, token))
    }

    fun forget() {
        prefs.edit().clear().apply()
        endpoint.set(null)
        rootEndpoint.set(null)
    }

    /** Ends both helpers and forgets how to reach them: no privileged access is left until one is started again (spec §32). */
    suspend fun revoke() {
        runCatching { client.stopServer() }
        runCatching { rootClient.stopServer() }
        forget()
    }

    val configured: Boolean get() = endpoint.get() != null

    private val apkPath: String get() = context.applicationInfo.sourceDir

    /** The shell command that starts the helper in the background. [dropToUid] makes a root-started helper become that user. */
    fun launchCommand(port: Int, token: String, dropToUid: Int? = null, logFile: String = "/dev/null"): String =
        "CLASSPATH=$apkPath setsid nohup app_process /system/bin app.umbra.platform.server.ServerMain --port $port --token $token" +
            (dropToUid?.let { " --uid $it" } ?: "") + " >$logFile 2>&1 &"

    /** What a user without root pastes into `adb shell` once per boot (spec §23). */
    fun adbCommand(): String {
        val port = endpoint.get()?.port ?: freshPort()
        val token = endpoint.get()?.token ?: freshToken()
        if (endpoint.get() == null) configure(port, token)
        return launchCommand(port, token, logFile = "/data/local/tmp/umbra-helper.log")
    }

    /** Starts the helper from a root shell, as the shell user, and waits until it answers. */
    suspend fun startWithRoot(root: ShellBackend, timeoutMs: Long = 20_000): Boolean {
        if (client.isUp()) return true
        val port = freshPort()
        val token = freshToken()
        configure(port, token)
        root.exec(launchCommand(port, token, dropToUid = SHELL_UID, logFile = "/data/local/tmp/umbra-helper.log"), timeoutMs = 15_000)
        var waited = 0L
        while (waited < timeoutMs) {
            if (client.isUp()) return true
            delay(500)
            waited += 500
        }
        return false
    }

    private fun freshPort(): Int = 40_000 + SecureRandom().nextInt(20_000)

    private fun freshToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private companion object {
        const val SHELL_UID = 2000
    }
}
