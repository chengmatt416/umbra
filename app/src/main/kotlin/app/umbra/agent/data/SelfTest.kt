package app.umbra.agent.data

import app.umbra.agent.AppContainer
import app.umbra.core.model.action.ActionRequest
import app.umbra.core.model.action.ActionStatus
import app.umbra.core.model.action.DisplayAction
import app.umbra.core.model.action.Environment
import app.umbra.core.model.action.GlobalAction
import app.umbra.core.model.action.ObserveLevel
import app.umbra.core.model.action.Operation
import app.umbra.core.model.action.Target
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class StepStatus { PENDING, RUNNING, PASSED, FAILED, SKIPPED }

data class StepResult(val id: String, val title: String, val status: StepStatus = StepStatus.PENDING, val detail: String = "", val durationMs: Long = 0)

private class Skip(message: String) : Exception(message)

/**
 * Exercises each capability for real on this phone: the screen is read, a shell command runs, an
 * app is opened, a background display is created and photographed, the Linux environment starts and
 * reaches the network, a model answers (spec §29 "capability detection", §31 "conformance"). Nothing
 * is simulated: a green step means the thing was done and its effect observed. It briefly presses Home
 * and opens Settings, so it is run on request, not automatically.
 */
class SelfTest(private val container: AppContainer) {
    private class Step(val id: String, val title: String, val run: suspend () -> String)

    private val mutable = MutableStateFlow<List<StepResult>>(emptyList())
    val results: StateFlow<List<StepResult>> = mutable.asStateFlow()

    @Volatile
    var running = false
        private set

    private fun request(op: Operation, display: Int? = null, timeoutMs: Long = 20_000) = ActionRequest(
        id = "selftest-${System.nanoTime()}", operation = op, target = Target(Environment.ANDROID, display = display),
        timeoutMs = timeoutMs, sessionId = "selftest",
    )

    private suspend fun act(op: Operation, display: Int? = null, timeoutMs: Long = 20_000): app.umbra.tools.ExecutionResult {
        val exec = container.pipeline.execute(request(op, display, timeoutMs))
        if (exec.result.status != ActionStatus.OK) throw IllegalStateException(exec.result.summary.take(300))
        return exec
    }

    private fun steps(): List<Step> = listOf(
        Step("shell", "Run a shell command") {
            val out = act(Operation.Shell("echo umbra-selftest && id -u")).result
            if ("umbra-selftest" !in out.summary) error("unexpected output: ${out.summary.take(120)}")
            "ran as ${out.backend ?: "a shell"}"
        },
        Step("observe", "Read the screen") {
            if (!container.accessibility.isAvailable()) throw Skip("the accessibility service is not running")
            val out = act(Operation.Observe(ObserveLevel.TREE)).result
            "read ${out.summary.lineSequence().count()} lines from ${out.backend}"
        },
        Step("screenshot", "Take a screenshot") {
            val exec = act(Operation.Screenshot())
            val image = exec.images.firstOrNull() ?: error("no image came back")
            "${image.bytes.size / 1024} KB ${image.mime}"
        },
        Step("launch", "Open an app and see it in front") {
            act(Operation.LaunchApp("com.android.settings"), timeoutMs = 30_000)
            val out = act(Operation.Observe(ObserveLevel.BRIEF)).result
            if (!out.summary.contains("settings", ignoreCase = true)) error("Settings did not come to the front: ${out.summary.take(160)}")
            act(Operation.Global(GlobalAction.HOME))
            "Settings opened and was seen"
        },
        Step("display", "Work in a background display") {
            if (!container.displays.isAvailable()) throw Skip("needs the privileged helper")
            val created = act(Operation.Display(DisplayAction.CREATE, width = 720, height = 1280, dpi = 320)).result
            val id = Regex("""(?:display|id)\D{0,12}(\d+)""", RegexOption.IGNORE_CASE).find(created.summary)?.groupValues?.get(1)?.toIntOrNull()
                ?: container.displays.ownedDisplays().firstOrNull()?.id ?: error("no display id in: ${created.summary.take(120)}")
            try {
                // A busy phone (or a slow emulator) can take a while to bring an app up on a new display.
                act(Operation.LaunchApp("com.android.settings"), display = id, timeoutMs = 90_000)
                val shot = act(Operation.Screenshot(), display = id)
                if (shot.images.isEmpty()) error("the background display produced no picture")
                "display $id showed Settings (${shot.images.first().bytes.size / 1024} KB)"
            } finally {
                runCatching { container.pipeline.execute(request(Operation.Display(DisplayAction.DESTROY, displayId = id))) }
            }
        },
        Step("linux", "Start the Linux environment") {
            if (container.guest.state.value == GuestManager.State.NoRoot) throw Skip("needs root")
            if (!container.guest.ensureReady()) throw Skip("the Linux environment is not installed")
            val versions = container.guest.runtime.versions() ?: error("it did not answer")
            "debian ${versions["debian"]}, bun ${versions["bun"]}, omp ${versions["omp"]}, git ${versions["git"]}"
        },
        Step("network", "Reach the internet from Linux") {
            if (!container.guest.ensureReady()) throw Skip("the Linux environment is not installed")
            val out = container.guest.runtime.exec(listOf("bash", "-c", "curl -sS -m 20 -o /dev/null -w '%{http_code}' https://deb.debian.org/debian/dists/trixie/Release"), timeoutMs = 40_000)
            if (out.stdout.trim() != "200") error("HTTP ${out.stdout.trim().ifEmpty { "none" }} ${out.stderr.take(120)}")
            "reached deb.debian.org"
        },
        Step("model", "Ask the AI model") {
            val prefs = container.settings.value
            val model = prefs.defaultModel ?: throw Skip("no model is chosen")
            if (!container.guest.ensureReady()) throw Skip("the Linux environment is not installed")
            val secrets = LinkedHashMap<String, String>()
            prefs.providers.forEach { p -> p.apiKeyEnv?.let { env -> container.secrets.get(env)?.let { secrets[env] = it } } }
            if (secrets.isEmpty() && prefs.providers.none { it.keyless }) throw Skip("no API key is saved")
            val reply = ModelProbe(container).ask(model.provider + "/" + model.modelId, secrets)
            "${model.provider}/${model.modelId}: “${reply.take(60)}”"
        },
        Step("logs", "Watch the system log") {
            val summary = container.logMonitor.summary()
            if (container.logMonitor.linesSeen == 0L && container.logMonitor.lastError != null) error(container.logMonitor.lastError!!)
            "${container.logMonitor.linesSeen} log lines seen; ${summary.crashes.size} crashes"
        },
    )

