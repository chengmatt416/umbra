package app.umbra.agent.telegram

import android.util.Base64
import android.util.Log
import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.agents.AgentRuntime
import app.umbra.core.model.UmbraJson
import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.safety.ApprovalRequest
import app.umbra.core.session.SessionState
import app.umbra.core.session.StoreChange
import app.umbra.runtime.omp.ContentBlock
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient

/**
 * Reaching the agents from Telegram (docs/design/agents-2026-10.md §11, Figma 14.3–14.5): the user makes a bot with
 * @BotFather, pastes its token here, and opens a one-time link from their Telegram; from then on the bot answers only
 * that account. Messages, photos, files and voice go to the current agent; its answers and what it wants to tell the
 * user come back; approvals arrive as inline buttons and are decided on the phone at once. The phone polls Telegram
 * itself (long polling): no server.
 */
class TelegramBot(private val container: AppContainer) {
    @Serializable
    data class State(
        val botId: Long = 0,
        val botUsername: String = "",
        /** The one Telegram account the bot answers, and its chat with the bot. */
        val userId: Long? = null,
        val userName: String? = null,
        val chatId: Long? = null,
        /** The one-time code of the link being opened, and until when it works. */
        val code: String? = null,
        val codeUntilMs: Long = 0,
        /** The agent the chat talks to now (/agents switches). */
        val agentId: String? = null,
        val offset: Long = 0,
        val messages: Boolean = true,
        val voice: Boolean = true,
        val approvals: Boolean = true,
        val switching: Boolean = true,
        /** The user's reactions to the bot's messages reach the agent as feedback. */
        val reactions: Boolean = true,
    )

    enum class Link { NONE, TOKEN, LINKING, LINKED }

    private val file = File(container.context.filesDir, "telegram/bot.json")
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<State> = mutable.asStateFlow()

