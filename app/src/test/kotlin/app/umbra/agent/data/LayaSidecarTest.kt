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
        var failInstall = false
        override var installFailedAt: Long? = null
        var clock: () -> Long = { 0L }
        override fun wallNowMs() = clock()
        var installCommand = ""
        var exit = CompletableDeferred<Unit>()
        override val name = "local-test-laya"
        override suspend fun ensureReady() = ready
        override suspend fun isAvailable() = available
        override suspend fun decide(request: DecisionRequest) = DecisionResponse(emptyMap(), name, 0)
        override val apiKey = "fixture-key"
        var startSecrets: Map<String, String>? = null
        override suspend fun exec(argv: List<String>, timeoutMs: Long, maxOutputBytes: Int, secrets: Map<String, String>): ShellResult {
            val command = argv.last()
            require("fixture-key" !in command) { "the key must never be part of a command line" }
            return when {
                command.startsWith("test -x") -> ShellResult(if (installed) 0 else 1, "")
                "pip install" in command -> {
                    installs++; installCommand = command
                    if (failInstall) ShellResult(1, "ERROR: No space left on device") else { installed = true; ShellResult(0, "") }
                }
                command.startsWith("pkill") -> {
                    kills++
                    if (failStop) return ShellResult(2, "Guest temporarily unavailable")
                    available = false
                    ShellResult(0, "")
                }
                "LAYA_PRELOAD=1" in command -> {
                    starts++
                    startSecrets = secrets
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
        assertEquals(mapOf("LAYA_API_KEY" to "fixture-key"), host.startSecrets, "the server gets its bearer key through the secret channel")
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
    fun aFailedInstallIsNotDownloadedAgainOnEveryTickAndLeavesNoPipTemp() = runTest {
        val host = Host().apply { installed = false; failInstall = true }
        host.clock = { testScheduler.currentTime }
        val laya = LayaSidecar(host, backgroundScope, host) { testScheduler.currentTime }
        laya.maintain()
        assertEquals(LayaSidecar.State.FAILED, laya.state.value)
        assertEquals(1, host.installs)
        // pip works in a private temp folder removed afterwards, and old pip leftovers in /tmp are cleared first.
        assertTrue("TMPDIR=" in host.installCommand && "pip-unpack-*" in host.installCommand && host.installCommand.trimEnd().endsWith("exit \$rc"))
        assertTrue("download.pytorch.org/whl/cpu torch" in host.installCommand, "CPU-only PyTorch, not PyPI's CUDA build")
        // The failure time survives a restart: a new sidecar on the same host does not download again.
        val restarted = LayaSidecar(host, backgroundScope, host) { testScheduler.currentTime }
        restarted.maintain()
        assertEquals(1, host.installs)
        repeat(12) { advanceTimeBy(5 * 60_000); laya.maintain() }
        assertEquals(1, host.installs, "an hour of maintenance does not download the wheels again")
        advanceTimeBy(24 * 60 * 60_000L)
        laya.maintain()
        assertEquals(2, host.installs, "it tries again by itself after a day")
        host.failInstall = false
        laya.download()                       // the person asks: at once (on the IO dispatcher, so wait in real time)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { while (host.installs < 3) kotlinx.coroutines.delay(10) }
        }
        assertEquals(3, host.installs)
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