    /** Runs every step in order; steps that cannot apply here are skipped with the reason. */
    suspend fun run() {
        if (running) return
        running = true
        try {
            val list = steps()
            mutable.value = list.map { StepResult(it.id, it.title) }
            for (step in list) {
                update(step.id) { it.copy(status = StepStatus.RUNNING) }
                val started = System.currentTimeMillis()
                try {
                    val detail = step.run()
                    update(step.id) { it.copy(status = StepStatus.PASSED, detail = detail, durationMs = System.currentTimeMillis() - started) }
                } catch (e: Skip) {
                    update(step.id) { it.copy(status = StepStatus.SKIPPED, detail = e.message.orEmpty(), durationMs = System.currentTimeMillis() - started) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    update(step.id) { it.copy(status = StepStatus.FAILED, detail = e.message ?: e::class.simpleName.orEmpty(), durationMs = System.currentTimeMillis() - started) }
                }
            }
        } finally {
            running = false
        }
    }

    private fun update(id: String, change: (StepResult) -> StepResult) = mutable.update { list -> list.map { if (it.id == id) change(it) else it } }

    /** A plain-text report the user can copy into a bug report. */
    fun report(): String = results.value.joinToString("\n") { "${it.status.name.padEnd(7)} ${it.title}${if (it.detail.isNotBlank()) " — ${it.detail}" else ""}" }
}

/** One tiny, real model call through omp in the Linux environment: proves the key, the network and omp together. */
class ModelProbe(private val container: AppContainer) {
    suspend fun ask(selector: String, secrets: Map<String, String>): String {
        val prefs = container.settings.value
        val custom = prefs.providers.filter { it.custom && it.baseUrl != null }
        val agentFiles = container.guestAgentFiles("probe")
        if (custom.isNotEmpty()) {
            agentFiles.write("models.yml", app.umbra.runtime.omp.OmpModelsFile.toYaml(custom.map {
                app.umbra.runtime.omp.OmpProvider(
                    id = it.id, baseUrl = it.baseUrl!!, api = it.api, apiKeyEnv = it.apiKeyEnv?.takeIf { env -> env in secrets },
                    auth = if (it.keyless) "none" else null, models = it.models.map { m -> app.umbra.runtime.omp.OmpModel(m, inputImages = it.images) },
                )
            }))
        }
        val out = container.guest.runtime.execWithSecrets(
            argv = listOf("omp", "--print", "Reply with the single word OK.", "--model", selector, "--no-session", "--no-tools", "--no-extensions", "--no-skills", "--no-rules", "--no-lsp", "--no-title"),
            secrets = secrets,
            env = mapOf("PI_CODING_AGENT_DIR" to agentFiles.ompPath, "NO_COLOR" to "1", "OTEL_SDK_DISABLED" to "true"),
            cwd = "/home/agent",
            timeoutMs = 120_000,
        )
        if (!out.ok) error((out.stderr.ifBlank { out.stdout }).lineSequence().filter { it.isNotBlank() }.take(3).joinToString(" ").take(300).ifBlank { "omp exited with ${out.exitCode}" })
        return out.stdout.trim().ifBlank { error("the model returned nothing") }
    }
}
