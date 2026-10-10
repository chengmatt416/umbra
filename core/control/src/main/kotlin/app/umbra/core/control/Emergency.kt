package app.umbra.core.control

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
enum class EmergencyLevel { NORMAL, LOCKDOWN }

/**
 * Emergency state (architecture spec §8.2), persisted so that neither an app restart nor a runner reconnecting lifts
 * it (INV-010, AC-006). Input Lockdown fences new managed input and every side-effecting tool dispatch; it does not
 * claim that commands already sent were cancelled. Lifting it resumes nothing.
 */
@Serializable
data class EmergencyState(
    val id: String = "emergency",
    val level: EmergencyLevel = EmergencyLevel.NORMAL,
    /** Grows with every lockdown and lift: the fencing generation dispatchers compare. */
    val generation: Long = 0,
    val reason: String? = null,
    val sinceMs: Long = 0,
    val by: String? = null,
)

class EmergencyControl(private val store: ControlStore, scope: CoroutineScope) {
    private val docs = store.docs("emergency", EmergencyState.serializer(), EmergencyState::id)
    private val journal = store.journal("emergency")

    val state: StateFlow<EmergencyState> = docs.all.map { it["emergency"] ?: EmergencyState() }.stateIn(scope, SharingStarted.Eagerly, docs["emergency"] ?: EmergencyState())

    val recent = journal.recent

    fun current(): EmergencyState = docs["emergency"] ?: EmergencyState()

    /** Non-null while input is locked down: the reason every fenced dispatcher reports. */
    fun lockdownReason(): String? = current().takeIf { it.level == EmergencyLevel.LOCKDOWN }?.let { it.reason ?: "locked down" }

    @Synchronized
    fun lockdown(reason: String, by: String = "user"): EmergencyState {
        val cur = current()
        if (cur.level == EmergencyLevel.LOCKDOWN) return cur
        val next = cur.copy(level = EmergencyLevel.LOCKDOWN, generation = cur.generation + 1, reason = reason, sinceMs = store.clock.nowMs(), by = by)
        docs.put(next)
        journal.append("lockdown", buildJsonObject { put("generation", next.generation); put("reason", reason); put("by", by) })
        return next
    }

    /** Lifts the lockdown. Nothing resumes by itself: each session is resumed by the user (§8.2). */
    @Synchronized
    fun lift(by: String = "user"): EmergencyState {
        val cur = current()
        if (cur.level == EmergencyLevel.NORMAL) return cur
        val next = cur.copy(level = EmergencyLevel.NORMAL, generation = cur.generation + 1, reason = null, sinceMs = store.clock.nowMs(), by = by)
        docs.put(next)
        journal.append("lift", buildJsonObject { put("generation", next.generation); put("by", by) })
        return next
    }
}
