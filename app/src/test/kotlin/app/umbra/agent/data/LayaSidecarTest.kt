package app.umbra.agent.data

import app.umbra.core.decision.DecisionEngine
import app.umbra.core.decision.DecisionRequest
import app.umbra.core.decision.DecisionResponse
import app.umbra.platform.api.ShellResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LayaSidecarTest {
    private class Host : LayaSidecar.Host, DecisionEngine {
        override var wanted = true
        override var ready = true
        var installed = true
        var available = false
        var failStart = false
        var failStop = false
        var starts = 0
        var kills = 0
        var installs = 0
        var exit = CompletableDeferred<Unit>()
        override val name = "local-test-laya"
        override suspend fun ensureReady() = ready
        override suspend fun isAvailable() = available
        override suspend fun decide(request: DecisionRequest) = DecisionResponse(emptyMap(), name, 0)
        override suspend fun exec(argv: List<String>, timeoutMs: Long, maxOutputBytes: Int): ShellResult {
            val command = argv.last()
            return when {
                command.startsWith("test -x") -> ShellResult(if (installed) 0 else 1, "")
                "pip install" in command -> { installs++; installed = true; ShellResult(0, "") }
                command.startsWith("pkill") -> {
                    kills++
                    if (failStop) return ShellResult(2, "Guest temporarily unavailable")
                    available = false
                    ShellResult(0, "")
                }
                "LAYA_PRELOAD=1" in command -> {
                    starts++
                    if (failStart) ShellResult(1, "") else {
                        available = true
                        try { exit.await() } finally { available = false }
                        ShellResult(1, "")
                    }
                }
                else -> ShellResult(0, "startup failed")
            }
        }
    }

    @Test
    fun preloadsOnceAndKeepsTheSameProcessBetweenIdleChecks() = runTest {
        val host = Host().apply { installed = false }
        val laya = LayaSidecar(host, backgroundScope, host) { testScheduler.currentTime }
        laya.maintain()
        repeat(10) { advanceTimeBy(30_000); laya.maintain() }
        assertEquals(LayaSidecar.State.RUNNING, laya.state.value)
        assertTrue(laya.engine.isAvailable())
        assertEquals(1, host.installs)
        assertEquals(1, host.starts)
        assertEquals(1, host.kills, "healthy maintenance must not kill the resident process")
    }

    @Test
    fun anExistingHealthyServerIsReusedAndDisabledServiceStaysStopped() = runTest {
        val host = Host().apply { available = true }
        val laya = LayaSidecar(host, backgroundScope, host) { testScheduler.currentTime }
        laya.maintain()
        assertEquals(0, host.starts)
        host.wanted = false
        laya.maintain()
        assertEquals(LayaSidecar.State.STOPPED, laya.state.value)
        repeat(3) { advanceTimeBy(30_000); laya.maintain() }
        assertEquals(0, host.starts)
        assertFalse(laya.engine.isAvailable())
        assertEquals(1, host.kills)
    }

    @Test
    fun failedStartsBackOffThenRecoverAndLinuxMayArriveLater() = runTest {
        val host = Host().apply { ready = false; failStart = true }
        val laya = LayaSidecar(host, backgroundScope, host) { testScheduler.currentTime }
        laya.maintain()
        assertEquals(LayaSidecar.State.NEEDS_LINUX, laya.state.value)
        assertEquals(0, host.starts)
        host.ready = true
        laya.maintain()
        assertEquals(LayaSidecar.State.FAILED, laya.state.value)
        repeat(5) { laya.maintain() }
        assertEquals(1, host.starts)
        advanceTimeBy(30_000)
        laya.maintain()
        assertEquals(2, host.starts)
        advanceTimeBy(30_000)
        laya.maintain()
        assertEquals(2, host.starts, "second failure backs off for a full minute")
        host.failStart = false
        advanceTimeBy(30_000)
        laya.maintain()
        assertEquals(LayaSidecar.State.RUNNING, laya.state.value)
        assertEquals(3, host.starts)
    }

    @Test
    fun processExitIsRecoveredWithoutAnActiveTask() = runTest {
        val host = Host()
        val laya = LayaSidecar(host, backgroundScope, host) { testScheduler.currentTime }
        laya.maintain()
        host.exit.complete(Unit)
        runCurrent()
        assertEquals(LayaSidecar.State.FAILED, laya.state.value)
        assertFalse(laya.engine.isAvailable())
        host.exit = CompletableDeferred()
        laya.maintain()
        assertEquals(2, host.starts)
        assertEquals(LayaSidecar.State.RUNNING, laya.state.value)
    }

    @Test
    fun aFailedStopKeepsMaintenanceAliveAndRetriesWithoutRestarting() = runTest {
        val host = Host().apply { available = true }
        val laya = LayaSidecar(host, backgroundScope, host) { testScheduler.currentTime }
        laya.maintain()
        host.wanted = false
        host.failStop = true
        laya.maintain()
        assertEquals(LayaSidecar.State.FAILED, laya.state.value)
        assertFalse(laya.engine.isAvailable())
        host.failStop = false
        laya.maintain()
        assertEquals(LayaSidecar.State.STOPPED, laya.state.value)
        assertEquals(0, host.starts)
        assertEquals(2, host.kills)
    }

    @Test
    fun concurrentMaintenanceDoesNotSpawnTwiceAndCancellationReleasesTheLock() = runTest {
        val host = Host()
        var probeBlocked = true
        val endpoint = object : DecisionEngine by host {
            override suspend fun isAvailable(): Boolean = if (probeBlocked) awaitCancellation() else host.isAvailable()
        }
        val laya = LayaSidecar(host, backgroundScope, endpoint) { testScheduler.currentTime }
        val pending = async { laya.maintain() }
        runCurrent()
        laya.maintain()
        assertEquals(0, host.starts)
        pending.cancel()
        pending.join()
        probeBlocked = false
        laya.maintain()
        assertEquals(1, host.starts)
    }
}
