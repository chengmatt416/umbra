package app.umbra.agent.agents

import app.umbra.agent.AppContainer
import app.umbra.core.orchestrator.SessionSpec
import app.umbra.core.session.Effort
import app.umbra.core.session.SessionKind
import app.umbra.runtime.omp.HostToolCall
import app.umbra.runtime.omp.HostToolDefinition
import app.umbra.runtime.omp.HostToolPayload
import app.umbra.runtime.omp.HostToolProgress
import app.umbra.runtime.omp.objectSchema
import app.umbra.tools.HostSideTool
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What only an agent's conversation gets (docs/design/agents-2026-10.md §8): its memory, its timers and rules, the
 * user's queue, a way to reach the user, and coding sessions for work that needs a computer. Talking to other agents
 * goes through the session tools (`session_send` to @name).
 */
class AgentTools(private val container: AppContainer) {
    private val runtime get() = container.agents
    private val store get() = container.agents.store

    fun forAgent(agentId: String): List<HostSideTool> = listOf(
        tool("memory_add", "Remember something durable about the user in your MEMORY: a preference, a person, a routine, a commitment. One fact per call, in your own short words. Never store passwords, one-time codes or bank details.", objectSchema {
            string("text", "The fact, e.g. \"Drinks oat milk, not cow's milk\".", required = true)
            string("source", "Where you learned it: \"user\", \"WhatsApp\", a coding session's #handle…")
        }) { a ->
            val e = store.memory(agentId).add(a.str("text") ?: return@tool err("text is missing"), a.str("source") ?: "agent")
            ok("Remembered (${e.date} · ${e.source}).")
        },
        tool("memory_edit", "Correct a memory: the first one containing `old` becomes `new`.", objectSchema {
            string("old", "Part of the memory to change.", required = true)
            string("new", "The corrected fact.", required = true)
        }) { a -> if (store.memory(agentId).edit(a.str("old") ?: "", a.str("new") ?: return@tool err("new is missing"))) ok("Changed.") else err("No memory contains that.") },
        tool("memory_forget", "Forget memories that contain the given text (the user asked you to, or they are wrong).", objectSchema {
            string("text", "Text the memories to forget contain.", required = true)
        }) { a -> ok("Forgot ${store.memory(agentId).forget(a.str("text") ?: return@tool err("text is missing"))}.") },
        tool("memory_list", "Read your MEMORY as it is now (it is also in your instructions, as of when this conversation's agent last started).", objectSchema {
            string("contains", "Only memories containing this text.")
        }) { a ->
            val q = a.str("contains")
            val list = store.memory(agentId).entries().filter { q == null || it.text.contains(q, true) || it.source.contains(q, true) }
            ok(list.joinToString("\n") { "- ${it.date} · ${it.source} · ${it.text}" }.ifEmpty { "(nothing)" })
        },
        tool("timer_set", "Wake yourself at a time: once (at a time, or in some minutes) or repeating (cron). When it fires you get the note back. Use it for reminders and for things to check later.", objectSchema {
            string("note", "What to do when it fires, e.g. \"Remind the user to buy oat milk\".", required = true)
            string("at", "When, in the phone's time: \"18:00\" (today, or tomorrow if past), \"2026-10-20 09:30\", or an ISO time.")
            integer("in_minutes", "Or: this many minutes from now.")
            string("cron", "Or repeating: a 5-field cron, e.g. \"0 9 * * 1-5\" (09:00 on weekdays).")
        }) { a -> setTimer(agentId, a) },
        tool("timer_list", "Your timers: when each fires and what for.", objectSchema { }) { _ ->
            val f = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm")
            ok(store.timers(agentId).sortedBy { it.nextMs }.joinToString("\n") { "${it.id} · ${f.format(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.nextMs), ZoneId.systemDefault()))}${it.cron?.let { c -> " · repeats $c" } ?: ""} · ${it.note}" }.ifEmpty { "(no timers)" })
        },
        tool("timer_cancel", "Cancel a timer by its id.", objectSchema { string("id", "The timer's id, e.g. t3.", required = true) }) { a ->
            val id = a.str("id") ?: return@tool err("id is missing")
            val before = store.timers(agentId)
            store.setTimers(agentId, before.filterNot { it.id == id })
            runtime.reschedule()
            if (before.any { it.id == id }) ok("Cancelled $id.") else err("No timer $id.")
        },
        tool("rule_add", "Add a rule to wake you at once when something arrives: a message or notification from a source and/or containing some text.", objectSchema {
            string("when_source", "A message app (whatsapp, line, telegram, sms…), an app's name or package; omit for any.")
            string("when_text", "Text it contains (also matches the sender or chat name); omit for any.")
            string("then", "What to do then, e.g. \"Tell the user Mom wrote\".", required = true)
        }) { a ->
            val then = a.str("then") ?: return@tool err("then is missing")
            if (a.str("when_source") == null && a.str("when_text") == null) return@tool err("Give when_source or when_text (a rule for everything would wake you for every message).")
            val rules = store.rules(agentId)
            val r = AgentRule(nextId("r", rules.map { it.id }), a.str("when_source"), a.str("when_text"), then, System.currentTimeMillis())
            store.setRules(agentId, rules + r)
            ok("Added rule ${r.id}.")
        },
        tool("rule_list", "Your rules.", objectSchema { }) { _ ->
            ok(store.rules(agentId).joinToString("\n") { "${it.id} · when ${it.whenSource ?: "any source"}${it.whenText?.let { t -> " contains \"$t\"" } ?: ""} → ${it.then}${if (!it.enabled) " (off)" else ""}" }.ifEmpty { "(no rules)" })
        },
        tool("rule_remove", "Remove a rule by its id.", objectSchema { string("id", "The rule's id, e.g. r2.", required = true) }) { a ->
            val id = a.str("id") ?: return@tool err("id is missing")
            store.setRules(agentId, store.rules(agentId).filterNot { it.id == id })
            ok("Removed $id.")
        },
        tool("queue_add", "Put something on your queue of things to do when you can (things the user asked for that are not urgent).", objectSchema {
            string("text", "What to do.", required = true)
        }) { a ->
            val q = store.queue(agentId)
            val item = QueueItem(nextId("q", q.map { it.id }), a.str("text") ?: return@tool err("text is missing"), System.currentTimeMillis(), addedBy = "agent")
            store.setQueue(agentId, q + item)
            ok("Queued ${item.id}.")
        },
        tool("queue_list", "Your queue: what the user asked you to do, done or not.", objectSchema { boolean("all", "Include done items.") }) { a ->
            ok(store.queue(agentId).filter { a.bool("all") == true || !it.done }.joinToString("\n") { "${it.id} · ${if (it.done) "done" else "to do"} · ${it.text} (from ${it.addedBy})" }.ifEmpty { "(empty)" })
        },
        tool("queue_done", "Mark a queue item done.", objectSchema { string("id", "The item's id, e.g. q1.", required = true) }) { a ->
            val id = a.str("id") ?: return@tool err("id is missing")
            store.setQueue(agentId, store.queue(agentId).map { if (it.id == id) it.copy(done = true) else it })
            ok("Done: $id.")
        },
        tool("notify_user", "Tell the user something now with a phone notification (your chat messages reach them too, but only when they look). Use it for what they should see soon; during quiet hours only when urgent.", objectSchema {
            string("text", "What to tell them, short.", required = true)
            boolean("urgent", "Urgent: also during quiet hours, with sound.")
        }) { a ->
            val p = store.get(agentId) ?: return@tool err("no such agent")
            val urgent = a.bool("urgent") == true
            if (runtime.inQuietHours(p) && !urgent) return@tool err("It is quiet hours; this will wait. Mark it urgent only if it cannot wait.")
            runtime.notify(p, a.str("text") ?: return@tool err("text is missing"), urgent)
            ok("Notified the user.")
        },
        tool("coding_start", "Start a coding session on this phone for work that needs a computer: code, files, builds, long research, operating apps step by step. It runs in its own folder with the terminal and Linux tools; its report comes back to you as a message when it finishes.", objectSchema {
            string("task", "The work, complete and self-contained.", required = true)
            string("title", "A short title.")
            string("model", "\"auto\" (default) or provider/id.")
            enum("effort", listOf("auto", "minimal", "low", "medium", "high", "xhigh", "max"), "How hard it thinks (default auto).")
        }) { a -> codingStart(agentId, a) },
    )

