package app.umbra.agent.capsule

import app.umbra.agent.ui.TimelineBuilder
import app.umbra.agent.ui.TimelineItem
import app.umbra.agent.ui.describeTool
import app.umbra.core.session.EventRecord
import app.umbra.core.session.Session
import app.umbra.core.session.SessionEvent
import app.umbra.core.session.SessionState
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a Capsule companion shows (docs/design/capsule-companion.md). Each state has its own shape, not only its own
 * colour, so it reads at a glance and without colour.
 */
internal enum class Mood { IDLE, THINKING, WORKING, COMPACTING, WAITING, PAUSED, ERROR, DONE }

/** One line of a session's activity feed, built from its stored timeline (arguments already redacted there). */
internal data class FeedEntry(
    val key: Long,
    val kind: Kind,
    /** The tool's name, for a tool call. */
    val tool: String?,
    val text: String,
    val status: Status,
    val atMs: Long,
    val durationMs: Long = 0,
) {
    enum class Kind { TOOL, SAID, YOU, APPROVAL, PROBLEM }

    enum class Status { RUNNING, OK, WARN, FAILED, NEUTRAL }
}

/** What the Capsule knows about one live session besides the session itself. */
internal data class SessionActivity(
    /** The latest feed entries, oldest first. */
    val feed: List<FeedEntry> = emptyList(),
    val toolRunning: Boolean = false,
    val compacting: Boolean = false,
    /** The provider failed and omp is retrying. */
    val retrying: Boolean = false,
    /** How full the model's context is, as omp last reported it. */
    val contextPercent: Int? = null,
    /** When the current run started (its last start or restart on the timeline), if the timeline still shows it. */
    val runStartedAtMs: Long? = null,
)

/** One companion: a session (or, with no session at all, Umbra at rest). [sinceMs] is when its run started. */
internal data class CrewMember(val sessionId: String?, val title: String, val mood: Mood, val sinceMs: Long?)

/** The companions on the Capsule, at most [Crews.MAX], and how many more there are. */
internal data class Crew(val members: List<CrewMember>, val overflow: Int) {
    val atRest: Boolean get() = members.all { it.mood == Mood.IDLE }

    fun moodOf(sessionId: String?): Mood? = members.firstOrNull { it.sessionId == sessionId }?.mood
}

internal object Crews {
    const val MAX = 3

    /** How long a completed session keeps its full-disc moment. */
    const val DONE_MS = 2_600L

    /** A failed session keeps its companion this long, unless dismissed (the failure peek uses the same window). */
    const val FAILED_MS = 10 * 60_000L

    private val LIVE = setOf(SessionState.RUNNING, SessionState.WAITING_APPROVAL, SessionState.PAUSED)

    val AT_REST = Crew(listOf(CrewMember(null, "", Mood.IDLE, null)), 0)

    fun isLive(session: Session) = session.state in LIVE

    /** The state a session's companion shows, or null when the session has no companion. */
    fun moodOf(session: Session, activity: SessionActivity?, needsYou: Boolean, finishedAtMs: Long?, dismissed: Boolean, nowMs: Long): Mood? = when {
        (needsYou && !session.state.isTerminal) || session.state == SessionState.WAITING_APPROVAL -> Mood.WAITING
        session.state == SessionState.RUNNING -> when {
            activity?.retrying == true -> Mood.ERROR
            activity?.compacting == true -> Mood.COMPACTING
            activity?.toolRunning == true -> Mood.WORKING
            else -> Mood.THINKING
        }
        session.state == SessionState.PAUSED -> Mood.PAUSED
        session.state == SessionState.FAILED && !dismissed && nowMs - session.updatedAtMs < FAILED_MS -> Mood.ERROR
        session.state == SessionState.COMPLETED && finishedAtMs != null && nowMs - finishedAtMs < DONE_MS -> Mood.DONE
        else -> null
    }

    /**
     * The crew: one companion per session that has one. When more than [max] do, the ones that need the user come
     * first, then errors, the done moment, work, and pauses; those shown keep their order of creation, so companions
     * do not trade places as their sessions update.
     */
    fun of(
        sessions: List<Session>,
        activity: Map<String, SessionActivity>,
        needsYou: Set<String>,
        finished: Map<String, Long>,
        dismissed: Set<String>,
        nowMs: Long,
        max: Int = MAX,
    ): Crew {
        val all = sessions.mapNotNull { s ->
            moodOf(s, activity[s.id], s.id in needsYou, finished[s.id], s.id in dismissed, nowMs)?.let { s to it }
        }
        if (all.isEmpty()) return AT_REST
        val shown = all.sortedWith(compareBy<Pair<Session, Mood>> { rank(it.second) }.thenByDescending { it.first.updatedAtMs }).take(max)
        val members = shown.sortedWith(compareBy<Pair<Session, Mood>> { it.first.createdAtMs }.thenBy { it.first.id })
            .map { (s, mood) -> CrewMember(s.id, s.title, mood, activity[s.id]?.runStartedAtMs ?: s.createdAtMs) }
        return Crew(members, all.size - shown.size)
    }

