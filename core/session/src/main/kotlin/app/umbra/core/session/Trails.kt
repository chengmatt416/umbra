package app.umbra.core.session

/** The session before, the one in focus, and the one after it, as the Capsule shows them (architecture spec §3.1). */
data class SessionTrail(val previous: Session?, val current: Session?, val next: Session?)

/** The step before, the step under way, and the step after it, within one session. */
data class StepTrail(val previous: PlanStep?, val current: PlanStep?, val next: PlanStep?)

/** Previous / Current / Next, with sessions and steps kept apart (§3.1: "Session 與 Step 各自分隔"). */
object Trails {
    private val LIVE = setOf(SessionState.RUNNING, SessionState.WAITING_APPROVAL, SessionState.PAUSED)
    private val FINISHED_STEP = setOf("done", "failed", "skipped")

    /**
     * Current is the focused session while it is live, otherwise the live one that changed last. Previous is the one
     * that finished last; next is another live session, or one that was created and has not started.
     */
    fun sessions(sessions: List<Session>, focusedId: String?): SessionTrail {
        val live = sessions.filter { it.state in LIVE }.sortedByDescending { it.updatedAtMs }
        val current = live.firstOrNull { it.id == focusedId } ?: live.firstOrNull()
        val previous = sessions.filter { it.state.isTerminal }.maxByOrNull { it.updatedAtMs }
        val next = live.firstOrNull { it.id != current?.id }
            ?: sessions.filter { it.state == SessionState.CREATED && it.id != current?.id }.minByOrNull { it.createdAtMs }
        return SessionTrail(previous, current, next)
    }

    /** Current is the active step; previous the last finished step before it; next the first pending one after it. */
    fun steps(session: Session?): StepTrail {
        val plan = session?.plan.orEmpty()
        if (plan.isEmpty()) return StepTrail(null, null, null)
        val at = plan.indexOfFirst { it.status == "active" }
        val pivot = if (at >= 0) at else plan.indexOfFirst { it.status == "pending" }.let { if (it >= 0) it else plan.size }
        val previous = plan.subList(0, pivot.coerceAtMost(plan.size)).lastOrNull { it.status in FINISHED_STEP }
        val next = plan.drop(if (at >= 0) at + 1 else pivot).firstOrNull { it.status == "pending" }
        return StepTrail(previous, plan.getOrNull(at), next)
    }
}
