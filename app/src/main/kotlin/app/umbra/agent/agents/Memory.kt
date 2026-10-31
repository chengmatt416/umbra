package app.umbra.agent.agents

import app.umbra.agent.agents.proactive.EvidenceRanker
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln

enum class MemoryCategory { PREFERENCE, PERSON, COMMITMENT, PROJECT_STYLE, ENTITY_FACT, FEEDBACK_RULE }

/**
 * Human-editable facts keep their provenance and superseded versions so a correction never silently erases history.
 * Recall uses keyword tokens (including CJK bigrams), entities, recency and confirmations; there is no embedding
 * model on the phone and this is not semantic search. Daily experiences belong in the agent's separate notes.
 */
class Memory(private val file: File) {
    data class Entry(val index: Int, val date: String, val source: String, val text: String,
        val category: MemoryCategory = MemoryCategory.ENTITY_FACT, val tags: List<String> = emptyList(), val expiresAtMs: Long? = null,
        val id: String = "L$index", val about: List<String> = emptyList(), val importance: Double = defaultImportance(category),
        val seenDate: String = date, val hits: Int = 1, val until: String? = null, val supersededBy: String? = null) {
        fun line(): String = "- $id · $date · [$category] · $source · $text" +
            (if (tags.isEmpty()) "" else " (tags: ${tags.joinToString(", ") { "#$it" }})") +
            (if (about.isEmpty()) "" else " (about: ${about.joinToString(", ")})") +
            (expiresAtMs?.let { " (expires: $it)" } ?: "")
    }

    sealed interface Remembered { val entry: Entry }
    data class Added(override val entry: Entry, val related: List<Entry> = emptyList()) : Remembered
    data class Reinforced(override val entry: Entry) : Remembered
    data class Replaced(override val entry: Entry, val old: Entry) : Remembered
    data class Scored(val entry: Entry, val score: Double)

    // The screen holds line indices. Keep its last snapshot across regrouping, including legacy id migration.
    private var listed = emptyMap<Int, String>()

    private fun parse(index: Int, line: String): Entry? {
        if (!line.startsWith("- ")) return null
        val comment = Regex(" <!-- ([^<>]*?) -->$").find(line)?.takeIf { Regex("(?:^|\\s)id=").containsMatchIn(it.groupValues[1]) }
        val metadata = comment?.groupValues?.get(1)?.split(Regex("\\s+"))?.mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.size == 2) pair[0] to pair[1] else null
        }?.toMap().orEmpty()
        val parts = (comment?.let { line.removeRange(it.range) } ?: line).removePrefix("- ").split(" · ", limit = 4)
        if (parts.size < 3 || runCatching { LocalDate.parse(parts[0]) }.isFailure) return null
        val structured = parts.size == 4 && parts[1].startsWith('[') && parts[1].endsWith(']')
        val category = if (structured) runCatching { MemoryCategory.valueOf(parts[1].trim('[', ']')) }.getOrDefault(MemoryCategory.ENTITY_FACT) else MemoryCategory.ENTITY_FACT
        val body = if (structured) parts[3] else parts.drop(2).joinToString(" · ")
        val expiry = Regex(" \\(expires: (\\d+)\\)$").find(body)
        val withoutExpiry = expiry?.let { body.removeRange(it.range) } ?: body
        val tagMatch = Regex(" \\(tags: ([^)]*)\\)$").find(withoutExpiry)
        val tags = tagMatch?.groupValues?.get(1)?.split(',')?.map { it.trim().removePrefix("#") }?.filter { it.isNotBlank() }.orEmpty()
        fun date(key: String): String? = metadata[key]?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
        return Entry(index, parts[0], if (structured) parts[2] else parts[1], tagMatch?.let { withoutExpiry.removeRange(it.range) } ?: withoutExpiry,
            category, tags, expiry?.groupValues?.get(1)?.toLongOrNull(),
            metadata["id"]?.takeIf { it.matches(Regex("m[0-9]+")) } ?: "L$index",
            metadata["about"]?.split('|')?.map { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }?.filter { it.isNotBlank() }.orEmpty(),
            metadata["imp"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: defaultImportance(category),
            date("seen") ?: parts[0], metadata["hits"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1, date("until"), metadata["by"])
    }

    @Synchronized fun entries(now: Long = System.currentTimeMillis()): List<Entry> = all().filter { active(it, now) }.also { list ->
        listed = list.associate { it.index to it.id }
    }
    @Synchronized fun count(): Int = all().count { active(it) }
    @Synchronized fun history(): List<Entry> = all().filter { it.until != null }

    @Synchronized fun add(text: String, source: String, date: LocalDate = LocalDate.now(),
        category: MemoryCategory = MemoryCategory.ENTITY_FACT, tags: List<String> = emptyList(), expiresAtMs: Long? = null,
        about: List<String> = emptyList(), importance: Double? = null, replaces: String? = null): Entry =
        remember(text, source, date, category, tags, expiresAtMs, about, importance, replaces).entry

    /** Consolidation is deterministic; related facts are hints for the agent to decide, never hidden model updates. */
    @Synchronized fun remember(text: String, source: String, date: LocalDate = LocalDate.now(),
        category: MemoryCategory = MemoryCategory.ENTITY_FACT, tags: List<String> = emptyList(), expiresAtMs: Long? = null,
        about: List<String> = emptyList(), importance: Double? = null, replaces: String? = null): Remembered {
        val cleanText = clean(text, 500)
        require(cleanText.isNotBlank()) { "Empty memory" }
        require(expiresAtMs == null || expiresAtMs > System.currentTimeMillis()) { "Memory is already expired" }
        require(importance == null || importance.isFinite() && importance in 0.0..1.0) { "Importance must be between 0 and 1" }
        require(replaces == null || replaces.isNotBlank()) { "Empty replacement" }
        val lines = read()
        val parsed = lines.mapIndexedNotNull(::parse)
        val replaced = replaces?.let { target -> find(parsed.filter { active(it) }, target)
            ?: throw IllegalArgumentException("No active memory matches replaces") }
        val facts = stable(parsed).toMutableList()
        val current = facts.filter { active(it) }
        val old = replaced?.let { previous -> current.first { it.index == previous.index } }
        val duplicate = if (old == null) current.firstOrNull {
            it.category == category && (fold(it.text) == fold(cleanText) || similarity(it.text, cleanText) >= .8)
        } else null
        val entities = about.map { clean(it, 80) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(20)
        val keywords = tags.map { it.replace(Regex("[^\\p{L}\\p{N}_-]"), "").take(40) }.filter { it.isNotEmpty() }.distinct().take(10)
        val today = LocalDate.now().toString()
        if (duplicate != null) {
            // The new words win only when they say more, not when they are the same words in other case or spacing.
            val confirmed = duplicate.copy(text = if (fold(cleanText).contains(fold(duplicate.text)) && fold(cleanText).length > fold(duplicate.text).length) cleanText else duplicate.text,
                tags = (duplicate.tags + keywords).distinct(), about = (duplicate.about + entities).distinctBy { it.lowercase() },
                seenDate = today, hits = if (duplicate.hits == Int.MAX_VALUE) Int.MAX_VALUE else duplicate.hits + 1)
            facts[facts.indexOf(duplicate)] = confirmed
            val saved = save(lines, facts).first { it.id == confirmed.id }
            return Reinforced(saved)
        }
        val entry = Entry(-1, date.toString(), clean(source.replace('·', '-'), 80).ifEmpty { "agent" }, cleanText,
            category, keywords, expiresAtMs, nextId(facts), entities, importance ?: defaultImportance(category))
        // A hint that an older fact may have stopped being true: the same kind of fact in similar words, or about the
        // same particular person, place or project with some words in common. Being about the user is not enough on its
        // own (most facts are), or every new preference would put every old one in doubt.
        val specific = entities.filterNot { it.trim().lowercase() in USER_ENTITIES }
        val related = current.filter {
            val overlap = similarity(it.text, cleanText)
            it.category == category && overlap < .8 && (overlap >= .35 || sharesEntity(it, specific) && overlap >= .15)
        }.sortedByDescending { similarity(it.text, cleanText) }.take(3)
        if (old != null) facts[facts.indexOf(old)] = old.copy(until = today, supersededBy = entry.id)
        facts += entry
        val saved = save(lines, facts)
        val added = saved.first { it.id == entry.id }
        return if (old != null) Replaced(added, saved.first { it.id == old.id }) else Added(added, related)
    }

    /** A compact core has its own budget so growing the searchable archive cannot grow every prompt. */
    @Synchronized fun core(budgetChars: Int = 1800): List<Entry> {
        require(budgetChars >= 0)
        val groups = listOf(MemoryCategory.FEEDBACK_RULE, MemoryCategory.PERSON, MemoryCategory.PREFERENCE, MemoryCategory.COMMITMENT)
        val candidates = all().filter { active(it) && it.category in groups }.sortedWith(
            compareBy<Entry> { when (it.category) {
                MemoryCategory.FEEDBACK_RULE -> 0
                MemoryCategory.PERSON, MemoryCategory.PREFERENCE -> 1
                else -> 2
            } }.thenByDescending { it.importance * (1 + ln(1.0 + it.hits)) }.thenByDescending { it.seenDate }.thenBy { it.index })
        var remaining = budgetChars
        return candidates.takeWhile { e -> (e.line().length + 1 <= remaining).also { if (it) remaining -= e.line().length + 1 } }
    }

    @Synchronized fun search(query: String, limit: Int = 12, category: MemoryCategory? = null, about: String? = null,
        includeHistory: Boolean = false): List<Scored> {
        require(limit in 1..30)
        val terms = EvidenceRanker.tokens(query)
        return all().filter { (active(it) || includeHistory && it.until != null && !expired(it)) && (category == null || it.category == category) &&
            (about == null || it.about.any { entity -> entity.equals(about, true) }) }.mapNotNull { e ->
            val entityMatch = e.about.any { it.equals(query.trim(), true) || about != null && it.equals(about, true) }
            val tokens = EvidenceRanker.tokens(e.text + " " + e.tags.joinToString(" ") + " " + e.about.joinToString(" "))
            val overlap = terms.count { it in tokens }
            if (overlap == 0 && !entityMatch) null else {
                val keyword = overlap.toDouble() / terms.size.coerceAtLeast(1) + if (entityMatch) 1.0 else 0.0
                Scored(e, .45 * keyword + .2 * e.importance + .15 * recency(e) + .1 * minOf(e.hits, 5) / 5 +
                    if (e.category == MemoryCategory.FEEDBACK_RULE || e.category == MemoryCategory.COMMITMENT) .1 else 0.0)
            }
        }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.entry.index }).take(limit)
    }

