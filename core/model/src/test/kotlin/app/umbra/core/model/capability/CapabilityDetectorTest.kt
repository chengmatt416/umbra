package app.umbra.core.model.capability

import app.umbra.core.model.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

class CapabilityDetectorTest {
    private fun probe(id: CapabilityId, block: suspend () -> Capability) = object : CapabilityProbe {
        override val id = id
        override suspend fun probe() = block()
    }

    @Test
    fun runsProbesConcurrentlyAndMergesResults() = runTest {
        val clock = Clock { currentTime }
        val detector = CapabilityDetector(
            probes = listOf(
                probe(CapabilityId.ROOT_PERMISSION) { delay(3_000); Capability(CapabilityId.ROOT_PERMISSION, CapabilityState.AVAILABLE) },
                probe(CapabilityId.SHIZUKU_AVAILABLE) { delay(3_000); Capability(CapabilityId.SHIZUKU_AVAILABLE, CapabilityState.UNAVAILABLE, "not installed") },
            ),
            clock = clock,
        )
        val snapshot = detector.refresh()
        assertEquals(3_000, currentTime, "probes must run concurrently, not back to back")
        assertEquals(CapabilityState.AVAILABLE, snapshot.state(CapabilityId.ROOT_PERMISSION))
        assertEquals("not installed", snapshot[CapabilityId.SHIZUKU_AVAILABLE]?.detail)
        assertEquals(PrivilegedLaunchMode.ROOT_AS_SHELL, detector.strategy.value.shellHelper)
    }

    @Test
    fun aHangingProbeBecomesUnknownInsteadOfBlockingTheRest() = runTest {
        val detector = CapabilityDetector(
            probes = listOf(
                probe(CapabilityId.X11) { delay(60_000); Capability(CapabilityId.X11, CapabilityState.AVAILABLE) },
                probe(CapabilityId.LAYA_RUNTIME) { Capability(CapabilityId.LAYA_RUNTIME, CapabilityState.AVAILABLE) },
            ),
            clock = Clock { currentTime },
            probeTimeoutMs = 2_000,
        )
        val snapshot = detector.refresh()
        assertEquals(CapabilityState.UNKNOWN, snapshot.state(CapabilityId.X11))
        assertTrue("timed out" in snapshot[CapabilityId.X11]!!.detail)
        assertEquals(CapabilityState.AVAILABLE, snapshot.state(CapabilityId.LAYA_RUNTIME))
        assertEquals(2_000, currentTime)
    }

    @Test
    fun aThrowingProbeIsReportedNotPropagated() = runTest {
        val detector = CapabilityDetector(
            probes = listOf(probe(CapabilityId.OMP_RUNTIME) { error("boom") }),
            clock = Clock { currentTime },
        )
        val snapshot = detector.refresh()
        assertEquals(CapabilityState.UNKNOWN, snapshot.state(CapabilityId.OMP_RUNTIME))
        assertTrue("boom" in snapshot[CapabilityId.OMP_RUNTIME]!!.detail)
    }

    @Test
    fun refreshOfSelectedIdsKeepsOtherResults() = runTest {
        var rootCalls = 0
        var layaCalls = 0
        val detector = CapabilityDetector(
            probes = listOf(
                probe(CapabilityId.ROOT_PERMISSION) { rootCalls++; Capability(CapabilityId.ROOT_PERMISSION, CapabilityState.AVAILABLE) },
                probe(CapabilityId.LAYA_RUNTIME) { layaCalls++; Capability(CapabilityId.LAYA_RUNTIME, CapabilityState.UNAVAILABLE) },
            ),
            clock = Clock { currentTime },
        )
        detector.refresh()
        detector.refresh(setOf(CapabilityId.LAYA_RUNTIME))
        assertEquals(1, rootCalls)
        assertEquals(2, layaCalls)
        assertEquals(CapabilityState.AVAILABLE, detector.snapshot.value.state(CapabilityId.ROOT_PERMISSION))
    }
}
