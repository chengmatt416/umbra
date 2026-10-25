package app.umbra.agent.agents.proactive

/** Shared validation runs before persistence and before a template creates any agent state. */
object RoutineDefinitions {
    fun validate(r: AgentRoutine, previousTrigger: RoutineTrigger? = null) {
        require(r.approvalTTLMinutes in 1..10 && r.idempotencyWindowMinutes >= 5 && r.idempotencyWindowMinutes <= 1440)
        require(r.title.isNotBlank() && r.instruction.isNotBlank() && r.instruction.length <= 20_000)
        if (r.trigger is RoutineTrigger.CronSchedule && previousTrigger != r.trigger) {
            val cron = app.umbra.agent.agents.Cron(r.trigger.cron)
            val zone = java.time.ZoneId.of(r.trigger.zoneId)
            var next = cron.next(System.currentTimeMillis(), zone)
            repeat(12) {
                val after = cron.next(next, zone)
                require(after - next >= 300_000) { "Cron routines must be at least five minutes apart" }
                next = after
            }
        }
        require(r.allowedConnectors.size <= 30 && r.allowedConnectors.all { it in setOf("sms", "whatsapp", "telegram", "line", "instagram", "messenger", "snapchat", "x", "imessage", "notifications", "calendar", "email", "contacts", "tasks", "drive_files", "web", "android", "linux", "coding", "privileged", "remote_nodes", "agents", "notify", "tools") }) { "Unknown routine capability" }
        require(!r.enabled || r.authorizedAtMs != null) { "A user must authorize a routine before it runs" }
    }
}
