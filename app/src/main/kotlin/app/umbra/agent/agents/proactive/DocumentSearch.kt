package app.umbra.agent.agents.proactive

internal data class ContextDocument(val id: String, val name: String, val mime: String,
    val directory: Boolean = false, val size: Long = -1, val modifiedAtMs: Long = 0, val virtual: Boolean = false)

/** Bounded, query-time text retrieval: no full-document copies or persistent index. */
internal object DocumentSearch {
    const val MAX_ENTRIES = 500
    const val MAX_FILE_BYTES = 512_000
    const val MAX_TOTAL_BYTES = 4_000_000
    fun search(root: String, query: String, now: Long,
        children: (String, Int) -> List<ContextDocument>, read: (ContextDocument, Int) -> ByteArray): List<ContextEvidence> {
        require(query.length <= 2000) { "Document searches are limited to 2000 characters" }
        val terms = EvidenceRanker.tokens(query).take(100)
        if (terms.isEmpty()) return emptyList()
        val seen = mutableSetOf(root)
        val queue = ArrayDeque<Pair<String, Int>>().apply { add(root to 0) }
        val matches = mutableListOf<ContextEvidence>()
        var scanned = 0
        var consumed = 0
        while (queue.isNotEmpty() && scanned < MAX_ENTRIES && consumed < MAX_TOTAL_BYTES - 1 && matches.size < 20) {
            val (parent, depth) = queue.removeFirst()
            val entries = children(parent, MAX_ENTRIES - scanned)
            require(entries.size <= MAX_ENTRIES - scanned) { "The document provider exceeded the metadata limit" }
            for (document in entries) {
                scanned++
                if (!seen.add(document.id)) continue
                if (document.directory) {
                    if (depth < 8) queue.add(document.id to depth + 1)
                    continue
                }
                val name = document.name.take(200)
                if (!DocumentText.supports(document)) continue
                val limit = minOf(MAX_FILE_BYTES, MAX_TOTAL_BYTES - consumed - 1)
                if (limit <= 0 || !document.virtual && (document.size > limit || document.size == 0L)) continue
                val bytes = read(document, limit + 1)
                require(bytes.size <= limit + 1) { "The document provider exceeded the content limit" }
                consumed += bytes.size
                if (bytes.size > limit) continue
                val text = DocumentText.extract(document, bytes) ?: continue
                val lower = text.lowercase()
                val position = terms.mapNotNull { term -> lower.indexOf(term).takeIf { it >= 0 } }.minOrNull()
                if (position == null && terms.none { it in name.lowercase() }) continue
                val start = ((position ?: 0) - 300).coerceIn(0, text.length)
                val excerpt = text.substring(start, minOf(text.length, start + 2400))
                matches += ContextEvidence("drive_files", document.id, "$name\n${if (start > 0) "…" else ""}$excerpt",
                    document.modifiedAtMs.takeIf { it > 0 }?.coerceAtMost(now) ?: now, "document")
                if (matches.size >= 20 || consumed >= MAX_TOTAL_BYTES - 1) break
            }
        }
        return matches
    }
}
