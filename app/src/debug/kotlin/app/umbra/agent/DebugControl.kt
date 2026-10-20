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
        "notifications.list" -> buildJsonArray {
            container.notificationReader.records().forEach { record -> add(app.umbra.core.model.UmbraJson.encodeToJsonElement(app.umbra.agent.notifications.NotificationRecord.serializer(), record)) }
        }
        "notifications.policy" -> {
            val pkg = str(args, "package")
            (args["policy"] as? JsonPrimitive)?.content?.let { policy ->
                container.notificationReader.setPolicy(pkg, app.umbra.agent.notifications.NotificationPrivacy.Policy.valueOf(policy))
            }
            buildJsonObject { put("policy", container.notificationReader.policy(pkg).name) }
        }
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
        "git.credential.add" -> {
            // Development only: a test token for a local test server, entered the way the vault's dialog stores it.
            val c = container.gitCredentials.add(str(args, "label"), str(args, "url"), str(args, "user"),
                setOf(app.umbra.core.control.GitOperation.READ, app.umbra.core.control.GitOperation.PUBLISH), (args["ca"] as? JsonPrimitive)?.content)
            container.gitSecrets.put(app.umbra.agent.integration.gitSecretName(c.id), str(args, "token"))
            buildJsonObject { put("id", c.id); put("url", c.url) }
        }
        "guest.exec_root" -> {
            val argv = (args["argv"] as JsonArray).map { (it as JsonPrimitive).content }
            val out = container.guest.runtime.exec(argv, asRoot = true, timeoutMs = (args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 120_000, maxOutputBytes = 1024 * 1024)
            buildJsonObject { put("exit", out.exitCode); put("stdout", out.stdout.takeLast(4000)); put("stderr", out.stderr.takeLast(2000)) }
        }
        "test.provider.begin" -> {
            check(container.sessionStore.list().none { it.state.isActive }) { "A session is working" }
            val backup = File(container.context.filesDir, "debug-provider-backup.json")
            check(!backup.exists()) { "Restore the previous test configuration first" }
            val url = str(args, "base_url")
            require(url.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/v1")))
            backup.writeText(UmbraJson.encodeToString(app.umbra.agent.data.AppPrefs.serializer(), container.settings.value))
            val provider = ProviderConfig("umbra-test-agent", "Local agent verification", custom = true, baseUrl = url, api = "openai-completions", models = listOf("fake-model"), keyless = true)
            container.settings.update { it.copy(providers = listOf(provider), defaultModel = ModelRef(provider.id, "fake-model"), autoModel = false, autoEffort = false, thinkingLevel = "minimal") }
            buildJsonObject { put("ok", true) }
        }
        "test.provider.restore" -> {
            val backup = File(container.context.filesDir, "debug-provider-backup.json")
            val original = UmbraJson.decodeFromString(app.umbra.agent.data.AppPrefs.serializer(), backup.readText())
            container.settings.update { original }
            backup.delete()
            buildJsonObject { put("ok", true) }
        }
        "agent.create" -> {
            val p = container.agents.create(str(args, "name"), "a1", app.umbra.agent.agents.Templates.ASSISTANT, str(args, "soul"), model = ModelRef("umbra-test-agent", "fake-model"), effort = app.umbra.core.session.Effort("minimal"), channels = emptyList())
            buildJsonObject { put("id", p.id); put("session", p.sessionId.orEmpty()) }
        }
        "agent.talk" -> { container.agents.talk(str(args, "id"), str(args, "text")); buildJsonObject { put("ok", true) } }
        "agent.delete" -> { container.agents.delete(str(args, "id")); buildJsonObject { put("ok", true) } }
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
        // The redesign's own switches, for recordings and tests: the mode, first-run setup, the theme.
        "nodes.list" -> UmbraJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(app.umbra.core.control.PairedNode.serializer()), container.nodes.all.value.values.toList())
        "nodes.trust" -> {
            container.nodes.setTrust(str(args, "id"), app.umbra.core.control.NodeTrust.valueOf(str(args, "trust")))
            buildJsonObject { put("ok", true) }
        }
        "nodes.unpair" -> {
            val id = str(args, "id")
            container.nodeConnections.unpair(id)
            buildJsonObject { put("ok", true) }
        }
        "ui.set" -> {
            container.settings.update { p ->
                var q = p
                (args["mode"] as? JsonPrimitive)?.content?.let { q = q.copy(uiMode = app.umbra.agent.data.UiMode.valueOf(it.uppercase())) }
                (args["onboarded"] as? JsonPrimitive)?.content?.let { q = q.copy(onboarded = it == "true") }
                (args["providers"] as? JsonPrimitive)?.content?.takeIf { it == "none" }?.let { q = q.copy(providers = emptyList(), defaultModel = null) }
                // "auto", or one of omp's levels (low, medium, high...) for new tasks.
                (args["effort"] as? JsonPrimitive)?.content?.let { e -> q = if (e == "auto") q.copy(autoEffort = true) else q.copy(autoEffort = false, thinkingLevel = e) }
                q
            }
            val p = container.settings.value
            buildJsonObject { put("mode", p.uiMode.name); put("onboarded", p.onboarded); put("providers", p.providers.size); put("effort", if (p.autoEffort) "auto" else p.thinkingLevel.orEmpty()) }
        }
        "approval_mode" -> {
            container.settings.update { it.copy(approvalMode = app.umbra.core.safety.ApprovalMode.valueOf(str(args, "mode").uppercase())) }
            buildJsonObject { put("mode", container.settings.value.approvalMode.name) }
        }
        "workspace.create" -> {
            val ws = container.workspaces.create(str(args, "name"))
            buildJsonObject { put("id", ws.id); put("host_path", File(container.guest.layout.workspaces, "projects/${ws.id}").path) }
        }
        "session.draft" -> sessionJson(container.supervisor.create(str(args, "request")))
        "session.files.list" -> UmbraJson.encodeToJsonElement(ListSerializer(app.umbra.agent.data.SessionFile.serializer()), container.sessionFiles.items.value)
        "session.files.attach", "session.files.mount" -> {
            val uri = android.net.Uri.parse(str(args, "uri"))
            val git = (args["git"] as? JsonPrimitive)?.content != "false"
            val item = if (command.endsWith("mount")) container.sessionFiles.mountFolder(str(args, "id"), uri, git)
                else container.sessionFiles.attach(str(args, "id"), uri, git)
            UmbraJson.encodeToJsonElement(app.umbra.agent.data.SessionFile.serializer(), item)
        }
        "session.files.checkpoint" -> {
            container.sessionFiles.checkpointSession(container.sessionStore.get(str(args, "id")) ?: error("No session"))
            buildJsonObject { put("ok", true) }
        }
        "session.files.history", "session.files.recover", "session.files.disconnect" -> {
            val item = container.sessionFiles.items.value.first { it.id == str(args, "file") }
            when (command) {
                "session.files.recover" -> buildJsonObject { put("path", container.sessionFiles.recover(item, str(args, "checkpoint"))) }
                "session.files.disconnect" -> { container.sessionFiles.disconnect(item); buildJsonObject { put("ok", true) } }
                else -> JsonArray(container.sessionFiles.history(item).map { buildJsonObject { put("id", it.id); put("at", it.at); put("message", it.message) } })
            }
        }
        "session.create" -> sessionJson(container.supervisor.launch(str(args, "request"), workspaceId = (args["workspace"] as? JsonPrimitive)?.content))
        "session.submit" -> sessionJson(container.supervisor.submit(str(args, "request"), projectId = (args["project"] as? JsonPrimitive)?.content))
        "session.queue" -> buildJsonObject {
            put("queued", kotlinx.serialization.json.JsonArray(container.supervisor.queued().map { kotlinx.serialization.json.JsonPrimitive(it.id) }))
            put("reason", container.supervisor.waitingReason(null) ?: "")
            put("slots", container.governor.decision.value.maxConcurrentSessions)
        }
        "session.send" -> { container.supervisor.send(str(args, "id"), str(args, "text")); buildJsonObject { put("ok", true) } }
        "session.pause" -> { container.supervisor.pause(str(args, "id")); buildJsonObject { put("ok", true) } }
        "session.resume" -> { container.supervisor.resume(str(args, "id")); buildJsonObject { put("ok", true) } }
        "session.delete" -> { container.supervisor.delete(str(args, "id")); buildJsonObject { put("ok", true) } }
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
        put("id", s.id); put("title", s.title); put("state", s.state.name); put("kind", s.kind.name); put("activity", s.activity.orEmpty()); put("error", s.lastError ?: ""); put("workspace", s.workspaceId ?: "")
        put("plan", s.plan.size); put("omp_session_file", s.ompSessionFile ?: "")
        put("queued", s.queuedAtMs != null); put("project", s.projectId ?: "")
    }
}
