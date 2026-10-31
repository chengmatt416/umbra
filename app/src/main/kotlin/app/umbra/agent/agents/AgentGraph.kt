package app.umbra.agent.agents

import app.umbra.agent.agents.proactive.AgentGoal
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A graph is another view of recorded facts, notes and goals, so exporting it never infers new memories. */
object AgentGraph {
    fun of(entries: List<Memory.Entry>, history: List<Memory.Entry>, notes: List<Pair<LocalDate, List<String>>>,
        goals: List<AgentGoal>): JsonObject {
        val facts = (entries + history).distinctBy { it.id }.sortedBy { it.id }
        val entities = (facts.flatMap { it.about } + "me").map { it.trim() }.filter { it.isNotEmpty() }
            .sorted().groupBy(::normalize).toSortedMap()
        val nodes = mutableListOf<JsonObject>()
        val links = mutableListOf<JsonObject>()
        fun entityId(name: String) = "entity_${key(normalize(name))}"
        fun factId(id: String) = "fact_${key(id)}"
        fun about(e: Memory.Entry) = e.about.map(::normalize).filter { it.isNotEmpty() }.distinct().sorted()
        for ((name, spellings) in entities) {
            val community = facts.filter { name in about(it) }.groupingBy { community(it.category) }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()?.key ?: 1
            nodes += node(entityId(name), if (name == "me") "me" else spellings.first(), "concept", "MEMORY.md", community)
        }
        for (e in facts) {
            nodes += node(factId(e.id), e.text.take(120), "document", "MEMORY.md", community(e.category)) {
                put("memory_id", e.id); put("text", e.text); put("category", e.category.name)
                put("date", e.date); put("hits", e.hits); put("importance", e.importance); put("seen_date", e.seenDate)
                e.until?.let { put("until", it); put("status", "no longer true") }
                e.supersededBy?.let { put("superseded_by", it) }
            }
            about(e).forEach { links += link(factId(e.id), entityId(it), "about", "MEMORY.md") }
            if (e.until != null && facts.any { it.id == e.supersededBy }) {
                links += link(factId(requireNotNull(e.supersededBy)), factId(e.id), "replaces", "MEMORY.md")
            }
        }
        val pairs = mutableMapOf<Pair<String, String>, Int>()
        for (e in facts) {
            val names = about(e)
            for (i in names.indices) for (j in i + 1 until names.size) {
                val pair = names[i] to names[j]
                pairs[pair] = (pairs[pair] ?: 0) + 1
            }
        }
        pairs.forEach { (pair, count) -> links += link(entityId(pair.first), entityId(pair.second), "related_to", "MEMORY.md", count.toDouble()) }
        for ((day, lines) in notes.sortedBy { it.first }) {
            val id = "note_$day"
            val source = "memory/$day.md"
            val text = lines.joinToString("\n")
            nodes += node(id, "Notes $day", "document", source, 3) { put("date", day.toString()); put("text", text) }
            for ((name, _) in entities) if (mentions(text, name)) links += link(id, entityId(name), "mentions", source)
        }
        for (goal in goals.sortedBy { it.id }) {
            val id = "goal_${key(goal.id)}"
            nodes += node(id, goal.title, "document", "goals.json", 2) {
                put("goal_id", goal.id); put("text", goal.description); put("status", goal.state.name)
            }
            for (step in goal.milestones.sortedBy { it.id }) {
                val stepId = "milestone_${key(goal.id)}:${key(step.id)}"
                nodes += node(stepId, step.title, "document", "goals.json", 2) { put("status", step.status) }
                links += link(id, stepId, "has_step", "goals.json")
            }
        }
        return buildJsonObject {
            put("directed", false); put("multigraph", false)
            put("graph", buildJsonObject { put("schema_version", 1); put("graphify_version", "0.9.97") })
            put("nodes", JsonArray(nodes.sortedBy { it.getValue("id").jsonPrimitive.content }))
            put("links", JsonArray(links.sortedWith(compareBy<JsonObject> { it.getValue("source").jsonPrimitive.content }
                .thenBy { it.getValue("target").jsonPrimitive.content }.thenBy { it.getValue("relation").jsonPrimitive.content })))
            put("hyperedges", JsonArray(emptyList()))
        }
    }

    private fun node(id: String, label: String, type: String, source: String, community: Int,
        extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("id", id); put("label", label); put("file_type", type); put("source_file", source)
        put("source_location", ""); put("norm_label", normalize(label)); put("community", community)
        extra()
    }

    private fun link(source: String, target: String, relation: String, file: String, weight: Double = 1.0) = buildJsonObject {
        put("source", source); put("target", target); put("relation", relation); put("confidence", "EXTRACTED")
        put("confidence_score", 1.0); put("source_file", file); put("weight", weight)
    }

    private fun community(category: MemoryCategory) = when (category) {
        MemoryCategory.FEEDBACK_RULE -> 0
        MemoryCategory.PERSON, MemoryCategory.PREFERENCE -> 1
        MemoryCategory.COMMITMENT -> 2
        MemoryCategory.PROJECT_STYLE, MemoryCategory.ENTITY_FACT -> 3
    }
    private fun normalize(text: String) = text.trim().lowercase(Locale.ROOT)
    private fun key(text: String) = URLEncoder.encode(text, "UTF-8")
    private fun mentions(text: String, name: String): Boolean {
        val cjk = name.any { it in '\u3400'..'\u9fff' || it in '\u3040'..'\u30ff' || it in '\uac00'..'\ud7af' }
        return if (cjk) text.contains(name, true) else
            Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(name)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }
}
