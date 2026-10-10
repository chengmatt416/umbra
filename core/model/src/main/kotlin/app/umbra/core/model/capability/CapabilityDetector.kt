package app.umbra.core.model.capability

import app.umbra.core.model.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One executed check (not a version check) that yields a [Capability]. */
interface CapabilityProbe {
    val id: CapabilityId
    suspend fun probe(): Capability
}

/**
 * Runs probes concurrently with a per-probe timeout and publishes the snapshot and the derived
 * [ExecutionStrategy]. A probe that throws or times out becomes UNKNOWN rather than failing the
 * whole refresh, so one broken subsystem never hides the rest.
 */
class CapabilityDetector(
    private val probes: List<CapabilityProbe>,
    private val clock: Clock = Clock.System,
    private val probeTimeoutMs: Long = 8_000,
) {
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(CapabilitySnapshot())
    private val _strategy = MutableStateFlow(StrategySelector.select(CapabilitySnapshot()))

    val snapshot: StateFlow<CapabilitySnapshot> = _snapshot.asStateFlow()
    val strategy: StateFlow<ExecutionStrategy> = _strategy.asStateFlow()

    /** Re-run [ids] (all probes when null) and publish the merged result. */
    suspend fun refresh(ids: Set<CapabilityId>? = null): CapabilitySnapshot = mutex.withLock {
        val selected = if (ids == null) probes else probes.filter { it.id in ids }
        val results = coroutineScope {
            selected.map { probe -> async { runProbe(probe) } }.map { it.await() }
        }
        val merged = _snapshot.value.copy(
            capabilities = _snapshot.value.capabilities + results.associateBy { it.id },
            takenAtMs = clock.nowMs(),
        )
        _snapshot.value = merged
        _strategy.value = StrategySelector.select(merged)
        merged
    }

    private suspend fun runProbe(probe: CapabilityProbe): Capability {
        val started = clock.nowMs()
        return try {
            val result = withTimeoutOrNull(probeTimeoutMs) { probe.probe() }
            result?.copy(measuredAtMs = clock.nowMs())
                ?: Capability(
                    id = probe.id,
                    state = CapabilityState.UNKNOWN,
                    detail = "probe timed out after ${probeTimeoutMs}ms",
                    measuredAtMs = clock.nowMs(),
                )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Capability(
                id = probe.id,
                state = CapabilityState.UNKNOWN,
                detail = "probe failed: ${e.message ?: e::class.simpleName} (after ${clock.nowMs() - started}ms)",
                measuredAtMs = clock.nowMs(),
            )
        }
    }
}
