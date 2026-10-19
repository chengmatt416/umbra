package app.umbra.agent.agents

/**
 * The message apps an agent can watch (docs/design/agents-2026-10.md §6) and the phone's notifications. An agent's
 * `channels` hold these ids; the connectors (phase 4) say how each one is read and answered.
 */
object Channels {
    const val NOTIFICATIONS = "notifications"
    const val SMS = "sms"

    /** [packages]: the Android apps whose notifications belong to this channel (first: the one to open). */
    data class App(val id: String, val label: String, val packages: List<String>)

    val apps: List<App> = listOf(
        App(SMS, "SMS", listOf("com.google.android.apps.messaging", "com.android.messaging", "com.samsung.android.messaging")),
        App("whatsapp", "WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b")),
        App("line", "LINE", listOf("jp.naver.line.android")),
        App("telegram", "Telegram", listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")),
        App("messenger", "Messenger", listOf("com.facebook.orca")),
        App("instagram", "Instagram", listOf("com.instagram.android")),
        App("snapchat", "Snapchat", listOf("com.snapchat.android")),
        App("x", "X", listOf("com.twitter.android")),
        App("imessage", "iMessage", listOf("com.openbubbles.messaging", "com.bluebubbles.messaging")),
    )

    fun app(id: String): App? = apps.firstOrNull { it.id == id }

    /** The channel a notification's app belongs to, if it is a message app. */
    fun ofPackage(pkg: String): App? = apps.firstOrNull { pkg in it.packages }
}
