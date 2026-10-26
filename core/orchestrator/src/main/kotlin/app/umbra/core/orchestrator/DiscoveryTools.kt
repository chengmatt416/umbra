package app.umbra.core.orchestrator

/** Immutable capability boundary, independent of tool descriptions and model instructions. */
object DiscoveryTools {
    private val names = setOf("memory_add", "memory_edit", "memory_forget", "memory_list", "note_write", "note_read", "note_list",
        "notifications_query", "messages_list", "messages_read", "calendar_read", "calendars_list", "email_read", "contacts_read", "tasks_read",
        "sessions_list", "session_read", "goal_list", "watcher_list", "skill_list", "routine_list", "context_resolve", "web_fetch",
        "opportunity_propose", "opportunity_list", "opportunity_update", "opportunity_withdraw", "brief_stage", "tool_run", "group_read", "demonstration_read",
        // Only its session_done form: WaitTools refuses the forms that run Linux commands in a discovery run.
        "wait_until")
    fun allows(name: String): Boolean = name in names
}
