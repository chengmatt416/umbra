package app.umbra.agent.data

import app.umbra.agent.data.laya.LayaExit
import app.umbra.agent.data.laya.LayaNative
import app.umbra.core.decision.DecisionEngine
import app.umbra.core.decision.DecisionRequest
import app.umbra.core.decision.DecisionResponse
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LayaSidecarTest {
    private class Host : LayaSidecar.Host {
        override var wanted = true
        override var failedAt: Long? = null
        override var failures = 0
        var clock = 0L
        override fun wallNowMs() = clock
        var unmetered = true
        override fun unmetered() = unmetered
        var downloaded = true
        override fun downloaded() = downloaded
        override fun bytesToDownload() = if (downloaded) 0L else 850L shl 20
        override val progress = MutableStateFlow<LayaNative.Progress?>(null)
        var fail: String? = null
        var backend = "npu"
        val downloads = mutableListOf<Boolean>()
        var loads = 0
        var closes = 0
        var cleared = false
        var gate: CompletableDeferred<Unit>? = null
        /** The loaded engine's process: completing it is the process dying. */
        var process = CompletableDeferred<LayaExit>()
        val deaths = mutableListOf<Pair<String, LayaExit>>()
        override fun died(opened: LayaSidecar.Opened, exit: LayaExit) { deaths += opened.backend to exit }
        override suspend fun load(download: Boolean): LayaSidecar.Opened {
            loads++
            downloads += download
            gate?.await()
            fail?.let { throw IOException(it) }
            if (!downloaded && !download) throw LayaNative.NeedsDownload(1)
            downloaded = true
            val engine = object : DecisionEngine {
                override val name = "laya"
                override suspend fun isAvailable() = true
                override suspend fun decide(request: DecisionRequest) = DecisionResponse(emptyMap(), "laya-$backend", 1)
            }
            process = CompletableDeferred()
            return LayaSidecar.Opened(engine, backend, 12, if (backend == "cpu") listOf("npu: not compiled for this SoC", "gpu: logits off") else emptyList(), process) { closes++ }
        }
        override fun clear() { cleared = true; downloaded = false }
    }

    @Test
    fun maintenanceLoadsOnceAndKeepsTheEngine() = runTest {
        val host = Host()
        val laya = LayaSidecar(host, backgroundScope, StandardTestDispatcher(testScheduler))
        laya.maintain()
        laya.maintain()
        assertEquals(LayaSidecar.State.RUNNING, laya.state.value)
        assertEquals("npu", laya.backend.value)
        assertEquals(1, host.loads)
        assertTrue(laya.engine.isAvailable())
        assertEquals("laya-npu", laya.engine.decide(DecisionRequest("s", mapOf("q" to app.umbra.core.decision.Question.Noul("x")))).engine)
    }

    @Test
    fun fallingBackToTheCpuSaysWhyTheBetterAcceleratorsWereSkipped() = runTest {
        val host = Host().apply { backend = "cpu" }
        val laya = LayaSidecar(host, backgroundScope, StandardTestDispatcher(testScheduler))
        laya.maintain()
        assertEquals("cpu", laya.backend.value)
        assertTrue(laya.message!!.contains("npu:") && laya.message!!.contains("gpu:"))
    }

    @Test
    fun unaskedDownloadsWaitForAnUnmeteredNetworkButATapDownloadsNow() = runTest {
        val host = Host().apply { downloaded = false; unmetered = false }
        val laya = LayaSidecar(host, backgroundScope, StandardTestDispatcher(testScheduler))
        laya.maintain()
        assertEquals(LayaSidecar.State.NOT_INSTALLED, laya.state.value)
        assertEquals(0, host.loads)
        assertEquals(850L shl 20, laya.downloadSize())
        laya.download()
        runCurrent()
        assertEquals(LayaSidecar.State.RUNNING, laya.state.value)
        assertEquals(listOf(true), host.downloads)
    }

    @Test
    fun withFilesHereButAMeteredNetworkItLoadsWithoutDownloadingMore() = runTest {
        val host = Host().apply { unmetered = false }
        val laya = LayaSidecar(host, backgroundScope, StandardTestDispatcher(testScheduler))
        laya.maintain()
        assertEquals(LayaSidecar.State.RUNNING, laya.state.value)
        assertEquals(listOf(false), host.downloads)
    }

    @Test
    fun aFailureRetriesByItselfAtMostHourlyAndAtOnceWhenAsked() = runTest {
        val host = Host().apply { fail = "No space left on device" }
        val laya = LayaSidecar(host, backgroundScope, StandardTestDispatcher(testScheduler))
        laya.maintain()
        assertEquals(LayaSidecar.State.FAILED, laya.state.value)
        assertEquals("No space left on device", laya.message)
        host.clock += 30 * 60_000L
        laya.maintain()
        assertEquals(1, host.loads, "no automatic retry within the hour")
        host.fail = null
        host.clock += 31 * 60_000L
        laya.maintain()
        assertEquals(2, host.loads)
        assertEquals(LayaSidecar.State.RUNNING, laya.state.value)
        assertNull(host.failedAt)
        // An explicit start after a failure does not wait for the hour.
        host.fail = "boom"
        laya.stop(); runCurrent()
        laya.start(); runCurrent()
        assertEquals(LayaSidecar.State.FAILED, laya.state.value)
        host.fail = null
        laya.start(); runCurrent()
        assertEquals(LayaSidecar.State.RUNNING, laya.state.value)
    }

    @Test
    fun stoppingReleasesTheEngineAndRemovingDeletesTheFiles() = runTest {
        val host = Host()
        val laya = LayaSidecar(host, backgroundScope, StandardTestDispatcher(testScheduler))
        laya.maintain()
        laya.stop()
        runCurrent()
        assertEquals(LayaSidecar.State.STOPPED, laya.state.value)
        assertEquals(1, host.closes)
        assertFalse(laya.engine.isAvailable())
        assertNull(laya.backend.value)
        laya.maintain()
        assertEquals(1, host.loads, "not wanted: maintenance leaves it stopped")
        laya.remove()
        runCurrent()
        assertTrue(host.cleared)
        assertEquals(LayaSidecar.State.NOT_INSTALLED, laya.state.value)
    }

    @Test
    fun turningItOffWhileLoadingDoesNotLeaveAnEngineBehind() = runTest {
        val host = Host().apply { gate = CompletableDeferred() }
        val laya = LayaSidecar(host, backgroundScope, StandardTestDispatcher(testScheduler))
        val loading = async { laya.maintain() }
        runCurrent()
        assertEquals(LayaSidecar.State.STARTING, laya.state.value)
        host.wanted = false
        host.gate!!.complete(Unit)
        loading.await()
        assertEquals(1, host.closes)
        assertFalse(laya.engine.isAvailable())
        assertEquals(LayaSidecar.State.STOPPED, laya.state.value)
    }

    @Test
    fun concurrentMaintenanceLoadsOnce() = runTest {
        val host = Host().apply { gate = CompletableDeferred() }
        val laya = LayaSidecar(host, backgroundScope, StandardTestDispatcher(testScheduler))
        val first = async { laya.maintain() }
        runCurrent()
        laya.maintain() // returns at once: the first one holds the lock
        host.gate!!.complete(Unit)
        first.await()
        assertEquals(1, host.loads)
    }

    @Test
    fun aCrashInLayasProcessFallsBackAtOnceAndStartsOnTheNextProcessor() = runTest {
        val host = Host()
        val laya = LayaSidecar(host, backgroundScope, StandardTestDispatcher(testScheduler))
        laya.maintain()
        runCurrent()
        assertEquals(LayaSidecar.State.RUNNING, laya.state.value)
        assertTrue(laya.engine.target != null)

        host.process.complete(LayaExit(crashed = true, lowMemory = false, text = "native crash"))
        runCurrent()
        // The router stops asking the dead engine at once; the app is still here.
        assertNull(laya.engine.target)
        assertNull(laya.backend.value)
        assertEquals(LayaSidecar.State.FAILED, laya.state.value)
        assertEquals(listOf("npu"), host.deaths.map { it.first })
        assertNull(host.failedAt)

        // A crash does not wait for the hourly retry: the next look loads again (the host refused the NPU).
        host.backend = "gpu"
        laya.maintain()
        runCurrent()
        assertEquals(2, host.loads)
        assertEquals("gpu", laya.backend.value)
        assertEquals(LayaSidecar.State.RUNNING, laya.state.value)
    }

    @Test
    fun androidEndingLayaForMemoryWaitsForTheHourlyRetry() = runTest {
        val host = Host()
        val laya = LayaSidecar(host, backgroundScope, StandardTestDispatcher(testScheduler))
        laya.maintain()
        runCurrent()
        host.process.complete(LayaExit(crashed = false, lowMemory = true, text = "Android ended it to free memory"))
        runCurrent()
        assertEquals(LayaSidecar.State.FAILED, laya.state.value)
        assertEquals(0L, host.failedAt)
        host.clock = 30 * 60_000L
        laya.maintain()
        runCurrent()
        assertEquals(1, host.loads)
        host.clock = 61 * 60_000L
        laya.maintain()
        runCurrent()
        assertEquals(2, host.loads)
        assertEquals(LayaSidecar.State.RUNNING, laya.state.value)
    }

    @Test
    fun anErrorFromANativeLibraryFailsLayaInsteadOfTheApp() = runTest {
        val host = object : LayaSidecar.Host by Host() {
            override suspend fun load(download: Boolean): LayaSidecar.Opened = throw UnsatisfiedLinkError("dlopen failed: libLiteRt.so")
        }
        val laya = LayaSidecar(host, backgroundScope, StandardTestDispatcher(testScheduler))
        laya.start()
        runCurrent()
        assertEquals(LayaSidecar.State.FAILED, laya.state.value)
        assertTrue(laya.message.orEmpty().contains("libLiteRt"))
    }

    @Test
    fun repeatedFailuresWaitLongerAndLongerUntilAManualStart() = runTest {
        val host = Host().apply { fail = "gpu: logits off" }
        val laya = LayaSidecar(host, backgroundScope, StandardTestDispatcher(testScheduler))
        laya.maintain(); runCurrent()
        assertEquals(1, host.loads)
        // Second try after an hour, the third only two hours after that.
        host.clock = 61 * 60_000L; laya.maintain(); runCurrent()
        assertEquals(2, host.loads)
        host.clock += 61 * 60_000L; laya.maintain(); runCurrent()
        assertEquals(2, host.loads)
        host.clock += 60 * 60_000L; laya.maintain(); runCurrent()
        assertEquals(3, host.loads)
        // Tapping Start tries at once and starts the count again.
        host.fail = null
        laya.start(); runCurrent()
        assertEquals(4, host.loads)
        assertEquals(0, host.failures)
        assertEquals(LayaSidecar.State.RUNNING, laya.state.value)
    }
}
