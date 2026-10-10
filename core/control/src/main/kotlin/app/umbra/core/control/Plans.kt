package app.umbra.core.control

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class PlanTask(val content: String, val status: String = "pending")

@Serializable
data class PlanPhase(val name: String, val tasks: List<PlanTask>)

/**
 * One immutable revision of a session's plan (architecture spec §4.1, §4.3): the ordered phases and their tasks.
 * A new revision is written only when the structure changes (phases or tasks added, removed, renamed, reordered);
 * status changes are progress within the same revision. History is never rewritten.
 */
@Serializable
data class PlanRevision(
    val id: String,
    val sessionId: String,
    val number: Int,
    val phases: List<PlanPhase>,
    /** `agent` (omp's todo list) or `user` (edited in the app). */
    val source: String,
    /** SHA-256 of the structure (phase names and task texts, in order). */
    val graphHash: String,
    val baseRevision: Int?,
    val createdAtMs: Long,
    /** What changed against the base, in words. */
    val changes: List<String> = emptyList(),
    /** Set when this revision dropped tasks the user had added in an earlier revision. */
    val conflict: String? = null,
)

/** Progress of the current revision: task statuses as last reported. */
@Serializable
data class PlanProgress(val sessionId: String, val revision: Int, val phases: List<PlanPhase>, val updatedAtMs: Long)

class PlanRegistry(private val store: ControlStore) {
    private val revisions = store.docs("plan-revisions", PlanRevision.serializer(), PlanRevision::id)
    private val progress = store.docs("plan-progress", PlanProgress.serializer(), PlanProgress::sessionId)
    private val journal = store.journal("plans")

    val all: StateFlow<Map<String, PlanRevision>> = revisions.all
    val progressAll: StateFlow<Map<String, PlanProgress>> = progress.all

    fun history(sessionId: String): List<PlanRevision> = revisions.list().filter { it.sessionId == sessionId }.sortedBy { it.number }

    fun current(sessionId: String): PlanRevision? = history(sessionId).lastOrNull()

    fun progressOf(sessionId: String): PlanProgress? = progress[sessionId]

    /**
     * Records what the agent (or the user) now has as the plan. Returns the new revision when the structure changed,
     * or null when only progress moved.
     */
    @Synchronized
    fun observe(sessionId: String, phases: List<PlanPhase>, source: String): PlanRevision? {
        val now = store.clock.nowMs()
        val hash = hashOf(phases)
        val last = current(sessionId)
        if (last != null && last.graphHash == hash) {
            progress.put(PlanProgress(sessionId, last.number, phases, now))
            return null
        }
        if (last == null && phases.isEmpty()) return null
        val number = (last?.number ?: 0) + 1
        val changes = describe(last?.phases.orEmpty(), phases)
        // A plan the agent rewrote after the user edited it: tasks the user added and the agent dropped are reported,
        // not silently lost (§4.3: scope changes go to arbitration).
        val userAdded = history(sessionId).filter { it.source == "user" }.flatMap { rev ->
            val before = revisions.list().firstOrNull { it.sessionId == sessionId && it.number == rev.baseRevision }?.phases.orEmpty().flatMap { p -> p.tasks.map { it.content } }.toSet()
            rev.phases.flatMap { p -> p.tasks.map { it.content } }.filter { it !in before }
        }.toSet()
        val dropped = if (source == "agent") userAdded.filter { t -> phases.none { p -> p.tasks.any { it.content == t } } } else emptyList()
        val conflict = dropped.takeIf { it.isNotEmpty() }?.let { "The agent's plan no longer has task(s) you added: ${it.joinToString("; ") { t -> "“$t”" }}" }
        val rev = PlanRevision("$sessionId-r$number", sessionId, number, phases, source, hash, last?.number, now, changes, conflict)
        revisions.put(rev)
        progress.put(PlanProgress(sessionId, number, phases, now))
        journal.append("plan.revision", buildJsonObject {
            put("session", sessionId); put("revision", number); put("source", source); put("hash", hash)
            conflict?.let { put("conflict", it) }
        })
        return rev
    }

    fun forget(sessionId: String) {
        history(sessionId).forEach { revisions.delete(it.id) }
        progress.delete(sessionId)
    }

    companion object {
        fun hashOf(phases: List<PlanPhase>): String =
            ControlStore.sha256(phases.joinToString("\n") { p -> "# ${p.name}\n" + p.tasks.joinToString("\n") { "- ${it.content}" } })

        fun describe(before: List<PlanPhase>, after: List<PlanPhase>): List<String> {
            val out = ArrayList<String>()
            val beforeNames = before.map { it.name }
            val afterNames = after.map { it.name }
            (afterNames - beforeNames.toSet()).forEach { out += "phase added: $it" }
            (beforeNames - afterNames.toSet()).forEach { out += "phase removed: $it" }
            val beforeTasks = before.flatMap { p -> p.tasks.map { p.name to it.content } }
            val afterTasks = after.flatMap { p -> p.tasks.map { p.name to it.content } }
            (afterTasks - beforeTasks.toSet()).forEach { out += "task added in ${it.first}: ${it.second}" }
            (beforeTasks - afterTasks.toSet()).forEach { out += "task removed from ${it.first}: ${it.second}" }
            if (out.isEmpty() && beforeNames != afterNames) out += "phases reordered"
            if (out.isEmpty() && beforeTasks != afterTasks) out += "tasks reordered"
            return out
        }
    }
}

/** A wait-for edge: [waiter] wants [resource], which [holder] holds. */
data class WaitEdge(val waiter: String, val resource: String, val holder: String)

/** Deadlock detection over the Resource Wait Graph (spec §4.1, §7): cycles among principals waiting on each other. */
object WaitGraph {
    fun cycles(edges: List<WaitEdge>): List<List<String>> {
        val next = edges.groupBy({ it.waiter }, { it.holder })
        val found = LinkedHashSet<List<String>>()
        fun walk(start: String, at: String, path: List<String>) {
            for (n in next[at].orEmpty()) {
                if (n == start) found += (path).let { p -> val i = p.indexOf(p.minOrNull()); p.drop(i) + p.take(i) }
                else if (n !in path && path.size < 16) walk(start, n, path + n)
            }
        }
        next.keys.forEach { walk(it, it, listOf(it)) }
        return found.toList()
    }
}
