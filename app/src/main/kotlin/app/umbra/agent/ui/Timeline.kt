package app.umbra.agent.ui

import app.umbra.core.session.EventRecord
import app.umbra.core.session.PlanStep
import app.umbra.core.session.Session
import app.umbra.core.session.SessionEvent
import app.umbra.core.session.SessionState
import app.umbra.core.session.SessionStore
import app.umbra.core.session.StoreChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the session screen draws, in order. Built from stored events, so it is the same after a restart. */
sealed interface TimelineItem {
    val key: Long

    data class User(override val key: Long, val text: String, val imageCount: Int) : TimelineItem
    data class Agent(override val key: Long, val text: String) : TimelineItem

    /** A tool call and, once it finished, its outcome. */
    data class Tool(
        override val key: Long,
        val callId: String,
        val tool: String,
        val source: String,
        val args: JsonElement?,
        val intent: String?,
        val status: String?,
        val summary: String?,
        val backend: String?,
        val durationMs: Long,
        val screenshots: List<String>,
    ) : TimelineItem {
        val running: Boolean get() = status == null
    }

    data class Approval(override val key: Long, val kind: String, val summary: String, val level: String) : TimelineItem
    data class StateChange(override val key: Long, val to: SessionState, val reason: String?) : TimelineItem
    data class Notice(override val key: Long, val level: String, val title: String, val text: String) : TimelineItem
    data class Subagent(override val key: Long, val id: String, val kind: String, val summary: String?) : TimelineItem
    data class Decision(override val key: Long, val question: String, val answer: String, val engine: String?) : TimelineItem
}

object TimelineBuilder {
    /** Folds [records] (oldest first) into display items; tool results and screenshots join their call. */
    fun build(records: List<EventRecord>): List<TimelineItem> {
        val items = ArrayList<TimelineItem>()
        val toolIndex = HashMap<String, Int>()
        for (r in records) {
            when (val e = r.event) {
                is SessionEvent.UserMessage -> items += TimelineItem.User(r.seq, e.text, e.imageCount)
                is SessionEvent.AgentMessage -> items += TimelineItem.Agent(r.seq, e.text)
                is SessionEvent.ToolCall -> {
                    toolIndex[e.callId] = items.size
                    items += TimelineItem.Tool(r.seq, e.callId, e.tool, e.source, e.args, e.intent, null, null, null, 0, emptyList())
                }
                is SessionEvent.ToolResult -> {
                    val index = toolIndex[e.callId]
                    val existing = index?.let { items[it] as? TimelineItem.Tool }
                    if (existing != null) {
                        items[index] = existing.copy(status = e.status, summary = e.summary, backend = e.backend, durationMs = e.durationMs)
                    } else {
                        items += TimelineItem.Tool(r.seq, e.callId, e.tool, "omp", null, null, e.status, e.summary, e.backend, e.durationMs, emptyList())
                        toolIndex[e.callId] = items.lastIndex
                    }
                }
                is SessionEvent.Observation -> {
                    val last = items.lastOrNull() as? TimelineItem.Tool
                    val path = e.screenshotPath
                    if (last != null && path != null) items[items.lastIndex] = last.copy(screenshots = last.screenshots + path)
                }
                is SessionEvent.Approval -> if (e.kind != "requested") items += TimelineItem.Approval(r.seq, e.kind, e.summary, e.level)
                is SessionEvent.StateChanged -> items += TimelineItem.StateChange(r.seq, e.to, e.reason)
                is SessionEvent.Failure -> items += TimelineItem.Notice(r.seq, "error", "Problem in ${e.subsystem}", e.message)
                is SessionEvent.Recovery -> items += TimelineItem.Notice(r.seq, if (e.success) "info" else "warning", if (e.success) "Recovered" else "Recovery failed", "${e.subsystem}: ${e.action}${e.detail?.let { " — $it" } ?: ""}")
                is SessionEvent.AppEvent -> items += TimelineItem.Notice(r.seq, "error", "App ${e.kind.replace('_', ' ')}", e.summary)
                is SessionEvent.Notice -> items += TimelineItem.Notice(r.seq, e.level, e.source ?: "Notice", e.message)
                is SessionEvent.Subagent -> items += TimelineItem.Subagent(r.seq, e.subagentId, e.kind, e.summary)
                is SessionEvent.Decision -> items += TimelineItem.Decision(r.seq, e.question, e.answer, e.engine)
                is SessionEvent.PlanUpdated -> Unit // shown pinned above the timeline
            }
        }
        return items
    }

    fun latestPlan(records: List<EventRecord>): List<PlanStep>? =
        records.asReversed().firstNotNullOfOrNull { (it.event as? SessionEvent.PlanUpdated)?.steps }
}

/** One short line describing what a tool call is doing, for the collapsed row. */
fun describeTool(tool: String, args: JsonElement?): String {
    val a = args as? JsonObject ?: return ""
    fun s(key: String) = (a[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    return when (tool) {
        "bash" -> s("command") ?: ""
        "read", "write", "edit" -> s("path") ?: s("file") ?: ""
        "android_launch" -> s("package") ?: s("intent_action") ?: s("uri") ?: ""
        "android_tap", "android_double_tap", "android_long_press" -> s("element") ?: s("text") ?: s("x")?.let { "($it, ${s("y")})" } ?: ""
        "android_type" -> if ((a["secret"] as? JsonPrimitive)?.content == "true") "••••••" else s("text")?.let { "“$it”" } ?: ""
        "android_key" -> s("key") ?: ""
        "android_scroll", "android_swipe" -> s("direction") ?: ""
        "android_shell", "priv_exec" -> s("command") ?: ""
        "android_wait_for" -> s("text") ?: s("package") ?: s("until") ?: ""
        "android_find" -> s("query") ?: s("text") ?: ""
        "android_display" -> s("action") ?: ""
        "verify" -> s("text") ?: s("expect") ?: ""
        else -> a.entries.firstOrNull { (it.value as? JsonPrimitive)?.isString == true }?.let { (it.value as JsonPrimitive).content } ?: ""
    }.lineSequence().firstOrNull().orEmpty().take(120)
}

/** Stored events of [sessionId], then each new one as it is appended; the list always holds the latest [limit]. */
fun SessionStore.eventFlow(sessionId: String, limit: Int = 600): Flow<List<EventRecord>> = channelFlow {
    val buffer = ArrayDeque(tail(sessionId, limit))
    send(buffer.toList())
    var lastSeq = buffer.lastOrNull()?.seq ?: 0L
    changes.collect { change ->
        if (change is StoreChange.EventAppended && change.sessionId == sessionId && change.record.seq > lastSeq) {
            lastSeq = change.record.seq
            buffer.addLast(change.record)
            while (buffer.size > limit) buffer.removeFirst()
            send(buffer.toList())
        } else if (change is StoreChange.AllCleared || (change is StoreChange.SessionDeleted && change.sessionId == sessionId)) {
            buffer.clear()
            send(emptyList())
        }
    }
}

/** All sessions, newest activity first, refreshed on every change. */
fun SessionStore.sessionsFlow(): Flow<List<Session>> = channelFlow {
    send(list())
    changes.collect { change ->
        // Appending events does not change a session's listing, only its updated time on state changes.
        if (change !is StoreChange.EventAppended) send(list())
    }
}
