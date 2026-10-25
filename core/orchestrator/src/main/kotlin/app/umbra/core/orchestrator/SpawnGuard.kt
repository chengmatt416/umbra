package app.umbra.core.orchestrator

import app.umbra.core.session.Session

/**
 * Bounds every chain of sessions that start other sessions (subagents, coding sessions an agent starts, team
 * handoffs, background research, routines): however a model behaves, a tree of work cannot grow without end. The
 * phone runs few agents at once, and a runaway tree would spend the user's model budget in the background.
 */
object SpawnGuard {
    /** Levels below the root: root → child → grandchild → great-grandchild. */
    const val MAX_DEPTH = 3
    /** Unfinished sessions under one root at once. */
    const val MAX_ACTIVE_PER_ROOT = 6
    /** Unfinished direct children of one session at once. */
    const val MAX_ACTIVE_PER_PARENT = 4
    /** Sessions started under one root in any rolling hour. */
    const val MAX_PER_ROOT_PER_HOUR = 24
    private const val HOUR_MS = 60 * 60_000L

    /** Why a new session under [parentId] is refused, or null to allow it. */
    fun refusal(sessions: List<Session>, parentId: String, now: Long): String? {
        val byId = sessions.associateBy { it.id }
        val parent = byId[parentId] ?: return "The session that would start this one no longer exists."
        // The parent's chain to its root; a malformed loop counts as too deep.
        val chain = ArrayList<Session>()
        var current: Session? = parent
        val seen = HashSet<String>()
        while (current != null) {
            if (!seen.add(current.id) || chain.size > 64) return "This chain of sessions is malformed; start the work from a fresh task."
            chain += current
            current = current.spawnedBy?.let(byId::get)
        }
        val root = chain.last()
        if (chain.size > MAX_DEPTH) return "Too many levels of sessions starting sessions (at most $MAX_DEPTH below a task). Do this part yourself or report back to the session that started you."
        val active = sessions.filter { !it.state.isTerminal }
        if (active.count { it.spawnedBy == parent.id } >= MAX_ACTIVE_PER_PARENT)
            return "This session already has $MAX_ACTIVE_PER_PARENT unfinished sessions of its own. Wait for one to finish (subagent_wait) before starting another."
        val tree = descendants(sessions, root.id)
        if (tree.count { !it.state.isTerminal } >= MAX_ACTIVE_PER_ROOT)
            return "$MAX_ACTIVE_PER_ROOT sessions are already working under this task. Wait for some to finish before starting more."
        if (tree.count { it.createdAtMs >= now - HOUR_MS } >= MAX_PER_ROOT_PER_HOUR)
            return "$MAX_PER_ROOT_PER_HOUR sessions were started under this task in the last hour. Pause and report to the user instead of starting more."
        return null
    }

    /** Every session below [rootId], however deep (bounded against loops). */
    fun descendants(sessions: List<Session>, rootId: String): List<Session> {
        val children = sessions.groupBy { it.spawnedBy }
        val out = ArrayList<Session>()
        val queue = ArrayDeque(listOf(rootId))
        val seen = hashSetOf(rootId)
        while (queue.isNotEmpty() && out.size < 10_000) {
            for (child in children[queue.removeFirst()].orEmpty()) if (seen.add(child.id)) { out += child; queue += child.id }
        }
        return out
    }
}
