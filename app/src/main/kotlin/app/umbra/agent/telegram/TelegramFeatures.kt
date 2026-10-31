package app.umbra.agent.telegram

import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.agents.AgentProfile
import app.umbra.agent.agents.SoulProposal
import app.umbra.agent.agents.proactive.CronText
import app.umbra.agent.cards.CardBlock
import app.umbra.agent.cards.CardTemplates
import app.umbra.agent.dashboards.Dashboard
import app.umbra.agent.dashboards.Source
import app.umbra.agent.skills.InstalledSkill
import app.umbra.agent.skills.SkillEntry
import app.umbra.agent.skills.SkillRateLimitException
import app.umbra.agent.skills.SkillScope
import app.umbra.core.orchestrator.Block
import app.umbra.core.orchestrator.ChatItem
import app.umbra.core.session.Session
import app.umbra.core.workspace.WorkspaceKind
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal data class TelegramCommand(val name: String, val arguments: String)
internal fun telegramCommand(text: String?): TelegramCommand? {
    val words = text?.trim()?.split(Regex("\\s+"), limit = 2) ?: return null
    val head = words.firstOrNull()?.takeIf { it.startsWith('/') } ?: return null
    return TelegramCommand(head.substringBefore('@').lowercase(java.util.Locale.ROOT), words.getOrElse(1) { "" }.trim())
}

