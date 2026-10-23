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
import kotlinx.coroutines.withContext
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
    )

    enum class Link { NONE, TOKEN, LINKING, LINKED }

    private val file = File(container.context.filesDir, "telegram/bot.json")
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<State> = mutable.asStateFlow()

    private val mutableHealth = MutableStateFlow<String?>(null)
    /** The last polling problem, or null while it works. */
    val health: StateFlow<String?> = mutableHealth.asStateFlow()

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(75, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    private var poller: Job? = null
    private var watchers: Job? = null
    /** Approvals already shown in the chat (request id -> message id). */
    private val shown = ConcurrentHashMap<String, Long>()
    private val prefixConfirmations = ConcurrentHashMap.newKeySet<String>()
    private val criticalConfirmations = ConcurrentHashMap.newKeySet<String>()
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
                    putJsonArray("allowed_updates") { add(JsonPrimitive("message")); add(JsonPrimitive("callback_query")) }
                }, token).jsonArray
                mutableHealth.value = null
                failures = 0
                for (u in updates) {
                    val id = u.jsonObject["update_id"]?.jsonPrimitive?.long ?: continue
                    save(mutable.value.copy(offset = id + 1))
                    runCatching { handle(u.jsonObject) }.onFailure { Log.w(TAG, "update $id", it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                mutableHealth.value = e.message ?: e::class.simpleName
                // The network is down or Doze holds it: wait a little longer each time, at most two minutes.
                delay((5_000L * failures).coerceAtMost(120_000))
            }
        }
    }

    private suspend fun handle(update: JsonObject) {
        update["callback_query"]?.jsonObject?.let { onCallback(it); return }
        val message = update["message"]?.jsonObject ?: return
        val from = message["from"]?.jsonObject ?: return
        val userId = from["id"]?.jsonPrimitive?.long ?: return
        val chatId = message["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.long ?: return
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
        if (userId != s.userId) return

        when {
            text == "/agents" -> { if (s.switching) showAgents(chatId); return }
            text == "/status" -> { send(chatId, status()); return }
            text == "/stop" -> { stopCurrent(chatId); return }
            text?.startsWith("/start") == true -> { send(chatId, container.context.getString(R.string.tg_linked_hello, currentAgent()?.name ?: "")); return }
        }
        val agent = currentAgent() ?: run { send(chatId, container.context.getString(R.string.tg_no_agent)); return }
        if (!s.messages) return

        // What the message carries: words, a picture, a file, a voice note.
        val images = ArrayList<ContentBlock.Image>()
        val notes = ArrayList<String>()
        message["photo"]?.jsonArray?.lastOrNull()?.jsonObject?.get("file_id")?.jsonPrimitive?.contentOrNull?.let { fileId ->
            download(fileId)?.let { images += ContentBlock.Image(Base64.encodeToString(it, Base64.NO_WRAP), "image/jpeg") }
        }
        message["document"]?.jsonObject?.let { doc ->
            val fileId = doc["file_id"]?.jsonPrimitive?.contentOrNull ?: return@let
            val name = doc["file_name"]?.jsonPrimitive?.contentOrNull ?: "file"
            val mime = doc["mime_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val bytes = download(fileId) ?: return@let
            if (mime.startsWith("image/") && bytes.size < 8_000_000) images += ContentBlock.Image(Base64.encodeToString(bytes, Base64.NO_WRAP), mime)
            else saveForAgent(agent.id, name, bytes)?.let { notes += container.context.getString(R.string.tg_file_saved, name, it) }
        }
        val voiceId = (message["voice"] ?: message["audio"])?.jsonObject?.get("file_id")?.jsonPrimitive?.contentOrNull
        if (voiceId != null) {
            if (!s.voice) return
            val bytes = download(voiceId)
            val heard = bytes?.let { runCatching { VoiceTranscriber(container.context).transcribe(it) }.getOrNull() }
            notes += if (!heard.isNullOrBlank()) container.context.getString(R.string.tg_voice_heard, heard) else container.context.getString(R.string.tg_voice_unheard)
        }
        val words = listOfNotNull(text?.takeIf { it.isNotBlank() }) + notes
        if (words.isEmpty() && images.isEmpty()) return

        // "Edit" on an approval: the next words say how to change it.
        val edit = editing
        val body = if (edit == agent.id) { editing = null; container.context.getString(R.string.tg_edit_prefix) + " " + words.joinToString("\n") } else words.joinToString("\n").ifEmpty { "(picture)" }
        typing(chatId)
        runCatching { container.agents.talk(agent.id, body, images, via = "Telegram") }.onFailure { send(chatId, it.message ?: "error") }
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
        val sid = agent.sessionId ?: return
        if (container.sessionStore.get(sid)?.state == SessionState.RUNNING) runCatching { container.supervisor.pause(sid) }
        send(chatId, container.context.getString(R.string.tg_stopped, agent.name))
    }

    private suspend fun onCallback(q: JsonObject) {
        val id = q["id"]?.jsonPrimitive?.contentOrNull ?: return
        val userId = q["from"]?.jsonObject?.get("id")?.jsonPrimitive?.long
        val data = q["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val message = q["message"]?.jsonObject
        val chatId = message?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.long
        val messageId = message?.get("message_id")?.jsonPrimitive?.long
        if (userId == null || userId != mutable.value.userId) { answer(id, null); return }
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
        }
    }

    suspend fun notifyAgent(text: String) {
        val chatId = mutable.value.chatId ?: error("Telegram is not linked")
        send(chatId, text)
    }

    // ---- Telegram's API ----

    private fun button(text: String, data: String) = buildJsonObject { put("text", text); put("callback_data", data) }

    suspend fun send(chatId: Long, text: String, markup: JsonObject? = null) {
        // Telegram takes 4096 characters a message.
        val parts = telegramMessageParts(text)
        parts.forEachIndexed { i, part ->
            call("sendMessage", buildJsonObject {
                put("chat_id", chatId)
                put("text", part)
                if (markup != null && i == parts.lastIndex) put("reply_markup", markup)
            })
        }
    }

    private suspend fun typing(chatId: Long) = runCatching { call("sendChatAction", buildJsonObject { put("chat_id", chatId); put("action", "typing") }) }

    private suspend fun answer(callbackId: String, text: String?) = runCatching {
        call("answerCallbackQuery", buildJsonObject { put("callback_query_id", callbackId); if (text != null) put("text", text.take(190)) })
    }

    private suspend fun download(fileId: String): ByteArray? = withContext(Dispatchers.IO) {
        val token = token() ?: return@withContext null
        val path = runCatching { call("getFile", buildJsonObject { put("file_id", fileId) }).jsonObject["file_path"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return@withContext null
        runCatching {
            http.newCall(Request.Builder().url("https://api.telegram.org/file/bot$token/$path").build()).execute().use { r -> if (r.isSuccessful) r.body.bytes().takeIf { it.size <= 20_000_000 } else null }
        }.getOrNull()
    }

    /** A file the user sent: into the agent's workspace, where its tools can read it. Returns the path the agent sees. */
    private suspend fun saveForAgent(agentId: String, name: String, bytes: ByteArray): String? {
        val sid = container.agents.store.get(agentId)?.sessionId ?: return null
        val session = container.sessionStore.get(sid) ?: return null
        val dir = session.workspaceId?.let { container.workspaceLayout.projectDir(it) } ?: container.workspaceLayout.sessionDir(sid).resolve("files")
        val target = dir.resolve("telegram").resolve(name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)).toFile()
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return container.workspaceLayout.toGuest(target.toPath())
    }

    private suspend fun call(method: String, body: JsonObject, token: String? = token()): JsonElement = withContext(Dispatchers.IO) {
        val key = token ?: error("no bot token")
        val request = Request.Builder().url("https://api.telegram.org/bot$key/$method")
            .post(UmbraJson.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON)).build()
        http.newCall(request).execute().use { r ->
            val parsed = runCatching { UmbraJson.parseToJsonElement(r.body.string()).jsonObject }.getOrNull()
            if (parsed?.get("ok")?.jsonPrimitive?.contentOrNull != "true") {
                throw IllegalStateException(parsed?.get("description")?.jsonPrimitive?.contentOrNull ?: "Telegram answered ${r.code}")
            }
            parsed["result"] ?: JsonObject(emptyMap())
        }
    }

    private fun save(s: State) {
        mutable.value = s
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(UmbraJson.encodeToString(State.serializer(), s))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    private fun load(): State = file.takeIf { it.exists() }?.let { runCatching { UmbraJson.decodeFromString(State.serializer(), it.readText()) }.getOrNull() } ?: State()

    companion object {
        private const val TAG = "TelegramBot"
        const val SECRET = "TELEGRAM_BOT_TOKEN"
        private val TOKEN = Regex("""^\d{5,16}:[A-Za-z0-9_-]{30,64}$""")
        private val JSON = "application/json".toMediaType()
    }
}
