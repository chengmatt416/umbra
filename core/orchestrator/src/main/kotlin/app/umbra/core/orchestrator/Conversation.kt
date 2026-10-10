package app.umbra.core.orchestrator

import app.umbra.runtime.omp.OmpEvent
import app.umbra.runtime.omp.OmpSessionState
import app.umbra.runtime.omp.OmpSlashCommand
import app.umbra.runtime.omp.PromptStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The conversation of one session the way a GUI shows it (ADR 0013): omp's transcript of the current branch, the
 * message being streamed, the tools that are running, and everything omp reports next to it (model, thinking level,
 * queue, todos, context use, goal, extension status lines and widgets). Built from `get_messages`/`get_state` and
 * kept current from events by [reduce]; nothing here talks to omp.
 */
data class ConversationState(
    /** The transcript of the current branch, plus what only the client saw (command output, notices), in time order. */
    val items: List<ChatItem> = emptyList(),
    /** Tool executions by tool call id: arguments, live output, result. */
    val tools: Map<String, ToolRun> = emptyMap(),
    val session: OmpSessionState? = null,
    val queuedSteering: List<String> = emptyList(),
    val queuedFollowUp: List<String> = emptyList(),
    val commands: List<OmpSlashCommand> = emptyList(),
    /** Extension status lines (`setStatus`), by key. */
    val statuses: Map<String, String> = emptyMap(),
    /** Extension widgets (`setWidget`), by key. */
    val widgets: Map<String, Widget> = emptyMap(),
    /** A title an extension or `/rename` set. */
    val title: String? = null,
    /** The agent is working on a turn. */
    val working: Boolean = false,
    val compacting: Boolean = false,
    /** The provider failed and omp is retrying. */
    val retry: Retry? = null,
    /** An omp process is running for this session (otherwise this is the last saved state). */
    val live: Boolean = false,
) {
    data class Widget(val lines: List<String>, val aboveEditor: Boolean)

    data class Retry(val attempt: Int, val maxAttempts: Int, val error: String)

    /** The model in use, as `provider/id`. */
    val modelKey: String? get() = session?.model?.key

    /** Transcript items only (what omp has), without the client's own notes. */
    val transcript: List<ChatItem> get() = items.filter { it.fromTranscript }
}

/** One thing in the conversation. [key] is stable while the item exists, for lists. */
sealed interface ChatItem {
    val key: String
    val timestamp: Long

    /** Part of omp's transcript (as opposed to something only this client printed). */
    val fromTranscript: Boolean get() = true

    data class User(override val key: String, override val timestamp: Long, val text: String, val images: List<Image>, val steering: Boolean, val synthetic: Boolean) : ChatItem

    data class Assistant(
        override val key: String,
        override val timestamp: Long,
        val blocks: List<Block>,
        val provider: String?,
        val model: String?,
        val inputTokens: Long,
        val outputTokens: Long,
        val cost: Double,
        /** `stop`, `length`, `toolUse`, `error`, `aborted`. */
        val stopReason: String?,
        val error: String?,
        val streaming: Boolean,
    ) : ChatItem {
        val text: String get() = blocks.filterIsInstance<Block.Text>().joinToString("") { it.text }
    }

    data class ToolResult(override val key: String, override val timestamp: Long, val toolCallId: String, val toolName: String, val text: String, val images: List<Image>, val isError: Boolean) : ChatItem

    /** A `!command` the user ran. */
    data class Bash(override val key: String, override val timestamp: Long, val command: String, val output: String, val exitCode: Int?, val cancelled: Boolean, val truncated: Boolean) : ChatItem

    /** The conversation was compacted (or a branch summarised) here. */
    data class Summary(override val key: String, override val timestamp: Long, val compaction: Boolean, val summary: String, val tokensBefore: Long?, val tokensAfter: Long?) : ChatItem

    /** A message an extension or omp itself added (`custom`, `hookMessage`, `developer`). */
    data class Custom(override val key: String, override val timestamp: Long, val type: String, val text: String) : ChatItem

    /** Files mentioned with `@` and attached to the conversation. */
    data class Files(override val key: String, override val timestamp: Long, val paths: List<String>) : ChatItem

    /** What a slash command printed. */
    data class CommandOutput(override val key: String, override val timestamp: Long, val text: String) : ChatItem {
        override val fromTranscript: Boolean get() = false
    }

