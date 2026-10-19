package app.umbra.agent.connectors

import app.umbra.agent.agents.Channels
import app.umbra.core.model.UmbraJson
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * How Umbra reaches a message app (docs/design/agents-2026-10.md §6.1). Several can be on for one app: reads use the
 * richest one that works, sends follow the user's order.
 */
enum class Method(val id: String) {
    /** Android's own providers (SMS). */
    SYSTEM("system"),
    /** The user's own API key, token or server (Telegram with their api_id, BlueBubbles, X API, Graph API). */
    API("api"),
    /** New messages from the app's notifications; replies through the notification's reply action. */
    NOTIFICATIONS("notifications"),
    /** The real app on a background screen, driven like a person would. */
    VISUAL("visual"),
    /** A reverse-engineered client in the Linux guest: opt-in, with the risk of a ban. */
    UNOFFICIAL("unofficial");

    companion object {
        fun of(id: String): Method? = entries.firstOrNull { it.id == id }
    }
}

/** What the user set for one app: the methods on (in their order), consent for the unofficial one, chats that may get replies without asking. */
@Serializable
data class ConnectorConfig(
    val appId: String,
    val methods: List<String> = emptyList(),
    /** When the user accepted the ban risk of the unofficial client (null: never). */
    val unofficialConsentMs: Long? = null,
    /** Chats the agents may answer on their own (§6.4); every other send asks first. Groups never default to this. */
    val autoChats: List<String> = emptyList(),
    /** Plain settings of the API method (server URL, account id); secrets live in the secret store. */
    val api: Map<String, String> = emptyMap(),
)

/** The methods each app offers, the default first (§6.2). */
object Offers {
    fun of(appId: String): List<Method> = when (appId) {
        Channels.SMS -> listOf(Method.SYSTEM, Method.NOTIFICATIONS)
        "telegram" -> listOf(Method.NOTIFICATIONS, Method.API, Method.VISUAL)
        "whatsapp" -> listOf(Method.NOTIFICATIONS, Method.VISUAL, Method.UNOFFICIAL)
        "instagram" -> listOf(Method.NOTIFICATIONS, Method.VISUAL, Method.UNOFFICIAL, Method.API)
        "messenger" -> listOf(Method.NOTIFICATIONS, Method.VISUAL, Method.UNOFFICIAL, Method.API)
        "line" -> listOf(Method.NOTIFICATIONS, Method.VISUAL)
        "imessage" -> listOf(Method.API, Method.NOTIFICATIONS, Method.VISUAL)
        "snapchat" -> listOf(Method.NOTIFICATIONS, Method.VISUAL)
        "x" -> listOf(Method.NOTIFICATIONS, Method.API, Method.VISUAL, Method.UNOFFICIAL)
        else -> listOf(Method.NOTIFICATIONS)
    }

    /** On before the user changes anything: the notification bridge everywhere, and the system method for SMS. */
    fun defaults(appId: String): List<Method> = if (appId == Channels.SMS) listOf(Method.SYSTEM, Method.NOTIFICATIONS) else listOf(Method.NOTIFICATIONS)
}

/** connectors.json: one [ConnectorConfig] per app the user touched. */
class ConnectorSettings(private val file: File) {
    private val mutable = MutableStateFlow(load())
    val all: StateFlow<List<ConnectorConfig>> = mutable.asStateFlow()

    fun of(appId: String): ConnectorConfig = mutable.value.firstOrNull { it.appId == appId } ?: ConnectorConfig(appId, Offers.defaults(appId).map { it.id })

    @Synchronized
    fun update(appId: String, change: (ConnectorConfig) -> ConnectorConfig): ConnectorConfig {
        val next = change(of(appId))
        val list = mutable.value.filterNot { it.appId == appId } + next
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(UmbraJson.encodeToString(ListSerializer(ConnectorConfig.serializer()), list))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        mutable.value = list
        return next
    }

    fun methods(appId: String): List<Method> = of(appId).methods.mapNotNull { Method.of(it) }

    private fun load(): List<ConnectorConfig> = file.takeIf { it.exists() }?.let {
        runCatching { UmbraJson.decodeFromString(ListSerializer(ConnectorConfig.serializer()), it.readText()) }.getOrNull()
    }.orEmpty()
}
