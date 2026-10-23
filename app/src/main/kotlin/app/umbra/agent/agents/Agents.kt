package app.umbra.agent.agents

import app.umbra.core.model.UmbraJson
import app.umbra.core.session.Effort
import app.umbra.core.session.ModelRef
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * An agent (docs/design/agents-2026-10.md §1, §2): a long-lived assistant with its own soul (SOUL.md: who it is, its
 * rules), memory (MEMORY.md: what it learned about the user), one conversation ([sessionId]) and a wake schedule. It
 * runs until the user stops it ([running]); between wakes it holds no agent process.
 */
@Serializable
data class AgentProfile(
    val id: String,
    val name: String,
    /** One of the generated avatars ([AgentAvatars]), or "letter" for the initial on a colour. */
    val avatar: String = "a1",
    val template: String = Templates.ASSISTANT,
    val model: ModelRef? = null,
    val effort: Effort? = null,
    /** Minutes between heartbeats (Power and Dev can change it; Simple keeps the default). */
    val heartbeatMinutes: Int = DEFAULT_HEARTBEAT_MIN,
    /** Quiet hours as minutes of the day (start..end, may wrap midnight); null start: none. */
    val quietStartMin: Int? = 23 * 60,
    val quietEndMin: Int? = 7 * 60,
    val running: Boolean = true,
    /** Its conversation: a session of kind AGENT. */
    val sessionId: String? = null,
    val createdAtMs: Long,
    /** Message apps and sources it watches: connector ids (sms, whatsapp…) and "notifications". */
    val channels: List<String> = listOf("notifications"),
    /** Chats (connector:chatKey) it may answer on its own; everything else is asked first. Groups never default to auto. */
    val autoReply: List<String> = emptyList(),
    /** When the user last read its conversation: newer agent messages count as unread. */
    val lastReadMs: Long = 0,
    val usageLimit: app.umbra.core.session.AiUsageLimit? = null,
) {
    companion object {
        const val DEFAULT_HEARTBEAT_MIN = 10
    }
}

/** A wake the agent set for itself: once (at [nextMs]), or repeating by a 5-field [cron] ([nextMs] is its next time). */
@Serializable
data class AgentTimer(val id: String, val note: String, val nextMs: Long, val cron: String? = null, val setBy: String = "agent", val createdAtMs: Long)

/** "When this happens, wake up and do that" (the proactive queue's rules). */
@Serializable
data class AgentRule(
    val id: String,
    /** A connector id, a package name, or null for any source. */
    val whenSource: String? = null,
    /** Text the message or notification contains (case-insensitive), or null for any. */
    val whenText: String? = null,
    val then: String,
    val createdAtMs: Long,
    val enabled: Boolean = true,
)

/** Something the user asked the agent to do when it can. */
@Serializable
data class QueueItem(val id: String, val text: String, val addedAtMs: Long, val done: Boolean = false, val addedBy: String = "user")

/** What the wake loop remembers between wakes, and today's counts for the battery screen. */
@Serializable
data class AgentState(
    val lastWakeMs: Long = 0,
    val lastRunMs: Long = 0,
    /** Incoming items (messages, notifications) up to this time were already given to the agent or set aside. */
    val seenUntilMs: Long = 0,
    val day: String = "",
    val wakesToday: Int = 0,
    val runsToday: Int = 0,
    /** Items the triage said "later": given to the agent at its next real run. */
    val later: List<String> = emptyList(),
    val nextWakeMs: Long = 0,
    /** How long the agent's model worked today (its turns, measured), for the battery estimate. */
    val activeMsToday: Long = 0,
)

/** Two agents that wrote to each other, and the last thing said: a row of its own in the Agents list. */
@Serializable
data class AgentPair(val a: String, val b: String, val fromId: String, val text: String, val atMs: Long)

object Templates {
    const val ASSISTANT = "assistant"
    const val INBOX = "inbox"
    const val WORK = "work"
    const val BLANK = "blank"

    val all = listOf(ASSISTANT, INBOX, WORK, BLANK)