    private suspend fun setTimer(agentId: String, a: JsonObject): HostToolPayload {
        val note = a.str("note") ?: return err("note is missing")
        val now = System.currentTimeMillis()
        val cron = a.str("cron")?.trim()?.takeIf { it.isNotEmpty() }
        val next = when {
            cron != null -> runCatching { Cron(cron).next(now) }.getOrElse { return err("Bad cron: ${it.message}") }
            a.int("in_minutes") != null -> now + a.int("in_minutes")!!.coerceIn(1, 60 * 24 * 366) * 60_000L
            a.str("at") != null -> parseAt(a.str("at")!!) ?: return err("Could not read the time \"${a.str("at")}\". Use 18:00, 2026-10-20 09:30 or an ISO time.")
            else -> return err("Give at, in_minutes or cron.")
        }
        if (next <= now) return err("That time has passed.")
        val timers = store.timers(agentId)
        val t = AgentTimer(nextId("t", timers.map { it.id }), note, next, cron, "agent", now)
        store.setTimers(agentId, timers + t)
        runtime.reschedule()
        val f = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm")
        return ok("Set ${t.id} for ${f.format(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), ZoneId.systemDefault()))}${cron?.let { ", repeating ($it)" } ?: ""}.")
    }

    private suspend fun codingStart(agentId: String, a: JsonObject): HostToolPayload {
        val p = store.get(agentId) ?: return err("no such agent")
        val task = a.str("task")?.takeIf { it.isNotBlank() } ?: return err("task is missing")
        val effort = when (val e = a.str("effort")) { null, "", "auto" -> Effort(Effort.OMP_AUTO, auto = true); in Effort.LEVELS -> Effort(e); else -> return err("bad effort") }
        val modelName = a.str("model")?.takeIf { it.isNotBlank() && it != "auto" }
        val model = modelName?.let { container.resolveModel(it) ?: return err("No connected model is called '$it'.") }
        val s = container.supervisor.submit(
            request = task, title = a.str("title") ?: task.lineSequence().first().take(80), model = model, effort = effort,
            spec = SessionSpec(kind = SessionKind.CODING, agentId = p.id),
        )
        val handle = container.sessionBus.address(s)
        return ok("Started the coding session $handle${if (s.queuedAtMs != null) " (queued: it starts when the phone can take another agent)" else ""}. Its report comes back to you when it finishes; follow it with session_read $handle.")
    }

