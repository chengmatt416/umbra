package app.umbra.agent.agents.proactive

/** The same complete readout goes to notifications and the authorized Telegram channel. */
internal object BriefText {
    fun format(brief: DailyBrief): String = buildString {
        appendLine("${brief.date} · ${brief.kind}")
        appendLine(brief.headline.take(1000))
        fun section(title: String, items: List<String>) {
            if (items.isEmpty()) return
            appendLine(); appendLine(title)
            appendLine(items.take(20).joinToString("\n") { "• ${it.take(500)}" }.take(2500))
        }
        // Put decisions and conflicts first so bounded external surfaces retain the actionable material.
        section("Needs your attention", brief.attentionNeeded.map { "${it.title}: ${it.summary}" })
        section("Conflicts", brief.conflictsDetected.map { it.description })
        section("Schedule", brief.scheduleHighlights.map { "${it.title}: ${it.detail}" })
        section("Completed", brief.overnightCompleted.map { "${it.title}: ${it.detail}" })
        if (brief.lowPriorityDigest.isNotBlank()) {
            appendLine(); appendLine("Updates"); append(brief.lowPriorityDigest.take(4000))
        }
    }.take(16_000).trim()
}
