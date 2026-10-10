package app.umbra.core.control

import app.umbra.core.recovery.RecoveryGate
import app.umbra.core.recovery.RecoveryPermit
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Whether a shared service takes new consumers (architecture spec §9). Draining closes admission first. */
@Serializable
enum class Admission { OPEN, DRAINING }

@Serializable
enum class Breaker { CLOSED, OPEN, HALF_OPEN }

/**
 * One consumer's registered reference to a shared service: a lease that says "I use this". Its absence proves
 * nothing about consumers Umbra does not know of (a user's own tmux client, a build started by hand).
 */
@Serializable
data class ServiceReference(val id: String, val serviceId: String, val consumer: String, val sinceMs: Long)

/**
 * A logical service's persisted state: admission, circuit breaker and restart budget, the fenced recovery owner, and
 * the last process instance seen (logical identity and process identity are kept apart). Persisted so that neither a
 * restart of the app nor reopening a screen clears a breaker; only an explicit, journaled reset does.
 */
@Serializable
data class ServiceRecord(
    val serviceId: String,
    val admission: Admission = Admission.OPEN,
    val admissionReason: String? = null,
    val breaker: Breaker = Breaker.CLOSED,
    val openUntilMs: Long? = null,
    /** How many times the breaker opened since it last closed: the next cooldown doubles with each. */
    val openings: Int = 0,
    /** Start times of recovery attempts still inside the budget window. */
    val attempts: List<Long> = emptyList(),
    /** Healthy probes in a row while half-open. */
    val stableProbes: Int = 0,
    /** Grows with every recovery that starts: an outcome for an older epoch is not this owner's to report. */
    val recoveryEpoch: Long = 0,
    val recoveryOwner: String? = null,
    val recoveryStartedMs: Long? = null,
    /** When the service last came back: new consumers are let in in stages from then on. */
    val reattachFromMs: Long? = null,
    /** Consumers admitted since [reattachFromMs]. */
    val reattached: Int = 0,
    /** The process instance last seen (pid, server epoch…), as the probe reported it. */
    val instance: String? = null,
    val lastOutcome: String? = null,
)

/** The probe levels (architecture spec §9): each is judged on its own, and no probe may have an irreversible effect. */
enum class HealthLevel(val label: String) {
    L1("Process alive"),
    L2("Transport"),
    L3("Readiness"),
    L4("Safe functional check"),
}

enum class ProbeResult { PASS, FAIL, UNKNOWN }

data class LevelProbe(val result: ProbeResult, val detail: String)

/** One probe of one service, all four levels, at [atMs]. */
data class ServiceHealth(val levels: Map<HealthLevel, LevelProbe>, val atMs: Long, val instance: String? = null) {
    fun passes(level: HealthLevel) = levels[level]?.result == ProbeResult.PASS

    /** Usable: every level up to readiness passes. A process that is alive is not a service that answers. */
    val ready: Boolean get() = passes(HealthLevel.L1) && passes(HealthLevel.L2) && passes(HealthLevel.L3)
}

data class ServicePolicy(
    /** Recovery attempts allowed per [windowMs]; one more opens the breaker. */
    val restartBudget: Int = 3,
    val windowMs: Long = 10 * 60_000,
    /** Cooldown of the first opening; it doubles with each further one, up to [maxCooldownMs], with jitter. */
    val cooldownMs: Long = 60_000,
    val maxCooldownMs: Long = 30 * 60_000,
    val jitterRatio: Double = 0.2,
    /** Healthy probes in a row that close a half-open breaker. */
    val stability: Int = 3,
    /** A recovery owner that has not reported back within this long is presumed gone; another may take over. */
    val ownerLeaseMs: Long = 2 * 60_000,
    /** After a recovery, new consumers are let in in stages: this many in the first stage, doubling every [stageMs]. */
    val firstStage: Int = 1,
    val stageMs: Long = 10_000,
)

/** What draining found: what still holds the service. Zero references is not proof that nothing uses it. */
data class DrainReport(val serviceId: String, val references: List<ServiceReference>)

/**
 * The Shared Runtime Service Manager's state (architecture spec §9): reference leases, admission and draining,
 * circuit breakers with a persisted restart budget, one fenced recovery owner per logical service, recovery only on
 * demand, staged reattachment after a recovery, and the latest L1–L4 health. As the [RecoveryGate] of the health
 * supervisor, it is what decides whether a shared service may be restarted now.
 */
