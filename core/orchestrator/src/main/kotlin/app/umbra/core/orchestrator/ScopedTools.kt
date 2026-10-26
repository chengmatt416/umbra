package app.umbra.core.orchestrator

/** Routine capabilities are immutable per run; new grants require a newly reviewed run. */
object ScopedTools {
    private val messages = setOf("sms", "whatsapp", "telegram", "line", "instagram", "messenger", "snapchat", "x", "imessage")
    fun allows(name: String, scope: Set<String>): Boolean = when {
        name in setOf("context_resolve", "sessions_list", "session_read", "wait_until", "deliverable_publish", "decide", "agent_status") -> true
        name.startsWith("memory_") || name.startsWith("note_") || name.startsWith("goal_") || name.startsWith("opportunity_") || name.startsWith("brief_") || name.startsWith("skill_") || name.startsWith("routine_") || name.startsWith("watcher_") || name.startsWith("queue_") -> true
        name.startsWith("messages_") -> scope.any { it in messages }
        name == "notifications_query" -> "notifications" in scope
        name.startsWith("calendar_") || name == "calendars_list" -> "calendar" in scope
        name.startsWith("email_") -> "email" in scope
        name.startsWith("contacts_") -> "contacts" in scope
        name.startsWith("tasks_") -> "tasks" in scope
        name == "web_fetch" -> "web" in scope
        name in setOf("tool_run", "demonstration_read") -> true // Pure transforms and owned teaching evidence.
        name == "tool_synthesize" -> "tools" in scope || "coding" in scope
        // Guest scripts run real programs; never part of read-only discovery (DiscoveryTools omits them).
        name == "script_run" -> "linux" in scope || "tools" in scope
        name.startsWith("android_") || name == "verify" -> "android" in scope
        name == "request_credential_handoff" -> "android" in scope
        name.startsWith("gui_") -> "linux" in scope || "coding" in scope
        name.startsWith("priv_") -> "privileged" in scope
        name.startsWith("node_") || name.startsWith("remote_node") || name == "remote_screen" -> "remote_nodes" in scope
        name == "coding_start" || name.startsWith("subagent_") -> "coding" in scope
        name.startsWith("session_") || name.startsWith("handoff_") || name.startsWith("group_") -> "agents" in scope
        name == "notify_user" -> "notify" in scope
        name.startsWith("timer_") || name.startsWith("rule_") -> false // Legacy triggers have no reviewed scope.
        else -> false
    }
}
