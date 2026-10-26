package app.umbra.agent.agents.proactive

/**
 * One event, one suggestion. Each wake is a fresh discovery run that does not remember what it proposed before, so the
 * same calendar event or message could come back as a second, third... card. A proposal is the same suggestion as an
 * earlier card when they share a specific evidence reference ("calendar:42", "email:abc"; not a bare source name) or
 * when their titles say the same thing.
 */
object OpportunityDedup {
    /** How long a card the user dismissed or adopted still blocks the same proposal. */
    const val SETTLED_WINDOW_MS = 14 * 86_400_000L

    /** A reference that names one item: a source and an id ("calendar:42"), or a long specific string (a URL). */
    fun specific(ref: String): String? {
        val r = ref.trim().lowercase()
        val id = r.substringAfter(':', "")
        return r.takeIf { id.any(Char::isLetterOrDigit) || r.length >= 16 }
    }

    fun sameTitle(a: String, b: String): Boolean {
        val x = EvidenceRanker.tokens(a)
        val y = EvidenceRanker.tokens(b)
        if (x.isEmpty() || y.isEmpty()) return a.trim().equals(b.trim(), ignoreCase = true)
        return (x intersect y).size.toDouble() / (x union y).size >= .75
    }

    fun same(a: OpportunityCard, b: OpportunityCard): Boolean {
        val refs = a.evidenceRefs.mapNotNull(::specific).toSet()
        if (refs.isNotEmpty() && b.evidenceRefs.mapNotNull(::specific).any { it in refs }) return true
        return sameTitle(a.title, b.title)
    }

    /**
     * The earlier card [proposal] repeats: one still open (pending or being adopted), else one the user dismissed or
     * adopted within [SETTLED_WINDOW_MS]. Expired cards and older settled ones do not count.
     */
    fun match(proposal: OpportunityCard, existing: List<OpportunityCard>, now: Long): OpportunityCard? {
        val candidates = existing.filter { it.id != proposal.id && same(proposal, it) }
        return candidates.firstOrNull { it.state == OpportunityCard.State.PENDING && (it.expiresAtMs == null || it.expiresAtMs > now) }
            ?: candidates.firstOrNull { it.state == OpportunityCard.State.ACCEPTING }
            ?: candidates.filter { it.state in setOf(OpportunityCard.State.DISMISSED, OpportunityCard.State.ACCEPTED) && now - it.createdAtMs < SETTLED_WINDOW_MS }
                .maxByOrNull { it.createdAtMs }
    }
}

/**
 * A goal is an outcome that takes several steps over days (a project, a habit, a plan), not one thing on the calendar.
 * A single event, appointment, reminder or errand stays where it is (calendar, a task, a timer) or becomes a suggestion.
 */
object GoalScope {
    const val MIN_MILESTONES = 2
    private const val DAY_MS = 86_400_000L

    /** Why this would be too small to be a goal, or null when it is one. [events]: calendar titles around now. */
    fun refusal(title: String, description: String, milestones: Int, deadlineMs: Long?, now: Long, events: List<String>): String? {
        if (milestones < MIN_MILESTONES)
            return "A goal needs at least $MIN_MILESTONES milestones. A single event, appointment, reminder or errand is not a goal: use tasks_write, a timer, the calendar or opportunity_propose instead."
        if (deadlineMs != null && deadlineMs - now < DAY_MS && milestones < 3)
            return "This finishes within a day: it is a task or a reminder, not a goal. Use tasks_write or a timer instead."
        val words = EvidenceRanker.tokens(title)
        val event = events.firstOrNull { e ->
            val t = EvidenceRanker.tokens(e)
            words.isNotEmpty() && t.isNotEmpty() && (words intersect t).size.toDouble() / minOf(words.size, t.size) >= .6
        }
        if (event != null) return "\"$title\" is the calendar event \"$event\". Events stay on the calendar; prepare for one with a note, a task or a suggestion, not a goal."
        return null
    }

    /** The user asked for a goal in their latest words ("make it a goal", "設個目標"): their call, not [refusal]'s. */
    fun requested(latestUserIntent: String?): Boolean =
        latestUserIntent != null && Regex("\\bgoals?\\b|目標|目标", RegexOption.IGNORE_CASE).containsMatchIn(latestUserIntent)
}
