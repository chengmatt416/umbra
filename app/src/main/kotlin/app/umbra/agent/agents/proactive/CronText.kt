package app.umbra.agent.agents.proactive

/** Common five-field cron schedules in words ("Weekdays at 08:30"); null when a schedule needs its own words. */
object CronText {
    private val days = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val zhDays = listOf("週日", "週一", "週二", "週三", "週四", "週五", "週六")

    fun describe(cron: String, zh: Boolean = false): String? {
        val f = cron.trim().split(Regex("\\s+"))
        if (f.size != 5) return null
        val (minute, hour, dom, month, dow) = f
        if (dom != "*" || month != "*") return null
        val m = minute.toIntOrNull()?.takeIf { it in 0..59 }
        val h = hour.toIntOrNull()?.takeIf { it in 0..23 }
        if (m != null && hour.startsWith("*/") && dow == "*") return null
        if (m == null) {
            val every = minute.removePrefix("*/").toIntOrNull()?.takeIf { minute.startsWith("*/") && hour == "*" && dow == "*" } ?: return null
            return if (zh) "每 $every 分鐘" else "Every $every minutes"
        }
        if (h == null) return if (hour == "*" && dow == "*") (if (zh) "每小時的第 $m 分" else "Hourly at :%02d".format(m)) else null
        val at = "%02d:%02d".format(h, m)
        val day = when (dow) {
            "*" -> if (zh) "每天" else "Every day"
            "1-5" -> if (zh) "平日" else "Weekdays"
            "0,6", "6,0", "6-7", "0,7" -> if (zh) "週末" else "Weekends"
            else -> dow.toIntOrNull()?.let { (if (zh) zhDays else days.map { d -> d + "s" })[it % 7] } ?: return null
        }
        return if (zh) "$day $at" else "$day at $at"
    }
}
