package app.umbra.tools

import app.umbra.core.model.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

data class Lease(val displayId: Int, val sessionId: String, val acquiredAtMs: Long, val lastUsedMs: Long)

class LeaseUnavailableException(val displayId: Int, val holder: String, message: String) : Exception(message)

/**
 * Who may drive which display (spec §9, §21). Interactive control of a display belongs to one session
 * at a time, so two agents never fight over the same screen, but sessions on different displays run
 * in parallel. Leases are sticky (kept between actions of the same session) and expire when idle, so
 * a stopped or crashed session cannot hold a screen forever.
 */
class DisplayLeaseManager(
    private val clock: Clock = Clock.System,
    private val idleTtlMs: Long = 120_000,
    private val pollMs: Long = 100,
) {
    private val leases = ConcurrentHashMap<Int, Lease>()

    /** Holder of [displayId] if its lease is still alive. */
    fun holder(displayId: Int): String? = leases[displayId]?.takeIf { alive(it) }?.sessionId

    fun leases(): List<Lease> = leases.values.filter { alive(it) }

    private fun alive(l: Lease) = clock.nowMs() - l.lastUsedMs <= idleTtlMs

    /**
     * Takes or refreshes the lease. Waits up to [waitMs] for another session's lease to expire or be
     * released, then throws [LeaseUnavailableException] naming the holder.
     */
    suspend fun acquire(sessionId: String, displayId: Int, waitMs: Long = 0): Lease {
        val deadline = clock.nowMs() + waitMs
        while (true) {
            tryAcquire(sessionId, displayId)?.let { return it }
            val holder = holder(displayId)
            if (clock.nowMs() >= deadline) {
                throw LeaseUnavailableException(
                    displayId, holder ?: "?",
                    "Display $displayId is being controlled by session $holder. Use another display (display create) or wait for it to finish.",
                )
            }
            delay(pollMs)
        }
    }

    private fun tryAcquire(sessionId: String, displayId: Int): Lease? {
        val now = clock.nowMs()
        var result: Lease? = null
        leases.compute(displayId) { _, current ->
            when {
                current == null || !alive(current) -> Lease(displayId, sessionId, now, now).also { result = it }
                current.sessionId == sessionId -> current.copy(lastUsedMs = now).also { result = it }
                else -> current
            }
        }
        return result
    }

    /** Refreshes the idle timer without contesting the lease; a no-op when the session does not hold it. */
    fun touch(sessionId: String, displayId: Int) {
        leases.computeIfPresent(displayId) { _, l -> if (l.sessionId == sessionId) l.copy(lastUsedMs = clock.nowMs()) else l }
    }

    fun release(sessionId: String, displayId: Int): Boolean {
        var released = false
        leases.computeIfPresent(displayId) { _, l ->
            if (l.sessionId == sessionId) { released = true; null } else l
        }
        return released
    }

    /** Called when a session stops, pauses or is deleted. */
    fun releaseAll(sessionId: String): Int {
        val mine = leases.values.filter { it.sessionId == sessionId }.map { it.displayId }
        mine.forEach { release(sessionId, it) }
        return mine.size
    }

    /** Drops the lease for a display that no longer exists. */
    fun forget(displayId: Int) {
        leases.remove(displayId)
    }
}