    @Synchronized fun contextual(query: String): List<Entry> = (core() + search(query).map { it.entry })
        .distinctBy { it.id }.take(40).sortedBy { it.index }

    /** Return the selected facts and both directions of their replacement chains, with cycle protection for hand edits. */
    @Synchronized fun get(ids: List<String>): List<Entry> {
        val facts = all()
        val selected = facts.filter { it.id in ids }.toMutableList()
        var i = 0
        while (i < selected.size) {
            val e = selected[i++]
            facts.filter { it.id == e.supersededBy || it.supersededBy == e.id }.forEach { linked ->
                if (selected.none { it.id == linked.id }) selected += linked
            }
        }
        return selected
    }

    @Synchronized fun edit(old: String, new: String): Boolean {
        require(old.isNotBlank() && new.isNotBlank())
        val lines = read()
        val parsed = lines.mapIndexedNotNull(::parse)
        val previous = find(parsed, old) ?: return false
        val facts = stable(parsed).toMutableList()
        val entry = facts.first { it.index == previous.index }
        facts[facts.indexOf(entry)] = entry.copy(text = clean(new, 500))
        save(lines, facts); return true
    }
    @Synchronized fun forget(text: String): Int {
        require(text.isNotBlank())
        val exact = all().firstOrNull { it.id == text }
        return removeWhere { if (exact != null) it.id == exact.id else it.text.contains(text, true) }
    }
    @Synchronized fun forgetSource(source: String): Int = removeWhere { it.source.equals(source, true) }
    @Synchronized fun forgetConnector(connector: String): Int = removeWhere { entry ->
        val source = entry.source.lowercase()
        val id = connector.lowercase()
        source == id || source.startsWith("$id:") || source == "messages:$id" || source.startsWith("messages:$id:") ||
            id == "drive_files" && (source == "documents" || source.startsWith("documents:"))
    }
    @Synchronized fun forgetBetween(from: LocalDate, to: LocalDate): Int = removeWhere { LocalDate.parse(it.date) in from..to }
    /**
     * Deletes the fact a person tapped: the one with [id], and only while it still says [text]. A row on screen is a
     * snapshot; the agent may have written memory since, and a legacy line's id is its position, so the text is what
     * makes sure the fact deleted is the fact shown. Returns whether it was deleted.
     */
    @Synchronized fun remove(id: String, text: String): Boolean {
        val lines = read()
        val facts = lines.mapIndexedNotNull(::parse)
        // A legacy line's id was its position and becomes a stable id at the first save, so for those the words decide.
        val target = facts.firstOrNull { it.id == id && it.text == text }
            ?: (if (id.startsWith("L")) facts.firstOrNull { it.text == text } else null) ?: return false
        save(lines, stable(facts.filterNot { it.id == target.id }))
        return true
    }