    /** A first SOUL.md for a template; the user edits it freely. */
    fun soul(template: String, name: String): String = when (template) {
        ASSISTANT -> "You are $name, the user's personal assistant on their phone. Speak briefly and warmly, in the user's language.\n" +
            "You look after their messages, notifications and plans, remind them of what matters, and get things done for them.\n" +
            "Rules:\n- Never send a message for the user without asking, unless they allowed it for that chat.\n- Ask before anything that costs money.\n- Don't disturb the user during quiet hours unless it is urgent."
        INBOX -> "You are $name. You keep the user's inbox calm: you read new messages and notifications, decide what needs them, " +
            "and give short summaries. You draft replies but never send them without asking.\nRules:\n- Group chats are summarised, not answered.\n- Codes, bank and security messages are never repeated or sent anywhere."
        WORK -> "You are $name, the user's work assistant. You follow work chats and email notifications, keep track of deadlines and " +
            "commitments, and start coding sessions on this phone for technical work.\nRules:\n- Ask before replying to anyone senior or external.\n- Report progress at the end of each workday."
        else -> "You are $name."
    }
}

/** MEMORY.md: one line per thing learned, `- 2026-10-18 · source · text`, newest last. */
class Memory(private val file: File) {
    data class Entry(val index: Int, val date: String, val source: String, val text: String)

    private val line = Regex("""^- (\d{4}-\d{2}-\d{2}) · ([^·]+?) · (.+)$""")

    @Synchronized
    fun entries(): List<Entry> = read().mapIndexedNotNull { i, l -> line.find(l)?.let { Entry(i, it.groupValues[1], it.groupValues[2].trim(), it.groupValues[3].trim()) } }

    @Synchronized
    fun add(text: String, source: String, date: LocalDate = LocalDate.now()): Entry {
        val clean = text.replace('\n', ' ').trim().take(500)
        require(clean.isNotEmpty()) { "empty memory" }
        val lines = read().toMutableList()
        if (lines.isEmpty()) lines += HEADER
        lines += "- ${date} · ${source.replace('·', '-').trim().take(40).ifEmpty { "agent" }} · $clean"
        write(lines)
        return entries().last()
    }

    /** Replaces the first entry containing [old] with [new] text (same date and source). */
    @Synchronized
    fun edit(old: String, new: String): Boolean {
        val lines = read().toMutableList()
        val i = lines.indexOfFirst { l -> line.find(l)?.groupValues?.get(3)?.contains(old, ignoreCase = true) == true }
        if (i < 0) return false
        val m = line.find(lines[i])!!
        lines[i] = "- ${m.groupValues[1]} · ${m.groupValues[2].trim()} · ${new.replace('\n', ' ').trim().take(500)}"
        write(lines)
        return true
    }

    /** Removes entries containing [text]; returns how many. */
    @Synchronized
    fun forget(text: String): Int {
        val lines = read()
        val kept = lines.filterNot { l -> line.find(l)?.groupValues?.get(3)?.contains(text, ignoreCase = true) == true }
        write(kept)
        return lines.size - kept.size
    }

    @Synchronized
    fun removeAt(index: Int) {
        val lines = read().toMutableList()
        if (index in lines.indices) { lines.removeAt(index); write(lines) }
    }

    /** Removes entries dated from [from] to [to] (inclusive). */
    @Synchronized
    fun forgetBetween(from: LocalDate, to: LocalDate): Int {
        val lines = read()
        val kept = lines.filterNot { l -> line.find(l)?.groupValues?.get(1)?.let { d -> LocalDate.parse(d).let { it >= from && it <= to } } == true }
        write(kept)
        return lines.size - kept.size
    }

    @Synchronized
    fun raw(): String = if (file.exists()) file.readText() else HEADER + "\n"

    @Synchronized
    fun setRaw(text: String) = file.writeText(text)

    private fun read(): List<String> = if (file.exists()) file.readLines() else emptyList()

