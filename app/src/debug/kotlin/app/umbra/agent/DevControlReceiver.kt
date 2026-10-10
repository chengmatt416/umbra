package app.umbra.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.umbra.core.safety.ApprovalDecision
import kotlinx.coroutines.launch

/**
 * Development control over adb (debug builds only):
 *
 *     adb shell am broadcast -a app.umbra.agent.dev.BRIDGE_START --es token <16+ chars> --ei port 47000 app.umbra.agent
 *     adb shell am broadcast -a app.umbra.agent.dev.RESOLVE_APPROVAL --es decision approve
 *
 * The first switches the loopback tool bridge on (the reply data is the bound port), the second answers
 * the oldest pending approval so scripted runs do not wait for a person.
 */
class DevControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        when (intent.action) {
            ACTION_START -> {
                val bridge = bridge(container)
                val port = bridge.start(intent.getStringExtra("token"), intent.getIntExtra("port", 0))
                setResult(0, port.toString(), null)
            }
            ACTION_STOP -> {
                bridge(container).stop()
                setResult(0, "stopped", null)
            }
            ACTION_HELPER -> {
                container.helper.configure(intent.getIntExtra("port", 0), intent.getStringExtra("token").orEmpty())
                setResult(0, "configured", null)
            }
            ACTION_ROOT_HELPER -> {
                container.helper.configureRoot(intent.getIntExtra("port", 0), intent.getStringExtra("token").orEmpty())
                // Root just became available: the Linux environment's state may have been read as "no root" at startup.
                container.scope.launch { container.guest.refresh() }
                setResult(0, "configured", null)
            }
            ACTION_RESOLVE -> {
                val pending = container.approvals.pending.value.firstOrNull()
                if (pending == null) {
                    setResult(0, "none", null)
                } else {
                    val decision = if (intent.getStringExtra("decision") == "deny") ApprovalDecision.Denied("denied from adb") else ApprovalDecision.Approved()
                    container.approvals.resolve(pending.id, decision)
                    setResult(0, pending.id, null)
                }
            }
        }
    }

    private fun bridge(container: AppContainer): BridgeController = BridgeHolder.get(container)

    companion object {
        const val ACTION_START = "app.umbra.agent.dev.BRIDGE_START"
        const val ACTION_STOP = "app.umbra.agent.dev.BRIDGE_STOP"
        const val ACTION_RESOLVE = "app.umbra.agent.dev.RESOLVE_APPROVAL"
        const val ACTION_HELPER = "app.umbra.agent.dev.HELPER_CONFIGURE"
        const val ACTION_ROOT_HELPER = "app.umbra.agent.dev.ROOT_HELPER_CONFIGURE"
    }
}

internal object BridgeHolder {
    @Volatile
    private var controller: BridgeController? = null

    @Synchronized
    fun get(container: AppContainer): BridgeController = controller ?: BridgeController(container, DebugControl(container)).also { controller = it }
}