    private fun rank(mood: Mood) = when (mood) {
        Mood.WAITING -> 0
        Mood.ERROR -> 1
        Mood.DONE -> 2
        Mood.WORKING, Mood.THINKING, Mood.COMPACTING -> 3
        Mood.PAUSED -> 4
        Mood.IDLE -> 5
    }

    /** Whether the state animates continuously (the frame loop runs only while one does); the others are still or play once. */
    fun moves(mood: Mood) = mood == Mood.IDLE || mood == Mood.THINKING || mood == Mood.WORKING || mood == Mood.COMPACTING || mood == Mood.WAITING
}

internal object Feed {
    /** How many stored records the Capsule reads per session. */
    const val TAIL = 40

    /** How many feed entries it keeps per session. */
    const val KEEP = 6

    private val WARN_TOOL = setOf("denied", "needs_user", "timeout", "cancelled", "unsupported")

    /** The feed of a timeline (oldest first): tool calls with their outcome, messages, approvals and problems. */
    fun of(records: List<EventRecord>, keep: Int = KEEP): List<FeedEntry> {
        val at = records.associate { it.seq to it.ts }
        return TimelineBuilder.build(records).mapNotNull { entry(it, at[it.key] ?: 0L) }.takeLast(keep)
    }

    private fun entry(item: TimelineItem, ts: Long): FeedEntry? = when (item) {
        is TimelineItem.Tool -> FeedEntry(
            item.key, FeedEntry.Kind.TOOL, item.tool,
            (if (item.tool == "todo") todoText(item.args) else null) ?: describeTool(item.tool, item.args).ifBlank { firstLine(item.summary) },
            when (item.status) {
                null -> FeedEntry.Status.RUNNING
                "ok" -> FeedEntry.Status.OK
                in WARN_TOOL -> FeedEntry.Status.WARN
                else -> FeedEntry.Status.FAILED
            },
            ts, item.durationMs,
        )
        is TimelineItem.Agent -> firstLine(item.text).takeIf { it.isNotEmpty() }?.let { FeedEntry(item.key, FeedEntry.Kind.SAID, null, it, FeedEntry.Status.NEUTRAL, ts) }
        is TimelineItem.User -> firstLine(item.text).takeIf { it.isNotEmpty() }?.let { FeedEntry(item.key, FeedEntry.Kind.YOU, null, it, FeedEntry.Status.NEUTRAL, ts) }
        is TimelineItem.Approval -> when (item.kind) {
            "approved" -> FeedEntry.Status.OK
            "denied", "auto_denied" -> FeedEntry.Status.FAILED
            "timed_out", "cancelled" -> FeedEntry.Status.WARN
            else -> null // allowed automatically: routine, not news
        }?.let { FeedEntry(item.key, FeedEntry.Kind.APPROVAL, null, firstLine(item.summary), it, ts) }
        is TimelineItem.Notice -> when (item.level) {
            "error" -> FeedEntry.Status.FAILED
            "warning" -> FeedEntry.Status.WARN
            else -> null
        }?.let { FeedEntry(item.key, FeedEntry.Kind.PROBLEM, null, firstLine(item.text), it, ts) }
        else -> null
    }

    /** omp's todo tool: the task it marks, or the items it lists, rather than the bare operation ("init", "done"). */
    private fun todoText(args: JsonElement?): String? {
        val a = args as? JsonObject ?: return null
        (a["task"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { return it }
        val items = (a["list"] as? JsonArray)?.flatMap { phase ->
            ((phase as? JsonObject)?.get("items") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        }
        return items?.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun firstLine(text: String?) = text.orEmpty().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(160)

    /** A tool call on the timeline without its result yet. */
    fun toolRunning(feed: List<FeedEntry>) = feed.lastOrNull { it.kind == FeedEntry.Kind.TOOL }?.status == FeedEntry.Status.RUNNING

    /** When the session last started or restarted, if [records] still reach back that far. A resume continues a run. */
    fun runStart(records: List<EventRecord>): Long? = records.lastOrNull { r ->
        val change = r.event as? SessionEvent.StateChanged
        change != null && change.to == SessionState.RUNNING && change.from != SessionState.WAITING_APPROVAL && change.from != SessionState.PAUSED
    }?.ts
}

/** Things that deserve a light haptic (docs/design/capsule-companion.md § Haptics). */
internal object Signals {
    private val WORKING = setOf(SessionState.RUNNING, SessionState.WAITING_APPROVAL)

    /** A new approval or question arrived. */
    fun arrived(before: Set<String>, now: Set<String>): Boolean = (now - before).isNotEmpty()

    /** Sessions that were working at [before] and have completed now (a stop or a failure is not a completion). */
    fun completed(before: Map<String, SessionState>, now: List<Session>): List<String> =
        now.filter { it.state == SessionState.COMPLETED && before[it.id] in WORKING }.map { it.id }
}

/** Elapsed time as a clock reads: 0:42, 12:05, 1:02:03. */
internal fun elapsedText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
}

/** A duration under a minute as seconds ("0.4", "12"); null when a clock reading ([elapsedText]) suits it better. */
internal fun shortSeconds(ms: Long): String? = when {
    ms < 10_000 -> String.format(Locale.ROOT, "%.1f", ms / 1000.0)
    ms < 60_000 -> (ms / 1000).toString()
    else -> null
}
