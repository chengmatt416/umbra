package app.umbra.core.recovery

import app.umbra.core.model.Clock
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Probes subsystems, restarts the failed ones with exponential backoff and jitter, honours
 * dependencies, and stops hammering something that will not come back (circuit breaker). A failure
 * that is unsafe to automate, or needs the user, is surfaced instead of retried (spec §30: "where
 * safe, restore the subsystem and continue the session").
 */
class HealthSupervisor(
    subsystems: List<Subsystem>,
    private val policy: RecoveryPolicy = RecoveryPolicy(),
    private val clock: Clock = Clock.System,
    private val random: Random = Random.Default,
    /** Persisted policy for shared services (architecture spec §9); everything is let through without one. */
    private val gate: RecoveryGate = RecoveryGate.OPEN,
    /** Who this supervisor is, as the recovery owner it registers with [gate]. */
    private val owner: String = "health-supervisor",
) {
    private val byId: Map<String, Subsystem> = subsystems.associateBy { it.id }
    private val locks: Map<String, Mutex> = subsystems.associate { it.id to Mutex() }
    private val _states = MutableStateFlow(subsystems.associate { it.id to SubsystemState(it.id) })
    private val _events = MutableSharedFlow<RecoveryEvent>(extraBufferCapacity = 256)

    init {
        for (s in subsystems) {
            val missing = s.dependsOn - byId.keys
            require(missing.isEmpty()) { "${s.id} depends on unknown subsystems $missing" }
        }
    }

    val states: StateFlow<Map<String, SubsystemState>> = _states.asStateFlow()
    val events: SharedFlow<RecoveryEvent> = _events.asSharedFlow()

    fun state(id: String): SubsystemState = _states.value.getValue(id)

    /** One probing loop per subsystem, so a slow restart never delays the others. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        for (sub in byId.values) {
            launch {
                while (isActive) {
                    step(sub)
                    delay(policy.probeIntervalMs)
                }
            }
        }
    }

    /** Probe (and recover) now; [id] null means every subsystem. */
    suspend fun checkNow(id: String? = null) {
        val targets = if (id == null) byId.values.toList() else listOf(byId.getValue(id))
        for (sub in targets) step(sub)
    }

    /** The user pressed "retry": forget past failures and close the circuit. */
    fun reset(id: String) {
        update(id) { SubsystemState(id, totalRestarts = it.totalRestarts) }
    }

    private suspend fun step(sub: Subsystem) {
        locks.getValue(sub.id).withLock { stepLocked(sub) }
    }

    private suspend fun stepLocked(sub: Subsystem) {
        val now = clock.nowMs()
        var s = state(sub.id)

        if (s.circuit == CircuitState.OPEN) {
            val until = s.circuitOpenUntilMs ?: now
            if (now < until) return
            s = update(sub.id) { it.copy(circuit = CircuitState.HALF_OPEN) }
            emit(RecoveryEvent.CircuitHalfOpen(sub.id, now))
        }

        val health = probe(sub)
        update(sub.id) { it.copy(health = health, lastProbeAtMs = clock.nowMs()) }
        gate.observed(sub.id, health.isHealthy)

        when (health) {
            Health.Healthy -> {
                if (s.consecutiveFailures > 0 || s.circuit != CircuitState.CLOSED || s.needsUser != null) {
                    emit(RecoveryEvent.Recovered(sub.id, clock.nowMs(), s.consecutiveFailures))
                }
                update(sub.id) {
                    it.copy(
                        consecutiveFailures = 0, circuit = CircuitState.CLOSED, nextAttemptAtMs = null,
                        circuitOpenUntilMs = null, needsUser = null, waitingFor = null, heldBy = null,
                    )
                }
            }
            is Health.Degraded -> {
                if (s.health !is Health.Degraded) emit(RecoveryEvent.Degraded(sub.id, clock.nowMs(), health.reason))
            }
            is Health.Failed -> recover(sub, s, health)
        }
    }

    private suspend fun recover(sub: Subsystem, before: SubsystemState, failed: Health.Failed) {
        val now = clock.nowMs()
        if (before.health !is Health.Failed || before.consecutiveFailures == 0) {
            emit(RecoveryEvent.ProbeFailed(sub.id, now, failed.reason))
        }
        if (before.needsUser != null) return // waiting for the user; probing continues so it clears on its own
        if (!failed.safeToRestart) {
            val action = "${sub.id} failed (${failed.reason}) and is not safe to restart automatically"
            update(sub.id) { it.copy(needsUser = action) }
            emit(RecoveryEvent.NeedsUser(sub.id, now, action))
            return
        }
        val unhealthyDependency = sub.dependsOn.firstOrNull { !state(it).health.isHealthy }
        if (unhealthyDependency != null) {
            // Say it once per outage, not on every probe.
            if (before.waitingFor != unhealthyDependency) {
                update(sub.id) { it.copy(waitingFor = unhealthyDependency) }
                emit(RecoveryEvent.Skipped(sub.id, now, "waiting for $unhealthyDependency"))
            }
            return
        }
        if (before.waitingFor != null) update(sub.id) { it.copy(waitingFor = null) }
        val dueAt = before.nextAttemptAtMs
        if (dueAt != null && now < dueAt) return

        val attempt = before.consecutiveFailures + 1
        // A HALF_OPEN circuit gets exactly one trial restart; otherwise too many failures keep it open.
        if (before.circuit != CircuitState.HALF_OPEN && before.consecutiveFailures >= policy.maxConsecutiveFailures) {
            openCircuit(sub, now)
            return
        }

        val permit = when (val p = gate.admit(sub.id, owner)) {
            is RecoveryPermit.Granted -> p
            is RecoveryPermit.Refused -> {
                // Say it once per reason, not on every probe.
                if (before.heldBy != p.reason) {
                    update(sub.id) { it.copy(heldBy = p.reason) }
                    emit(RecoveryEvent.Held(sub.id, now, p.reason))
                }
                return
            }
        }
        if (before.heldBy != null) update(sub.id) { it.copy(heldBy = null) }

        emit(RecoveryEvent.RestartStarted(sub.id, now, attempt))
        val result = try {
            withTimeoutOrNull(policy.restartTimeoutMs) { sub.restart() }
                ?: RestartResult.Failed("restart timed out after ${policy.restartTimeoutMs}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            RestartResult.Failed("restart threw ${e::class.simpleName}: ${e.message}")
        }
        update(sub.id) { it.copy(totalRestarts = it.totalRestarts + 1) }

        when (result) {
            RestartResult.Restarted -> {
                val after = probe(sub)
                gate.finished(permit, after.isHealthy, if (after.isHealthy) null else describe(after))
                if (after.isHealthy) {
                    emit(RecoveryEvent.Recovered(sub.id, clock.nowMs(), attempt))
                    update(sub.id) {
                        it.copy(health = after, consecutiveFailures = 0, circuit = CircuitState.CLOSED, nextAttemptAtMs = null, circuitOpenUntilMs = null)
                    }
                } else {
                    restartFailed(sub, attempt, "restarted but still unhealthy: ${describe(after)}")
                }
            }
            is RestartResult.Failed -> {
                gate.finished(permit, false, result.reason)
                restartFailed(sub, attempt, result.reason)
            }
            is RestartResult.NeedsUser -> {
                gate.finished(permit, false, result.action)
                update(sub.id) { it.copy(needsUser = result.action, consecutiveFailures = attempt) }
                emit(RecoveryEvent.NeedsUser(sub.id, clock.nowMs(), result.action))
            }
        }
    }

    private suspend fun restartFailed(sub: Subsystem, attempt: Int, reason: String) {
        val now = clock.nowMs()
        emit(RecoveryEvent.RestartFailed(sub.id, now, attempt, reason))
        val wasHalfOpen = state(sub.id).circuit == CircuitState.HALF_OPEN
        if (wasHalfOpen || attempt >= policy.maxConsecutiveFailures) {
            update(sub.id) { it.copy(consecutiveFailures = attempt) }
            openCircuit(sub, now)
        } else {
            update(sub.id) { it.copy(consecutiveFailures = attempt, nextAttemptAtMs = now + backoffMs(attempt)) }
        }
    }

    private suspend fun openCircuit(sub: Subsystem, now: Long) {
        val until = now + policy.circuitOpenMs
        update(sub.id) { it.copy(circuit = CircuitState.OPEN, circuitOpenUntilMs = until, nextAttemptAtMs = null) }
        emit(RecoveryEvent.CircuitOpened(sub.id, now, until))
    }

    /** Exponential backoff with symmetric jitter, capped. */
    internal fun backoffMs(attempt: Int): Long {
        val exp = policy.baseBackoffMs * (1L shl minOf(attempt - 1, 20))
        val capped = minOf(exp, policy.maxBackoffMs)
        val jitter = if (policy.jitterRatio <= 0.0) 0.0 else (random.nextDouble() * 2 - 1) * policy.jitterRatio
        return (capped * (1 + jitter)).toLong().coerceAtLeast(0)
    }

    private suspend fun probe(sub: Subsystem): Health =
        try {
            withTimeoutOrNull(policy.probeTimeoutMs) { sub.probe() }
                ?: Health.Failed("probe timed out after ${policy.probeTimeoutMs}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Health.Failed("probe threw ${e::class.simpleName}: ${e.message}")
        }

    private fun describe(h: Health) = when (h) {
        Health.Healthy -> "healthy"
        is Health.Degraded -> h.reason
        is Health.Failed -> h.reason
    }

    private fun update(id: String, transform: (SubsystemState) -> SubsystemState): SubsystemState {
        var result: SubsystemState? = null
        _states.update { map ->
            val next = transform(map.getValue(id))
            result = next
            map + (id to next)
        }
        return result!!
    }

    private suspend fun emit(event: RecoveryEvent) {
        _events.emit(event)
    }
}
