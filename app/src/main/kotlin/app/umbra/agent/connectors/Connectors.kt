package app.umbra.agent.connectors

import app.umbra.agent.AppContainer
import app.umbra.agent.R
import java.io.File

/**
 * The message apps, whatever reaches them (docs/design/agents-2026-10.md §6): which methods are on for each app and how
 * that reads to the user. Agents read and send through here and never care which method is underneath.
 */
class Connectors(private val container: AppContainer) {
    val settings = ConnectorSettings(File(container.context.filesDir, "connectors/connectors.json"))
    val messages = Messages(container)
    val apis = MessageApis(container)

    /** "Notifications · Visual": the methods on for [appId], in the user's order. */
    fun summary(appId: String): String {
        val on = settings.methods(appId)
        if (on.isEmpty()) return container.context.getString(R.string.cn_off)
        return on.joinToString(" · ") { methodName(it) }
    }

    fun methodName(m: Method): String = container.context.getString(
        when (m) {
            Method.SYSTEM -> R.string.cn_m_system
            Method.API -> R.string.cn_m_api
            Method.NOTIFICATIONS -> R.string.cn_m_notifications
            Method.VISUAL -> R.string.cn_m_visual
            Method.UNOFFICIAL -> R.string.cn_m_unofficial
        },
    )
}
