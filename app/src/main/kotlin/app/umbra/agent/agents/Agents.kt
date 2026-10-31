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
    val proactivityLevel: Int = 3,
    val coordinator: Boolean = false,
    val shareBrowserProfile: Boolean = false,
    val morningBriefMinute: Int? = 8 * 60,
    val eveningBriefMinute: Int? = 18 * 60,
    val prepareMeetingMinutes: Int = 30,
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
    /** When background runs started in the last hour ([WakeBudget]). */
    val backgroundRuns: List<Long> = emptyList(),
    /** Work a wake had to hold back (budget spent, or the previous background run still working): it runs next time. */
    val deferred: List<String> = emptyList(),
)

/** Two agents that wrote to each other, and the last thing said: a row of its own in the Agents list. */
@Serializable
data class AgentPair(val a: String, val b: String, val fromId: String, val text: String, val atMs: Long)

/** A suggested SOUL change waits for the user's decision; proposing it never changes the agent's instructions. */
@Serializable
data class SoulProposal(val id: String, val section: String, val text: String, val why: String, val atMs: Long, val state: String = "pending")

object Templates {
    const val ASSISTANT = "assistant"
    const val INBOX = "inbox"
    const val WORK = "work"
    const val BLANK = "blank"

    val all = listOf(ASSISTANT, INBOX, WORK, BLANK)

    /** A first SOUL.md for a template; the user edits it freely. */
    fun soul(template: String, name: String): String {
        if (template !in listOf(ASSISTANT, INBOX, WORK)) return "# $name\n\n## Who I am\nI'm $name."
        val who = when (template) {
            INBOX -> "I'm $name. I keep your inbox calm: I read new messages and notifications, decide what needs you, " +
                "and give short summaries. I draft replies but never send them without asking."
            WORK -> "I'm $name, your work assistant. I follow work chats and email notifications, keep track of deadlines and " +
                "commitments, and start coding sessions on this phone for technical work. I report progress at the end of each workday."
            else -> "I'm $name, your assistant on this phone. I look after your messages, notifications and plans, remind you of what " +
                "matters, and get things done for you."
        }
        return "# $name\n\n## Who I am\n$who\n\n" +
            "## How I talk\nBriefly and warmly, in your language. One clear sentence beats three careful ones. I say when I'm unsure.\n\n" +
            "## What I care about\n" +
            "- Your attention: I bring you what needs you and keep the rest for your brief.\n" +
            "- Being right: I check before I say something is done, and I show where a fact came from.\n" +
            "- Learning you: I remember your preferences, people and routines, and I correct myself when you correct me.\n\n" +
            "## Boundaries\n" +
            "- I never send a message for you without asking, unless you allowed it for that chat.\n" +
            "- I ask before anything that costs money, deletes something or can't be undone.\n" +
            "- I don't disturb you in quiet hours unless it's urgent.\n" +
            "- Codes, passwords and bank details are never repeated, stored or sent anywhere." + when (template) {
                INBOX -> "\n- Group chats are summarised, not answered."
                WORK -> "\n- I ask before replying to anyone senior or external."
                else -> ""
            }
    }
}

/**
 * The agents on this phone, each in `agents/<id>/`: profile.json, SOUL.md, MEMORY.md, timers.json, rules.json,
 * queue.json, state.json. Profiles are kept in memory (a flow for the screens); the files are the truth across
 * restarts and updates.
 */
class AgentStore(private val root: File) {
    private val memories = java.util.concurrent.ConcurrentHashMap<String, Memory>()
    private val mutable = MutableStateFlow(loadAll())
    val profiles: StateFlow<List<AgentProfile>> = mutable.asStateFlow()

    fun dir(id: String) = File(root, id)
    fun memory(id: String) = memories.getOrPut(id) { Memory(File(dir(id), "MEMORY.md")) }
    fun teamMemory() = memories.getOrPut("TEAM_MEMORY") { Memory(File(root, "TEAM_MEMORY.md")) }
    fun get(id: String): AgentProfile? = mutable.value.firstOrNull { it.id == id }
    fun bySession(sessionId: String): AgentProfile? = mutable.value.firstOrNull { it.sessionId == sessionId }