    private val mutableHealth = MutableStateFlow<String?>(null)
    /** The last polling problem, or null while it works. */
    val health: StateFlow<String?> = mutableHealth.asStateFlow()

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(75, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    private val api = TelegramApi(http, ::token)
    private var poller: Job? = null
    private var watchers: Job? = null
    /** Approvals already shown in the chat (request id -> message id). */
    private val shown = ConcurrentHashMap<String, Long>()
    private val prefixConfirmations = ConcurrentHashMap.newKeySet<String>()
    private val criticalConfirmations = ConcurrentHashMap.newKeySet<String>()
    private val proactive by lazy { ProactiveTelegram(container, ::send) }
    /** Recent messages the bot sent (message id -> excerpt), so a reaction can say what it was about. */
    private val sent = object : LinkedHashMap<Long, String>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > 300
    }
    /** Pictures and files of one album arrive as separate messages; they reach the agent as one. */
    private val albums = ConcurrentHashMap<String, Album>()
    private class Album(val chatId: Long, val agentId: String) {
        val images = ArrayList<ContentBlock.Image>()
        val notes = ArrayList<String>()
        val captions = ArrayList<String>()
        val messageIds = ArrayList<Long>()
        var flush: Job? = null
    }
    /** Deliverable bundles already sent to the chat (session id:turn). */
    private val deliveredBundles = java.util.Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 200
    })
    /** The agent waiting for the user's words after "Edit" on an approval. */
    @Volatile private var editing: String? = null

    val link: Link get() = mutable.value.let { s ->
        when {
            token() == null -> Link.NONE
            s.userId != null -> Link.LINKED
            s.code != null && s.codeUntilMs > System.currentTimeMillis() -> Link.LINKING
            else -> Link.TOKEN
        }
    }

    /** "@mia_umbra_bot" once a Telegram account is linked, for the agent pages. */
    fun linkedName(agentId: String): String? = mutable.value.takeIf { it.userId != null && token() != null }?.let { "@" + it.botUsername }

    private fun token(): String? = container.secrets.get(SECRET)

    // ---- set-up ----

    /** Checks [token] with Telegram (getMe) and keeps it; returns the bot's username. */
    suspend fun setToken(token: String): String {
        val clean = token.trim()
        require(TOKEN.matches(clean)) { container.context.getString(R.string.tg_bad_token) }
        val me = call("getMe", JsonObject(emptyMap()), clean).jsonObject
        val username = me["username"]?.jsonPrimitive?.contentOrNull ?: error("Telegram did not name the bot")
        val id = me["id"]?.jsonPrimitive?.long ?: 0
        container.secrets.put(SECRET, clean)
        save(State(botId = id, botUsername = username))
        runCatching {
            call("setMyCommands", buildJsonObject {
                putJsonArray("commands") {
                    add(command("agents", container.context.getString(R.string.tg_cmd_agents)))
                    add(command("status", container.context.getString(R.string.tg_cmd_status)))
                    add(command("stop", container.context.getString(R.string.tg_cmd_stop)))
                    add(command("goals", "View goals and milestone progress"))
                    add(command("routines", "View authorized routines and recent runs"))
                    add(command("brief", "Read the latest daily brief"))
                    add(command("skills", "View reusable skills and their review state"))
                    add(command("suggestions", "Review and adopt prepared next steps"))
                }
            })
        }
        start()
        return username
    }

    private fun command(name: String, description: String) = buildJsonObject { put("command", name); put("description", description) }

    /** A new one-time link, valid ten minutes: t.me/<bot>?start=<code>. */
    fun newLink(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        val code = (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("").let { it.take(4) + "-" + it.drop(4) }
        save(mutable.value.copy(code = code, codeUntilMs = System.currentTimeMillis() + 10 * 60_000))
        start()
        return linkUrl()!!
    }

    fun linkUrl(): String? = mutable.value.code?.let { "https://t.me/${mutable.value.botUsername}?start=$it" }

    fun unlink() {
        save(mutable.value.copy(userId = null, userName = null, chatId = null, code = null, codeUntilMs = 0))
    }

    /** Forgets the bot entirely: the token is removed and polling stops. */
    fun forget() {
        poller?.cancel()
        container.secrets.remove(SECRET)
        save(State())
    }

    fun setOptions(change: (State) -> State) = save(change(mutable.value))

    // ---- running ----

    /** Starts polling once there is a token (at app start, after set-up). */
    fun start() {
        if (token() == null) return
        if (poller?.isActive != true) poller = container.scope.launch(Dispatchers.IO) { poll() }
        if (watchers?.isActive != true) watchers = container.scope.launch {
            launch { watchApprovals() }
            launch { watchAnswers() }
        }
    }

    /** Whether the bot keeps the phone's service alive (it is linked, or a link is open). */
    val active: Boolean get() = token() != null && (mutable.value.userId != null || link == Link.LINKING)

    private suspend fun poll() {
        var failures = 0
        while (kotlin.coroutines.coroutineContext.isActive) {
            val token = token() ?: return
            try {
                val updates = call("getUpdates", buildJsonObject {
                    put("offset", mutable.value.offset)
                    put("timeout", 50)
                    putJsonArray("allowed_updates") { add(JsonPrimitive("message")); add(JsonPrimitive("callback_query")); add(JsonPrimitive("message_reaction")) }
                }, token).jsonArray
                mutableHealth.value = null
                failures = 0
                for (u in updates) {
                    val id = u.jsonObject["update_id"]?.jsonPrimitive?.long ?: continue
                    handle(u.jsonObject)
                    save(mutable.value.copy(offset = id + 1))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                mutableHealth.value = "Telegram connection failed. Retrying."
                Log.w(TAG, "Polling failed: ${e::class.simpleName}")
                // The network is down or Doze holds it: wait a little longer each time, at most two minutes.
                delay((5_000L * failures).coerceAtMost(120_000))
            }
        }
    }

    private suspend fun handle(update: JsonObject) {
        update["callback_query"]?.jsonObject?.let { onCallback(it); return }
        update["message_reaction"]?.jsonObject?.let { onReaction(it); return }
        val message = update["message"]?.jsonObject ?: return
        val from = message["from"]?.jsonObject ?: return
        val userId = from["id"]?.jsonPrimitive?.long ?: return
        val chatId = message["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.long ?: return
        if (message["chat"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull != "private") return
        val text = message["text"]?.jsonPrimitive?.contentOrNull ?: message["caption"]?.jsonPrimitive?.contentOrNull
        val s = mutable.value

        // Linking: the one-time code from the link, within its ten minutes.
        if (s.userId == null) {
            val code = text?.removePrefix("/start")?.trim()
            if (text?.startsWith("/start") == true && s.code != null && code.equals(s.code, true) && s.codeUntilMs > System.currentTimeMillis()) {
                val agent = container.agents.defaultAgent()
                save(s.copy(userId = userId, userName = from["username"]?.jsonPrimitive?.contentOrNull ?: from["first_name"]?.jsonPrimitive?.contentOrNull, chatId = chatId, code = null, codeUntilMs = 0, agentId = agent?.id))
                send(chatId, container.context.getString(R.string.tg_linked_hello, agent?.name ?: container.context.getString(R.string.ag_default_name)))
            }
            return
        }
        // Everyone else gets nothing back.
        if (userId != s.userId || chatId != s.chatId) return

        when {
            text == "/agents" -> { if (s.switching) showAgents(chatId); return }
            text == "/status" -> { send(chatId, status()); return }
            text == "/stop" -> { stopCurrent(chatId); return }
            text?.startsWith("/start") == true -> { send(chatId, container.context.getString(R.string.tg_linked_hello, currentAgent()?.name ?: "")); return }
        }
        val agent = currentAgent() ?: run { send(chatId, container.context.getString(R.string.tg_no_agent)); return }
        if (proactive.command(chatId, text, agent)) return
        if (!s.messages) return
        val messageId = message["message_id"]?.jsonPrimitive?.longOrNull
        react(chatId, messageId, "👀")

        // What the message carries: words, pictures, files, video, a sticker, a voice note.
        val images = ArrayList<ContentBlock.Image>()
        val notes = ArrayList<String>()
        try {
            if (!attachments(message, agent.id, s, images, notes)) return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            react(chatId, messageId, "👎")
            send(chatId, container.context.getString(R.string.tg_attachment_failed))
            return
        }
        // Album parts come one by one, a moment apart: wait for the rest and deliver them together.
        message["media_group_id"]?.jsonPrimitive?.contentOrNull?.let { group ->
            val album = albums.getOrPut(group) { Album(chatId, agent.id) }
            synchronized(album) {
                album.images += images; album.notes += notes
                text?.takeIf { it.isNotBlank() }?.let { album.captions += it }
                messageId?.let { album.messageIds += it }
                album.flush?.cancel()
                album.flush = container.scope.launch { delay(ALBUM_WAIT_MS); albums.remove(group)?.let { deliverAlbum(it) } }
            }
            return
        }
        deliver(chatId, agent.id, listOfNotNull(text?.takeIf { it.isNotBlank() }) + notes, images, listOfNotNull(messageId))
    }

    private suspend fun deliverAlbum(album: Album) {
        val (words, images, ids) = synchronized(album) { Triple(album.captions + album.notes, album.images.take(MAX_IMAGES), album.messageIds.toList()) }
        val extra = synchronized(album) { album.images.size } - images.size
        deliver(album.chatId, album.agentId, if (extra > 0) words + container.context.getString(R.string.tg_album_more, extra) else words, images, ids)
    }

    private suspend fun deliver(chatId: Long, agentId: String, words: List<String>, images: List<ContentBlock.Image>, messageIds: List<Long>) {
        if (words.isEmpty() && images.isEmpty()) return
        // "Edit" on an approval: the next words say how to change it.
        val edit = editing
        val body = if (edit == agentId) { editing = null; container.context.getString(R.string.tg_edit_prefix) + " " + words.joinToString("\n") } else words.joinToString("\n").ifEmpty { "(picture)" }
        typing(chatId)
        try {
            container.agents.talk(agentId, body, images, via = "Telegram")
            messageIds.forEach { react(chatId, it, "👍") }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            messageIds.forEach { react(chatId, it, "👎") }
            send(chatId, container.context.getString(R.string.tg_delivery_failed))
        }
    }

    /** Reads what [message] carries into [images] and [notes]; false when it should be dropped (voice is off). */
    private suspend fun attachments(message: JsonObject, agentId: String, s: State, images: MutableList<ContentBlock.Image>, notes: MutableList<String>): Boolean {
        message["photo"]?.jsonArray?.lastOrNull()?.jsonObject?.get("file_id")?.jsonPrimitive?.contentOrNull?.let { fileId ->
            images += app.umbra.agent.ui.current.encodePicture(api.download(fileId)) ?: error("This picture could not be read")
        }
        // Documents, videos, animations and video notes: pictures go to the model, the rest into the agent's files.
        for ((key, fallback) in listOf("document" to "file", "video" to "video.mp4", "animation" to "animation.mp4", "video_note" to "video-note.mp4")) {
            val doc = message[key]?.jsonObject ?: continue
            val fileId = doc["file_id"]?.jsonPrimitive?.contentOrNull ?: continue
            val name = doc["file_name"]?.jsonPrimitive?.contentOrNull ?: fallback
            val mime = doc["mime_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val bytes = api.download(fileId)
            val image = if (mime.startsWith("image/")) app.umbra.agent.ui.current.encodePicture(bytes) else null
            if (image != null) images += image
            else notes += container.context.getString(R.string.tg_file_saved, name, saveForAgent(agentId, name, bytes))
        }
        message["sticker"]?.jsonObject?.let { sticker ->
            notes += container.context.getString(R.string.tg_sticker, sticker["emoji"]?.jsonPrimitive?.contentOrNull ?: "🙂")
        }
        val voiceId = (message["voice"] ?: message["audio"])?.jsonObject?.get("file_id")?.jsonPrimitive?.contentOrNull
        if (voiceId != null) {
            if (!s.voice) return false
            val heard = VoiceTranscriber(container.context).transcribe(api.download(voiceId))
            notes += if (!heard.isNullOrBlank()) container.context.getString(R.string.tg_voice_heard, heard) else container.context.getString(R.string.tg_voice_unheard)
        }
        return true
    }

    /** A reaction the user puts on one of the bot's messages: feedback for the agent, which answers only if needed. */
    private suspend fun onReaction(r: JsonObject) {
        val s = mutable.value
        val chat = r["chat"]?.jsonObject ?: return
        val chatId = chat["id"]?.jsonPrimitive?.long ?: return
        val userId = r["user"]?.jsonObject?.get("id")?.jsonPrimitive?.long ?: return
        if (chat["type"]?.jsonPrimitive?.contentOrNull != "private" || s.userId == null || userId != s.userId || chatId != s.chatId) return
        if (!s.messages || !s.reactions) return
        val messageId = r["message_id"]?.jsonPrimitive?.long ?: return
        val added = reactionEmojis(r["new_reaction"]) - reactionEmojis(r["old_reaction"]).toSet()
        if (added.isEmpty()) return
        val agent = currentAgent()?.takeIf { it.running } ?: return
        val about = synchronized(sent) { sent[messageId] }
        val body = if (about != null) container.context.getString(R.string.tg_reaction_note, added.joinToString(" "), about, AgentRuntime.QUIET)
            else container.context.getString(R.string.tg_reaction_note_unknown, added.joinToString(" "), AgentRuntime.QUIET)
        try { container.agents.talk(agent.id, body, via = "Telegram") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* A reaction is optional feedback; a stopped agent simply misses it. */ }
    }

    private fun reactionEmojis(list: JsonElement?): List<String> = (list as? JsonArray).orEmpty().mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        when (o["type"]?.jsonPrimitive?.contentOrNull) {
            "emoji" -> o["emoji"]?.jsonPrimitive?.contentOrNull
            "custom_emoji" -> "(custom emoji)"
            "paid" -> "⭐"
            else -> null
        }
    }

    private fun currentAgent() = mutable.value.agentId?.let { container.agents.store.get(it) }?.takeIf { it.running } ?: container.agents.defaultAgent()

    private suspend fun showAgents(chatId: Long) {
        val agents = container.agents.profiles()
        if (agents.isEmpty()) { send(chatId, container.context.getString(R.string.tg_no_agent)); return }
        val current = currentAgent()?.id
        val keyboard = buildJsonObject {
            putJsonArray("inline_keyboard") {
                agents.forEach { p ->
                    add(buildJsonArray { add(button((if (p.id == current) "● " else "") + p.name + if (!p.running) " (" + container.context.getString(R.string.ag_state_off) + ")" else "", "ag:${p.id}")) })
                }
            }
        }
        send(chatId, container.context.getString(R.string.tg_pick_agent), keyboard)
    }

    private fun status(): String = container.agents.profiles().joinToString("\n") { p ->
        val st = container.agents.store.state(p.id)
        "${if (p.running) "●" else "○"} ${p.name}: " + container.context.getString(R.string.ag_counts, st.wakesToday, st.runsToday)
    }.ifEmpty { container.context.getString(R.string.tg_no_agent) }

    private suspend fun stopCurrent(chatId: Long) {
        val agent = currentAgent() ?: return
        container.agents.setRunning(agent.id, false)
        send(chatId, container.context.getString(R.string.tg_stopped, agent.name))
    }

    private suspend fun onCallback(q: JsonObject) {
        val id = q["id"]?.jsonPrimitive?.contentOrNull ?: return
        val userId = q["from"]?.jsonObject?.get("id")?.jsonPrimitive?.long
        val data = q["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val message = q["message"]?.jsonObject
        val chatId = message?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.long
        val messageId = message?.get("message_id")?.jsonPrimitive?.long
        if (userId == null || userId != mutable.value.userId || chatId != mutable.value.chatId || message?.get("chat")?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull != "private") { answer(id, null); return }
        if (data.startsWith("op:")) { answer(id, proactive.callback(chatId!!, data)); return }
        when {
            data.startsWith("ag:") -> {
                val agentId = data.removePrefix("ag:")
                val p = container.agents.store.get(agentId)
                if (p != null) save(mutable.value.copy(agentId = agentId))
                answer(id, p?.name)
                if (chatId != null && p != null) send(chatId, container.context.getString(R.string.tg_now_talking, p.name))
            }
            data.startsWith("ap:") -> {
                val parts = data.split(':')
                val requestId = parts.getOrNull(1) ?: return
                val request = container.approvals.pending.value.firstOrNull { it.id == requestId }
                if (request == null) { answer(id, container.context.getString(R.string.tg_approval_gone)); return }
                val action = parts.getOrNull(2)
                val prefixScope = app.umbra.core.safety.RememberScope.THIS_COMMAND_FOREVER
                val isCritical = request.assessment.level >= app.umbra.core.safety.RiskLevel.CRITICAL
                if (action == "p" || (action == "y" && isCritical)) {
                    val prefix = action == "p"
                    if (prefix && (prefixScope !in request.rememberOptions || isCritical)) { answer(id, container.context.getString(R.string.tg_approval_gone)); return }
                    if (chatId == null) return
                    val text = if (prefix) container.context.getString(R.string.appr_prefix_confirm, request.details["command_prefix"].orEmpty())
                        else container.context.getString(R.string.appr_critical_body)
                    val keyboard = buildJsonObject { putJsonArray("inline_keyboard") { add(buildJsonArray {
                        add(button(container.context.getString(if (prefix) R.string.appr_remember_forever else R.string.appr_critical_confirm), "ap:$requestId:${if (prefix) "f" else "c"}"))
                        add(button(container.context.getString(R.string.appr_deny), "ap:$requestId:n"))
                    }) } }
                    send(chatId, text, keyboard)
                    if (prefix) prefixConfirmations.add(requestId) else criticalConfirmations.add(requestId)
                    answer(id, null)
                    return
                }
                val outcome = when (action) {
                    "y", "c", "f" -> {
                        val permitted = when (action) {
                            "f" -> prefixConfirmations.remove(requestId) && prefixScope in request.rememberOptions && !isCritical
                            "c" -> criticalConfirmations.remove(requestId)
                            else -> !isCritical
                        }
                        val decision = ApprovalDecision.Approved(if (action == "f") prefixScope else null)
                        if (permitted && container.approvals.resolve(requestId, decision)) container.context.getString(R.string.tg_approved)
                        else container.context.getString(R.string.tg_approval_gone)
                    }
                    "e" -> {
                        container.approvals.resolve(requestId, ApprovalDecision.Denied("the user wants to change it; their words follow"))
                        editing = container.agents.store.bySession(request.sessionId)?.id ?: currentAgent()?.id
                        container.context.getString(R.string.tg_edit_ask)
                    }
                    else -> { container.approvals.resolve(requestId, ApprovalDecision.Denied("not now (from Telegram)")); container.context.getString(R.string.tg_denied) }
                }
                answer(id, outcome)
                if (chatId != null && messageId != null) runCatching {
                    call("editMessageReplyMarkup", buildJsonObject { put("chat_id", chatId); put("message_id", messageId); put("reply_markup", buildJsonObject { putJsonArray("inline_keyboard") {} }) })
                    send(chatId, outcome)
                }
            }
            else -> answer(id, null)
        }
    }

    /** Approvals of the agents' work (sending a message for the user, spending, deleting) become inline buttons. */
    private suspend fun watchApprovals() {
        container.approvals.pending.collect { list ->
            val s = mutable.value
            val chatId = s.chatId ?: return@collect
            if (!s.approvals) return@collect
            for (request in list) {
                if (shown.containsKey(request.id)) continue
                if (!fromAgent(request)) continue
                shown[request.id] = 0
                val keyboard = buildJsonObject {
                    putJsonArray("inline_keyboard") {
                        add(buildJsonArray {
                            add(button(container.context.getString(R.string.appr_approve_once), "ap:${request.id}:y"))
                            add(button(container.context.getString(R.string.ag_edit), "ap:${request.id}:e"))
                            add(button(container.context.getString(R.string.ag_dont), "ap:${request.id}:n"))
                        })
                        if (app.umbra.core.safety.RememberScope.THIS_COMMAND_FOREVER in request.rememberOptions && request.assessment.level < app.umbra.core.safety.RiskLevel.CRITICAL) {
                            add(buildJsonArray { add(button(container.context.getString(R.string.appr_remember_forever), "ap:${request.id}:p")) })
                        }
                    }
                }
                val who = container.agents.store.bySession(request.sessionId)?.name ?: container.sessionStore.get(request.sessionId)?.agentId?.let { container.agents.store.get(it)?.name }
                runCatching { send(chatId, listOfNotNull(who, request.description, app.umbra.agent.data.approvalExecutionDetails(request).takeIf { it.isNotBlank() }).joinToString("\n\n"), keyboard) }
            }
            val pendingIds = list.map { it.id }.toSet()
            shown.keys.retainAll(pendingIds)
            prefixConfirmations.retainAll(pendingIds)
            criticalConfirmations.retainAll(pendingIds)
        }
    }

    private suspend fun fromAgent(request: ApprovalRequest): Boolean {
        if (container.agents.store.bySession(request.sessionId) != null) return true
        return container.sessionStore.get(request.sessionId)?.agentId != null
    }

    /** An agent's answer to words that came from Telegram goes back there, and so does what it wants the user to know. */
    private suspend fun watchAnswers() {
        val sentFor = ConcurrentHashMap<String, Long>()
        container.sessionStore.changes.collect { change ->
            val session = (change as? StoreChange.SessionChanged)?.session ?: return@collect
            val p = session.agentId?.let { container.agents.store.get(it) }?.takeIf { it.sessionId == session.id } ?: return@collect
            if (session.state != SessionState.COMPLETED && session.state != SessionState.FAILED) return@collect
            if (app.umbra.core.orchestrator.CompletionEvidence.awaitingDelegates(session,
                    container.sessionStore.list(app.umbra.core.session.SessionFilter(limit = Int.MAX_VALUE)))) return@collect
            if (sentFor[session.id]?.let { session.updatedAtMs - it < 1_500 } == true) return@collect
            sentFor[session.id] = session.updatedAtMs
            val chatId = mutable.value.chatId ?: return@collect
            val asked = container.agents.repliesOnTelegram(p.id)
            val text = if (session.state == SessionState.FAILED) container.context.getString(
                R.string.notif_failed_text, session.title, session.lastError ?: container.context.getString(R.string.notif_see_task),
            ) else container.supervisor.finalText(session.id).trim()
            if (text.isEmpty() || text.startsWith(AgentRuntime.QUIET)) return@collect
            // Answers to Telegram always; the agent's own news only while the user is not looking at its chat.
            if (asked || container.agents.onScreen.value != p.id) runCatching { send(chatId, if (asked) text else "${p.name}: $text") }
            if (asked && session.state == SessionState.COMPLETED) sendDeliverables(chatId, session)
        }
    }

    /** Files the agent published for this turn go to the chat as documents, each checked against its receipt. */
    private suspend fun sendDeliverables(chatId: Long, session: app.umbra.core.session.Session) {
        val recipient = mutable.value
        if (recipient.userId == null || recipient.chatId != chatId || !recipient.messages) return
        val bundle = session.attachments[app.umbra.core.orchestrator.CompletionEvidence.KEY] as? JsonObject ?: return
        val turn = (bundle["turnStartSeq"] as? JsonPrimitive)?.longOrNull ?: return
        if (!deliveredBundles.add("${session.id}:$turn")) return
        val artifacts = (bundle["artifacts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(10)
        val dir = File(container.context.cacheDir, "telegram/out")
        for (artifact in artifacts) {
            val title = artifact["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            var copy: File? = null
            try {
                copy = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    app.umbra.agent.data.DeliverableFiles.copyVerified(container, session, artifact, dir, TelegramApi.MAX_UPLOAD_BYTES)
                }
                val guard = app.umbra.core.safety.OutboundDlpGuard(4) { container.protectedValues() }
                val upload = requireNotNull(copy)
                kotlinx.coroutines.withContext(Dispatchers.IO) { upload.reader(Charsets.UTF_8).use { guard.requireSafe(it, TelegramApi.MAX_UPLOAD_BYTES) } }
                val current = mutable.value
                check(current.userId == recipient.userId && current.chatId == recipient.chatId && current.botId == recipient.botId && current.messages) { "Telegram delivery settings changed" }
                api.sendDocument(chatId, upload, upload.name, artifact["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty(), guard.redact(title))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { send(chatId, container.context.getString(R.string.tg_file_not_sent, title.ifBlank { "file" }, e.message ?: "")) }
            } finally {
                copy?.let { f -> f.delete(); f.parentFile?.delete() }
            }
        }
    }

    fun receivesSuggestions(agentId: String): Boolean = mutable.value.let {
        it.userId != null && it.chatId != null && it.messages && currentAgent()?.id == agentId
    }

    suspend fun notifyAgent(text: String, agentId: String? = null, suggestionId: String? = null) {
        val state = mutable.value
        val chatId = state.chatId ?: error("Telegram is not linked")
        if (agentId != null && suggestionId != null && state.userId != null && state.messages) {
            val profile = container.agents.store.get(agentId)
            if (profile?.running == true && proactive.offer(chatId, profile, suggestionId)) return
        }
        send(chatId, text)
    }

    // ---- Telegram's API ----

    private fun button(text: String, data: String) = buildJsonObject { put("text", text); put("callback_data", data) }

    suspend fun send(chatId: Long, text: String, markup: JsonObject? = null) {
        // Telegram takes 4096 characters a message.
        val guard = app.umbra.core.safety.OutboundDlpGuard(4) { container.protectedValues() }
        markup?.let { app.umbra.core.safety.ModelPayloadGuard(guard).requireSafe(it) }
        val parts = telegramMessageParts(guard.redact(text))
        parts.forEachIndexed { i, part ->
            val result = call("sendMessage", buildJsonObject {
                put("chat_id", chatId)
                put("text", part)
                if (markup != null && i == parts.lastIndex) put("reply_markup", markup)
            })
            (result as? JsonObject)?.get("message_id")?.jsonPrimitive?.longOrNull?.let { id -> synchronized(sent) { sent[id] = part.take(300) } }
        }
    }

    private suspend fun typing(chatId: Long) = runCatching { call("sendChatAction", buildJsonObject { put("chat_id", chatId); put("action", "typing") }) }

    private suspend fun answer(callbackId: String, text: String?) = runCatching {
        call("answerCallbackQuery", buildJsonObject { put("callback_query_id", callbackId); if (text != null) put("text", text.take(190)) })
    }

    private suspend fun react(chatId: Long, messageId: Long?, emoji: String) {
        if (messageId == null) return
        try { api.react(chatId, messageId, emoji) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Reactions are optional in Telegram chats. */ }
    }

    /** A file the user sent: into the agent's workspace, where its tools can read it. Returns the path the agent sees. */
    private suspend fun saveForAgent(agentId: String, name: String, bytes: ByteArray): String {
        val sid = requireNotNull(container.agents.store.get(agentId)?.sessionId) { "No agent session" }
        val session = requireNotNull(container.sessionStore.get(sid)) { "No agent session" }
        val dir = session.workspaceId?.let { container.workspaceLayout.projectDir(it) } ?: container.workspaceLayout.sessionDir(sid).resolve("files")
        val target = requireNotNull(container.workspaceLayout.toGuest(dir.resolve("telegram").resolve(TelegramApi.attachmentName(name)))) { "The agent workspace is unavailable" }
        val quote = app.umbra.platform.shell.ProcessShell.Companion::quote
        val result = container.guest.runtime.exec(listOf("bash", "-c", "umask 077; mkdir -p -- ${quote(target.substringBeforeLast('/'))} && (set -C; base64 -d > ${quote(target)})"),
            stdin = Base64.encodeToString(bytes, Base64.NO_WRAP), timeoutMs = 60_000, maxOutputBytes = 1024)
        check(result.ok) { "The attachment could not be saved" }
        return target
    }

    private suspend fun call(method: String, body: JsonObject, token: String? = token()): JsonElement = api.call(method, body, token)

    private fun save(s: State) {
        val previous = mutable.value
        if (s.botId != previous.botId || s.userId != previous.userId || s.chatId != previous.chatId || previous.messages && !s.messages)
            proactive.clearReviews()
        mutable.value = s
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(UmbraJson.encodeToString(State.serializer(), s))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    private fun load(): State = file.takeIf { it.exists() }?.let { runCatching { UmbraJson.decodeFromString(State.serializer(), it.readText()) }.getOrNull() } ?: State()

    companion object {
        private const val TAG = "TelegramBot"
        private const val ALBUM_WAIT_MS = 1_500L
        private const val MAX_IMAGES = 8
        const val SECRET = "TELEGRAM_BOT_TOKEN"
        private val TOKEN = Regex("""^\d{5,16}:[A-Za-z0-9_-]{30,64}$""")
    }
}