    /** A notice from omp or an extension (`info`, `warning`, `error`). */
    data class Notice(override val key: String, override val timestamp: Long, val level: String, val text: String) : ChatItem {
        override val fromTranscript: Boolean get() = false
    }
}

data class Image(val base64: String, val mimeType: String)

sealed interface Block {
    data class Text(val text: String) : Block

    data class Thinking(val text: String, val redacted: Boolean) : Block

    data class ToolCall(val id: String, val name: String, val arguments: JsonObject, val intent: String?) : Block

    data class Picture(val image: Image) : Block
}

/** A tool the agent called: arguments, live output while it runs, and the result. */
data class ToolRun(
    val id: String,
    val name: String,
    val arguments: JsonElement?,
    val intent: String?,
    val partial: String?,
    val result: String?,
    val isError: Boolean,
    val running: Boolean,
)

/** One-shot requests from omp that are not state: a URL to open (sign-in), text to put in the composer. */
sealed interface ConversationEffect {
    data class OpenUrl(val url: String, val instructions: String?) : ConversationEffect

    data class SetComposer(val text: String) : ConversationEffect
}

object ConversationReducer {
    /** The transcript replaced (start, branch, fork, switch, compaction): client notes after the last message stay. */
    fun withMessages(state: ConversationState, messages: List<JsonObject>): ConversationState {
        val transcript = messages.mapIndexedNotNull { i, m -> item(m, "m$i-${m.long("timestamp") ?: 0}") }
        val last = transcript.maxOfOrNull { it.timestamp } ?: 0
        val notes = state.items.filter { !it.fromTranscript && it.timestamp >= last }
        val results = transcript.filterIsInstance<ChatItem.ToolResult>().associateBy { it.toolCallId }
        // Tool runs are rebuilt from the transcript so a fresh client still shows what each call returned.
        val tools = transcript.filterIsInstance<ChatItem.Assistant>().flatMap { it.blocks }.filterIsInstance<Block.ToolCall>().associate { call ->
            val r = results[call.id]
            call.id to (state.tools[call.id]?.takeIf { it.running } ?: ToolRun(call.id, call.name, call.arguments, call.intent, null, r?.text, r?.isError ?: false, running = false))
        }
        return state.copy(items = (transcript + notes).sortedBy { it.timestamp }, tools = tools)
    }

    fun withSession(state: ConversationState, session: OmpSessionState): ConversationState = state.copy(
        session = session,
        queuedSteering = session.queuedSteering,
        queuedFollowUp = session.queuedFollowUp,
        working = session.isStreaming,
        compacting = session.isCompacting,
        title = session.sessionName ?: state.title,
    )