    @Synchronized
    fun save(profile: AgentProfile) {
        require(profile.proactivityLevel in 0..4 && profile.prepareMeetingMinutes in 5..120)
        require(listOfNotNull(profile.morningBriefMinute, profile.eveningBriefMinute, profile.quietStartMin, profile.quietEndMin).all { it in 0..1439 })
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

    /** Daily experiences stay searchable without filling the durable facts or every wake's prompt. */
    @Synchronized
    fun appendNote(id: String, text: String, at: Long = System.currentTimeMillis()) {
        val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        val day = time.toLocalDate()
        val today = LocalDate.now()
        require(noteDate("$day.md") == day) { "Invalid note date" }
        val clean = text.replace('\n', ' ').replace('\r', ' ').trim().take(2000)
        require(clean.isNotBlank()) { "Empty note" }
        val folder = agentFile(id, "memory").apply { mkdirs() }
        val file = agentFile(id, "memory", "$day.md")
        require(!file.exists() || file.length() <= 2_000_000) { "Notes file exceeds 2 MB" }
        val previous = if (file.exists()) file.readText().trimEnd() + "\n" else ""
        val next = previous + "- ${time.format(DateTimeFormatter.ofPattern("HH:mm"))} · $clean\n"
        require(next.toByteArray(Charsets.UTF_8).size <= 2_000_000) { "Notes file exceeds 2 MB" }
        writeAgentFile(file, next)
        folder.listFiles().orEmpty().forEach { old ->
            val date = noteDate(old.name)
            if (date != null && date < today.minusDays(59)) agentFile(id, "memory", old.name).delete()
        }
    }

    @Synchronized
    fun notes(id: String, days: Int = 2): List<Pair<LocalDate, List<String>>> {
        require(days in 1..60)
        val folder = agentFile(id, "memory")
        val today = LocalDate.now()
        return folder.listFiles().orEmpty().mapNotNull { file ->
            val day = noteDate(file.name) ?: return@mapNotNull null
            if (day !in today.minusDays(days.toLong() - 1)..today) return@mapNotNull null
            val safe = agentFile(id, "memory", file.name)
            require(safe.length() <= 2_000_000) { "Notes file exceeds 2 MB" }
            day to safe.readLines().filter { it.startsWith("- ") }.map { it.removePrefix("- ") }
        }.sortedBy { it.first }
    }

    @Synchronized
    fun searchNotes(id: String, query: String, days: Int = 30): List<Pair<LocalDate, List<String>>> {
        val terms = app.umbra.agent.agents.proactive.EvidenceRanker.tokens(query)
        return notes(id, days).mapNotNull { (day, lines) ->
            val matches = lines.filter { line -> query.isNotBlank() && (line.contains(query, true) ||
                app.umbra.agent.agents.proactive.EvidenceRanker.tokens(line).any { it in terms }) }
            if (matches.isEmpty()) null else day to matches
        }
    }

    @Synchronized
    fun soulProposals(id: String): List<SoulProposal> {
        val file = agentFile(id, "soul-proposals.json")
        require(!file.exists() || file.length() <= 2_000_000) { "SOUL proposals exceed 2 MB" }
        return readList(id, "soul-proposals.json", SoulProposal.serializer())
    }

    @Synchronized
    fun proposeSoul(id: String, section: String, text: String, why: String): SoulProposal {
        val heading = section.trim()
        require(heading.isNotBlank() && heading.length <= 120 && heading.none { it == '\n' || it == '\r' || it == '#' }) { "Invalid SOUL section" }
        require(text.isNotBlank() && text.length <= 10_000 && why.isNotBlank() && why.length <= 2000) { "Invalid SOUL proposal" }
        val proposals = soulProposals(id)
        val proposal = SoulProposal(java.util.UUID.randomUUID().toString(), heading, text.trim(), why.trim(), System.currentTimeMillis())
        val next = proposals + proposal
        val pending = next.filter { it.state == "pending" }.takeLast(20).map { it.id }.toSet()
        writeAgentFile(agentFile(id, "soul-proposals.json"), UmbraJson.encodeToString(ListSerializer(SoulProposal.serializer()),
            next.filter { it.state != "pending" || it.id in pending }))
        return proposal
    }

    /** Only the user-facing acceptance path applies a proposal; an agent can merely put one in the pending list. */
    @Synchronized
    fun resolveSoulProposal(id: String, proposalId: String, accept: Boolean): Boolean {
        val proposals = soulProposals(id)
        val proposal = proposals.firstOrNull { it.id == proposalId && it.state == "pending" } ?: return false
        if (accept) {
            val file = agentFile(id, "SOUL.md")
            require(!file.exists() || file.length() <= 2_000_000) { "SOUL file exceeds 2 MB" }
            val lines = (if (file.exists()) file.readText() else "").trimEnd().lines().toMutableList()
            val heading = "## ${proposal.section}"
            val section = lines.indexOfFirst { it.trimEnd() == heading }
            if (section < 0) lines += listOf("", heading, proposal.text)
            else {
                val end = (section + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") || lines[it].startsWith("# ") } ?: lines.size
                lines.addAll(end, listOf("", proposal.text, ""))
            }
            writeAgentFile(file, lines.joinToString("\n").trim() + "\n")
        }
        writeAgentFile(agentFile(id, "soul-proposals.json"), UmbraJson.encodeToString(ListSerializer(SoulProposal.serializer()),
            proposals.map { if (it.id == proposalId) it.copy(state = if (accept) "accepted" else "declined") else it }))
        return true
    }

    private fun noteDate(name: String): LocalDate? = if (name.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.md")))
        runCatching { LocalDate.parse(name.removeSuffix(".md")) }.getOrNull() else null

    /** Reject links as well as path components so new note and proposal helpers cannot escape an agent's folder. */
    private fun agentFile(id: String, vararg parts: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid agent id" }
        require(!java.nio.file.Files.isSymbolicLink(root.toPath())) { "Linked agent folders are not supported" }
        var file = root
        for (part in listOf(id) + parts) {
            require(part.isNotBlank() && part != "." && part != ".." && '/' !in part && '\\' !in part) { "Invalid agent path" }
            file = File(file, part)
            require(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "Linked agent files are not supported" }
        }
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Invalid agent path" }
        return file
    }

    private fun writeAgentFile(file: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2_000_000) { "Agent file exceeds 2 MB" }
        file.parentFile!!.mkdirs()
        val temp = java.nio.file.Files.createTempFile(file.parentFile!!.toPath(), ".agent-", ".tmp")
        try {
            java.nio.file.Files.write(temp, bytes)
            java.nio.file.Files.move(temp, file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { java.nio.file.Files.deleteIfExists(temp) }
    }

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
