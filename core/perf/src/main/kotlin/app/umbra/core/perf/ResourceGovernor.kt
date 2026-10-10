package app.umbra.core.perf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Reads current device pressure. The Android implementation lives in `:app`. */
fun interface ResourceSampler {
    suspend fun sample(): ResourceSample
}

/**
 * Samples the device on an interval, publishes the current [ThrottleDecision], and gates work on it:
 * callers wrap expensive work in [withAdmission] and are suspended (not failed) while the device
 * is under pressure, then resumed when it recovers.
 */
class ResourceGovernor(
    private val sampler: ResourceSampler,
    policy: ThrottlePolicy = ThrottlePolicy(),
    private val intervalMs: Long = 5_000,
) {
    private data class Active(val sessions: Int = 0, val light: Int = 0, val expensive: Int = 0)
    private data class State(val decision: ThrottleDecision, val active: Active = Active())

    private val throttle = HysteresisThrottle(policy)
    private val state = MutableStateFlow(State(throttle.decision))
    private val decisionFlow = MutableStateFlow(throttle.decision)

    @Volatile
    private var lastSample: ResourceSample? = null

    /** The decision currently in force; changes only through [refreshNow]. */
    val decision: StateFlow<ThrottleDecision> = decisionFlow.asStateFlow()

    val activeSessions: Int get() = state.value.active.sessions
    val activeExpensive: Int get() = state.value.active.expensive
    val latestSample: ResourceSample? get() = lastSample

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                refreshNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failing sampler must not stop the governor; keep the last decision.
            }
            delay(intervalMs)
        }
    }

    suspend fun refreshNow(): ThrottleDecision {
        val sample = sampler.sample()
        lastSample = sample
        return apply(throttle.next(sample))
    }

    private fun apply(decision: ThrottleDecision): ThrottleDecision {
        state.update { it.copy(decision = decision) }
        decisionFlow.value = decision
        return decision
    }

    /** Whether a new top-level session may start right now. */
    fun canStartSession(): Boolean = state.value.let { it.decision.allowNewSessions && it.active.sessions < it.decision.maxConcurrentSessions }

    /** Registers a running session. Returns false (and registers nothing) if the device cannot take another. */
    fun tryStartSession(): Boolean {
        var started = false
        state.update { s ->
            if (s.decision.allowNewSessions && s.active.sessions < s.decision.maxConcurrentSessions) {
                started = true
                s.copy(active = s.active.copy(sessions = s.active.sessions + 1))
            } else {
                started = false
                s
            }
        }
        return started
    }

    fun endSession() {
        state.update { s -> s.copy(active = s.active.copy(sessions = maxOf(0, s.active.sessions - 1))) }
    }

    /** Runs [block] once the device allows [workClass]; suspends while it does not. */
    suspend fun <T> withAdmission(workClass: WorkClass, block: suspend () -> T): T {
        acquire(workClass)
        try {
            return block()
        } finally {
            release(workClass)
        }
    }

    private suspend fun acquire(workClass: WorkClass) {
        while (true) {
            var acquired = false
            state.update { s ->
                if (admits(s, workClass)) {
                    acquired = true
                    s.copy(active = s.active.bump(workClass, +1))
                } else {
                    acquired = false
                    s
                }
            }
            if (acquired) return
            state.first { admits(it, workClass) }
        }
    }

    private fun release(workClass: WorkClass) {
        state.update { s -> s.copy(active = s.active.bump(workClass, -1)) }
    }

    private fun admits(s: State, workClass: WorkClass): Boolean = when (workClass) {
        WorkClass.INTERACTIVE -> true
        WorkClass.LIGHT -> s.decision.allows(WorkClass.LIGHT)
        WorkClass.EXPENSIVE -> s.decision.allows(WorkClass.EXPENSIVE) && s.active.expensive < s.decision.maxConcurrentExpensive
    }

    private fun Active.bump(workClass: WorkClass, by: Int): Active = when (workClass) {
        WorkClass.INTERACTIVE -> this
        WorkClass.LIGHT -> copy(light = maxOf(0, light + by))
        WorkClass.EXPENSIVE -> copy(expensive = maxOf(0, expensive + by))
    }
}
