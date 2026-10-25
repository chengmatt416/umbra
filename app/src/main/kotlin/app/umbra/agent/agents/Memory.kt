package app.umbra.agent.agents

import app.umbra.agent.agents.proactive.EvidenceRanker
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

enum class MemoryCategory { PREFERENCE, PERSON, COMMITMENT, PROJECT_STYLE, ENTITY_FACT, FEEDBACK_RULE }

/** Human-editable Markdown, including legacy dated lines, with structured retrieval metadata. */
class Memory(private val file: File) {
    data class Entry(val index: Int, val date: String, val source: String, val text: String,
        val category: MemoryCategory = MemoryCategory.ENTITY_FACT, val tags: List<String> = emptyList(), val expiresAtMs: Long? = null)

    private fun parse(index: Int, line: String): Entry? {
        if (!line.startsWith("- ")) return null
        val parts = line.removePrefix("- ").split(" · ", limit = 4)
        if (parts.size < 3 || runCatching { LocalDate.parse(parts[0]) }.isFailure) return null
        val structured = parts.size == 4 && parts[1].startsWith('[') && parts[1].endsWith(']')
        val category = if (structured) runCatching { MemoryCategory.valueOf(parts[1].trim('[', ']')) }.getOrDefault(MemoryCategory.ENTITY_FACT) else MemoryCategory.ENTITY_FACT
        val body = if (structured) parts[3] else parts.drop(2).joinToString(" · ")
        val expiry = Regex(" \\(expires: (\\d+)\\)$").find(body)
        val withoutExpiry = expiry?.let { body.removeRange(it.range) } ?: body
        val tagMatch = Regex(" \\(tags: ([^)]*)\\)$").find(withoutExpiry)
        val tags = tagMatch?.groupValues?.get(1)?.split(',')?.map { it.trim().removePrefix("#") }?.filter { it.isNotBlank() }.orEmpty()
        return Entry(index, parts[0], if (structured) parts[2] else parts[1], tagMatch?.let { withoutExpiry.removeRange(it.range) } ?: withoutExpiry,
            category, tags, expiry?.groupValues?.get(1)?.toLongOrNull())
    }
    @Synchronized fun entries(now: Long = System.currentTimeMillis()): List<Entry> = read().mapIndexedNotNull(::parse).filter { it.expiresAtMs?.let { at -> at <= now } != true }
    @Synchronized fun add(text: String, source: String, date: LocalDate = LocalDate.now(),
        category: MemoryCategory = MemoryCategory.ENTITY_FACT, tags: List<String> = emptyList(), expiresAtMs: Long? = null): Entry {
        val clean = text.replace('\n', ' ').trim().take(500)
        require(clean.isNotBlank()) { "Empty memory" }
        require(expiresAtMs == null || expiresAtMs > System.currentTimeMillis()) { "Memory is already expired" }
        val lines = read().toMutableList().also { if (it.isEmpty()) it += HEADER }
        val entry = Entry(lines.size, date.toString(), source.replace('·', '-').replace('\n', ' ').trim().take(80).ifEmpty { "agent" }, clean,
            category, tags.map { it.replace(Regex("[^\\p{L}\\p{N}_-]"), "").take(40) }.filter { it.isNotEmpty() }.distinct().take(10), expiresAtMs)
        lines += format(entry)
        write(lines)
        return entry
    }
    @Synchronized fun contextual(query: String): List<Entry> {
        val all = entries()
        val recent = all.takeLast(30)
        val terms = EvidenceRanker.tokens(query)
        val relevant = all.dropLast(minOf(30, all.size)).map { e -> e to terms.count { it in (e.text + " " + e.tags.joinToString(" ")).lowercase() } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(30).map { it.first }
        return (all.filter { it.category == MemoryCategory.FEEDBACK_RULE } + recent + relevant).distinctBy { it.index }.sortedBy { it.index }
    }
    @Synchronized fun edit(old: String, new: String): Boolean {
        require(old.isNotBlank() && new.isNotBlank())
        val lines = read().toMutableList()
        val entry = lines.mapIndexedNotNull(::parse).firstOrNull { it.text.contains(old, true) } ?: return false
        lines[entry.index] = format(entry.copy(text = new.replace('\n', ' ').trim().take(500)))
        write(lines); return true
    }
    @Synchronized fun forget(text: String): Int {
        require(text.isNotBlank())
        return removeWhere { it.text.contains(text, true) }
    }
    @Synchronized fun forgetSource(source: String): Int = removeWhere { it.source.equals(source, true) }
    @Synchronized fun forgetConnector(connector: String): Int = removeWhere { entry ->
        val source = entry.source.lowercase()
        val id = connector.lowercase()
        source == id || source.startsWith("$id:") || source == "messages:$id" || source.startsWith("messages:$id:") ||
            id == "drive_files" && (source == "documents" || source.startsWith("documents:"))
    }
    @Synchronized fun forgetBetween(from: LocalDate, to: LocalDate): Int = removeWhere { LocalDate.parse(it.date) in from..to }
    @Synchronized fun removeAt(index: Int) { write(read().filterIndexed { i, _ -> i != index }) }
    @Synchronized fun raw(): String = if (file.exists()) file.readText() else "$HEADER\n"
    @Synchronized fun setRaw(text: String) = write(text.lines())
    private fun removeWhere(predicate: (Entry) -> Boolean): Int {
        val lines = read()
        val kept = lines.filterIndexed { i, s -> parse(i, s)?.let(predicate) != true }
        write(kept); return lines.size - kept.size
    }
    private fun format(e: Entry) = "- ${e.date} · [${e.category}] · ${e.source} · ${e.text}" +
        (if (e.tags.isEmpty()) "" else " (tags: ${e.tags.joinToString(", ") { "#$it" }})") +
        (e.expiresAtMs?.let { " (expires: $it)" } ?: "")
    private fun read(): List<String> {
        require(!Files.isSymbolicLink(file.toPath())) { "Linked memory files are not supported" }
        require(!file.exists() || file.length() <= 2_000_000) { "Memory file exceeds 2 MB" }
        return if (file.exists()) file.readLines() else emptyList()
    }
    private fun write(lines: List<String>) {
        file.parentFile?.mkdirs()
        val text = lines.joinToString("\n").trimEnd() + "\n"
        require(text.length <= 2_000_000) { "Memory file exceeds 2 MB" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2_000_000) { "Memory file exceeds 2 MB" }
        val temp = Files.createTempFile(file.parentFile!!.toPath(), ".memory-", ".tmp")
        try { Files.write(temp, bytes); Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temp) }
    }
    companion object { const val HEADER = "# Memory" }
}
