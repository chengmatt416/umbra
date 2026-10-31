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
import kotlinx.serialization.json.*
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
    private val handoffFixtures = container.context.getSharedPreferences("debug-handoff-fixtures", 0)
    /** The `:laya` process the laya.probe.* commands drive. */
    @Volatile private var layaProbe: app.umbra.agent.data.laya.LayaProcess? = null
    override suspend fun handle(command: String, args: JsonObject): JsonElement = when (command) {
        "status" -> status()
        "test.watchers.create" -> {
            check(container.telegram.state.value.userId == null) { "Disconnect Telegram before running this local fixture" }
            val p = container.agents.create("Watchers QA ${System.nanoTime()}", "a1", app.umbra.agent.agents.Templates.BLANK, "Synthetic watcher fixture only; no external messages.",
                model = ModelRef("umbra-test-agent", "fake-model"), effort = app.umbra.core.session.Effort("minimal"), channels = emptyList(), running = false)
            val now = System.currentTimeMillis()
            container.agents.store.update(p.id) { it.copy(running = true, proactivityLevel = 2, morningBriefMinute = null, eveningBriefMinute = null) }
            container.agents.store.setState(p.id, container.agents.store.state(p.id).copy(lastWakeMs = now))
            container.agents.proactive.store.saveWatcher(app.umbra.agent.agents.proactive.AgentWatcher("qa-watcher", p.id, "Synthetic watcher",
                app.umbra.agent.agents.proactive.ProbeSpec.NotificationPattern("app.umbra.fixture.unused", "Synthetic.*", literal = false),
                app.umbra.agent.agents.proactive.ConditionSpec.Contains("Synthetic"), onMatchInstruction = "Synthetic fixture", triggerOnce = false, enabled = true, authorizedAtMs = now))
            buildJsonObject { put("agent", p.id) }
        }
        "test.watchers.state", "test.watchers.dispatch", "test.watchers.revoke", "test.watchers.renew", "test.watchers.cleanup" -> {
            val id = str(args, "agent")
            check(container.agents.store.get(id)?.name?.startsWith("Watchers QA ") == true)
            val runtime = container.agents.proactive
            val store = runtime.store
            val watcher = store.watchers(id).single()
            var created: String? = null
            when (command) {
                "test.watchers.dispatch" -> created = runtime.discovery(requireNotNull(container.agents.store.get(id)),
                    "[Watcher qa-watcher] Synthetic watcher fixture only. Save a private note with note_write then report completion.", watcher).id
                "test.watchers.revoke" -> store.saveWatcher(watcher.copy(enabled = false, authorizedAtMs = null))
                "test.watchers.renew" -> store.saveWatcher(watcher.copy(enabled = true, authorizedAtMs = System.currentTimeMillis()))
                "test.watchers.cleanup" -> {
                    container.agents.setRunning(id, false)
                    container.sessionStore.list().filter { it.agentId == id && it.id != container.agents.store.get(id)?.sessionId }.forEach { container.supervisor.stop(it.id); container.supervisor.delete(it.id) }
                    container.agents.delete(id)
                }
            }
            buildJsonObject {
                created?.let { put("created", it) }
                put("note", store.note(id, "qa-watcher"))
                put("watchers", JsonArray(store.watchers(id).map { UmbraJson.encodeToJsonElement(app.umbra.agent.agents.proactive.AgentWatcher.serializer(), it) }))
                put("tasks", JsonArray(container.sessionStore.list().filter { it.agentId == id }.map { UmbraJson.encodeToJsonElement(Session.serializer(), it) }))
            }
        }
        "test.goals.create" -> {
            check(container.telegram.state.value.userId == null) { "Disconnect Telegram before running this local fixture" }
            val p = container.agents.create("Goals QA ${System.nanoTime()}", "a1", app.umbra.agent.agents.Templates.BLANK, "Synthetic goal fixture only; no external messages.",
                model = ModelRef("umbra-test-agent", "fake-model"), effort = app.umbra.core.session.Effort("minimal"), channels = emptyList(), running = false)
            val now = System.currentTimeMillis()
            container.agents.store.update(p.id) { it.copy(running = true, proactivityLevel = 4, morningBriefMinute = null, eveningBriefMinute = null) }
            container.agents.store.setState(p.id, container.agents.store.state(p.id).copy(lastWakeMs = now))
            val store = container.agents.proactive.store
            val goal = store.saveGoal(app.umbra.agent.agents.proactive.AgentGoal("qa-goal", p.id, "Synthetic goal", "Complete only the fixture milestones.",
                acceptanceCriteria = listOf("Both milestone checks pass"), createdAtMs = now, lastCheckedAtMs = now,
                milestones = listOf(app.umbra.agent.agents.proactive.GoalMilestone("first", "First fixture", updatedAtMs = now),
                    app.umbra.agent.agents.proactive.GoalMilestone("second", "Second fixture", dependsOn = listOf("first"), updatedAtMs = now))))
            buildJsonObject { put("agent", p.id); put("goal", goal.id) }
        }
        "test.goals.state", "test.goals.authorize", "test.goals.replan", "test.goals.cleanup" -> {
            val id = str(args, "agent")
            check(container.agents.store.get(id)?.name?.startsWith("Goals QA ") == true)
            val runtime = container.agents.proactive
            val store = runtime.store
            when (command) {
                "test.goals.authorize" -> runtime.authorizeGoal(id, "qa-goal", (args["enabled"] as? JsonPrimitive)?.content == "true")
                "test.goals.replan" -> {
                    val goal = store.goals(id).single()
                    val now = System.currentTimeMillis()
                    runtime.replanGoal(goal, goal.copy(state = app.umbra.agent.agents.proactive.GoalState.IN_PROGRESS,
                        milestones = goal.milestones.filter { it.status == "done" } + app.umbra.agent.agents.proactive.GoalMilestone("revised", "Revised fixture", dependsOn = listOf("first"), updatedAtMs = now),
                        replanHistory = goal.replanHistory + app.umbra.agent.agents.proactive.ReplanRecord(now, "fixture", "Keep verified work and replace unfinished work")))
                }
                "test.goals.cleanup" -> {
                    container.agents.setRunning(id, false)
                    container.sessionStore.list().filter { it.agentId == id && it.id != container.agents.store.get(id)?.sessionId }.forEach { container.supervisor.stop(it.id); container.supervisor.delete(it.id) }
                    container.agents.delete(id)
                }
            }
            buildJsonObject {
                put("goals", JsonArray(store.goals(id).map { UmbraJson.encodeToJsonElement(app.umbra.agent.agents.proactive.AgentGoal.serializer(), it) }))
                put("cards", JsonArray(store.opportunities(id).map { UmbraJson.encodeToJsonElement(app.umbra.agent.agents.proactive.OpportunityCard.serializer(), it) }))
                put("attention", JsonArray(store.attention(id).map { UmbraJson.encodeToJsonElement(app.umbra.agent.agents.proactive.AttentionItem.serializer(), it) }))
                put("tasks", JsonArray(container.sessionStore.list().filter { it.agentId == id }.map { UmbraJson.encodeToJsonElement(Session.serializer(), it) }))
            }
        }
        "test.suggestions.create" -> {
            val p = container.agents.create("Capsule QA ${System.nanoTime()}", "a1", app.umbra.agent.agents.Templates.BLANK, "Synthetic capsule suggestions fixture",
                model = ModelRef("umbra-test-agent", "fake-model"), channels = emptyList(), running = false)
            container.agents.store.update(p.id) { it.copy(running = true, proactivityLevel = 0, morningBriefMinute = null, eveningBriefMinute = null) }
            val store = container.agents.proactive.store
            val now = System.currentTimeMillis()
            for ((id, title, tier) in listOf(Triple("qa-ambient", "Synthetic ambient suggestion", app.umbra.agent.agents.proactive.DeliveryTier.CAPSULE_AMBIENT),
                Triple("qa-quiet", "Synthetic quiet suggestion", app.umbra.agent.agents.proactive.DeliveryTier.BRIEF_QUEUE))) {
                val card = app.umbra.agent.agents.proactive.OpportunityCard(id, p.id, app.umbra.agent.agents.proactive.OpportunityCard.Kind.FOLLOW_UP,
                    title, "Synthetic prepared step; viewing does not execute it.", listOf("fixture"), .7, .9,
                    app.umbra.agent.agents.proactive.PreparedNextStep("Review synthetic step", "agent_turn", "{\"instruction\":\"Do not execute from this fixture\"}"), createdAtMs = now,
                    expiresAtMs = if (id == "qa-ambient") now + 120_000 else null)
                store.saveOpportunity(card)
                store.stage(p.id, app.umbra.agent.agents.proactive.AttentionItem(id, title, card.summary, .7, .7, .9, "fixture", now, tier = tier))
            }
            buildJsonObject {
                put("agent", p.id); put("expanded", container.capsule.expanded.value); put("tucked", container.capsule.tucked.value)
                container.capsule.expanded.value = false; container.capsule.tucked.value = false
            }
        }
        "test.suggestions.state", "test.suggestions.targets", "test.suggestions.expireSoon", "test.suggestions.cleanup" -> {
            val id = str(args, "agent")
            check(container.agents.store.get(id)?.name?.startsWith("Capsule QA ") == true)
            if (command == "test.suggestions.expireSoon") {
                val store = container.agents.proactive.store
                val card = store.opportunities(id).first { it.id == "qa-ambient" }
                store.saveOpportunity(card.copy(expiresAtMs = System.currentTimeMillis() + 5_000))
            }
            if (command == "test.suggestions.cleanup") {
                container.agents.delete(id)
                container.capsule.expanded.value = (args["expanded"] as? JsonPrimitive)?.content == "true"
                container.capsule.tucked.value = (args["tucked"] as? JsonPrimitive)?.content == "true"
            }
            buildJsonObject {
                val sessions = container.sessionStore.list().filter { it.agentId == id }
                put("sessions", sessions.size); put("active", sessions.any { it.state.isActive })
                put("started", sessions.any { it.state != app.umbra.core.session.SessionState.CREATED || it.queuedAtMs != null })
                if (command == "test.suggestions.targets") {
                    container.capsule.debugTapPoint?.let { put("pill", JsonArray(listOf(it.x, it.y).map(::JsonPrimitive))) }
                    container.capsule.debugSuggestionTargets.filterKeys { it in setOf("qa-ambient", "qa-quiet") }.forEach { (key, point) ->
                        put(key, JsonArray(listOf(point.x, point.y).map(::JsonPrimitive)))
                    }
                }
            }
        }
        "test.note_ui.seed" -> {
            check(container.settings.value.providers.isEmpty() && container.telegram.state.value.userId == null && !container.settings.value.shareUsageCounts) { "Use only a provider-free, unlinked QA app with usage counts off" }
            val id = str(args, "agent")
            val p = requireNotNull(container.agents.store.get(id))
            check(p.name == "Documents QA fixture")
            container.agents.store.update(id) { it.copy(name = "Documents QA navigation", running = true, proactivityLevel = 0, morningBriefMinute = null, eveningBriefMinute = null) }
            val now = System.currentTimeMillis()
            val store = container.agents.proactive.store
            store.saveOpportunity(app.umbra.agent.agents.proactive.OpportunityCard("qa-note-ui", id, app.umbra.agent.agents.proactive.OpportunityCard.Kind.FOLLOW_UP,
                "UI suggestion fixture", "Synthetic review only", listOf("fixture"), .6, .9,
                app.umbra.agent.agents.proactive.PreparedNextStep("Review UI instruction", "agent_turn", "{\"instruction\":\"Synthetic original instruction\",\"note\":\"preserve\"}"), createdAtMs = now))
            store.saveBrief(id, app.umbra.agent.agents.proactive.DailyBrief("qa-note-ui", java.time.LocalDate.now().toString(), app.umbra.agent.agents.proactive.DailyBrief.Kind.MORNING,
                "UI brief fixture", generatedAtMs = now))
            val child = container.supervisor.create("Synthetic fixture only", title = "UI child task", kind = app.umbra.core.session.SessionKind.CODING, agentId = id, spawnedBy = p.sessionId)
            val grandchild = container.supervisor.create("Synthetic fixture only", title = "UI nested task", kind = app.umbra.core.session.SessionKind.SUBAGENT, spawnedBy = child.id)
            for (task in listOf(child, grandchild)) container.sessionStore.update(task.id) { it.copy(state = app.umbra.core.session.SessionState.PAUSED,
                attachments = it.attachments + ("qa.note_ui.owner" to JsonPrimitive(id))) }
            buildJsonObject { put("child", child.id); put("nested", grandchild.id) }
        }
        "test.note_ui.state", "test.note_ui.cleanup" -> {
            val id = str(args, "agent")
            check(container.agents.store.get(id)?.name == "Documents QA navigation")
            val tasks = container.sessionStore.list().filter { it.agentId == id || it.attachments["qa.note_ui.owner"] == JsonPrimitive(id) }
            if (command == "test.note_ui.cleanup") {
                for (task in tasks) { container.supervisor.stop(task.id); container.supervisor.delete(task.id) }
                container.agents.delete(id)
            }
            buildJsonObject {
                put("tasks", JsonArray(tasks.map { UmbraJson.encodeToJsonElement(Session.serializer(), it) }))
                put("focus", container.ui.focusedSession.value)
                put("usageCounts", container.settings.value.shareUsageCounts)
            }
        }
        "test.documents.create" -> {
            val p = container.agents.create("Documents QA fixture", "a1", app.umbra.agent.agents.Templates.BLANK, "Synthetic document context fixture", channels = emptyList(), running = false)
            buildJsonObject { put("agent", p.id) }
        }
        "test.documents.blocked_create" -> {
            val profiles = container.agents.profiles().map { it.id }.toSet()
            val sessions = container.sessionStore.list().map { it.id }.toSet()
            val result = runCatching { container.agents.create("Documents QA 4242424242424242", "a1", app.umbra.agent.agents.Templates.BLANK,
                "Synthetic document context fixture", channels = emptyList(), running = false) }
            buildJsonObject {
                put("blocked", result.exceptionOrNull() is IllegalArgumentException)
                put("profilesUnchanged", profiles == container.agents.profiles().map { it.id }.toSet())
                put("sessionsUnchanged", sessions == container.sessionStore.list().map { it.id }.toSet())
            }
        }
        "test.documents.legacy" -> {
            val id = "ag" + java.util.UUID.randomUUID().toString().replace("-", "")
            container.agents.store.save(app.umbra.agent.agents.AgentProfile(id, "Documents QA 4242424242424242",
                running = true, sessionId = null, createdAtMs = System.currentTimeMillis(), channels = emptyList(), proactivityLevel = 0))
            buildJsonObject { put("agent", id) }
        }
        "test.documents.query", "test.documents.state", "test.documents.revoke", "test.documents.cleanup" -> {
            val id = str(args, "agent")
            check(container.agents.store.get(id)?.name?.startsWith("Documents QA ") == true)
            when (command) {
                "test.documents.query" -> {
                    container.agents.proactive.store.grants(id).find { it.connectorId == "drive_files" && it.enabled }?.let {
                        check(it.endpoint?.contains("UmbraQADocuments") == true) { "Choose only the synthetic fixture folder" }
                    }
                    val scope = if ((args["restricted"] as? JsonPrimitive)?.content == "true") emptyList() else listOf("drive_files")
                    val result = kotlinx.coroutines.withContext(app.umbra.core.orchestrator.ToolAuthority("documents-qa", id, scope)) {
                        container.personalContext.resolve(id, "research invoice", setOf("documents"), 3650, "document")
                    }
                    JsonArray(result.map { UmbraJson.encodeToJsonElement(app.umbra.agent.agents.proactive.ContextEvidence.serializer(), it) })
                }
                "test.documents.revoke" -> { container.personalContext.revoke(id, "drive_files", true); buildJsonObject { put("ok", true) } }
                "test.documents.cleanup" -> {
                    container.agents.proactive.store.grants(id).find { it.connectorId == "drive_files" }?.endpoint
                        ?.takeIf { it.contains("UmbraQADocuments") }?.let { uri ->
                            container.context.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    container.agents.delete(id)
                    buildJsonObject { put("ok", true) }
                }
                else -> buildJsonObject {
                    val profile = requireNotNull(container.agents.store.get(id))
                    put("running", profile.running); put("hasSession", profile.sessionId != null)
                    val grant = container.agents.proactive.store.grants(id).find { it.connectorId == "drive_files" && it.enabled }
                    put("connected", grant != null)
                    put("fixtureTree", grant?.endpoint?.contains("UmbraQADocuments") == true)
                    put("persistentRead", grant?.endpoint?.let { endpoint -> container.context.contentResolver.persistedUriPermissions.any { it.uri.toString() == endpoint && it.isReadPermission } } == true)
                    put("persistentWrite", grant?.endpoint?.let { endpoint -> container.context.contentResolver.persistedUriPermissions.any { it.uri.toString() == endpoint && it.isWritePermission } } == true)
                }
            }
        }
        "test.handoff.create" -> {
            val session = container.supervisor.create("Synthetic native credential panel verification", title = "Credential QA ${java.util.UUID.randomUUID()}", model = ModelRef("umbra-test-agent", "fake-model"))
            container.sessionStore.update(session.id) { it.copy(state = app.umbra.core.session.SessionState.RUNNING) }
            check(handoffFixtures.edit().putBoolean(session.id, true).commit())
            buildJsonObject { put("id", session.id) }
        }
        "test.handoff.request", "test.handoff.state", "test.handoff.stop", "test.handoff.deleteSource", "test.handoff.cleanup", "test.handoff.ui", "test.handoff.field" -> {
            val id = str(args, "id")
            val session = container.sessionStore.get(id)
            check(handoffFixtures.getBoolean(id, false) && (session == null || session.title.startsWith("Credential QA ")))
            when (command) {
                "test.handoff.request" -> {
                    requireNotNull(session)
                    val snapshot = container.accessibility.snapshot(app.umbra.platform.api.SnapshotOptions(displayId = 0))
                    val field = snapshot.windows.flatMap { it.nodes }.first { it.packageName == "app.umbra.fixture.crashylogin" && it.isPassword && it.isEditable }
                    val link = container.credentialHandoff.request(session.id, "Synthetic native login", field.id, "Verify the local panel without a real account")
                    buildJsonObject {
                        put("link", link)
                        val username = snapshot.windows.flatMap { it.nodes }.first { it.packageName == field.packageName && it.isEditable && !it.isPassword }
                        put("username", JsonArray(listOf(username.bounds.centerX, username.bounds.centerY).map(::JsonPrimitive)))
                    }
                }
                "test.handoff.ui" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    buildJsonObject { app.umbra.agent.credentials.CredentialHandoffActivity.panelBounds().forEach { (key, value) -> put(key, JsonArray(value.map(::JsonPrimitive))) } }
                }
                "test.handoff.field" -> {
                    val snapshot = container.accessibility.snapshot(app.umbra.platform.api.SnapshotOptions(displayId = 0))
                    val field = snapshot.windows.flatMap { it.nodes }.first { it.packageName == "app.umbra.fixture.crashylogin" && it.isPassword && it.isEditable }
                    JsonArray(listOf(field.bounds.centerX, field.bounds.centerY).map(::JsonPrimitive))
                }
                else -> {
                    if (session != null && command in setOf("test.handoff.stop", "test.handoff.cleanup", "test.handoff.deleteSource")) container.supervisor.stop(id)
                    if (command == "test.handoff.deleteSource") container.supervisor.delete(id)
                    if (command == "test.handoff.cleanup") {
                        container.credentialHandoff.pending.value?.takeIf { it.sessionId == id }?.let { container.credentialHandoff.finish(it.id, clearField = true) }
                        if (session != null) container.supervisor.delete(id)
                        check(handoffFixtures.edit().remove(id).commit())
                    }
                    buildJsonObject {
                        val request = container.credentialHandoff.pending.value?.takeIf { it.sessionId == id }
                        put("pending", request != null)
                        request?.let { put("request", it.id); put("state", it.state); put("linkUsed", it.linkUsed) }
                        if (app.umbra.platform.android.UmbraAccessibilityService.connected.value)
                            request?.let { put("fieldClear", container.accessibility.credentialFieldClear(it.target, clear = false)) }
                        val value = "NativeQaCredential7"
                        put("protected", value in container.credentialHandoff.protectedValues())
                        put("timelineClean", !UmbraJson.encodeToString(ListSerializer(EventRecord.serializer()), container.sessionStore.events(id)).contains(value))
                    }
                }
            }
        }
        "test.transform.run" -> try {
            buildJsonObject { put("result", container.transformSandbox.run(str(args, "source"), args["input"] ?: JsonObject(emptyMap()))) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { buildJsonObject { put("error", e.message ?: "Transform failed") } }
        // Demonstration data for screen recordings (website clips): realistic goals, suggestions, a brief, a watcher and a
        // routine for one agent. Nothing here is enabled or authorized, so seeding runs no work by itself.
        "demo.proactive.seed" -> {
            val id = str(args, "agent")
            val zh = (args["lang"] as? JsonPrimitive)?.content == "zh"
            fun t(en: String, tw: String) = if (zh) tw else en
            val store = container.agents.proactive.store
            val now = System.currentTimeMillis()
            val hour = 3_600_000L
            val existing = store.goals(id).map { it.id }.toSet()
            if ("demo-kyoto" !in existing) store.saveGoal(app.umbra.agent.agents.proactive.AgentGoal("demo-kyoto", id, t("Plan the Kyoto trip in November", "規劃 11 月京都旅行"),
                t("Four nights, two people, under NT$60,000 in total. Quiet ryokan, one day in Nara.", "兩人四晚，總預算 NT$60,000 以內。安靜的旅館，其中一天去奈良。"),
                acceptanceCriteria = listOf(t("Flights and stay within budget", "機票與住宿在預算內"), t("Day-by-day plan you approved", "你確認過的每日行程")),
                milestones = listOf(app.umbra.agent.agents.proactive.GoalMilestone("m1", t("Compare flights TPE → KIX", "比較台北 → 關西機票"), "done", updatedAtMs = now - 20 * hour),
                    app.umbra.agent.agents.proactive.GoalMilestone("m2", t("Shortlist ryokans under ¥25,000", "篩選 ¥25,000 以下的旅館"), "done", updatedAtMs = now - 6 * hour),
                    app.umbra.agent.agents.proactive.GoalMilestone("m3", t("Draft the day-by-day plan", "擬定每日行程"), "active", updatedAtMs = now - hour),
                    app.umbra.agent.agents.proactive.GoalMilestone("m4", t("Book after your OK", "你確認後再預訂"), "pending", dependsOn = listOf("m3"), updatedAtMs = now)),
                replanHistory = listOf(app.umbra.agent.agents.proactive.ReplanRecord(now - 3 * hour, "new_evidence", t("Peach moved the 07:10 flight to 09:40; day 1 now starts at Fushimi Inari.", "樂桃把 07:10 班機改到 09:40，第一天改從伏見稻荷開始。"))),
                createdAtMs = now - 48 * hour, lastProgressAtMs = now - hour))
            if ("demo-crash" !in existing) store.saveGoal(app.umbra.agent.agents.proactive.AgentGoal("demo-crash", id, t("Fix the login crash reports", "修好登入閃退回報"),
                t("Users on Android 15 report a crash right after sign-in.", "Android 15 使用者回報登入後立即閃退。"),
                milestones = listOf(app.umbra.agent.agents.proactive.GoalMilestone("c1", t("Reproduce on the emulator", "在模擬器重現"), "done", updatedAtMs = now - 30 * hour),
                    app.umbra.agent.agents.proactive.GoalMilestone("c2", t("Patch and test in a coding session", "在 Coding session 修補並測試"), "done", updatedAtMs = now - 10 * hour),
                    app.umbra.agent.agents.proactive.GoalMilestone("c3", t("Open the pull request", "開 Pull Request"), "done", updatedAtMs = now - 9 * hour)),
                acceptanceCriteria = listOf(t("No crash on sign-in in the test run", "測試中登入不再閃退")), deliverables = listOf("PR #212"),
                state = app.umbra.agent.agents.proactive.GoalState.COMPLETED, createdAtMs = now - 72 * hour, lastProgressAtMs = now - 9 * hour))
            val conflict = app.umbra.agent.agents.proactive.OpportunityCard("demo-conflict", id, app.umbra.agent.agents.proactive.OpportunityCard.Kind.CONFLICT_ALERT,
                t("Dentist at 14:00 overlaps the client call at 14:30", "14:00 牙醫與 14:30 客戶通話重疊"),
                t("The clinic is 25 minutes from home. I drafted a reply to move the call to 16:00.", "診所離家 25 分鐘。我擬好了把通話改到 16:00 的回覆。"),
                listOf("calendar:demo-dentist", "calendar:demo-acme", "whatsapp:acme"), .9, .9,
                app.umbra.agent.agents.proactive.PreparedNextStep(t("Review the reschedule draft", "檢視改期草稿"), "send_message_draft", "{\"to\":\"Acme · WhatsApp\",\"text\":\"${t("Could we move our call to 16:00 today? Something came up at 14:00.", "今天的通話可以改到 16:00 嗎？我 14:00 臨時有事。")}\"}",
                    listOf("16:00", "17:30")), createdAtMs = now - 20 * 60_000,
                explain = app.umbra.agent.agents.proactive.CardExplanation(
                    why = t("Your dentist is at 14:00 on Ren'ai Rd, 25 minutes from home, so you can't make the 14:30 call with Acme.", "你 14:00 在仁愛路看牙醫，離家 25 分鐘，趕不上 14:30 和 Acme 的通話。"),
                    steps = listOf(t("Ask Acme on WhatsApp to move the call to 16:00", "在 WhatsApp 請 Acme 把通話改到 16:00"), t("Update your calendar once they agree", "對方同意後更新你的行事曆")),
                    needs = t("Check the wording of the message, or pick 17:30 instead.", "看一下訊息的措辭，或改選 17:30。"),
                    ifIgnored = t("Both stay on your calendar and you'll be late for the call.", "兩個行程都會保留，你會晚到這通電話。"),
                    sources = listOf(t("Calendar · Dentist, today 14:00", "行事曆 · 牙醫，今天 14:00"), t("Calendar · Client call, today 14:30", "行事曆 · 客戶通話，今天 14:30"))))
            val follow = app.umbra.agent.agents.proactive.OpportunityCard("demo-follow", id, app.umbra.agent.agents.proactive.OpportunityCard.Kind.FOLLOW_UP,
                t("Lena asked for the Q4 slides twice", "Lena 已經兩次要 Q4 簡報"),
                t("Last asked yesterday at 18:12. The deck is ready in your Q4 project. I can send it after you look.", "最後一次是昨天 18:12。簡報已在你的 Q4 專案裡，你看過後我可以傳給她。"),
                listOf("telegram"), .6, .85, app.umbra.agent.agents.proactive.PreparedNextStep(t("Prepare the reply", "準備回覆"), "agent_turn", "{\"instruction\":\"Prepare a reply to Lena with the Q4 deck\"}"), createdAtMs = now - 2 * hour,
                explain = app.umbra.agent.agents.proactive.CardExplanation(
                    why = t("Lena asked for the Q4 slides yesterday and again this morning; she presents them on Friday.", "Lena 昨天和今天早上都在要 Q4 簡報，她星期五要報告。"),
                    steps = listOf(t("Write a short reply to Lena with the Q4 deck attached", "寫一則附上 Q4 簡報的簡短回覆給 Lena"), t("Show you the reply before anything is sent", "傳送前先給你看回覆")),
                    ifIgnored = t("Lena keeps waiting for the slides.", "Lena 會繼續等這份簡報。"),
                    sources = listOf(t("Telegram · Lena, yesterday 18:12", "Telegram · Lena，昨天 18:12"))))
            val price = app.umbra.agent.agents.proactive.OpportunityCard("demo-price", id, app.umbra.agent.agents.proactive.OpportunityCard.Kind.PRICE_OR_MONITOR,
                t("Ryokan in Higashiyama dropped to ¥23,800", "東山的旅館降到 ¥23,800"),
                t("Your watcher matched at 07:55. Free cancellation until 3 days before.", "監測器在 07:55 觸發，入住前 3 天可免費取消。"),
                listOf("watcher:ryokan"), .5, .8, app.umbra.agent.agents.proactive.PreparedNextStep(t("Add it to the Kyoto plan", "加入京都行程"), "agent_turn", "{\"instruction\":\"Add the Higashiyama ryokan to the Kyoto goal shortlist\"}"), createdAtMs = now - 3 * hour,
                explain = app.umbra.agent.agents.proactive.CardExplanation(
                    why = t("The quiet ryokan in Higashiyama you were watching is now ¥23,800 a night, under your ¥25,000 limit.", "你在留意的東山安靜旅館降到每晚 ¥23,800，低於你設的 ¥25,000。"),
                    steps = listOf(t("Add it to the Kyoto trip's shortlist", "把它加進京都行程的候選清單"), t("Compare it with the two places already there", "和清單上原有的兩間比較")),
                    ifIgnored = t("The price may go back up; free cancellation ends 3 days before check-in.", "價格可能回漲；入住前 3 天就不能免費取消。"),
                    sources = listOf(t("Watching “Ryokan under ¥25,000” · today 07:55", "留意「旅館低於 ¥25,000」· 今天 07:55"))))
            val refund = app.umbra.agent.agents.proactive.OpportunityCard("demo-refund", id, app.umbra.agent.agents.proactive.OpportunityCard.Kind.TASK_DISCOVERY,
                t("Two Shopee orders look late", "兩筆蝦皮訂單似乎延遲"),
                t("Both were due yesterday. I can check their delivery status and whether a refund is needed.", "兩筆都應在昨天送達。我可以檢查到貨狀態，以及是否需要申請退款。"),
                listOf("notifications"), .55, .8,
                app.umbra.agent.agents.proactive.PreparedNextStep(t("Check the orders", "檢查訂單"), "agent_turn",
                    "{\"task\":\"${t("Check the Shopee delivery status and whether a refund is needed", "檢查蝦皮到貨狀態及是否需申請退款")}\",\"order_ids\":[\"261015N0V335KM\",\"261016R2XU2G7H\"]}"),
                createdAtMs = now - 40 * 60_000,
                explain = app.umbra.agent.agents.proactive.CardExplanation(
                    why = t("Your phone case and the HDMI cable from Shopee were due yesterday and still show “in transit”.", "你在蝦皮買的手機殼和 HDMI 線應該昨天送到，但還顯示「運送中」。"),
                    steps = listOf(t("Look up where both parcels are", "查詢兩個包裹的位置"), t("Tell you if either qualifies for a refund", "告訴你是否有哪一筆可以退款"), t("Prepare the refund request for you to send", "準備好退款申請讓你送出")),
                    ifIgnored = t("Shopee's refund window for late orders closes in 5 days.", "蝦皮延遲訂單的退款期限 5 天後截止。"),
                    sources = listOf(t("Shopee notifications · yesterday", "蝦皮通知 · 昨天"))))
            listOf(conflict, follow, price, refund).forEach { card ->
                store.saveOpportunity(card)
                store.stage(id, app.umbra.agent.agents.proactive.AttentionItem(card.id, card.title, card.summary, card.urgency, .8, card.confidence, "demo", card.createdAtMs,
                    tier = if (card.urgency >= .8) app.umbra.agent.agents.proactive.DeliveryTier.CAPSULE_AMBIENT else app.umbra.agent.agents.proactive.DeliveryTier.BRIEF_QUEUE, preparedStep = card.preparedAction))
            }
            val today = java.time.LocalDate.now().toString()
            store.saveBrief(id, app.umbra.agent.agents.proactive.DailyBrief("brief_${today}_morning", today, app.umbra.agent.agents.proactive.DailyBrief.Kind.MORNING,
                t("A light day: one conflict to settle, and the Kyoto plan is nearly ready.", "今天不忙：有一個衝突要處理，京都行程快完成了。"),
                scheduleHighlights = listOf(app.umbra.agent.agents.proactive.BriefItem("10:00", t("Stand-up · notes ready", "站立會議 · 筆記已備好")), app.umbra.agent.agents.proactive.BriefItem("14:00", t("Dentist · Ren'ai Rd", "牙醫 · 仁愛路")), app.umbra.agent.agents.proactive.BriefItem("14:30", t("Client call · Acme", "客戶通話 · Acme"))),
                conflictsDetected = listOf(app.umbra.agent.agents.proactive.ConflictAlert("cf1", app.umbra.agent.agents.proactive.ConflictJudge.OVERLAP,
                    t("Dentist (today 14:00–15:00, Personal) overlaps Client call (today 14:30–15:00, Work) by 30 minutes.", "「牙醫」（今天 14:00–15:00，個人）和「客戶通話」（今天 14:30–15:00，工作）重疊 30 分鐘。"), listOf("calendar:demo-dentist", "calendar:demo-acme"),
                    listOf(app.umbra.agent.agents.proactive.PreparedNextStep(t("Ask for a fix", "請它提出解決辦法"), "agent_turn", "{\"instruction\":\"The user asked from their brief: suggest how to resolve the clash between the dentist at 14:00 and the client call at 14:30. Ask before sending anything or editing either event.\"}")),
                    title = t("Dentist and Client call overlap", "「牙醫」和「客戶通話」時間重疊"),
                    explain = app.umbra.agent.agents.proactive.CardExplanation(
                        why = t("Dentist (today 14:00–15:00, Personal) overlaps Client call (today 14:30–15:00, Work) by 30 minutes.", "「牙醫」（今天 14:00–15:00，個人）和「客戶通話」（今天 14:30–15:00，工作）重疊 30 分鐘。"),
                        steps = listOf(t("Compare both events and who is invited, and suggest which to move", "比較兩個行程與受邀的人，建議移動哪一個"), t("Draft the message or the calendar change", "草擬改時間的訊息或行事曆變更"), t("Show it to you first: nothing is sent or changed until you agree", "先給你看，你同意前不會傳送或修改任何行程")),
                        ifIgnored = t("Both stay on your calendar as they are.", "兩個行程都維持原樣。"),
                        sources = listOf(t("Calendar · Dentist · today 14:00–15:00", "行事曆 · 牙醫 · 今天 14:00–15:00"), t("Calendar · Client call · today 14:30–15:00", "行事曆 · 客戶通話 · 今天 14:30–15:00"))))),
                tasks = app.umbra.agent.agents.proactive.BriefTasks.of(container.sessionStore.list(app.umbra.core.session.SessionFilter(limit = Int.MAX_VALUE)), id, now, sinceMs = Long.MIN_VALUE, conversationId = container.agents.store.get(id)?.sessionId),
                attentionNeeded = listOf(conflict),
                lowPriorityDigest = t("Package arrives tomorrow. Two newsletters saved for the weekend.", "包裹明天到。兩份電子報留到週末看。"), generatedAtMs = now - 2 * hour))
            store.saveWatcher(app.umbra.agent.agents.proactive.AgentWatcher("demo-ryokan", id, t("Ryokan under ¥25,000", "旅館低於 ¥25,000"),
                app.umbra.agent.agents.proactive.ProbeSpec.HttpOrCss("https://example.com/ryokan/higashiyama", ".price"), app.umbra.agent.agents.proactive.ConditionSpec.NumericCompare("<", 25000.0), 60,
                t("Tell me and add it to the Kyoto shortlist.", "通知我並加入京都候選清單。"), lastCheckedMs = now - 30 * 60_000, lastValue = "¥23,800"))
            store.saveRoutine(app.umbra.agent.agents.proactive.AgentRoutine("demo-sweep", id, t("Weekday inbox sweep", "平日收件匣整理"), app.umbra.agent.agents.proactive.RoutineTrigger.CronSchedule("30 8 * * 1-5", java.time.ZoneId.systemDefault().id),
                instruction = t("Sort new mail; draft replies to anything from Acme; never send.", "整理新郵件；為 Acme 的來信擬草稿；不要寄出。"), allowedConnectors = listOf("email"), createdAtMs = now - 96 * hour))
            container.agents.store.update(id) { it.copy(proactivityLevel = 3, morningBriefMinute = 480, eveningBriefMinute = 1110) }
            buildJsonObject { put("ok", true) }
        }
        // Synthesized guest scripts: the real store gates and the real Linux run (Laya's review is recorded by hand here,
        // since the fixture checks execution, not the model's judgement). The skill is removed afterwards.
        "test.script.run" -> try {
            val language = str(args, "language")
            val caps = app.umbra.agent.agents.proactive.ScriptCapabilities(args["network"]?.jsonPrimitive?.booleanOrNull ?: false,
                (args["domains"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content })
            val source = str(args, "source")
            val skill = app.umbra.agent.agents.proactive.UmbraSkill("toolqa" + System.nanoTime(), "Script QA", "Fixture", null,
                app.umbra.agent.agents.proactive.ExecutionTarget.LINUX_CLI, verificationChecks = listOf("fixture"), approvalBoundary = "fixture",
                updatedAtMs = System.currentTimeMillis(), synthesizedScript = source, scriptLanguage = language, scriptCapabilities = caps,
                testCases = listOf(app.umbra.agent.agents.proactive.TransformTestCase(args["input"] ?: JsonObject(emptyMap()), expectedKeys = listOf("n"))))
            try {
                container.skills.propose(skill, "Fixture")
                container.skills.beginTest(skill.id, "qa"); container.skills.recordTest(skill.id, "qa", true)
                container.skills.recordCodeReview(skill.id, 1, app.umbra.agent.agents.proactive.SkillStore.hash(source)); container.skills.approve(skill.id)
                val ready = container.skills.scriptForRun(skill.id)
                val started = System.currentTimeMillis()
                val out = app.umbra.agent.agents.proactive.GuestScriptRunner { container.guest.runtime }.run(ready, args["input"] ?: JsonObject(emptyMap()))
                val path = app.umbra.agent.agents.proactive.GuestScriptPolicy.path(ready.id, ready.version, language)
                val stat = container.guest.runtime.exec(listOf("/usr/bin/stat", "-c", "%U %a", path), timeoutMs = 10_000)
                buildJsonObject { put("result", out); put("ms", System.currentTimeMillis() - started); put("installed", stat.stdout.trim()) }
            } finally {
                runCatching { container.skills.disable(skill.id); container.skills.removeDraft(skill.id) }
                runCatching { container.guest.runtime.exec(listOf("/bin/rm", "-rf", "/opt/umbra/synthesized-tools/${skill.id}"), asRoot = true, timeoutMs = 10_000) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { buildJsonObject { put("error", e.message ?: "Script failed") } }
        "test.teams.create" -> {
            val members = (1..2).map { n -> container.agents.create("Teams QA ${System.nanoTime()} $n", "a$n", app.umbra.agent.agents.Templates.BLANK, "Answer only the explicit test request. No notifications or external messages.", channels = emptyList()).let { p ->
                container.agents.store.update(p.id) { it.copy(proactivityLevel = 0, morningBriefMinute = null, eveningBriefMinute = null) }!!
            } }
            val group = container.teams.create("Teams QA ${System.nanoTime()}", members.map { it.id }, members.first().id)
            UmbraJson.encodeToJsonElement(app.umbra.agent.agents.proactive.AgentGroup.serializer(), group)
        }
        "test.teach.create" -> {
            val p = container.agents.create("Teach QA ${System.nanoTime()}", "a1", app.umbra.agent.agents.Templates.BLANK, "Synthetic demonstration fixture", channels = emptyList(), running = false)
            buildJsonObject { put("agent", p.id) }
        }
        "test.teach.start", "test.teach.stop", "test.teach.state", "test.teach.extract", "test.teach.cleanup" -> {
            val id = str(args, "agent")
            check(container.agents.store.get(id)?.name?.startsWith("Teach QA ") == true)
            when (command) {
                "test.teach.start" -> container.demonstrations.start(id)
                "test.teach.stop" -> container.demonstrations.stop()
                "test.teach.extract" -> container.demonstrations.extract(id, str(args, "id"))
                "test.teach.cleanup" -> {
                    if (container.demonstrations.active.value?.agentId == id) container.demonstrations.stop()
                    container.demonstrations.list(id).forEach { container.demonstrations.remove(id, it.id) }
                    container.skills.list().filter { it.ownerAgentId == id }.forEach { container.skills.disable(it.id); container.skills.removeDraft(it.id) }
                    container.returnControl("display:0")
                    container.agents.delete(id)
                }
            }
            buildJsonObject {
                put("active", container.demonstrations.active.value != null)
                put("records", JsonArray(container.demonstrations.list(id).map { UmbraJson.encodeToJsonElement(app.umbra.agent.agents.proactive.Demonstration.serializer(), it) }))
                put("skills", JsonArray(container.skills.list().filter { it.ownerAgentId == id }.map { UmbraJson.encodeToJsonElement(app.umbra.agent.agents.proactive.UmbraSkill.serializer(), it) }))
            }
        }
        "test.teams.send" -> {
            val group = container.agents.proactive.store.groups().first { it.id == str(args, "group") && it.name.startsWith("Teams QA ") }
            buildJsonObject { put("root", container.teams.sendUser(group.id, str(args, "text"))) }
        }
        "test.teams.state" -> {
            val store = container.agents.proactive.store
            val group = store.groups().first { it.id == str(args, "group") && it.name.startsWith("Teams QA ") }
            buildJsonObject {
                put("messages", JsonArray(store.groupMessages(group.id).map { UmbraJson.encodeToJsonElement(app.umbra.agent.agents.proactive.GroupMessage.serializer(), it) }))
                put("turns", JsonArray(store.groupTurns().filter { it.groupId == group.id }.map { UmbraJson.encodeToJsonElement(app.umbra.agent.agents.proactive.GroupTurn.serializer(), it) }))
                put("parents", buildJsonObject { container.sessionStore.list().filter { it.agentId in group.memberAgentIds }.forEach { s -> s.spawnedBy?.let { put(s.id, it) } } })
            }
        }
        "test.teams.receipt" -> {
            val group = container.agents.proactive.store.groups().first { it.id == str(args, "group") && it.name.startsWith("Teams QA ") }
            val session = requireNotNull(container.sessionStore.get(str(args, "id")))
            check(session.agentId in group.memberAgentIds)
            session.attachments[app.umbra.core.orchestrator.CompletionEvidence.KEY] ?: JsonObject(emptyMap())
        }
        "test.teams.cleanup" -> {
            val store = container.agents.proactive.store
            val group = store.groups().first { it.id == str(args, "group") && it.name.startsWith("Teams QA ") }
            container.teams.stop(group.id)
            for (agent in group.memberAgentIds) {
                check(container.agents.store.get(agent)?.name?.startsWith("Teams QA ") == true)
                container.sessionStore.list().filter { it.agentId == agent }.forEach { s -> container.supervisor.stop(s.id); container.supervisor.delete(s.id) }
                container.agents.delete(agent)
            }
            store.removeGroup(group.id)
            buildJsonObject { put("ok", true) }
        }
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
        "guest.exec_secrets" -> {
            // Development only: proves credentials reach a guest command through stdin, never a file or a command line.
            val argv = (args["argv"] as JsonArray).map { (it as JsonPrimitive).content }
            val secrets = (args["secrets"] as JsonObject).mapValues { (it.value as JsonPrimitive).content }
            val out = container.guest.runtime.execWithSecrets(argv, secrets, timeoutMs = 60_000)
            buildJsonObject { put("exit", out.exitCode); put("stdout", out.stdout.takeLast(4000)); put("stderr", out.stderr.takeLast(2000)) }
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
            // With "key", the provider needs a (dummy) API key: omp then starts with it as a secret, which the local
            // scripted server can check in the Authorization header (the credential path into the guest).
            val key = (args["key"] as? JsonPrimitive)?.content
            if (key != null) container.secrets.put("UMBRA_TEST_AGENT_KEY", key)
            val provider = ProviderConfig("umbra-test-agent", "Local agent verification", apiKeyEnv = if (key != null) "UMBRA_TEST_AGENT_KEY" else null, custom = true, baseUrl = url, api = "openai-completions", models = listOf("fake-model"), keyless = key == null)
            container.settings.update { it.copy(providers = listOf(provider), defaultModel = ModelRef(provider.id, "fake-model"), autoModel = false, autoEffort = false, thinkingLevel = "minimal") }
            buildJsonObject { put("ok", true) }
        }
        "test.provider.restore" -> {
            val backup = File(container.context.filesDir, "debug-provider-backup.json")
            val original = UmbraJson.decodeFromString(app.umbra.agent.data.AppPrefs.serializer(), backup.readText())
            container.settings.update { original }
            container.secrets.remove("UMBRA_TEST_AGENT_KEY")
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
            val placement = (args["placement"] as? JsonPrimitive)?.content?.let { app.umbra.agent.data.CapsulePlacement.valueOf(it.uppercase()) }
            container.settings.update { it.copy(overlayEnabled = on, capsulePlacement = placement ?: it.capsulePlacement) }
            buildJsonObject { put("on", on); put("placement", container.settings.value.capsulePlacement.name) }
        }
        "capsule.status" -> buildJsonObject {
            put("enabled", container.settings.value.overlayEnabled)
            put("started", container.capsule.isStarted)
            put("revision", container.capsuleSignals.revision.value)
            put("suggestions", container.capsule.projectedSuggestions)
            put("suggestionExpirySignals", container.capsule.suggestionExpirySignals)
        }
        "test.capsule.signal" -> { container.capsuleSignals.request(); buildJsonObject { put("ok", true) } }
        "test.composer.draft" -> buildJsonObject { put("text", container.ui.draft.value) }
        "test.qr.state" -> {
            val connected = app.umbra.platform.android.UmbraAccessibilityService.connected.value
            val snapshot = if (connected) container.accessibility.snapshot(app.umbra.platform.api.SnapshotOptions(displayId = 0)) else null
            buildJsonObject {
                put("connected", connected)
                put("found", snapshot?.containsText(container.context.getString(R.string.rn_found, str(args, "name")), exact = true, displayId = 0) == true)
            }
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
                (args["homeAgents"] as? JsonPrimitive)?.content?.let { q = q.copy(homeAgents = it == "true") }
                (args["providers"] as? JsonPrimitive)?.content?.takeIf { it == "none" }?.let { q = q.copy(providers = emptyList(), defaultModel = null) }
                // "auto", or one of omp's levels (low, medium, high...) for new tasks.
                (args["effort"] as? JsonPrimitive)?.content?.let { e -> q = if (e == "auto") q.copy(autoEffort = true) else q.copy(autoEffort = false, thinkingLevel = e) }
                q
            }
            val p = container.settings.value
            buildJsonObject { put("mode", p.uiMode.name); put("onboarded", p.onboarded); put("homeAgents", p.homeAgents); put("providers", p.providers.size); put("effort", if (p.autoEffort) "auto" else p.thinkingLevel.orEmpty()) }
        }
        "approval_mode" -> {
            (args["mode"] as? JsonPrimitive)?.content?.let { mode ->
                container.settings.update { it.copy(approvalMode = app.umbra.core.safety.ApprovalMode.valueOf(mode.uppercase())) }
            }
            buildJsonObject { put("mode", container.settings.value.approvalMode.name) }
        }
        "laya.status" -> buildJsonObject {
            put("state", container.laya.state.value.name)
            put("backend", container.laya.backend.value ?: "")
            put("message", container.laya.message ?: "")
            put("progress", container.laya.progress.value?.let { "${it.file} ${it.have}/${it.total}" } ?: "")
            put("processors", container.laya.processors().joinToString("; ") { "${it.backend.key}=${it.status}${it.detail?.let { d -> " ($d)" }.orEmpty()}" })
        }
        // A native crash (SIGSEGV) in Laya's process: with a model loaded, the app falls back to its rules and stays up;
        // without one, a bare :laya process is started and crashed, and Android's record of how it ended is returned.
        "laya.crash" -> {
            if (container.laya.state.value == app.umbra.agent.data.LayaSidecar.State.RUNNING) {
                container.laya.crashForTest()
                buildJsonObject { put("crashed", "the loaded model's process"); put("appPid", android.os.Process.myPid()) }
            } else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val process = app.umbra.agent.data.laya.LayaProcess.start(container.context)
                val pid = process.pid
                val accelerators = runCatching { process.accelerators().sorted().joinToString(",") }.getOrElse { "failed: ${it.message}" }
                process.crash()
                val exit = kotlinx.coroutines.withTimeoutOrNull(10_000) { process.died.await() }
                buildJsonObject {
                    put("layaPid", pid); put("appPid", android.os.Process.myPid()); put("accelerators", accelerators)
                    put("crashed", exit?.crashed ?: false); put("reason", exit?.text ?: "no death seen in 10 s")
                }
            }
        }
        // Laya's process lifecycle with a small model ({"path": a .tflite the app can read}): "open" starts a process and
        // opens it on the CPU, keeping it (a second "open" without "stop" leaves the first one behind, as a lost process
        // would be); "call" makes one binder call into it (freeze it first with `am freeze --sticky <pid>` to see what a
        // phone that freezes background apps does); "stop" ends it.
        "laya.probe.open" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val process = app.umbra.agent.data.laya.LayaProcess.start(container.context)
            layaProbe = process
            val result = try { process.open(java.io.File(str(args, "path")), app.umbra.agent.data.laya.LayaBackend.CPU, false); "opened" }
                catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            buildJsonObject { put("pid", process.pid); put("result", result) }
        }
        "laya.probe.call" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val process = layaProbe ?: error("no probe process")
            val result = try { "ok: " + process.accelerators().sorted().joinToString(",") }
                catch (e: app.umbra.agent.data.laya.LayaProcessDied) { "died: ${e.reason.text} (paused=${e.reason.paused})" }
                catch (e: Exception) { "failed: ${e.message}" }
            buildJsonObject { put("pid", process.pid); put("result", result); put("alive", app.umbra.agent.data.laya.LayaProcess.alive(process.pid)) }
        }
        "laya.probe.stop" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val process = layaProbe ?: error("no probe process")
            process.stop()
            layaProbe = null
            buildJsonObject { put("pid", process.pid); put("alive", app.umbra.agent.data.laya.LayaProcess.alive(process.pid)) }
        }
        // Opens a file that does not exist on each processor in a bare :laya process: the refusal must be about the
        // file, which shows the processor was read correctly (no model is loaded).
        "laya.open.probe" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            buildJsonObject {
                for (backend in app.umbra.agent.data.laya.LayaBackend.entries.filter { it != app.umbra.agent.data.laya.LayaBackend.NPU }) {
                    val process = app.umbra.agent.data.laya.LayaProcess.start(container.context)
                    val result = try { process.open(java.io.File(container.context.cacheDir, "laya-missing.tflite"), backend, false); "opened" }
                        catch (e: Exception) { e.message ?: e.javaClass.simpleName } finally { process.stop() }
                    put(backend.key, result)
                }
            }
        }
        "laya.decide" -> {
            // A real decision through the engine the router uses (the native one while Laya runs).
            val request = app.umbra.core.decision.DecisionRequest(
                (args["state"] as? JsonPrimitive)?.content ?: "Action: tapped 'Save'. Screen after: a toast reads \"Contact saved\" and the contact card for Mei Lin is shown.",
                mapOf(
                    "yes" to app.umbra.core.decision.Question.Noul((args["statement"] as? JsonPrimitive)?.content ?: "The action succeeded."),
                    "pick" to app.umbra.core.decision.Question.Choice(listOf(
                        app.umbra.core.decision.Option("e0", "Wi-Fi switch (on)"), app.umbra.core.decision.Option("e1", "Mobile data switch (off)"),
                        app.umbra.core.decision.Option("e2", "Back button"),
                    ), "Which element should be tapped to turn on mobile data?"),
                ),
            )
            val response = container.laya.engine.decide(request)
            buildJsonObject {
                put("engine", response.engine)
                put("latencyMs", response.latencyMs)
                for ((id, answer) in response.answers) put(id, answer.toString())
            }
        }
        "laya.security.check" -> {
            val request = app.umbra.core.model.action.ActionRequest(
                id = "laya-android-fixture", operation = app.umbra.core.model.action.Operation.Tap,
            )
            val check = app.umbra.core.safety.LayaActionSecurityCheck(
                app.umbra.core.decision.LayaHttpEngine("http://127.0.0.1:8765"),
            )
            val assessment = check.assess(request, app.umbra.core.safety.ActionContext())
            val decision = app.umbra.core.safety.ApprovalPolicy(container.settings.value.approvalMode).decide(request, assessment)
            val network = android.security.NetworkSecurityPolicy.getInstance()
            buildJsonObject {
                put("risk", assessment.level.name)
                put("decision", decision.javaClass.simpleName)
                put("loopbackAllowed", network.isCleartextTrafficPermitted("127.0.0.1"))
                put("externalHttpAllowed", network.isCleartextTrafficPermitted("example.com"))
            }
        }
        "workspace.create" -> {
            val ws = container.workspaces.create(str(args, "name"))
            buildJsonObject { put("id", ws.id); put("host_path", File(container.guest.layout.workspaces, "projects/${ws.id}").path) }
        }
        "test.tool.call" -> {
            // One of a session's app tools (dashboards, cards, skills…), called the way omp calls it, so a tool and the
            // screens it feeds can be checked without a model. With "agent", the agent's own tools are included.
            val agentId = (args["agent"] as? JsonPrimitive)?.content
            val host = container.toolHost(str(args, "session"), agentId, agentConversation = agentId != null)
            val id = "debug-" + System.nanoTime()
            val payload = host.handle(app.umbra.runtime.omp.HostToolCall(id, id, str(args, "name"), args["arguments"] as? JsonObject ?: JsonObject(emptyMap())),
                app.umbra.runtime.omp.HostToolProgress { })
            buildJsonObject {
                put("error", payload.isError)
                put("text", payload.content.filterIsInstance<app.umbra.runtime.omp.ContentBlock.Text>().joinToString("\n") { it.text })
            }
        }
        "test.task.fixture" -> {
            val session = container.supervisor.create("Synthetic task controls verification", title = "Task controls QA")
            val summary = (args["summary"] as? JsonPrimitive)?.content ?: "Synthetic task result: implementation and tests passed."
            val done = container.sessionStore.update(session.id) { it.copy(state = app.umbra.core.session.SessionState.COMPLETED, resultSummary = summary) }!!
            container.sessionStore.append(session.id, app.umbra.core.session.SessionEvent.AgentMessage(done.resultSummary!!))
            sessionJson(done)
        }
        "test.command.review" -> {
            val session = container.sessionStore.get(str(args, "id")) ?: error("No fixture session")
            check(session.title == "Task controls QA")
            val shell = (args["command"] as? JsonPrimitive)?.content ?: "git status --short"
            container.scope.launch {
                container.safetyGate.check(
                    app.umbra.core.model.action.ActionRequest(id = "debug-prefix-${System.nanoTime()}", sessionId = session.id, operation = app.umbra.core.model.action.Operation.Shell(shell)),
                    app.umbra.core.safety.ActionContext(cwd = "/workspace/task-controls-qa"),
                    additionalAssessment = app.umbra.core.safety.RiskAssessment(listOf(app.umbra.core.safety.RiskFinding(app.umbra.core.safety.RiskCategory.NETWORK_CHANGE, app.umbra.core.safety.RiskLevel.HIGH, "Synthetic approval fixture; no command is executed."))),
                )
            }
            buildJsonObject { put("ok", true) }
        }
        "test.command.grants" -> JsonArray(container.settings.value.commandApprovals.filter { it.contextDirectory == "/workspace/task-controls-qa" }.map { rule ->
            buildJsonObject { put("id", rule.id); put("prefix", rule.commandPrefix.orEmpty()) }
        })
        "test.command.clear" -> {
            container.settings.update { it.copy(commandApprovals = it.commandApprovals.filterNot { rule -> rule.contextDirectory == "/workspace/task-controls-qa" }) }
            buildJsonObject { put("ok", true) }
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
        put("result_summary", s.resultSummary.orEmpty())
        put("usage_limit", s.usageLimit?.let { UmbraJson.encodeToJsonElement(app.umbra.core.session.AiUsageLimit.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
        put("usage_block_reason", s.usageBlockReason.orEmpty())
    }
}
