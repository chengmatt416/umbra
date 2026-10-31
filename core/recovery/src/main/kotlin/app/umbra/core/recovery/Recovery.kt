package app.umbra.core.recovery

/** How much the product needs a subsystem; shown in the UI and used to order recovery. */
enum class Criticality { ESSENTIAL, IMPORTANT, OPTIONAL }

sealed interface Health {
    data object Healthy : Health

    /** Working, but not fully; never triggers a restart on its own. */
    data class Degraded(val reason: String) : Health

    /** Not working. [safeToRestart] is false when restarting could lose work or cause harm. */
    data class Failed(val reason: String, val safeToRestart: Boolean = true) : Health

    val isHealthy: Boolean get() = this is Healthy
}

sealed interface RestartResult {
    data object Restarted : RestartResult
    data class Failed(val reason: String) : RestartResult

    /** Cannot be fixed automatically, for example a revoked permission; the user must act. */
    data class NeedsUser(val action: String) : RestartResult
}

/** One thing that can break and be brought back (spec §30), such as Shizuku, the runtime, or OMP. */
interface Subsystem {
    val id: String
    val criticality: Criticality get() = Criticality.IMPORTANT

    /** Subsystems that must be healthy before this one is restarted. */
    val dependsOn: Set<String> get() = emptySet()

    suspend fun probe(): Health
    suspend fun restart(): RestartResult
}

data class RecoveryPolicy(
    val probeIntervalMs: Long = 5_000,
    val baseBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 60_000,
    /** After this many consecutive failed recoveries the circuit opens. */
    val maxConsecutiveFailures: Int = 5,
    val circuitOpenMs: Long = 120_000,
    val jitterRatio: Double = 0.2,
    val probeTimeoutMs: Long = 10_000,
    val restartTimeoutMs: Long = 30_000,
    /** Idle probes need not wake the phone as often as probes supporting a live task. */
    val idleProbeIntervalMs: Long = 60_000,
)

enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

data class SubsystemState(
    val id: String,
    val health: Health = Health.Healthy,
    val consecutiveFailures: Int = 0,
    val circuit: CircuitState = CircuitState.CLOSED,
    val nextAttemptAtMs: Long? = null,
    val circuitOpenUntilMs: Long? = null,
    /** Set when only the user can fix it; cleared when the subsystem is healthy or [HealthSupervisor.reset]. */
    val needsUser: String? = null,
    /** The dependency this subsystem's recovery is currently waiting on, if any. */
    val waitingFor: String? = null,
    /** Why the recovery gate holds this subsystem's recovery back (draining, no consumer, budget, another owner). */
    val heldBy: String? = null,
    val totalRestarts: Int = 0,
    val lastProbeAtMs: Long? = null,
)

sealed interface RecoveryEvent {
    val subsystem: String
    val atMs: Long

    data class ProbeFailed(override val subsystem: String, override val atMs: Long, val reason: String) : RecoveryEvent
    data class Degraded(override val subsystem: String, override val atMs: Long, val reason: String) : RecoveryEvent
    data class RestartStarted(override val subsystem: String, override val atMs: Long, val attempt: Int) : RecoveryEvent
    data class RestartFailed(override val subsystem: String, override val atMs: Long, val attempt: Int, val reason: String) : RecoveryEvent
    data class Recovered(override val subsystem: String, override val atMs: Long, val attempts: Int) : RecoveryEvent
    data class CircuitOpened(override val subsystem: String, override val atMs: Long, val untilMs: Long) : RecoveryEvent
    data class CircuitHalfOpen(override val subsystem: String, override val atMs: Long) : RecoveryEvent
    data class NeedsUser(override val subsystem: String, override val atMs: Long, val action: String) : RecoveryEvent
    data class Skipped(override val subsystem: String, override val atMs: Long, val because: String) : RecoveryEvent
    data class Held(override val subsystem: String, override val atMs: Long, val because: String) : RecoveryEvent
}

/**
 * Decides, outside the supervisor and persisted, whether a recovery may start now (architecture spec §9: one fenced
 * recovery owner per service, a restart budget and circuit breaker that survive a restart of the app, recovery only
 * while something needs the service), and learns how each attempt and probe went.
 */
interface RecoveryGate {
    fun admit(subsystem: String, owner: String): RecoveryPermit

    /** The attempt [permit] allowed ended; an outcome for a permit that is no longer current is ignored. */
    fun finished(permit: RecoveryPermit.Granted, ok: Boolean, reason: String?)

    /** A probe's verdict, for the breaker's stability count. */
    fun observed(subsystem: String, healthy: Boolean)

    companion object {
        /** Lets every attempt through: the supervisor's own backoff and breaker are all there is. */
        val OPEN = object : RecoveryGate {
            override fun admit(subsystem: String, owner: String) = RecoveryPermit.Granted(subsystem, 0)
            override fun finished(permit: RecoveryPermit.Granted, ok: Boolean, reason: String?) = Unit
            override fun observed(subsystem: String, healthy: Boolean) = Unit
        }
    }
}

sealed interface RecoveryPermit {
    /** [epoch] fences the attempt: only its outcome counts, and only while no newer owner took over. */
    data class Granted(val subsystem: String, val epoch: Long) : RecoveryPermit

    data class Refused(val reason: String) : RecoveryPermit
}