    private fun write(lines: List<String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(lines.joinToString("\n") + "\n")
        tmp.renameTo(file)
    }

    companion object {
        const val HEADER = "# Memory"
    }
}

/**
 * The agents on this phone, each in `agents/<id>/`: profile.json, SOUL.md, MEMORY.md, timers.json, rules.json,
 * queue.json, state.json. Profiles are kept in memory (a flow for the screens); the files are the truth across
 * restarts and updates.
 */
class AgentStore(private val root: File) {
    private val mutable = MutableStateFlow(loadAll())
    val profiles: StateFlow<List<AgentProfile>> = mutable.asStateFlow()

    fun dir(id: String) = File(root, id)
    fun memory(id: String) = Memory(File(dir(id), "MEMORY.md"))
    fun get(id: String): AgentProfile? = mutable.value.firstOrNull { it.id == id }
    fun bySession(sessionId: String): AgentProfile? = mutable.value.firstOrNull { it.sessionId == sessionId }

    @Synchronized
    fun save(profile: AgentProfile) {
        val d = dir(profile.id).apply { mkdirs() }
        writeAtomic(File(d, "profile.json"), UmbraJson.encodeToString(AgentProfile.serializer(), profile))
        mutable.value = (mutable.value.filterNot { it.id == profile.id } + profile).sortedBy { it.createdAtMs }
    }

    @Synchronized
    fun update(id: String, change: (AgentProfile) -> AgentProfile): AgentProfile? = get(id)?.let(change)?.also(::save)

    @Synchronized
    fun delete(id: String) {
        dir(id).deleteRecursively()
        mutable.value = mutable.value.filterNot { it.id == id }
    }

    fun soul(id: String): String = File(dir(id), "SOUL.md").takeIf { it.exists() }?.readText().orEmpty()
    fun setSoul(id: String, text: String) = writeAtomic(File(dir(id).apply { mkdirs() }, "SOUL.md"), text.trim() + "\n")

    fun timers(id: String): List<AgentTimer> = readList(id, "timers.json", AgentTimer.serializer())
    fun setTimers(id: String, list: List<AgentTimer>) = writeList(id, "timers.json", AgentTimer.serializer(), list)
    fun rules(id: String): List<AgentRule> = readList(id, "rules.json", AgentRule.serializer())
    fun setRules(id: String, list: List<AgentRule>) = writeList(id, "rules.json", AgentRule.serializer(), list)
    fun queue(id: String): List<QueueItem> = readList(id, "queue.json", QueueItem.serializer())
    fun setQueue(id: String, list: List<QueueItem>) = writeList(id, "queue.json", QueueItem.serializer(), list)

    @Synchronized
    fun state(id: String): AgentState = File(dir(id), "state.json").takeIf { it.exists() }?.let { runCatching { UmbraJson.decodeFromString(AgentState.serializer(), it.readText()) }.getOrNull() } ?: AgentState()

    @Synchronized
    fun setState(id: String, state: AgentState) = writeAtomic(File(dir(id).apply { mkdirs() }, "state.json"), UmbraJson.encodeToString(AgentState.serializer(), state))

    /** Conversations between agents, newest first. */
    @Synchronized
    fun pairs(): List<AgentPair> = File(root, "pairs.json").takeIf { it.exists() }?.let { runCatching { UmbraJson.decodeFromString(ListSerializer(AgentPair.serializer()), it.readText()) }.getOrNull() }.orEmpty()

    @Synchronized
    fun notePair(fromId: String, toId: String, text: String, atMs: Long) {
        val (a, b) = listOf(fromId, toId).sorted()
        val next = listOf(AgentPair(a, b, fromId, text.lineSequence().first().take(200), atMs)) + pairs().filterNot { it.a == a && it.b == b }
        root.mkdirs()
        writeAtomic(File(root, "pairs.json"), UmbraJson.encodeToString(ListSerializer(AgentPair.serializer()), next.take(20)))
        mutablePairs.value = next.take(20)
    }

