package app.umbra.core.orchestrator

import app.umbra.core.session.Effort
import app.umbra.core.session.ModelRef
import app.umbra.core.session.Session
import app.umbra.core.session.SessionKind
import app.umbra.core.session.SessionState
import app.umbra.core.session.SessionStore
import app.umbra.runtime.omp.HostToolCall
import app.umbra.runtime.omp.HostToolDefinition
import app.umbra.runtime.omp.HostToolPayload
import app.umbra.runtime.omp.HostToolProgress
import app.umbra.runtime.omp.objectSchema
import app.umbra.tools.HostSideTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The tools that let a session work with other sessions (docs/design/agents-2026-10.md §3, §4):
 *
 * - `sessions_list`, `session_send`, `session_reply`, `session_read`: talk to any session or agent by its handle.
 * - `subagent_start`, `subagent_status`, `subagent_send`, `subagent_wait`, `subagent_stop`: child sessions that work
 *   in the same folder with the same permissions, on a model and effort the parent chooses. A subagent's answer comes
 *   back into the parent's conversation each time it finishes a turn.
 */
class SessionTools(
    private val bus: SessionBus,
    private val supervisor: AgentSupervisor,
    private val store: SessionStore,
    /** Turns a model name the agent wrote (`provider/id`, a bare id, or `auto`) into a model of a connected account. */
    private val resolveModel: suspend (String) -> ModelRef? = { name -> name.split('/', limit = 2).takeIf { it.size == 2 }?.let { ModelRef(it[0], it[1]) } },
) {
    fun forSession(sessionId: String, allowSubagents: Boolean = true): List<HostSideTool> = buildList {
        add(tool("sessions_list", LIST_DESCRIPTION, objectSchema {
            boolean("all", "Include finished sessions too (default: active sessions and the last few finished).")
            string("query", "Search session titles and original requests.")
            integer("offset", "Continue a full listing after this many sessions (default 0).")
            integer("limit", "Full-list page size (default 50, at most 100).")
        }) { args, _ -> list(sessionId, args.bool("all") == true, args.str("query"), (args.int("offset") ?: 0).coerceAtLeast(0), (args.int("limit") ?: 50).coerceIn(1, 100)) })
        add(tool("session_send", SEND_DESCRIPTION, objectSchema {
            string("to", "Who: a #handle, an @agent name or a session id, as sessions_list shows them.", required = true)
            string("message", "What to say. Write it so it stands on its own: the other session did not see your conversation.", required = true)
            boolean("urgent", "Interrupt what the other session is doing now (default: it reads this after its current turn).")
        }) { args, _ -> send(sessionId, args) })
        add(tool("session_reply", "Answer a message another session sent you (its id is in the message header, e.g. m7). The answer arrives in the sender's conversation.", objectSchema {
            string("message_id", "The id from the message header, e.g. \"m7\".", required = true)
            string("message", "Your answer.", required = true)
        }) { args, _ ->
            val e = bus.reply(sessionId, args.str("message_id") ?: return@tool err("message_id is missing"), args.str("message") ?: return@tool err("message is missing"))
            HostToolPayload.text("Replied (${e.id}).")
        })
        add(tool("session_read", "Read a session title and result summary first. Request history=true only when full persisted messages and activity are needed; page with after_seq.", objectSchema {
            string("who", "A #handle, @agent or session id.", required = true)
            boolean("history", "Return full redacted persisted history instead of only the title and summary (default false).")
            integer("after_seq", "Number of whole transcript messages already read (default 0).")
            integer("offset", "Character offset within the next message; copy next_offset from the previous page (default 0).")
            integer("lines", "History page size (default 20, at most 100).")
        }) { args, _ -> read(args.str("who") ?: return@tool err("who is missing"), args.bool("history") == true, (args.int("after_seq") ?: 0).toLong().coerceAtLeast(0), (args.int("lines") ?: 20).coerceIn(1, 100), (args.int("offset") ?: 0).coerceAtLeast(0)) })

        if (allowSubagents) {
            add(tool("subagent_start", START_DESCRIPTION, objectSchema {
                string("task", "The work, complete and self-contained: what to do, where (paths), what done looks like, what to report back.", required = true)
                string("name", "A short handle for it, e.g. \"token-split\" (it becomes #token-split).", required = true)
                string("model", "Which model: \"auto\" (default: the same as yours), or provider/id such as \"anthropic/claude-sonnet-5-5\". A light model suits mechanical work.")
                enum("effort", listOf("auto", "minimal", "low", "medium", "high", "xhigh", "max"), "How hard it thinks (default: auto).")
                boolean("wait", "Wait until it finishes and return its answer (default false: it runs while you continue, and its answer arrives as a message).")
            }) { args, progress -> start(sessionId, args, progress) })
            add(tool("subagent_status", "How a subagent is doing: its state, model, effort and what it did lately.", objectSchema {
                string("name", "Its handle (with or without #).", required = true)
                integer("lines", "How many recent lines of activity (default 15).")
            }) { args, _ -> status(sessionId, args.str("name") ?: return@tool err("name is missing"), (args.int("lines") ?: 15).coerceIn(1, 60)) })
            add(tool("subagent_send", "Write to one of your subagents: an instruction, a correction or an answer to its question. Its reply comes back as a message when it finishes the turn.", objectSchema {
                string("name", "Its handle.", required = true)
                string("message", "What to tell it.", required = true)
                boolean("urgent", "Interrupt its current step (default false).")
            }) { args, _ ->
                val child = child(sessionId, args.str("name") ?: return@tool err("name is missing")) ?: return@tool err("You have no subagent called '${args.str("name")}'.")
                val e = bus.send(sessionId, child.id, args.str("message") ?: return@tool err("message is missing"), urgent = args.bool("urgent") == true)
                HostToolPayload.text("Sent to ${bus.address(child)} (${e.id}). Its answer arrives as a message when it finishes its turn.")
            })
            add(tool("subagent_wait", "Wait for subagents to finish their current work and get their answers.", objectSchema {
                stringArray("names", "Handles to wait for (default: all of yours that are working).")
                integer("timeout_s", "Give up waiting after this many seconds (default 600, at most 1800).")
            }) { args, progress -> wait(sessionId, args, progress) })
            add(tool("subagent_stop", "Stop a subagent (its work so far stays in the folder).", objectSchema {
                string("name", "Its handle.", required = true)
            }) { args, _ ->
                val child = child(sessionId, args.str("name") ?: return@tool err("name is missing")) ?: return@tool err("You have no subagent called '${args.str("name")}'.")
                supervisor.stop(child.id, "stopped by ${bus.address(store.get(sessionId) ?: child)}")
                HostToolPayload.text("Stopped ${bus.address(child)}.")
            })
        }
    }

    // ---- the tools ----

    private suspend fun list(selfId: String, all: Boolean, query: String?, offset: Int, limit: Int): HostToolPayload {
        val sessions = store.list(app.umbra.core.session.SessionFilter(query = query, limit = Int.MAX_VALUE)).filter { it.id != selfId }
        val active = sessions.filter { !it.state.isTerminal || it.kind == SessionKind.AGENT }
        val shown = if (all) sessions.drop(offset).take(limit) else active + sessions.filter { it.state.isTerminal && it.kind != SessionKind.AGENT }.take(5)
        if (shown.isEmpty()) return HostToolPayload.text("No other sessions or agents.")
        val text = shown.distinctBy { it.id }.joinToString("\n") { s ->
            val kind = when (s.kind) { SessionKind.AGENT -> "agent"; SessionKind.SUBAGENT -> "subagent of " + (s.spawnedBy ?: "?"); SessionKind.CODING -> "coding" }
            "${bus.address(s)} · $kind · ${s.state.name.lowercase()} · ${s.title.take(70)}" + (s.resultSummary?.let { "\n  Summary: ${it.take(600)}" } ?: "") + (s.plan.firstOrNull { it.status == "active" }?.let { " · now: ${it.text.take(60)}" } ?: "")
        }
        return HostToolPayload.text(text + (if (all) "\nnext_offset=${offset + shown.size}; has_more=${offset + shown.size < sessions.size}" else "") + "\n\nUse session_read for title and summary, then history=true only when needed. Write with session_send.")
    }

    private suspend fun send(selfId: String, args: JsonObject): HostToolPayload {
        val to = args.str("to") ?: return err("to is missing")
        val message = args.str("message") ?: return err("message is missing")
        val e = bus.send(selfId, to, message, urgent = args.bool("urgent") == true)
        val target = store.get(e.to)
        return HostToolPayload.text("Sent to ${target?.let(bus::address) ?: to} as ${e.id}. If it answers, the reply arrives in this conversation as a message; you do not need to wait for it.")
    }

    private suspend fun read(who: String, history: Boolean, afterSeq: Long, lines: Int, offset: Int): HostToolPayload {
        val s = bus.resolve(who) ?: return err("No session or agent is called '$who'.")
        val head = "${bus.address(s)} · ${s.state.name.lowercase()} · ${s.title}"
        val summary = s.resultSummary ?: store.tail(s.id, 100).asReversed()
            .firstNotNullOfOrNull { (it.event as? app.umbra.core.session.SessionEvent.AgentMessage)?.text }
            ?.let { Redaction.text(it).take(ResultSummaries.LIMIT) } ?: "No result summary yet."
        if (!history) return HostToolPayload.text("$head\nSummary: $summary\n\nUse session_read with history=true, after_seq=0 to read the full saved history.")
        val transcript = supervisor.historyMessages(s.id)
        if (transcript.isNotEmpty()) {
            val page = SessionHistoryPages.page(transcript, afterSeq.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), offset, lines)
            return HostToolPayload.text("$head\nSummary: $summary\n\nFull saved transcript of the current branch (images are viewed in the app). Continue with next_after_seq and next_offset; it may change while the session is working.\n$page")
        }
        val records = store.events(s.id, afterSeq, lines)
        val text = records.joinToString("\n") { app.umbra.core.model.UmbraJson.encodeToString(app.umbra.core.session.EventRecord.serializer(), it) }
        val next = records.lastOrNull()?.seq ?: afterSeq
        val more = next < store.eventCount(s.id)
        return HostToolPayload.text("$head\nSummary: $summary\n\nNo full transcript is cached; saved timeline follows (tool results may be shortened).\n$text\n\nnext_after_seq=$next; next_offset=0; has_more=$more")
    }

    private suspend fun start(selfId: String, args: JsonObject, progress: HostToolProgress): HostToolPayload {
        val parent = store.get(selfId) ?: return err("This session no longer exists.")
        // One level of subagents: a subagent does its part itself (a phone runs few agents at once).
        if (parent.kind == SessionKind.SUBAGENT) return err("A subagent cannot start subagents of its own. Do this part yourself, or tell the session that started you what else is needed.")
        val task = args.str("task")?.takeIf { it.isNotBlank() } ?: return err("task is missing")
        val handle = Handles.clean(args.str("name") ?: "subagent")
        if (store.list().any { it.spawnedBy == selfId && bus.handleOf(it) == handle && !it.state.isTerminal }) return err("You already have a working subagent #$handle; pick another name or use subagent_send.")
        val modelName = args.str("model")?.trim()?.takeIf { it.isNotEmpty() && it != "auto" }
        val model = if (modelName == null) parent.model else resolveModel(modelName) ?: return err("No connected model is called '$modelName'. Use \"auto\" or provider/id.")
        val effort = when (val e = args.str("effort")?.trim()) {
            null, "" -> parent.effort ?: Effort(Effort.OMP_AUTO, auto = true)
            "auto" -> Effort(Effort.OMP_AUTO, auto = true)
            else -> if (e in Effort.LEVELS) Effort(e) else return err("effort must be auto or one of ${Effort.LEVELS}")
        }
        val child = supervisor.submit(
            request = task,
            title = task.lineSequence().first().take(80),
            workspaceId = parent.workspaceId,
            model = model,
            projectId = parent.projectId,
            effort = effort,
            spec = SessionSpec(kind = SessionKind.SUBAGENT, handle = handle, spawnedBy = parent.id, agentId = parent.agentId),
        )
        val where = "#$handle (session ${child.id}) · ${model?.let { "${it.provider}/${it.modelId}" } ?: "the default model"} · effort ${effort.level}"
        if (child.queuedAtMs != null && args.bool("wait") != true) {
            return HostToolPayload.text("Queued $where: the phone cannot run another agent right now; it starts by itself when one finishes. Its answer arrives as a message.")
        }
        if (args.bool("wait") == true) {
            bus.markConsumed(child.id)
            val done = awaitTurn(listOf(child.id), 600_000, progress)
            return HostToolPayload.text("Subagent $where:\n" + done.joinToString("\n\n"))
        }
        return HostToolPayload.text("Started $where. It works in your folder with your permissions. Its answer arrives in this conversation as a message each time it finishes; follow it with subagent_status, steer it with subagent_send.")
    }

    private suspend fun status(selfId: String, name: String, lines: Int): HostToolPayload {
        val c = child(selfId, name) ?: return err("You have no subagent called '$name'.")
        val model = c.model?.let { "${it.provider}/${it.modelId}" } ?: "default model"
        val activity = supervisor.activity(c.id, lines)
        return HostToolPayload.text(
            "${bus.address(c)} · ${c.state.name.lowercase()}${if (c.queuedAtMs != null) " (queued)" else ""} · $model · effort ${c.effort?.level ?: "default"}\n" +
                (c.plan.takeIf { it.isNotEmpty() }?.joinToString("\n") { "plan: [${it.status}] ${it.text}" }?.plus("\n") ?: "") +
                activity.joinToString("\n").ifEmpty { "(nothing yet)" },
        )
    }

    private suspend fun wait(selfId: String, args: JsonObject, progress: HostToolProgress): HostToolPayload {
        val names = (args["names"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val mine = store.list().filter { it.spawnedBy == selfId }
        val targets = if (names.isNullOrEmpty()) mine.filter { !it.state.isTerminal } else names.mapNotNull { n -> mine.firstOrNull { bus.handleOf(it) == Handles.clean(n) } }
        if (targets.isEmpty()) return HostToolPayload.text("No subagent of yours is working.")
        targets.forEach { bus.markConsumed(it.id) }
        val timeout = ((args.int("timeout_s") ?: 600).coerceIn(5, 1800)) * 1000L
        return HostToolPayload.text(awaitTurn(targets.map { it.id }, timeout, progress).joinToString("\n\n"))
    }

    /** Waits until each session has finished its current turn; returns each one's answer (or where it stands at the timeout). */
    private suspend fun awaitTurn(ids: List<String>, timeoutMs: Long, progress: HostToolProgress): List<String> {
        val results = LinkedHashMap<String, String>()
        withTimeoutOrNull(timeoutMs) {
            while (results.size < ids.size) {
                for (id in ids) {
                    if (id in results) continue
                    val s = store.get(id)
                    if (s == null) {
                        results[id] = "($id was deleted)"
                        continue
                    }
                    if (s.state == SessionState.COMPLETED || s.state == SessionState.FAILED || s.state == SessionState.STOPPED) {
                        results[id] = "${bus.address(s)} ${s.state.name.lowercase()}:\n" + supervisor.finalText(id).ifBlank { s.lastError ?: "(no answer)" }.take(SessionBus.REPORT_LIMIT)
                    }
                }
                if (results.size < ids.size) {
                    runCatching { progress.update(HostToolPayload.text("Waiting for ${ids.size - results.size} subagent(s)…")) }
                    delay(1_000)
                }
            }
        }
        for (id in ids) if (id !in results) {
            val s = store.get(id)
            results[id] = "${s?.let(bus::address) ?: id} is still ${s?.state?.name?.lowercase() ?: "gone"} (waiting timed out). Its answer will arrive as a message."
        }
        return ids.map { results.getValue(it) }
    }

    private suspend fun child(selfId: String, name: String): Session? {
        val handle = Handles.clean(name.removePrefix("#"))
        return store.list().filter { it.spawnedBy == selfId }.sortedByDescending { it.updatedAtMs }.firstOrNull { bus.handleOf(it) == handle || it.id == name }
    }

    // ---- small helpers ----

    private fun tool(name: String, description: String, schema: JsonObject, run: suspend (JsonObject, HostToolProgress) -> HostToolPayload) =
        HostSideTool(HostToolDefinition(name, description, schema)) { call: HostToolCall, progress -> run(call.arguments, progress) }

    private fun err(message: String) = HostToolPayload.error(message)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    companion object {
        val NAMES = listOf("sessions_list", "session_send", "session_reply", "session_read", "subagent_start", "subagent_status", "subagent_send", "subagent_wait", "subagent_stop")

        private const val LIST_DESCRIPTION = "List the other sessions and agents on this phone that you can write to: their #handle or @name, kind, state and what they are doing."
        private const val SEND_DESCRIPTION = "Send a message to another session or agent on this phone (like messaging a colleague). It arrives in their conversation after their current turn (or at once with urgent). Their answer, if any, comes back into this conversation as a message; do not wait for it unless you need it."
        private const val START_DESCRIPTION = "Start a subagent: a separate agent that works on part of your task in your folder, with your permissions, while you continue. Choose its model and effort to suit the work. Give it a complete, self-contained task. Its answer comes back as a message when it finishes; you can follow it with subagent_status and steer it with subagent_send."
    }
}
