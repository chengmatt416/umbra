package app.umbra.platform.logs

import app.umbra.core.model.Clock
import java.util.concurrent.ConcurrentHashMap

/** One agent action as seen by the log correlator. */
data class JournalEntry(
    val actionId: String,
    val sessionId: String?,
    val operation: String,
    val displayId: Int?,
    val packageName: String?,
    val startedAtMs: Long,
    /** Null while the action is still running. */
    val endedAtMs: Long?,
)

/**
 * Remembers which agent action was running when, so a crash that appears in the log a moment after a
 * tap can be tied to that tap (spec §18 "log correlation"). Bounded: only the most recent actions are kept.
 */
class ActionJournal(
    private val clock: Clock = Clock.System,
    private val capacity: Int = 512,
    /** A log line within this long after an action ended is still attributed to it (crashes lag the cause). */
    private val graceMs: Long = 3_000,
) {
    private val entries = ConcurrentHashMap<String, JournalEntry>()
    private val order = java.util.concurrent.ConcurrentLinkedDeque<String>()

    fun begin(actionId: String, sessionId: String?, operation: String, displayId: Int?, packageName: String?): JournalEntry {
        val entry = JournalEntry(actionId, sessionId, operation, displayId, packageName, clock.nowMs(), endedAtMs = null)
        entries[actionId] = entry
        order.addLast(actionId)
        while (order.size > capacity) order.pollFirst()?.let { entries.remove(it) }
        return entry
    }

    fun end(actionId: String) {
        entries.computeIfPresent(actionId) { _, e -> e.copy(endedAtMs = clock.nowMs()) }
    }

    fun get(actionId: String): JournalEntry? = entries[actionId]

    /**
     * The action a log line at [timestampMs] belongs to: one that was running then, or else the one
     * that ended most recently within the grace period. [sessionId] restricts the search to one session.
     */
    fun actionAt(timestampMs: Long, sessionId: String? = null, packageName: String? = null): JournalEntry? {
        val candidates = entries.values.filter { e ->
            (sessionId == null || e.sessionId == sessionId) &&
                (packageName == null || e.packageName == null || e.packageName == packageName)
        }
        val running = candidates.filter { it.startedAtMs <= timestampMs && (it.endedAtMs == null || it.endedAtMs >= timestampMs) }
        if (running.isNotEmpty()) return running.maxByOrNull { it.startedAtMs }
        return candidates
            .filter { it.endedAtMs != null && it.endedAtMs <= timestampMs && timestampMs - it.endedAtMs <= graceMs }
            .maxByOrNull { it.endedAtMs!! }
    }

    fun recent(limit: Int = 20): List<JournalEntry> = order.toList().takeLast(limit).mapNotNull { entries[it] }
}
