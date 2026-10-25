package app.umbra.core.control

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Who holds input: the person using the phone, or an agent (a session). */
@Serializable
data class Principal(val kind: String, val id: String) {
    val isUser: Boolean get() = kind == USER

    override fun toString() = if (isUser) "you" else "agent $id"

    companion object {
        const val USER = "user"
        const val AGENT = "agent"
        val User = Principal(USER, "user")
        fun agent(sessionId: String) = Principal(AGENT, sessionId)
    }
}

@Serializable
enum class LeaseState { ACTIVE, HANDOFF_PENDING, RELEASED, REVOKED, EXPIRED, UNCERTAIN }

/**
 * Input authority over one resource (architecture spec §7, UILA): a terminal pane (`terminal:<id>`), the phone's
 * screen (`display:0`), a background display, a Linux desktop. Each change of owner gets a new [generation]; the
 * dispatchers that actually deliver input compare it, so a stale owner cannot type (INV-006: fencing at the writer).
 */
@Serializable
data class InputLease(
    val resourceId: String,
    val owner: Principal? = null,
    val generation: Long = 0,
    val state: LeaseState = LeaseState.RELEASED,
    val sinceMs: Long = 0,
    val expiresAtMs: Long? = null,
    /** Set while the user asked the agent to hand over at its next safe point. */
    val handoffTo: Principal? = null,
    val reason: String? = null,
    /** The runner instance the lease was granted against (tmux server epoch, display id, …). */
    val runnerEpoch: String? = null,
    val needsObservation: Boolean = false,
    val lastAgentId: String? = null,
) {
    val id: String get() = resourceId.replace(':', '_').replace('/', '_')

    fun heldBy(p: Principal): Boolean = owner == p && (state == LeaseState.ACTIVE || state == LeaseState.HANDOFF_PENDING)
}

/** The answer of a dispatcher's check. */
sealed interface LeaseCheck {
    data class Allowed(val generation: Long) : LeaseCheck
    data class Fenced(val reason: String) : LeaseCheck
}

class InputLeases(private val store: ControlStore, private val lockdown: () -> String? = { null }) {
    private val revisions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val allRevisions = java.util.concurrent.atomic.AtomicLong()
    private val docs = store.docs("leases", InputLease.serializer(), InputLease::id)
    private val timeline = store.journal("control-timeline")

    val all = docs.all

    /** The Unified Control Timeline: acquire, handoff, takeback, revoked, blocked, reconcile, restore (§7). */
    val recent = timeline.recent

    fun current(resourceId: String): InputLease? = docs.list().firstOrNull { it.resourceId == resourceId }

    /** Process-local fencing for in-flight actions, including deletion and recreation of a lease. */
    fun revision(resourceId: String? = null): Long = if (resourceId == null) allRevisions.get() else revisions[resourceId] ?: 0L

    private fun put(lease: InputLease, event: String, by: Principal?, detail: String? = null): InputLease {
        docs.put(lease)
        revisions.merge(lease.resourceId, 1L, Long::plus)
        allRevisions.incrementAndGet()
        timeline.append(event, buildJsonObject {
            put("resource", lease.resourceId)
            put("generation", lease.generation)
            lease.owner?.let { put("owner", it.toString()) }
            by?.let { put("by", it.toString()) }
            detail?.let { put("detail", it) }
        })
        return lease
    }

    /**
     * Takes input for [principal]. Free, expired or released resources are granted; a resource held by someone else
     * is refused (an agent never takes input from the user; the user takes it with [takeBack]).
     */
    @Synchronized
    fun acquire(resourceId: String, principal: Principal, reason: String? = null, runnerEpoch: String? = null, ttlMs: Long? = null): Result<InputLease> {
        val now = store.clock.nowMs()
        val cur = current(resourceId)?.let { expireIfDue(it, now) }
        return when {
            cur != null && cur.heldBy(principal) -> Result.success(cur)
            cur != null && cur.owner != null && (cur.state == LeaseState.ACTIVE || cur.state == LeaseState.HANDOFF_PENDING) -> {
                timeline.append("blocked", buildJsonObject { put("resource", resourceId); put("by", principal.toString()); put("holder", cur.owner.toString()) })
                Result.failure(IllegalStateException("${cur.owner} holds input of $resourceId (generation ${cur.generation})"))
            }
            else -> Result.success(
                put(
                    InputLease(resourceId, principal, (cur?.generation ?: 0) + 1, LeaseState.ACTIVE, now, ttlMs?.let { now + it }, null, reason, runnerEpoch,
                        needsObservation = cur?.needsObservation == true, lastAgentId = if (principal.isUser) cur?.lastAgentId else principal.id),
                    "acquire", principal, reason,
                ),
            )
        }
    }

    /** Immediate override (§6.1 "Immediate Override"): the user owns input now; the old owner's generation is fenced. */
    @Synchronized
    fun takeBack(resourceId: String, reason: String = "taken back by the user", runnerEpoch: String? = null, lastAgentId: String? = null): InputLease {
        val cur = current(resourceId)
        val now = store.clock.nowMs()
        return put(InputLease(resourceId, Principal.User, (cur?.generation ?: 0) + 1, LeaseState.ACTIVE, now, null, null, reason, runnerEpoch ?: cur?.runnerEpoch,
            needsObservation = true, lastAgentId = lastAgentId ?: cur?.owner?.takeUnless { it.isUser }?.id ?: cur?.lastAgentId), "takeback", Principal.User, cur?.owner?.let { "fenced $it" })
    }