/** Feature buttons carry only a short-lived reference; they are invalidated whenever the linked account changes. */
internal class TelegramFeatures(
    private val container: AppContainer,
    private val send: suspend (Long, String, JsonObject?) -> Long?,
    private val edit: suspend (Long, Long, String, JsonObject?) -> Unit,
) {
    private data class Action(val chatId: Long, val agentId: String, val dashboard: String? = null,
        val skill: SkillEntry? = null, val soul: SoulProposal? = null, val session: Session? = null, val artifact: JsonObject? = null)
    private val publishing = Mutex()
    private val actions = TelegramTokens<Action>()
    private val cards = TelegramCards { text(it) }
    private val cardTurns = linkedSetOf<String>()
    private fun text(id: Int, vararg args: Any) = container.context.getString(id, *args)
    private fun escaped(id: Int, vararg args: Any) = telegramEscape(text(id, *args))
    private val zh get() = container.context.resources.configuration.locales[0].language == "zh"
    fun clear() { actions.clear(); cards.clear(); synchronized(cardTurns) { cardTurns.clear() } }
    private fun button(action: Action, label: Int, op: String) = telegramButton(text(label), "tf:${actions.put(action)}:$op")
    private fun time(at: Long, pattern: String = "HH:mm") = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern, container.context.resources.configuration.locales[0]))
    private fun cron(value: String) = CronText.describe(value, zh) ?: text(R.string.tgf_custom_schedule)

    suspend fun help(chatId: Long) { send(chatId, escaped(R.string.tgf_help), null) }

    suspend fun command(chatId: Long, raw: String?, agent: AgentProfile): Boolean {
        val command = telegramCommand(raw) ?: return false
        try {
            when (command.name) {
                "/help" -> help(chatId)
                "/dashboards" -> dashboards(chatId, agent.id)
                "/skills" -> skills(chatId, agent.id)
                "/findskill" -> findSkill(chatId, agent, command.arguments)
                "/sites" -> sites(chatId)
                "/memory" -> memory(chatId, agent.id)
                "/timers" -> timers(chatId, agent.id)
                "/soul" -> {
                    val proposals = container.agents.store.soulProposals(agent.id).filter { it.state == "pending" }
                    if (proposals.isEmpty()) send(chatId, escaped(R.string.tgf_no_soul), null)
                    else proposals.forEach { offerSoul(chatId, agent.id, it) }
                }
                else -> return false
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { send(chatId, escaped(R.string.tgf_feature_failed), null) }
        return true
    }

    private fun head(d: Dashboard): String = "📊 <b>${telegramEscape(d.title)}</b> · " + escaped(when (d.status) {
        Dashboard.Status.LIVE -> R.string.tgf_live; Dashboard.Status.PAUSED -> R.string.dash_paused; Dashboard.Status.ERROR -> R.string.tgf_needs_attention
    }) + " · " + escaped(R.string.tgf_updated, time(d.updatedAtMs))
    private suspend fun dashboards(chatId: Long, agentId: String) {
        val list = container.dashboards.forAgent(agentId)
        if (list.isEmpty()) { send(chatId, escaped(R.string.tgf_no_dashboards), null); return }
        for (d in list) {
            val stats = d.tiles.flatMap { (it.block as? CardBlock.Stats)?.items.orEmpty() }.take(3).joinToString(" · ") { "${telegramEscape(it.label)} ${telegramEscape(it.value)}" }
            send(chatId, head(d) + if (stats.isEmpty()) "" else "\n$stats", telegramKeyboard(listOf(listOf(button(Action(chatId, agentId, dashboard = d.id), R.string.tgf_open, "open")))))
        }
    }
    private suspend fun dashboard(chatId: Long, id: String) {
        val d = container.dashboards.get(id) ?: return
        val html = buildList {
            add(head(d))
            d.subtitle?.let { add(telegramEscape(it)) }
            d.statusText?.let { add(telegramEscape(it)) }
            d.tiles.forEach { add("<b>${telegramEscape(it.title)}</b>\n" + cards.block(it.block, System.currentTimeMillis())) }
            if (d.sources.isNotEmpty()) add("<b>${escaped(R.string.tgf_sources)}</b>\n" + d.sources.joinToString(" · ") { s -> telegramEscape(when (s.kind) {
                Source.Kind.FILE, Source.Kind.FOLDER -> s.path?.trimEnd('/')?.substringAfterLast('/') ?: text(R.string.dash_source_file)
                Source.Kind.MODEL -> text(R.string.dash_source_agent)
                Source.Kind.TASKS -> text(R.string.dash_source_tasks)
            }) })
            add(escaped(R.string.tgf_schedule, d.refreshCron?.let(::cron) ?: text(R.string.tgf_no_schedule)))
        }.joinToString("\n\n")
        val action = Action(chatId, d.agentId, dashboard = id)
        send(chatId, html, telegramKeyboard(listOf(listOf(button(action, R.string.tgf_refresh, "refresh"),
            button(action, if (d.status == Dashboard.Status.PAUSED) R.string.tgf_resume else R.string.tgf_pause, if (d.status == Dashboard.Status.PAUSED) "resume" else "pause")))))
    }
    private suspend fun skills(chatId: Long, agentId: String) {
        val sections = mutableListOf<String>()
        fun section(title: String, skills: List<InstalledSkill>) {
            if (skills.isNotEmpty()) sections += "<b>${telegramEscape(title)}</b>\n" + skills.joinToString("\n") {
                "• <b>${telegramEscape(it.name)}</b> — ${telegramEscape(it.description)}" + it.sourceRepo?.let { repo -> " (${escaped(R.string.tgf_from, repo)})" }.orEmpty()
            }
        }
        section(text(R.string.tgf_every_project), container.installedSkills.scan())
        val coding = container.sessionStore.list(app.umbra.core.session.SessionFilter(limit = Int.MAX_VALUE)).filter { it.kind == app.umbra.core.session.SessionKind.CODING }.map { it.id }.toSet()
        container.workspaces.list().filter { it.kind == WorkspaceKind.PROJECT && (it.origin != null || it.externalPath != null || it.pinned || it.sessionIds.any { sid -> sid in coding }) }.forEach { project ->
            section(project.name, container.installedSkills.scan(project.id).filter { it.scope == SkillScope.PROJECT })
        }
        val learned = container.skills.list().filter { it.ownerAgentId == null || it.ownerAgentId == agentId }.joinToString("\n\n") {
            escaped(R.string.tgf_learned_skill, it.name, it.version, text(when (it.status) {
                app.umbra.agent.agents.proactive.UmbraSkill.Status.DRAFT -> R.string.tgf_skill_draft
                app.umbra.agent.agents.proactive.UmbraSkill.Status.TESTING -> R.string.tgf_skill_testing
                app.umbra.agent.agents.proactive.UmbraSkill.Status.READY -> R.string.tgf_skill_ready
                app.umbra.agent.agents.proactive.UmbraSkill.Status.DISABLED -> R.string.tgf_skill_disabled
            }), it.description, it.approvalBoundary)
        }
        sections += "<b>${escaped(R.string.tgf_learned)}</b>\n" + learned.ifEmpty { escaped(R.string.tgf_no_skills) }
        sections += escaped(R.string.tgf_find_hint)
        send(chatId, sections.joinToString("\n\n"), null)
    }
    private suspend fun findSkill(chatId: Long, agent: AgentProfile, words: String) {
        if (words.isBlank()) { send(chatId, escaped(R.string.tgf_find_hint), null); return }
        try {
            val entries = mutableListOf<SkillEntry>()
            for (repo in container.skillHub.search(words).take(5)) {
                entries += container.skillHub.skills(repo).take(5 - entries.size)
                if (entries.size >= 5) break
            }
            if (entries.isEmpty()) send(chatId, escaped(R.string.tgf_no_search), null)
            entries.forEach { entry -> send(chatId, "${telegramEscape(entry.repo)} · <b>${telegramEscape(entry.name)}</b> · ${telegramEscape(entry.description)}",
                telegramKeyboard(listOf(listOf(button(Action(chatId, agent.id, skill = entry), R.string.tgf_install_global, "install"))))) }
        } catch (e: SkillRateLimitException) {
            send(chatId, escaped(R.string.tgf_github_wait, time(e.resetAtMs ?: (System.currentTimeMillis() + (e.retryAfterMs ?: 60_000)))), null)
        }
    }
    suspend fun sites(chatId: Long) {
        if (!container.pagesPublisher.connected()) { send(chatId, escaped(R.string.tgf_not_connected), null); return }
        val list = container.pagesPublisher.publications().filter { it.publishedAt != null }
        if (list.isEmpty()) send(chatId, escaped(R.string.tgf_no_sites), null)
        for (site in list) {
            val host = runCatching { java.net.URI(site.publicUrl).host }.getOrNull() ?: continue
            val ago = android.text.format.DateUtils.getRelativeTimeSpanString(site.publishedAt!!, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
            send(chatId, "🌐 <b>${telegramEscape(host)}</b> · ${escaped(R.string.tgf_published, ago)}", telegramKeyboard(listOf(listOfNotNull(telegramUrl(text(R.string.tgf_open), site.publicUrl)))))
        }
    }
    private suspend fun memory(chatId: Long, agentId: String) {
        val facts = container.agents.store.memory(agentId).entries().sortedWith(compareByDescending<app.umbra.agent.agents.Memory.Entry> { it.seenDate }.thenByDescending { it.index }).take(10)
        val html = facts.joinToString("\n") { fact -> "• ${telegramEscape(fact.text)}" +
            fact.about.takeIf { it.isNotEmpty() }?.let { " — ${escaped(R.string.tgf_about, it.joinToString(", "))}" }.orEmpty() +
            " · ${escaped(R.string.tgf_from, source(fact.source))}" }
        send(chatId, html.ifEmpty { escaped(R.string.tgf_no_memory) }, null)
    }
    private fun source(value: String): String = when (value.lowercase(java.util.Locale.ROOT).removePrefix("messages:").substringBefore(':')) {
        "whatsapp" -> text(R.string.tgf_source_whatsapp)
        "telegram" -> text(R.string.tgf_source_telegram)
        "agent" -> text(R.string.dash_source_agent)
        "user", "manual" -> text(R.string.tgf_source_you)
        else -> text(R.string.tgf_source_saved)
    }
    private suspend fun timers(chatId: Long, agentId: String) {
        val html = container.agents.store.timers(agentId).sortedBy { it.nextMs }.take(10).joinToString("\n") {
            "${telegramEscape(time(it.nextMs, "EEE HH:mm"))} · ${telegramEscape(it.note)}\n" + telegramEscape(it.cron?.let(::cron) ?: text(R.string.tgf_once))
        }
        send(chatId, html.ifEmpty { escaped(R.string.tgf_no_timers) }, null)
    }
    suspend fun offerSoul(chatId: Long, agentId: String, proposal: SoulProposal) {
        val action = Action(chatId, agentId, soul = proposal)
        send(chatId, escaped(R.string.tgf_soul_proposal, proposal.section, proposal.text, proposal.why), telegramKeyboard(listOf(listOf(
            button(action, R.string.tgf_add, "add"), button(action, R.string.tgf_not_this, "decline")))))
    }

    suspend fun sendCards(chatId: Long, agentId: String, sessionId: String) {
        val conversation = container.supervisor.conversation(sessionId).value
        val start = conversation.items.indexOfLast { it is ChatItem.User }
        val calls = conversation.items.drop(start + 1).filterIsInstance<ChatItem.Assistant>().flatMap { it.blocks }.filterIsInstance<Block.ToolCall>()
            .filter { it.name == "show_card" && conversation.tools[it.id]?.isError == false }.take(2)
        for (call in calls) {
            val key = "$sessionId:${call.id}"
            if (synchronized(cardTurns) { key in cardTurns }) continue
            val spec = CardTemplates.fromArguments(call.arguments).getOrNull() ?: continue
            val rendered = cards.render(spec, System.currentTimeMillis())
            cards.bind(rendered, agentId, chatId)
            send(chatId, rendered.html, rendered.keyboard)
            synchronized(cardTurns) { cardTurns += key; while (cardTurns.size > 500) cardTurns.remove(cardTurns.first()) }
        }
    }

    /** Folder deliverables use the index receipt, keeping document delivery and publication on the same proof. */
    suspend fun documentArtifact(session: Session, artifact: JsonObject): JsonObject {
        val path = artifact["path"]?.jsonPrimitive?.contentOrNull ?: return artifact
        val directory = artifactFile(session, path)
        if (!directory.isDirectory) return artifact
        val index = directory.resolve("index.html").canonicalFile
        val bundle = session.attachments[app.umbra.core.orchestrator.CompletionEvidence.KEY] as? JsonObject
        return (bundle?.get("artifacts") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { item ->
            item["path"]?.jsonPrimitive?.contentOrNull?.let { artifactFile(session, it).canonicalFile == index } == true
        } ?: artifact
    }

    suspend fun offerPublish(chatId: Long, session: Session, artifact: JsonObject, messageId: Long?) {
        val path = artifact["path"]?.jsonPrimitive?.contentOrNull ?: return
        val target = artifactFile(session, path)
        if (!target.extension.equals("html", true) && !(target.isDirectory && target.resolve("index.html").isFile)) return
        if (!container.pagesPublisher.connected()) { send(chatId, escaped(R.string.tgf_not_connected), null); return }
        val action = Action(chatId, session.agentId ?: return, session = session, artifact = artifact)
        val markup = telegramKeyboard(listOf(listOf(button(action, R.string.tgf_publish, "publish"))))
        if (messageId != null) container.telegram.editMarkup(chatId, messageId, markup)
        else send(chatId, escaped(R.string.tgf_publish_ready), markup)
    }
    private suspend fun artifactFile(session: Session, path: String): File = (container.workspaceLayout.toHost(path) ?: java.nio.file.Path.of(path).let {
        if (it.isAbsolute) it else (session.workspaceId?.let(container.workspaceLayout::projectDir) ?: container.workspaces.sessionDirs(session.id).files).resolve(it)
    }).toFile()

    /** Publish a receipt-checked private snapshot so neither edits nor secrets can slip between review and upload. */
    private suspend fun publish(chatId: Long, action: Action): Unit = publishing.withLock {
        if (!container.pagesPublisher.connected()) { send(chatId, escaped(R.string.tgf_not_connected), null); return@withLock }
        val session = action.session ?: return@withLock
        val artifact = action.artifact ?: return@withLock
        val linked = container.telegram.state.value
        if (linked.chatId != chatId || linked.userId == null || !linked.messages) return@withLock
        val parent = File(container.context.cacheDir, "telegram/publish").apply { mkdirs() }
        var snapshot: File? = null
        var verified: File? = null
        try {
            val original = artifactFile(session, artifact["path"]!!.jsonPrimitive.content)
            val siteRoot = original
            verified = withContext(Dispatchers.IO) { app.umbra.agent.data.DeliverableFiles.copyVerified(container, session, documentArtifact(session, artifact), parent) }
            snapshot = withContext(Dispatchers.IO) {
                val key = java.security.MessageDigest.getInstance("SHA-256").digest(original.canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }
                val target = File(parent, key).resolve(siteRoot.nameWithoutExtension.ifBlank { "site" })
                snapshot = target
                if (target.exists()) check(target.deleteRecursively())
                check(target.mkdirs())
                val files = app.umbra.agent.publish.PagesPublisher.selectFiles(siteRoot)
                val guard = app.umbra.core.safety.OutboundDlpGuard(4) { container.protectedValues() }
                for (file in files) {
                    val bytes = file.read()
                    guard.requireSafe(bytes.toString(Charsets.UTF_8))
                    val destination = target.resolve(file.path.removePrefix("/"))
                    destination.parentFile?.mkdirs()
                    destination.writeBytes(bytes)
                }
                check(target.resolve("index.html").readBytes().contentEquals(requireNotNull(verified).readBytes()))
                target
            }
            val current = container.telegram.state.value
            check(current.userId == linked.userId && current.chatId == linked.chatId && current.botId == linked.botId && current.messages)
            val site = container.pagesPublisher.publish(requireNotNull(snapshot)) {}
            send(chatId, escaped(R.string.tgf_live_at, site.publicUrl), telegramKeyboard(listOf(listOfNotNull(telegramUrl(text(R.string.tgf_open), site.publicUrl)))))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { send(chatId, escaped(R.string.tgf_publish_failed), null) }
        finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { snapshot?.deleteRecursively(); verified?.let { it.delete(); it.parentFile?.delete() } } }
    }

    suspend fun callback(chatId: Long, messageId: Long?, data: String): String {
        if (!container.telegram.state.value.messages) return text(R.string.tgf_messages_off)
        if (data.startsWith("tc:")) {
            val reply = cards.callback(chatId, data) ?: return text(R.string.tgf_button_expired)
            if (reply.answer == null) { if (messageId != null) edit(chatId, messageId, reply.html, reply.keyboard); return text(R.string.tgf_answer_saved) }
            try {
                container.agents.talk(requireNotNull(reply.agentId), reply.answer, via = "Telegram")
                cards.finish(data, true)
                if (messageId != null) edit(chatId, messageId, reply.html, telegramKeyboard(emptyList()))
            } catch (e: CancellationException) { cards.finish(data, false); throw e }
            catch (_: Exception) { cards.finish(data, false); return text(R.string.tgf_feature_failed) }
            return text(R.string.tgf_answered)
        }
        val parts = data.split(':')
        val token = parts.getOrNull(1) ?: return text(R.string.tgf_button_expired)
        val action = actions.get(token)?.takeIf { it.chatId == chatId } ?: return text(R.string.tgf_button_expired)
        return try {
            when (parts.getOrNull(2)) {
                "open" -> dashboard(chatId, action.dashboard ?: return text(R.string.tgf_button_expired))
                "refresh" -> {
                    val d = action.dashboard?.let(container.dashboards::get)?.takeIf { it.agentId == action.agentId } ?: return text(R.string.tgf_button_expired)
                    container.agents.talk(action.agentId, text(R.string.dash_refresh_message, d.title, d.id), via = "Telegram")
                    send(chatId, escaped(R.string.tgf_sent_to, container.agents.store.get(action.agentId)?.name.orEmpty()), null)
                }
                "pause", "resume" -> {
                    val dashboardId = action.dashboard ?: return text(R.string.tgf_button_expired)
                    container.dashboards.update(dashboardId) {
                        check(it.agentId == action.agentId)
                        it.copy(status = if (parts[2] == "pause") Dashboard.Status.PAUSED else Dashboard.Status.LIVE, updatedAtMs = System.currentTimeMillis())
                    }
                    dashboard(chatId, dashboardId)
                }
                "install" -> {
                    val entry = action.skill ?: return text(R.string.tgf_button_expired)
                    container.skillInstallTools.allow(entry.repo, entry.path, null)
                    container.agents.talk(action.agentId, text(R.string.skills_install_message, entry.name, entry.repo,
                        entry.path.ifEmpty { text(R.string.skills_msg_root) }, entry.ref, text(R.string.skills_msg_global)), via = "Telegram")
                    actions.remove(token)
                    send(chatId, escaped(R.string.tgf_sent_to, container.agents.store.get(action.agentId)?.name.orEmpty()), null)
                }
                "add", "decline" -> {
                    val soul = action.soul ?: return text(R.string.tgf_button_expired)
                    if (!container.agents.store.resolveSoulProposal(action.agentId, soul.id, parts[2] == "add")) return text(R.string.tgf_button_expired)
                    actions.remove(token)
                    if (messageId != null) edit(chatId, messageId, escaped(R.string.tgf_soul_proposal, soul.section, soul.text, soul.why) + "\n\n" + escaped(R.string.tgf_answered), telegramKeyboard(emptyList()))
                }
                "publish" -> {
                    if (action.session == null || action.artifact == null) return text(R.string.tgf_button_expired)
                    actions.remove(token)
                    container.scope.launch { publish(chatId, action) }
                    return text(R.string.tgf_publishing)
                }
                else -> return text(R.string.tgf_button_expired)
            }
            text(R.string.tgf_done)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { text(R.string.tgf_feature_failed) }
    }
}