    /** Applies one event; returns the new state and an effect when the event asks the client to do something. */
    fun reduce(state: ConversationState, event: OmpEvent, now: Long): Pair<ConversationState, ConversationEffect?> = when (event) {
        OmpEvent.AgentStart -> state.copy(working = true) to null
        is OmpEvent.AgentEnd -> state.copy(working = event.isTerminal == false) to null
        is OmpEvent.MessageStart -> upsert(state, event.messageId, event.raw["message"] as? JsonObject, streaming = true) to null
        is OmpEvent.MessageUpdate -> upsert(state, event.messageId, event.raw["message"] as? JsonObject, streaming = true) to null
        is OmpEvent.MessageEnd -> upsert(state, event.messageId, event.raw["message"] as? JsonObject, streaming = false) to null
        is OmpEvent.ToolStart -> state.copy(tools = state.tools + (event.toolCallId to ToolRun(event.toolCallId, event.toolName, event.args, event.intent, null, null, false, running = true))) to null
        is OmpEvent.ToolUpdate -> state.copy(
            tools = state.tools + (event.toolCallId to (state.tools[event.toolCallId] ?: ToolRun(event.toolCallId, event.toolName, null, null, null, null, false, true)).copy(partial = resultText(event.partial))),
        ) to null
        is OmpEvent.ToolEnd -> state.copy(
            tools = state.tools + (event.toolCallId to (state.tools[event.toolCallId] ?: ToolRun(event.toolCallId, event.toolName, null, null, null, null, false, true))
                .copy(result = resultText(event.result), isError = event.isError, running = false)),
        ) to null
        is OmpEvent.QueueUpdate -> state.copy(queuedSteering = event.steering, queuedFollowUp = event.followUp) to null
        is OmpEvent.CommandsChanged -> state.copy(commands = event.commands) to null
        is OmpEvent.CommandOutput -> note(state, ChatItem.CommandOutput("out$now-${state.items.size}", now, event.text)) to null
        is OmpEvent.Notice -> note(state, ChatItem.Notice("n$now-${state.items.size}", now, event.level, event.message)) to null
        is OmpEvent.SessionInfo -> state.copy(title = event.title ?: state.title) to null
        is OmpEvent.CompactionStarted -> state.copy(compacting = true) to null
        is OmpEvent.CompactionEnded -> {
            val s = state.copy(compacting = false)
            (if (event.error != null && !event.aborted) note(s, ChatItem.Notice("c$now", now, "warning", "Compaction failed: ${event.error}")) else s) to null
        }
        is OmpEvent.RetryStarted -> state.copy(retry = ConversationState.Retry(event.attempt, event.maxAttempts, event.error)) to null
        is OmpEvent.RetryEnded -> state.copy(retry = null) to null
        is OmpEvent.Prompt -> {
            val e = event.result.error
            (if (event.result.status == PromptStatus.ERROR && e != null) note(state, ChatItem.Notice("e$now", now, "error", e.message)) else state) to null
        }
        is OmpEvent.Ui -> ui(state, event, now)
        is OmpEvent.ExtensionError -> note(state, ChatItem.Notice("x$now", now, "warning", event.error)) to null
        is OmpEvent.Closed -> state.copy(live = false, working = false, compacting = false, retry = null, tools = state.tools.mapValues { (_, t) -> if (t.running) t.copy(running = false) else t }) to null
        else -> state to null
    }