    /** Coordinated handoff: the agent is asked to stop at its next safe point; the user can still [takeBack] at once. */
    @Synchronized
    fun requestHandoff(resourceId: String, to: Principal = Principal.User): InputLease? {
        val cur = current(resourceId) ?: return null
        if (cur.owner == null || cur.owner == to || cur.state != LeaseState.ACTIVE) return cur
        return put(cur.copy(state = LeaseState.HANDOFF_PENDING, handoffTo = to), "handoff", to, "requested")
    }

    /** The holder lets go (a safe point reached, or the user returns control). */
    @Synchronized
    fun release(resourceId: String, principal: Principal, reason: String? = null): InputLease? {
        val cur = current(resourceId) ?: return null
        if (cur.owner != principal) return cur
        return put(cur.copy(owner = null, state = LeaseState.RELEASED, handoffTo = null, reason = reason, sinceMs = store.clock.nowMs()), "release", principal, reason)
    }

    /** The user hands input to an agent explicitly ("Return to agent"). */
    @Synchronized
    fun grant(resourceId: String, to: Principal, reason: String = "returned by the user"): InputLease {
        val cur = current(resourceId)
        return put(InputLease(resourceId, to, (cur?.generation ?: 0) + 1, LeaseState.ACTIVE, store.clock.nowMs(), null, null, reason, cur?.runnerEpoch,
            needsObservation = cur?.needsObservation == true, lastAgentId = if (to.isUser) cur?.lastAgentId else to.id), "restore", Principal.User, reason)
    }

    /** A read started before takeover or while the user still holds input cannot authorize new actions. */
    @Synchronized fun observed(resourceId: String, by: Principal, expectedRevision: Long): Boolean {
        val cur = current(resourceId) ?: return false
        if (by.isUser || !cur.needsObservation || revision(resourceId) != expectedRevision || cur.owner?.isUser == true || cur.owner?.let { it != by } == true) return false
        if (cur.lastAgentId != null && cur.lastAgentId != by.id) return false
        put(cur.copy(needsObservation = false), "observed", by, "fresh observation after user takeover")
        return true
    }

    /** Deletion fences the old actor without taking a user-held screen away from the person. */
    @Synchronized fun forgetSession(sessionId: String) {
        all.value.values.filter { it.owner == Principal.agent(sessionId) || it.lastAgentId == sessionId }.forEach { lease ->
            val owned = lease.owner == Principal.agent(sessionId)
            put(lease.copy(owner = if (owned) null else lease.owner, state = if (owned) LeaseState.RELEASED else lease.state,
                generation = lease.generation + 1, lastAgentId = null, needsObservation = true, handoffTo = null, expiresAtMs = if (owned) null else lease.expiresAtMs),
                "forget", null, "the owning task was deleted; a new actor must observe the screen")
        }
    }

    /** The runner behind the resource went away or restarted: the lease can no longer be vouched for. */
    @Synchronized
    fun markUncertain(resourceId: String, why: String): InputLease? {
        val cur = current(resourceId) ?: return null
        if (cur.state == LeaseState.UNCERTAIN) return cur
        return put(cur.copy(state = LeaseState.UNCERTAIN, reason = why), "reconcile", null, why)
    }

    /** Records that [waiter] could not get [resourceId] because [holder] has it (an edge of the wait graph). */
    fun recordBlocked(resourceId: String, waiter: Principal, holder: Principal) {
        timeline.append("blocked", buildJsonObject { put("resource", resourceId); put("by", waiter.toString()); put("holder", holder.toString()) })
    }

    /** Removes the record of a resource that no longer exists (a closed terminal). */
    @Synchronized
    fun forget(resourceId: String) {
        current(resourceId)?.let {
            docs.delete(it.id)
            revisions.merge(resourceId, 1L, Long::plus)
            allRevisions.incrementAndGet()
            timeline.append("revoked", buildJsonObject { put("resource", resourceId); put("detail", "resource closed") })
        }
    }

    /**
     * What a dispatcher asks right before it delivers input from [principal]: allowed only to the active owner, with
     * a matching generation when one is given, and never during an emergency input lockdown (§8.2).
     */
    fun check(resourceId: String, principal: Principal, generation: Long? = null): LeaseCheck {
        lockdown()?.let { return LeaseCheck.Fenced("Emergency input lockdown: $it") }
        val cur = current(resourceId)?.let { expireIfDue(it, store.clock.nowMs()) } ?: return LeaseCheck.Fenced("nobody holds input of $resourceId")
        return when {
            cur.owner != principal -> LeaseCheck.Fenced(if (cur.owner == null) "input of $resourceId is not held" else "${cur.owner} holds input of $resourceId")
            cur.state == LeaseState.UNCERTAIN -> LeaseCheck.Fenced("the lease on $resourceId is uncertain (${cur.reason}); reacquire it")
            cur.state != LeaseState.ACTIVE && cur.state != LeaseState.HANDOFF_PENDING -> LeaseCheck.Fenced("the lease on $resourceId is ${cur.state.name.lowercase()}")
            generation != null && generation != cur.generation -> LeaseCheck.Fenced("stale lease generation $generation (now ${cur.generation})")
            else -> LeaseCheck.Allowed(cur.generation)
        }
    }

    private fun expireIfDue(lease: InputLease, now: Long): InputLease {
        val exp = lease.expiresAtMs ?: return lease
        if (now < exp || lease.state != LeaseState.ACTIVE) return lease
        return put(lease.copy(state = LeaseState.EXPIRED), "expired", null)
    }
}