    @Synchronized fun removeAt(index: Int) {
        val lines = read()
        val facts = lines.mapIndexedNotNull(::parse)
        val id = listed[index]
        val target = if (id != null) facts.firstOrNull { it.id == id } else facts.firstOrNull { it.index == index }
        if (target != null) save(lines, stable(facts.filterNot { it.id == target.id }))
    }
    @Synchronized fun raw(): String { read(); return if (file.exists()) file.readText() else "$HEADER\n" }
    @Synchronized fun setRaw(text: String) { write(text.lines()) }

    private fun all() = read().mapIndexedNotNull(::parse)
    private fun expired(e: Entry, now: Long = System.currentTimeMillis()) = e.expiresAtMs?.let { it <= now } == true
    private fun active(e: Entry, now: Long = System.currentTimeMillis()) = e.until == null && !expired(e, now)
    private fun clean(text: String, limit: Int) = text.replace(Regex("\\s+"), " ").trim().take(limit)
    private fun fold(text: String) = text.trim().lowercase().replace(Regex("\\s+"), " ")
    private fun find(facts: List<Entry>, target: String): Entry? = facts.firstOrNull { it.id == target }
        ?: facts.firstOrNull { it.text.contains(target, true) }
    private fun sharesEntity(e: Entry, entities: List<String>) = e.about.any { a -> entities.any { it.equals(a, true) } }
    private fun similarity(a: String, b: String): Double {
        val left = EvidenceRanker.tokens(a)
        val right = EvidenceRanker.tokens(b)
        val union = left + right
        return if (union.isEmpty()) 0.0 else left.intersect(right).size.toDouble() / union.size
    }
    private fun recency(e: Entry): Double = exp(-ln(2.0) * ChronoUnit.DAYS.between(LocalDate.parse(e.seenDate), LocalDate.now()).coerceAtLeast(0) / 30.0)
    private fun nextId(facts: List<Entry>): String {
        val taken = facts.map { it.id }.toSet()
        return generateSequence(1L) { it + 1 }.map { "m$it" }.first { it !in taken }
    }
    private fun stable(facts: List<Entry>): List<Entry> {
        val taken = facts.map { it.id }.toMutableSet()
        val migrated = mutableMapOf<String, String>()
        var next = 1L
        val result = facts.map { entry ->
            if (!entry.id.startsWith("L")) entry else {
                while ("m$next" in taken) next++
                val id = "m$next"
                taken += id
                migrated[entry.id] = id
                entry.copy(id = id)
            }
        }
        listed = listed.mapValues { migrated[it.value] ?: it.value }
        return result.map { entry -> entry.supersededBy?.let { migrated[it] }?.let { entry.copy(supersededBy = it) } ?: entry }
    }
    private fun removeWhere(predicate: (Entry) -> Boolean): Int {
        val lines = read()
        val facts = lines.mapIndexedNotNull(::parse)
        val kept = facts.filterNot(predicate)
        save(lines, stable(kept)); return facts.size - kept.size
    }