    private val mutablePairs = MutableStateFlow(pairs())
    val pairsFlow: StateFlow<List<AgentPair>> = mutablePairs.asStateFlow()

    private fun loadAll(): List<AgentProfile> = (root.listFiles() ?: emptyArray()).mapNotNull { d ->
        File(d, "profile.json").takeIf { it.exists() }?.let { runCatching { UmbraJson.decodeFromString(AgentProfile.serializer(), it.readText()) }.getOrNull() }
    }.sortedBy { it.createdAtMs }

    @Synchronized
    private fun <T> readList(id: String, name: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        File(dir(id), name).takeIf { it.exists() }?.let { runCatching { UmbraJson.decodeFromString(ListSerializer(serializer), it.readText()) }.getOrNull() }.orEmpty()

    @Synchronized
    private fun <T> writeList(id: String, name: String, serializer: kotlinx.serialization.KSerializer<T>, list: List<T>) =
        writeAtomic(File(dir(id).apply { mkdirs() }, name), UmbraJson.encodeToString(ListSerializer(serializer), list))

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
    }
}

/**
 * A 5-field cron expression (minute hour day-of-month month day-of-week), with `*`, numbers, lists (`1,15`), ranges
 * (`1-5`) and steps (a star or a range, a slash and a number: every 15 minutes, every 2 hours from 9 to 17);
 * day-of-week 0 or 7 is Sunday. As in cron, when both day fields are
 * restricted a day matches either.
 */
class Cron(expression: String) {
    private val minutes: Set<Int>
    private val hours: Set<Int>
    private val days: Set<Int>
    private val months: Set<Int>
    private val weekdays: Set<Int>
    private val daysRestricted: Boolean
    private val weekdaysRestricted: Boolean

    init {
        val f = expression.trim().split(Regex("\\s+"))
        require(f.size == 5) { "a cron expression has 5 fields: minute hour day month weekday" }
        minutes = field(f[0], 0, 59)
        hours = field(f[1], 0, 23)
        days = field(f[2], 1, 31)
        months = field(f[3], 1, 12)
        weekdays = field(f[4], 0, 7).map { if (it == 7) 0 else it }.toSet()
        daysRestricted = f[2] != "*"
        weekdaysRestricted = f[4] != "*"
    }

    /** The first minute strictly after [afterMs] that matches, in [zone]. */
    fun next(afterMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        var t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(afterMs), zone).withSecond(0).withNano(0).plusMinutes(1)
        val limit = t.plusYears(5)
        while (t.isBefore(limit)) {
            if (t.monthValue !in months) { t = t.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0); continue }
            val dayOk = when {
                daysRestricted && weekdaysRestricted -> t.dayOfMonth in days || (t.dayOfWeek.value % 7) in weekdays
                daysRestricted -> t.dayOfMonth in days
                weekdaysRestricted -> (t.dayOfWeek.value % 7) in weekdays
                else -> true
            }
            if (!dayOk) { t = t.plusDays(1).withHour(0).withMinute(0); continue }
            if (t.hour !in hours) { t = t.plusHours(1).withMinute(0); continue }
            if (t.minute !in minutes) { t = t.plusMinutes(1); continue }
            return t.toInstant().toEpochMilli()
        }
        error("the cron expression never matches")
    }

    private fun field(s: String, min: Int, max: Int): Set<Int> = s.split(',').flatMap { part ->
        val (range, step) = part.split('/').let { it[0] to (it.getOrNull(1)?.toInt() ?: 1) }
        require(step >= 1) { "bad step in '$part'" }
        val (lo, hi) = when {
            range == "*" -> min to max
            '-' in range -> range.split('-').let { it[0].toInt() to it[1].toInt() }
            else -> range.toInt().let { if ('/' in part) it to max else it to it }
        }
        require(lo in min..max && hi in min..max && lo <= hi) { "'$part' is outside $min-$max" }
        (lo..hi step step).toList()
    }.toSet()

    companion object {
        fun isValid(expression: String): Boolean = runCatching { Cron(expression) }.isSuccess
    }
}

internal val DAY: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
