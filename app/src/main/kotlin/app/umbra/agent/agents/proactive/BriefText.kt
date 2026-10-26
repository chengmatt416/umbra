package app.umbra.agent.agents.proactive

/** The same complete readout goes to notifications and the authorized Telegram channel. */
internal object BriefText {
    fun format(brief: DailyBrief): String = buildString {
        appendLine("${brief.date} · ${brief.kind}")
        appendLine(brief.headline.take(1000))
        fun section(title: String, items: List<String>, limit: Int = 20, maxLength: Int = 2500) {
            if (items.isEmpty()) return
            appendLine(); appendLine(title)
            appendLine(items.take(limit).joinToString("\n") { "• ${it.take(500)}" }.take(maxLength))
        }
        // Put decisions and conflicts first so bounded external surfaces retain the actionable material.
        section("Needs your attention", brief.attentionNeeded.map { "${it.title}: ${it.summary}" })
        section("Conflicts", brief.conflictsDetected.map { it.description })
        section("Schedule", brief.scheduleHighlights.map { "${it.title}: ${it.detail}" })
        section("Tasks", brief.tasks.map { "[${it.state}] ${it.title.take(160)}${if (it.detail.isBlank()) "" else ": ${it.detail}"}".take(180) }, limit = 30, maxLength = 6000)
        if (brief.lowPriorityDigest.isNotBlank()) {
            appendLine(); appendLine("Updates"); append(brief.lowPriorityDigest.take(4000))
        }
    }.take(16_000).trim()
}