    /** Expiry and conservative pruning run only on API writes; unparsed user prose survives regrouping. */
    private fun save(original: List<String>, facts: List<Entry>): List<Entry> {
        var kept = facts.filterNot { expired(it) }
        val excess = kept.count { active(it) } - 600
        if (excess > 0) {
            val cutoff = LocalDate.now().minusDays(180).toString()
            val pruned = kept.filter { active(it) && (it.source.equals("agent", true) || it.source.startsWith("agent:", true)) &&
                it.category in listOf(MemoryCategory.ENTITY_FACT, MemoryCategory.PROJECT_STYLE) && it.seenDate < cutoff }
                .sortedBy { .2 * it.importance + .15 * recency(it) + .1 * minOf(it.hits, 5) / 5 }.take(excess).map { it.id }.toSet()
            kept = kept.map { if (it.id in pruned) it.copy(until = LocalDate.now().toString(), supersededBy = "pruned") else it }
        }
        val lines = mutableListOf(HEADER)
        val prose = original.filterIndexed { i, line -> parse(i, line) == null && line.isNotBlank() && line != HEADER && line !in sections }
        if (prose.isNotEmpty()) { lines += ""; lines += prose }
        for (heading in sections) {
            lines += ""
            lines += heading
            kept.filter { section(it) == heading }.forEach { lines += format(it) }
        }
        write(lines)
        return lines.mapIndexedNotNull(::parse)
    }
    private fun section(e: Entry): String = if (e.until != null) sections[4] else when (e.category) {
        MemoryCategory.PERSON, MemoryCategory.PREFERENCE -> sections[0]
        MemoryCategory.FEEDBACK_RULE -> sections[1]
        MemoryCategory.COMMITMENT -> sections[2]
        else -> sections[3]
    }
    private fun format(e: Entry) = "- ${e.date} · [${e.category}] · ${e.source} · ${e.text}" +
        (if (e.tags.isEmpty()) "" else " (tags: ${e.tags.joinToString(", ") { "#$it" }})") +
        (e.expiresAtMs?.let { " (expires: $it)" } ?: "") +
        " <!-- id=${e.id}" + (if (e.about.isEmpty()) "" else " about=${e.about.joinToString("|") { URLEncoder.encode(it, "UTF-8") }}") +
        " imp=${e.importance} seen=${e.seenDate} hits=${e.hits}" + (e.until?.let { " until=$it" } ?: "") +
        (e.supersededBy?.let { " by=$it" } ?: "") + " -->"
    private fun read(): List<String> {
        require(!Files.isSymbolicLink(file.toPath())) { "Linked memory files are not supported" }
        require(!file.exists() || file.length() <= 2_000_000) { "Memory file exceeds 2 MB" }
        return if (file.exists()) file.readLines() else emptyList()
    }
    private fun write(lines: List<String>) {
        require(!Files.isSymbolicLink(file.toPath())) { "Linked memory files are not supported" }
        file.parentFile?.mkdirs()
        val text = lines.joinToString("\n").trimEnd() + "\n"
        require(text.length <= 2_000_000) { "Memory file exceeds 2 MB" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2_000_000) { "Memory file exceeds 2 MB" }
        val temp = Files.createTempFile(file.parentFile!!.toPath(), ".memory-", ".tmp")
        try { Files.write(temp, bytes); Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temp) }
    }
    companion object {
        /** Names a fact uses for the user themself. */
        private val USER_ENTITIES = setOf("me", "user", "the user", "myself", "我")

        const val HEADER = "# Memory"
        private val sections = listOf("## About you", "## Rules you gave me", "## Commitments", "## Projects and facts", "## No longer true")
        fun defaultImportance(category: MemoryCategory): Double = when (category) {
            MemoryCategory.FEEDBACK_RULE -> .9
            MemoryCategory.COMMITMENT -> .8
            MemoryCategory.PERSON -> .7
            MemoryCategory.PREFERENCE -> .6
            MemoryCategory.PROJECT_STYLE, MemoryCategory.ENTITY_FACT -> .5
        }
    }
}
