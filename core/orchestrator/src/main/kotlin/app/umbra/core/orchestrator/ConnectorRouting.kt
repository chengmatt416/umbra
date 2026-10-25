package app.umbra.core.orchestrator

/**
 * Connector-first execution routing (spec §9.1): when the agent has a structured connector for a service, its
 * browser or computer tools may not drive that service's web app instead. Structured tools are faster, keep the
 * approval and DLP boundaries of the connector, and do not race the user's own logged-in session.
 */
object ConnectorRouting {
    data class Route(val connector: String, val host: String, val tools: String) {
        val message get() = "Connector first: $host is served by the enabled $connector connector. Use $tools instead of the browser or screen tools for it; " +
            "use visual control only for what the connector cannot do, after telling the user why."
    }

    private val messageTools = "messages_list / messages_read / messages_send"
    /** Web hosts of each connector id; a host matches itself and its subdomains. */
    private val hosts: Map<String, Pair<List<String>, String>> = mapOf(
        "whatsapp" to (listOf("web.whatsapp.com", "whatsapp.com", "wa.me") to messageTools),
        "telegram" to (listOf("web.telegram.org", "telegram.org", "t.me") to messageTools),
        "messenger" to (listOf("messenger.com") to messageTools),
        "instagram" to (listOf("instagram.com") to messageTools),
        "x" to (listOf("x.com", "twitter.com") to messageTools),
        "line" to (listOf("line.me") to messageTools),
        "snapchat" to (listOf("web.snapchat.com", "snapchat.com") to messageTools),
        "sms" to (listOf("messages.google.com") to messageTools),
        "imessage" to (listOf("icloud.com") to messageTools),
        "email" to (listOf("mail.google.com", "gmail.com") to "email_read / email_draft / email_send"),
        "calendar" to (listOf("calendar.google.com") to "calendar_read / calendar_write / calendar_update"),
        "contacts" to (listOf("contacts.google.com") to "contacts_read"),
        "tasks" to (listOf("tasks.google.com") to "tasks_read / tasks_write"),
    )
    private val url = Regex("""(?i)\b(?:https?|wss?)://([a-z0-9.-]+)""")

    /** The first connector-served host named in [text] whose connector is in [enabled]. */
    fun route(text: String, enabled: Set<String>): Route? {
        if (enabled.isEmpty()) return null
        for (match in url.findAll(text.take(200_000))) {
            val host = match.groupValues[1].lowercase().trimEnd('.')
            for ((connector, entry) in hosts) {
                if (connector !in enabled) continue
                if (entry.first.any { host == it || host.endsWith(".$it") }) return Route(connector, host, entry.second)
            }
        }
        return null
    }

    /** Android apps of each connector, for the launch/switch tools. */
    private val packages = mapOf(
        "com.whatsapp" to "whatsapp", "com.whatsapp.w4b" to "whatsapp", "org.telegram.messenger" to "telegram", "org.thunderdog.challegram" to "telegram",
        "com.facebook.orca" to "messenger", "com.instagram.android" to "instagram", "com.twitter.android" to "x", "jp.naver.line.android" to "line",
        "com.snapchat.android" to "snapchat", "com.google.android.apps.messaging" to "sms", "com.google.android.gm" to "email",
        "com.google.android.calendar" to "calendar", "com.google.android.contacts" to "contacts", "com.google.android.apps.tasks" to "tasks",
    )

    fun routePackage(packageName: String, enabled: Set<String>): Route? {
        val connector = packages[packageName]?.takeIf { it in enabled } ?: return null
        return Route(connector, packageName, requireNotNull(hosts[connector]).second)
    }

    /** Tools that drive a web page or screen; structured host tools and file tools are never routed. */
    fun drivesUi(tool: String) = tool in setOf("browser", "eval", "computer", "puppeteer") || tool.startsWith("gui_") || tool.startsWith("browser") || tool.startsWith("remote_screen")

    /** A host tool call that would drive a connector's app or web app instead of using the connector. */
    fun routeHostCall(tool: String, arguments: kotlinx.serialization.json.JsonObject, enabled: Set<String>): Route? = when {
        enabled.isEmpty() -> null
        tool == "android_launch" || tool == "android_switch" ->
            (arguments["package"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { routePackage(it, enabled) }
                ?: route(arguments.toString(), enabled)
        drivesUi(tool) -> route(arguments.toString(), enabled)
        else -> null
    }

    /**
     * Connector first, then a deliberate fallback (spec §9.1 tiers): the first UI-driving call to a connector's app
     * in a session is refused with the reason; after that the model has been told, and a repeated call is its
     * decision that the connector cannot do the job — it proceeds through the ordinary gates and is noted.
     */
    class Guard {
        private val told = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        /** The route to refuse with now, or null to proceed; [fallback] is called when a told connector is bypassed. */
        suspend fun check(route: Route?, fallback: suspend (Route) -> Unit = {}): Route? {
            route ?: return null
            if (told.add(route.connector)) return route
            fallback(route)
            return null
        }
    }
}
