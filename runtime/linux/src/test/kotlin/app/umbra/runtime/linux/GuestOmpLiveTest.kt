package app.umbra.runtime.linux

import app.umbra.platform.api.exec
import app.umbra.runtime.omp.HostToolDefinition
import app.umbra.runtime.omp.HostToolHandler
import app.umbra.runtime.omp.HostToolPayload
import app.umbra.runtime.omp.OmpClientConfig
import app.umbra.runtime.omp.OmpEvent
import app.umbra.runtime.omp.OmpLaunch
import app.umbra.runtime.omp.OmpModel
import app.umbra.runtime.omp.OmpProcess
import app.umbra.runtime.omp.OmpProvider
import app.umbra.runtime.omp.OmpSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * omp itself, running inside the Debian guest on the real Android 16 emulator as the app's uid, spoken
 * to over the same RPC the app uses. No model is called: this proves the runtime part of the product
 * (Bun, omp's natives, the config files, the guest launcher, process cleanup) on the device.
 */
class GuestOmpLiveTest {
    @Test
    fun ompStartsInsideTheGuestRegistersHostToolsAndStopsCleanly(): Unit = runBlocking {
        val runtime = LiveGuest.installed()
        val adb = LiveGuest.device()
        runtime.mount()
        // A previous failed run may have left directories behind.
        runtime.exec(listOf("rm", "-rf", "${RuntimeLayout.GUEST_HOME}/.umbra"), asRoot = true)
        val spawner = GuestOmpSpawner(runtime) { adb.rootCommand(it) }
        val files = GuestAgentFiles(runtime, "${RuntimeLayout.GUEST_HOME}/.umbra/agents/live-test")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = OmpProcess.start(
            launch = OmpLaunch(
                executable = listOf("omp"), workingDirectory = RuntimeLayout.GUEST_WORKSPACE, model = "probe/probe-model",
                noSession = true, loadExtensions = false, loadSkills = false, loadRules = false,
            ),
            settings = OmpSettings(extra = mapOf("startup" to mapOf("checkUpdate" to false))),
            providers = listOf(OmpProvider("probe", "http://127.0.0.1:9/v1", auth = "none", models = listOf(OmpModel("probe-model", "Probe Model")))),
            files = files,
            scope = scope,
            environment = mapOf("OTEL_SDK_DISABLED" to "true"),
            // An emulator starts omp in two to three minutes; a phone in a few seconds.
            clientConfig = OmpClientConfig(readyTimeoutMs = 900_000, requestTimeoutMs = 600_000),
            spawner = spawner,
        )
        val client = started.client
        val events = java.util.concurrent.CopyOnWriteArrayList<OmpEvent>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { events += it } }
        try {
            val info = client.start()
            assertTrue(info.supportsV2, "omp offers protocol v2 inside the guest: $info")

            val models = client.availableModels()
            assertTrue(models.any { it.provider == "probe" && it.id == "probe-model" }, "omp read the models.yml the host wrote into the guest: $models")

            val names = client.setHostTools(
                listOf(HostToolDefinition("probe_tool", "A tool for the live test.", buildJsonObject { put("type", "object") })),
                HostToolHandler { _, _ -> HostToolPayload.text("unused") },
            )
            assertEquals(listOf("probe_tool"), names, "host tools are registered with the omp running in the guest")

            val state = client.getState()
            assertNotNull(state["model"], "the session has the model chosen on the command line: $state")

            // The agent runs as the app user, not as root.
            // The first letter of the pattern is bracketed so pgrep does not match the shell that carries the pattern.
            val who = runtime.exec(listOf("bash", "-c", "pgrep -u \$(id -u) -f '[b]un /usr/local/bin/omp' | head -3"))
            assertTrue(who.stdout.isNotBlank(), "omp is a live process of the app user in the guest: ${who.combined}")
        } finally {
            client.close(graceMs = 8_000)
            started.transport.destroy()
            scope.cancel()
        }
        // Closing stdin makes omp exit, and nothing of it may remain in the guest.
        withTimeout(20_000) {
            while (runtime.exec(listOf("bash", "-c", "pgrep -u \$(id -u) -f '[b]un /usr/local/bin/omp' | wc -l")).stdout.trim() != "0") delay(300)
        }
        assertFalse(events.any { it is OmpEvent.FrameError }, "no protocol errors: ${events.filterIsInstance<OmpEvent.FrameError>()}")
    }
}