class SharedServices(
    private val store: ControlStore,
    val policy: ServicePolicy = ServicePolicy(),
    private val random: Random = Random.Default,
) : RecoveryGate {
    private val records = store.docs("services", ServiceRecord.serializer(), ServiceRecord::serviceId)
    private val refs = store.docs("service-refs", ServiceReference.serializer(), ServiceReference::id)
    private val journal = store.journal("services")
    private val known = HashSet<String>()
    private val healthState = MutableStateFlow<Map<String, ServiceHealth>>(emptyMap())

    val all: StateFlow<Map<String, ServiceRecord>> = records.all
    val references: StateFlow<Map<String, ServiceReference>> = refs.all
    val health: StateFlow<Map<String, ServiceHealth>> = healthState.asStateFlow()
    val recent = journal.recent

    /** Declares a shared service; only declared services are governed (others pass the gate untouched). */
    @Synchronized
    fun declare(serviceId: String) {
        require(ControlStore.SAFE.matches(serviceId)) { "bad service id: $serviceId" }
        known += serviceId
        if (records[serviceId] == null) records.put(ServiceRecord(serviceId))
    }

    fun record(serviceId: String): ServiceRecord? = records[serviceId]

    fun referencesOf(serviceId: String): List<ServiceReference> = refs.list().filter { it.serviceId == serviceId }.sortedBy { it.sinceMs }

    // ---- references ----

    fun register(serviceId: String, consumer: String): ServiceReference {
        val id = refId(serviceId, consumer)
        return refs[id] ?: refs.put(ServiceReference(id, serviceId, consumer, store.clock.nowMs())).also {
            journal.append("service.ref.registered", buildJsonObject { put("service", serviceId); put("consumer", consumer) })
        }
    }

    fun release(serviceId: String, consumer: String) {
        if (refs.delete(refId(serviceId, consumer))) {
            journal.append("service.ref.released", buildJsonObject { put("service", serviceId); put("consumer", consumer) })
        }
    }

    /** Makes the registered references of [serviceId] exactly [consumers]: the ones Umbra knows of right now. */
    @Synchronized
    fun sync(serviceId: String, consumers: Set<String>) {
        val current = referencesOf(serviceId)
        current.filter { it.consumer !in consumers }.forEach { release(serviceId, it.consumer) }
        val have = current.map { it.consumer }.toSet()
        (consumers - have).forEach { register(serviceId, it) }
    }

    // ---- admission ----

    /**
     * May a new consumer start using [serviceId]? Null = yes (and, when [count], it is counted toward the staged
     * reattachment); otherwise why not. Undeclared services always admit.
     */
    @Synchronized
    fun admitConsumer(serviceId: String, count: Boolean = true): String? {
        val r = records[serviceId] ?: return null
        if (r.admission == Admission.DRAINING) return "${serviceId} is draining" + (r.admissionReason?.let { ": $it" } ?: "") + "; it takes no new work"
        if (r.breaker == Breaker.OPEN) return "${serviceId} is failing and its recovery is paused (circuit open); it takes no new work for now"
        val from = r.reattachFromMs ?: return null
        val now = store.clock.nowMs()
        val stage = ((now - from) / policy.stageMs).toInt()
        if (stage >= RAMP_STAGES) {
            records.put(r.copy(reattachFromMs = null, reattached = 0))
            return null
        }
        val allowed = policy.firstStage.toLong() shl stage
        if (r.reattached >= allowed) {
            val wait = (policy.stageMs - (now - from) % policy.stageMs + 999) / 1000
            return "${serviceId} came back moments ago and lets work in in stages; try again in ${wait}s"
        }
        if (count) records.put(r.copy(reattached = r.reattached + 1))
        return null
    }

    /** Whether new work is still let into [record]'s service in stages after its last recovery. */
    fun reattaching(record: ServiceRecord): Boolean =
        record.reattachFromMs?.let { store.clock.nowMs() - it < RAMP_STAGES * policy.stageMs } ?: false

    /** Closes admission first; what still holds the service is reported, nothing is stopped. */
    @Synchronized
    fun drain(serviceId: String, reason: String, by: String = "user"): DrainReport {
        val r = records[serviceId] ?: throw IllegalArgumentException("unknown service $serviceId")
        records.put(r.copy(admission = Admission.DRAINING, admissionReason = reason))
        val held = referencesOf(serviceId)
        journal.append("service.draining", buildJsonObject { put("service", serviceId); put("by", by); put("reason", reason); put("references", held.size) })
        return DrainReport(serviceId, held)
    }

    @Synchronized
    fun resume(serviceId: String, by: String = "user") {
        records.update(serviceId) { it.copy(admission = Admission.OPEN, admissionReason = null) }
        journal.append("service.admission_open", buildJsonObject { put("service", serviceId); put("by", by) })
    }

    // ---- health ----

    /** The latest probe of [serviceId]; a new process instance is journaled (logical service ≠ process instance). */
    fun recordHealth(serviceId: String, h: ServiceHealth) {
        healthState.update { it + (serviceId to h) }
        val r = records[serviceId] ?: return
        if (h.instance != null && h.instance != r.instance) {
            records.put(r.copy(instance = h.instance))
            journal.append("service.instance", buildJsonObject { put("service", serviceId); put("instance", h.instance); r.instance?.let { put("previous", it) } })
        }
    }

    // ---- recovery: the health supervisor's gate ----

    @Synchronized
    override fun admit(subsystem: String, owner: String): RecoveryPermit {
        if (subsystem !in known) return RecoveryPermit.Granted(subsystem, 0)
        val now = store.clock.nowMs()
        var r = records[subsystem] ?: ServiceRecord(subsystem)
        if (r.admission == Admission.DRAINING) return RecoveryPermit.Refused("draining: not recovered while it is being taken out of use")
        // Demand-aware: nothing Umbra knows of needs it, so it is left as it is.
        if (referencesOf(subsystem).isEmpty()) return RecoveryPermit.Refused("no consumer needs it now")
        if (r.breaker == Breaker.OPEN) {
            val until = r.openUntilMs ?: now
            if (now < until) return RecoveryPermit.Refused("circuit open until ${until}: repeated failures, recovery paused")
            r = r.copy(breaker = Breaker.HALF_OPEN, stableProbes = 0)
            journal.append("service.breaker", buildJsonObject { put("service", subsystem); put("state", "half_open") })
        }
        val owned = r.recoveryOwner != null && r.recoveryStartedMs != null && now - r.recoveryStartedMs!! < policy.ownerLeaseMs
        if (owned) {
            records.put(r)
            return RecoveryPermit.Refused("${r.recoveryOwner} is recovering it (epoch ${r.recoveryEpoch})")
        }
        val recent = r.attempts.filter { now - it < policy.windowMs }
        if (r.breaker == Breaker.CLOSED && recent.size >= policy.restartBudget) {
            records.put(open(r.copy(attempts = recent), now, "restart budget used: ${recent.size} attempts in ${policy.windowMs / 60_000} min"))
            return RecoveryPermit.Refused("restart budget used (${recent.size} in ${policy.windowMs / 60_000} min); circuit opened")
        }
        val next = r.copy(attempts = recent + now, recoveryEpoch = r.recoveryEpoch + 1, recoveryOwner = owner, recoveryStartedMs = now)
        records.put(next)
        journal.append("service.recovery_started", buildJsonObject { put("service", subsystem); put("owner", owner); put("epoch", next.recoveryEpoch); put("breaker", next.breaker.name.lowercase()) })
        return RecoveryPermit.Granted(subsystem, next.recoveryEpoch)
    }

    @Synchronized
    override fun finished(permit: RecoveryPermit.Granted, ok: Boolean, reason: String?) {
        if (permit.subsystem !in known) return
        val r = records[permit.subsystem] ?: return
        if (permit.epoch != r.recoveryEpoch || r.recoveryOwner == null) {
            journal.append("service.recovery_stale", buildJsonObject { put("service", permit.subsystem); put("epoch", permit.epoch); put("current", r.recoveryEpoch) })
            return
        }
        val now = store.clock.nowMs()
        val done = r.copy(recoveryOwner = null, recoveryStartedMs = null, lastOutcome = if (ok) "recovered" else (reason ?: "failed"))
        val next = when {
            !ok && r.breaker == Breaker.HALF_OPEN -> open(done, now, "the trial recovery failed")
            ok -> done.copy(reattachFromMs = now, reattached = 0)
            else -> done
        }
        records.put(next)
        journal.append(if (ok) "service.recovered" else "service.recovery_failed", buildJsonObject {
            put("service", permit.subsystem); put("epoch", permit.epoch); reason?.let { put("reason", it) }
        })
    }

    @Synchronized
    override fun observed(subsystem: String, healthy: Boolean) {
        val r = records[subsystem] ?: return
        if (r.breaker != Breaker.HALF_OPEN) return
        if (!healthy) {
            if (r.stableProbes != 0) records.put(r.copy(stableProbes = 0))
            return
        }
        val stable = r.stableProbes + 1
        if (stable >= policy.stability) {
            records.put(r.copy(breaker = Breaker.CLOSED, openUntilMs = null, openings = 0, stableProbes = 0, attempts = emptyList()))
            journal.append("service.breaker", buildJsonObject { put("service", subsystem); put("state", "closed"); put("after_stable_probes", stable) })
        } else {
            records.put(r.copy(stableProbes = stable))
        }
    }

    /** The user closes a breaker by hand: an explicit, journaled act, never a side effect of reopening a screen. */
    @Synchronized
    fun resetBreaker(serviceId: String, by: String, reason: String) {
        records.update(serviceId) { it.copy(breaker = Breaker.CLOSED, openUntilMs = null, openings = 0, attempts = emptyList(), stableProbes = 0) }
        journal.append("service.breaker_reset", buildJsonObject { put("service", serviceId); put("by", by); put("reason", reason) })
    }

    private fun open(r: ServiceRecord, now: Long, why: String): ServiceRecord {
        val base = minOf(policy.cooldownMs shl minOf(r.openings, 20), policy.maxCooldownMs)
        val jitter = if (policy.jitterRatio <= 0) 0.0 else (random.nextDouble() * 2 - 1) * policy.jitterRatio
        val until = now + (base * (1 + jitter)).toLong()
        journal.append("service.breaker", buildJsonObject { put("service", r.serviceId); put("state", "open"); put("until", until); put("why", why) })
        return r.copy(breaker = Breaker.OPEN, openUntilMs = until, openings = r.openings + 1, stableProbes = 0, recoveryOwner = null, recoveryStartedMs = null)
    }

    private fun refId(serviceId: String, consumer: String) = "r-" + ControlStore.sha256(serviceId + "\n" + consumer).take(32)

    private companion object {
        /** After this many stages the ramp is over and admission is whole again. */
        const val RAMP_STAGES = 16
    }
}