    // ---- helpers ----

    private fun parseAt(text: String): Long? {
        val zone = ZoneId.systemDefault()
        val t = text.trim()
        runCatching { return java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(t.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(zone).toInstant().toEpochMilli() }
        runCatching {
            val time = LocalTime.parse(t)
            var at = LocalDate.now().atTime(time).atZone(zone)
            if (at.toInstant().toEpochMilli() <= System.currentTimeMillis()) at = at.plusDays(1)
            return at.toInstant().toEpochMilli()
        }
        return null
    }

    private fun nextId(prefix: String, taken: List<String>): String = (1..9_999).map { "$prefix$it" }.first { it !in taken }

    private fun tool(name: String, description: String, schema: JsonObject, run: suspend (JsonObject) -> HostToolPayload) =
        HostSideTool(HostToolDefinition(name, description, schema)) { call: HostToolCall, _: HostToolProgress -> run(call.arguments) }

    private fun ok(text: String) = HostToolPayload.text(text)
    private fun err(text: String) = HostToolPayload.error(text)
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull

    companion object {
        val NAMES = listOf("memory_add", "memory_edit", "memory_forget", "memory_list", "timer_set", "timer_list", "timer_cancel", "rule_add", "rule_list", "rule_remove", "queue_add", "queue_list", "queue_done", "notify_user", "coding_start")
    }
}

/**
 * What an agent's system prompt adds (docs/design/agents-2026-10.md §2): its soul, what it remembers, and how it
 * lives: the wake digest, quiet turns, memory, timers, coding sessions, other agents.
 */
object AgentPrompt {
    fun of(p: AgentProfile, soul: String, memory: List<Memory.Entry>, heartbeatMinutes: Int?): String = buildString {
        appendLine("# You are ${p.name}, an agent that lives on this phone")
        appendLine()
        appendLine("## Your soul (SOUL.md, written by the user)")
        appendLine(soul.trim().ifEmpty { "You are ${p.name}." })
        appendLine()
        appendLine("## What you remember about the user (MEMORY.md, newest last)")
        if (memory.isEmpty()) appendLine("(nothing yet)") else memory.takeLast(80).forEach { appendLine("- ${it.date} · ${it.source} · ${it.text}") }
        appendLine()
        appendLine("## How you live")
        appendLine("- You run all the time for the user, but you are only awake for moments: ${heartbeatMinutes?.let { "about every $it minutes" } ?: "when something happens"}, when a timer you set is due, when a rule fires, or when a message arrives. Each wake starts with a `[Wake · time · reason]` digest of what is new. The user's own messages to you come as normal messages (from Telegram they start with `[Telegram]`).")
        appendLine("- When a wake needs nothing from the user, do what is needed quietly and end your turn with exactly ${AgentRuntime.QUIET} (nothing else). Otherwise write a short message to the user, as in a chat.")
        appendLine("- Learn the user: when you learn something durable (preferences, people, routines, commitments, how they like things done), save it with `memory_add`; correct it with `memory_edit`. Never store passwords, one-time codes or bank details.")
        appendLine("- Time: `timer_set` for anything that must happen at a time (reminders, checks later), `rule_add` for \"when X arrives, do Y\". The user's own requests that can wait are in your queue (`queue_list`, `queue_done`).")
        appendLine("- Reach the user with `notify_user` for what they should see soon. Respect quiet hours: only urgent things then.")
        appendLine("- Work that needs a computer (code, files, builds, long research, many app steps): start a coding session with `coding_start`; its report comes back to you as a `[Report from #…]` message. Tell the user the outcome in a sentence and keep what it taught you about them.")
        appendLine("- Other agents on this phone (`sessions_list` shows them as @name) can help: write with `session_send`. Messages from them start with `[Message …]`.")
        appendLine("- Messages to other people: never send one without the user's approval, unless they allowed that chat. Draft it, and the user is asked.")
        appendLine("- You can also operate the phone with the `android_*` tools like any task; prefer a background display so the user's screen stays theirs.")
    }
}
