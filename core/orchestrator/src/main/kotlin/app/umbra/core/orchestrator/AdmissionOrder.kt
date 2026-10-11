package app.umbra.core.orchestrator

import app.umbra.core.session.Session

/**
 * The order queued sessions are admitted in (architecture spec §5, AC-022). Fair between owners: the next one comes
 * from the project (or the independent sessions, key null) with the fewest sessions running now, and within it the
 * one waiting longest. An owner whose budget is full is passed over, not blocking the others.
 */
object AdmissionOrder {
    /** The session to admit next, or null when none may start. */
    fun next(queued: List<Session>, running: Map<String?, Int>, admits: (projectId: String?, running: Map<String?, Int>) -> Boolean): Session? =
        queued.filter { it.queuedAtMs != null }
            .groupBy { it.projectId }
            .filterKeys { admits(it, running) }
            .minWithOrNull(compareBy({ running[it.key] ?: 0 }, { it.value.minOf { s -> s.queuedAtMs!! } }))
            ?.value?.minByOrNull { it.queuedAtMs!! }

    /**
     * The order the queue would be admitted in if nothing running finished: a forecast for the screen, not a
     * promise (a finished session elsewhere changes it).
     */
    fun forecast(queued: List<Session>, running: Map<String?, Int>, admits: (String?, Map<String?, Int>) -> Boolean = { _, _ -> true }): List<Session> {
        val left = queued.filter { it.queuedAtMs != null }.toMutableList()
        val counts = running.toMutableMap()
        val out = ArrayList<Session>(left.size)
        while (left.isNotEmpty()) {
            // A full budget still gets its turn in the forecast once the others are placed: it is waiting, not refused.
            val pick = next(left, counts, admits) ?: next(left, counts) { _, _ -> true } ?: break
            out += pick
            left.remove(pick)
            counts[pick.projectId] = (counts[pick.projectId] ?: 0) + 1
        }
        return out
    }
}
