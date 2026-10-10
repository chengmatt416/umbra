package app.umbra.agent

import app.umbra.agent.data.ProviderConfig
import app.umbra.agent.data.ProviderPresets
import app.umbra.agent.ui.CapabilityProbe
import app.umbra.core.model.UmbraJson
import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.session.EventRecord
import app.umbra.core.session.ModelRef
import app.umbra.core.session.Session
import app.umbra.runtime.omp.AskAnswer
import app.umbra.runtime.omp.UiRequest
import app.umbra.runtime.omp.UiResponse
import app.umbra.tools.ControlHandler
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Session control over the debug tool bridge, so a development machine can drive the real app on a
 * real device or emulator the way the UI does (debug builds only). Nothing here has a release
 * counterpart: it is the test harness's hands, not a product feature.
 */
class DebugControl(private val container: AppContainer) : ControlHandler {
    override suspend fun handle(command: String, args: JsonObject): JsonElement = when (command) {
        "status" -> status()
        "capabilities" -> JsonArray(CapabilityProbe(container).probe().map { r ->
            buildJsonObject { put("id", r.id); put("title", r.title); put("status", r.status.name); put("detail", r.detail); put("essential", r.essential) }
        })
        "selftest.run" -> {
            val test = app.umbra.agent.data.SelfTest(container)
            test.run()
            JsonArray(test.results.value.map { r ->
                buildJsonObject { put("id", r.id); put("title", r.title); put("status", r.status.name); put("detail", r.detail); put("ms", r.durationMs) }
            })
        }
        "a11y.snapshot" -> try {
            val snapshot = container.accessibility.snapshot(app.umbra.platform.api.SnapshotOptions())
            buildJsonObject { put("windows", snapshot.windows.size); put("nodes", snapshot.windows.sumOf { it.nodes.size }); put("available", container.accessibility.isAvailable()) }
        } catch (e: Exception) {
            buildJsonObject { put("error", "${e::class.simpleName}: ${e.message}"); put("available", container.accessibility.isAvailable()) }
        }
        "a11y.tap" -> try {
            container.accessibility.tap(null, app.umbra.core.model.Point(str(args, "x").toInt(), str(args, "y").toInt()))
            buildJsonObject { put("tapped", true) }
        } catch (e: Exception) {
            buildJsonObject { put("error", "${e::class.simpleName}: ${e.message}") }
        }
        // A coroutine of the app's UI that ends with an uncaught BackendException must not take the app down (UmbraApp).
        "crash.backend" -> {
            container.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                throw app.umbra.platform.api.BackendException(app.umbra.core.model.action.ErrorCode.BACKEND_UNAVAILABLE, "provoked by the test harness")
            }
            buildJsonObject { put("launched", true) }
        }
        // Like the start of a task: finds the state, mounts an installed environment and brings its scripts up to date.
        "guest.status" -> { container.prepareAgentEnvironment(); buildJsonObject { put("state", container.guest.state.value.toString()); put("mode", container.guest.mode.name) } }
        "guest.info" -> {
            container.guest.refresh()
            buildJsonObject {
                put("state", container.guest.state.value.toString())
                put("mode", container.guest.mode.name)
                put("bundled", container.guest.bundle != null)
                put("update_available", container.guest.updateAvailable)
                put("packs", JsonObject(container.guest.packStates.value.mapValues { JsonPrimitive(it.value.toString()) }))
            }
        }
        "guest.install_file" -> {
            val file = File(str(args, "path"))
            check(file.canRead()) { "the app cannot read ${file.path}" }
            container.guest.installFileNow(file)
            container.guest.refresh()
            buildJsonObject { put("state", container.guest.state.value.toString()); put("mode", container.guest.mode.name); put("versions", JsonObject((container.guest.runtime.versions() ?: emptyMap()).mapValues { JsonPrimitive(it.value) })) }
        }
        // What the Set up screen's Install does with the environment the APK carries; waits until it is done.
        "guest.install_bundled" -> {
            container.guest.installBundledNow()
            container.guest.refresh()
            buildJsonObject { put("state", container.guest.state.value.toString()); put("mode", container.guest.mode.name); put("versions", JsonObject((container.guest.runtime.versions() ?: emptyMap()).mapValues { JsonPrimitive(it.value) })) }
        }
        "guest.install_packs" -> {
            val ids = (args["ids"] as JsonArray).map { (it as JsonPrimitive).content }
            container.guest.installPacksAndWait(ids)
            buildJsonObject { put("packs", JsonObject(container.guest.packStates.value.mapValues { JsonPrimitive(it.value.toString()) })) }
        }
        "guest.exec" -> {
            val argv = (args["argv"] as JsonArray).map { (it as JsonPrimitive).content }
            val out = container.guest.runtime.exec(argv, timeoutMs = (args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 120_000)
            buildJsonObject { put("exit", out.exitCode); put("stdout", out.stdout); put("stderr", out.stderr) }
        }
        "guest.exec_root" -> {
            val argv = (args["argv"] as JsonArray).map { (it as JsonPrimitive).content }
            val out = container.guest.runtime.exec(argv, asRoot = true, timeoutMs = (args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 120_000, maxOutputBytes = 1024 * 1024)
            buildJsonObject { put("exit", out.exitCode); put("stdout", out.stdout.takeLast(4000)); put("stderr", out.stderr.takeLast(2000)) }
        }
        "provider.set" -> {
            val preset = ProviderPresets.byId(str(args, "id"))
            val keyGiven = !(args["key"] as? JsonPrimitive)?.content.isNullOrBlank()
            val config = if (preset != null) ProviderConfig(preset.id, preset.label, apiKeyEnv = preset.keyEnv) else ProviderConfig(
                str(args, "id"), str(args, "id"),
                apiKeyEnv = if (keyGiven) "UMBRA_KEY_" + str(args, "id").uppercase().replace(Regex("[^A-Z0-9]"), "_") else null,
                custom = true, baseUrl = str(args, "base_url"), api = (args["api"] as? JsonPrimitive)?.content ?: "openai-completions",
                models = listOf(str(args, "model")), keyless = !keyGiven,
                images = (args["images"] as? JsonPrimitive)?.content == "true",
            )
            (args["key"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && config.apiKeyEnv != null }?.let { container.secrets.put(config.apiKeyEnv!!, it) }
            container.settings.update { p -> p.copy(providers = p.providers.filterNot { it.id == config.id } + config, defaultModel = ModelRef(config.id, str(args, "model"))) }
            buildJsonObject { put("ok", true) }
        }
        "capsule.set" -> {
            val on = (args["on"] as? JsonPrimitive)?.content != "false"
            container.settings.update { it.copy(overlayEnabled = on) }
            buildJsonObject { put("on", on) }
        }
        "approval_mode" -> {
            container.settings.update { it.copy(approvalMode = app.umbra.core.safety.ApprovalMode.valueOf(str(args, "mode").uppercase())) }
            buildJsonObject { put("mode", container.settings.value.approvalMode.name) }
        }
        "workspace.create" -> {
            val ws = container.workspaces.create(str(args, "name"))
            buildJsonObject { put("id", ws.id); put("host_path", File(container.guest.layout.workspaces, "projects/${ws.id}").path) }
        }
        "session.create" -> sessionJson(container.supervisor.launch(str(args, "request"), workspaceId = (args["workspace"] as? JsonPrimitive)?.content))
        "session.send" -> { container.supervisor.send(str(args, "id"), str(args, "text")); buildJsonObject { put("ok", true) } }
        "session.pause" -> { container.supervisor.pause(str(args, "id")); buildJsonObject { put("ok", true) } }
        "session.resume" -> { container.supervisor.resume(str(args, "id")); buildJsonObject { put("ok", true) } }
        "session.stop" -> { container.supervisor.stop(str(args, "id")); buildJsonObject { put("ok", true) } }
        "session.get" -> container.sessionStore.get(str(args, "id"))?.let(::sessionJson) ?: error("no such session")
        "contract.set" -> {
            container.contracts.set(str(args, "id"), (args["resume"] as? JsonPrimitive)?.content != "false", null, (args["allowed"] as? JsonPrimitive)?.content.orEmpty())
            buildJsonObject { put("ok", true) }
        }
        "contract.log" -> JsonArray(container.contracts.recent.value.map { e -> buildJsonObject { put("kind", e.kind); put("data", e.data.toString()) } })
        "terminal.open" -> {
            val t = container.terminals.open(str(args, "title"), (args["cwd"] as? JsonPrimitive)?.content ?: "/home/agent")
            buildJsonObject { put("id", t.id); put("epoch", t.serverEpoch) }
        }
        "terminal.send" -> buildJsonObject { put("result", container.terminals.sendText(str(args, "id"), str(args, "text"), app.umbra.core.control.Principal.User).toString()) }
        "terminal.screen" -> {
            val screen = kotlinx.coroutines.withTimeoutOrNull(10_000) { container.terminals.screen(str(args, "id")).first { it != null } }
            buildJsonObject { put("text", screen?.plainText.orEmpty()); put("dead", screen?.dead ?: false) }
        }
        "terminal.list" -> {
            container.terminals.reconcile()
            JsonArray(container.terminals.terminals.value.map { t -> buildJsonObject { put("id", t.record.id); put("status", t.status.name); put("epoch", t.record.serverEpoch) } })
        }
        "session.list" -> JsonArray(container.sessionStore.list().map(::sessionJson))
        "session.events" -> {
            val after = (args["after"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            UmbraJson.encodeToJsonElement(ListSerializer(EventRecord.serializer()), container.sessionStore.events(str(args, "id"), after, 500))
        }
        "approvals.list" -> JsonArray(container.approvals.pending.value.map { r ->
            buildJsonObject { put("id", r.id); put("session", r.sessionId); put("source", r.source.name); put("description", r.description); put("level", r.assessment.level.name) }
        })
        "approvals.resolve" -> {
            val decision = if (str(args, "decision") == "deny") ApprovalDecision.Denied("denied by the test harness") else ApprovalDecision.Approved()
            buildJsonObject { put("resolved", container.approvals.resolve(str(args, "id"), decision)) }
        }
        "questions.list" -> JsonArray(container.questions.pending.value.map { q ->
            buildJsonObject { put("id", q.request.id); put("session", q.sessionId); put("kind", q.request::class.simpleName.orEmpty()) }
        })
        "questions.answer" -> {
            val id = str(args, "id")
            val pending = container.questions.pending.value.firstOrNull { it.request.id == id } ?: error("no such question")
            val response = when (val r = pending.request) {
                is UiRequest.Select -> UiResponse.Value(str(args, "value"))
                is UiRequest.Confirm -> UiResponse.Confirmed(str(args, "value") == "yes")
                is UiRequest.Input, is UiRequest.Editor -> UiResponse.Value(str(args, "value"))
                is UiRequest.Ask -> UiResponse.Answers(r.questions.map { AskAnswer(it.id, listOf(str(args, "value"))) })
            }
            buildJsonObject { put("answered", container.questions.answer(id, response)) }
        }
        else -> error("unknown control command '$command'")
    }

    private fun str(args: JsonObject, key: String): String = (args[key] as? JsonPrimitive)?.content ?: error("missing '$key'")

    private suspend fun status(): JsonElement = buildJsonObject {
        put("accessibility", container.accessibility.isAvailable())
        put("root", container.rootShell.isAvailable())
        put("helper", container.helper.client.isUp())
        put("guest", container.guest.state.value.toString())
        put("providers", buildJsonArray { container.settings.value.providers.forEach { add(JsonPrimitive(it.id)) } })
        put("model", container.settings.value.defaultModel?.let { "${it.provider}/${it.modelId}" } ?: "")
        put("sessions", container.sessionStore.list().size)
        put("pid", android.os.Process.myPid())
    }

    private fun sessionJson(s: Session): JsonObject = buildJsonObject {
        put("id", s.id); put("title", s.title); put("state", s.state.name); put("error", s.lastError ?: ""); put("workspace", s.workspaceId ?: "")
        put("plan", s.plan.size); put("omp_session_file", s.ompSessionFile ?: "")
    }
}