    private fun ui(state: ConversationState, event: OmpEvent.Ui, now: Long): Pair<ConversationState, ConversationEffect?> {
        val p = event.notification.payload
        return when (event.notification.method) {
            "notify" -> note(state, ChatItem.Notice("u$now-${state.items.size}", now, p.str("notifyType") ?: "info", p.str("message").orEmpty())) to null
            "setStatus" -> {
                val key = p.str("statusKey").orEmpty()
                val text = p.str("statusText")
                state.copy(statuses = if (text.isNullOrBlank()) state.statuses - key else state.statuses + (key to text)) to null
            }
            "setWidget" -> {
                val key = p.str("widgetKey").orEmpty()
                val lines = (p["widgetLines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                state.copy(widgets = if (lines == null) state.widgets - key else state.widgets + (key to ConversationState.Widget(lines, p.str("widgetPlacement") != "belowEditor"))) to null
            }
            "setTitle" -> state.copy(title = p.str("title")) to null
            "set_editor_text" -> state to ConversationEffect.SetComposer(p.str("text").orEmpty())
            "open_url" -> state to ConversationEffect.OpenUrl(p.str("launchUrl") ?: p.str("url").orEmpty(), p.str("instructions"))
            else -> state to null
        }
    }

    private fun note(state: ConversationState, item: ChatItem): ConversationState =
        if (item is ChatItem.Notice && item.text.isBlank() || item is ChatItem.CommandOutput && item.text.isBlank()) state
        else state.copy(items = state.items + item)

    /** Adds or replaces the message with this id (or, for omp versions without ids, the one with this timestamp and role). */
    private fun upsert(state: ConversationState, messageId: String?, message: JsonObject?, streaming: Boolean): ConversationState {
        message ?: return state
        val key = messageId?.let { "id-$it" } ?: "m-${message.str("role")}-${message.long("timestamp") ?: 0}"
        val item = item(message, key, streaming) ?: return state
        val index = state.items.indexOfFirst { it.key == key }
        val items = if (index >= 0) state.items.toMutableList().also { it[index] = item } else state.items + item
        var tools = state.tools
        if (item is ChatItem.ToolResult && !streaming) {
            val run = tools[item.toolCallId]
            tools = tools + (item.toolCallId to (run ?: ToolRun(item.toolCallId, item.toolName, null, null, null, null, false, false)).copy(result = item.text, isError = item.isError, running = false))
        }
        return state.copy(items = items, tools = tools, live = true)
    }

    /** One transcript message as a [ChatItem], or null for messages a client does not show. */
    fun item(m: JsonObject, key: String, streaming: Boolean = false): ChatItem? {
        val ts = m.long("timestamp") ?: 0
        return when (m.str("role")) {
            "user" -> ChatItem.User(key, ts, text(m["content"]), images(m["content"]), m.bool("steering") ?: false, m.bool("synthetic") ?: false)
            "assistant" -> {
                val usage = m["usage"] as? JsonObject
                ChatItem.Assistant(
                    key, ts,
                    blocks = (m["content"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::block) }.orEmpty(),
                    provider = m.str("provider"), model = m.str("model"),
                    inputTokens = usage?.long("input") ?: 0, outputTokens = usage?.long("output") ?: 0,
                    cost = ((usage?.get("cost") as? JsonObject)?.get("total") as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                    stopReason = m.str("stopReason"), error = m.str("errorMessage"), streaming = streaming,
                )
            }
            "toolResult" -> ChatItem.ToolResult(key, ts, m.str("toolCallId").orEmpty(), m.str("toolName").orEmpty(), text(m["content"]), images(m["content"]), m.bool("isError") ?: false)
            "bashExecution" -> ChatItem.Bash(key, ts, m.str("command").orEmpty(), m.str("output").orEmpty(), (m["exitCode"] as? JsonPrimitive)?.intOrNull, m.bool("cancelled") ?: false, m.bool("truncated") ?: false)
            "pythonExecution" -> ChatItem.Bash(key, ts, "python: " + m.str("code").orEmpty(), m.str("output").orEmpty(), (m["exitCode"] as? JsonPrimitive)?.intOrNull, m.bool("cancelled") ?: false, m.bool("truncated") ?: false)
            "compactionSummary" -> ChatItem.Summary(key, ts, true, m.str("shortSummary") ?: m.str("summary").orEmpty(), m.long("tokensBefore"), m.long("tokensAfter"))
            "branchSummary" -> ChatItem.Summary(key, ts, false, m.str("summary").orEmpty(), null, null)
            "custom", "hookMessage" -> if (m.bool("display") == false) null else ChatItem.Custom(key, ts, m.str("customType").orEmpty(), text(m["content"]))
            "developer" -> null // instructions for the model, not part of the conversation a person reads
            "fileMention" -> ChatItem.Files(key, ts, (m["files"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("path") }.orEmpty())
            else -> null
        }
    }

    private fun block(b: JsonObject): Block? = when (b.str("type")) {
        "text" -> Block.Text(b.str("text").orEmpty())
        "thinking" -> Block.Thinking(b.str("thinking").orEmpty(), false)
        "redactedThinking" -> Block.Thinking("", true)
        "toolCall" -> Block.ToolCall(b.str("id").orEmpty(), b.str("name").orEmpty(), b["arguments"] as? JsonObject ?: JsonObject(emptyMap()), b.str("intent"))
        "image" -> Block.Picture(Image(b.str("data").orEmpty(), b.str("mimeType") ?: "image/png"))
        else -> null
    }

    /** Text of message content: a string, or its text blocks. */
    fun text(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString("")
        else -> ""
    }

    private fun images(content: JsonElement?): List<Image> = (content as? JsonArray)?.mapNotNull { b ->
        (b as? JsonObject)?.takeIf { it.str("type") == "image" }?.let { Image(it.str("data").orEmpty(), it.str("mimeType") ?: "image/png") }
    }.orEmpty()

    /** A tool result as text: its content blocks (`{content:[...]}`), a plain string, or the JSON. */
    fun resultText(result: JsonElement?): String? = when (result) {
        null -> null
        is JsonPrimitive -> result.contentOrNull
        is JsonObject -> (result["content"] as? JsonArray)?.let { text(it) } ?: result.toString()
        is JsonArray -> text(result)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}

/**
 * Keeps the last transcript of each session, so a session whose omp process is not running (released when idle,
 * or the app restarted) still shows its conversation without starting omp.
 */
interface ConversationCache {
    suspend fun load(sessionId: String): List<JsonObject>?

    suspend fun save(sessionId: String, messages: List<JsonObject>)

    suspend fun delete(sessionId: String)

    object None : ConversationCache {
        override suspend fun load(sessionId: String): List<JsonObject>? = null
        override suspend fun save(sessionId: String, messages: List<JsonObject>) = Unit
        override suspend fun delete(sessionId: String) = Unit
    }
}
