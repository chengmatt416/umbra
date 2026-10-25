package app.umbra.agent

import app.umbra.tools.ToolBridgeServer
import java.security.SecureRandom

/**
 * Owns the loopback tool bridge (spec §14: tools are available to anything on the device that holds
 * the token, with the same safety checks as the agent). Off by default; the user (or a debug build's
 * dev receiver) turns it on and gets the port and token.
 */
class BridgeController(private val container: AppContainer, private val control: app.umbra.tools.ControlHandler? = null) {
    @Volatile
    private var server: ToolBridgeServer? = null

    @Volatile
    var token: String? = null
        private set

    val port: Int get() = server?.boundPort ?: -1
    val running: Boolean get() = server != null

    @Synchronized
    fun start(requestedToken: String? = null, requestedPort: Int = 0): Int {
        if (server != null && requestedToken != null && requestedToken == token && (requestedPort == 0 || requestedPort == port)) return port
        stop()
        val t = requestedToken?.takeIf { it.length >= 16 } ?: randomToken()
        val s = ToolBridgeServer(t, container::toolHost, requestedPort, control = control)
        val bound = s.start()
        server = s
        token = t
        return bound
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
        token = null
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
