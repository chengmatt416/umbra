package app.umbra.agent.agents

import app.umbra.agent.agents.proactive.AgentGoal
import app.umbra.agent.agents.proactive.GoalMilestone
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AgentGraphTest {
    private val day = LocalDate.of(2026, 10, 28)
    private fun fact(id: String, text: String, vararg about: String, category: MemoryCategory = MemoryCategory.ENTITY_FACT) =
        Memory.Entry(1, day.toString(), "user", text, category = category, id = id, about = about.toList())
    private fun JsonObject.nodes() = getValue("nodes").jsonArray.map { it.jsonObject }
    private fun JsonObject.links() = getValue("links").jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun goal(id: String = "g1") = AgentGoal(id, "ag1", "Ship the app", "Publish the next version",
        milestones = listOf(GoalMilestone("s1", "Review the build", updatedAtMs = 1)), createdAtMs = 1)

    @Test fun schemaParsesBackWithGraphifyFieldsAndRecordedMetadata() {
        val entry = fact("m1", "x".repeat(150), "me", category = MemoryCategory.PREFERENCE).copy(hits = 3)
        val graph = AgentGraph.of(listOf(entry), emptyList(), emptyList(), emptyList())
        val parsed = Json.parseToJsonElement(graph.toString()).jsonObject
        assertEquals(graph, parsed)
        assertEquals(setOf("directed", "multigraph", "graph", "nodes", "links", "hyperedges"), parsed.keys)
        assertFalse(parsed.getValue("directed").jsonPrimitive.boolean)
        assertFalse(parsed.getValue("multigraph").jsonPrimitive.boolean)
        assertEquals(1, parsed.getValue("graph").jsonObject.getValue("schema_version").jsonPrimitive.int)
        assertEquals("0.9.97", parsed.getValue("graph").jsonObject.text("graphify_version"))
        assertTrue(parsed.getValue("hyperedges").jsonArray.isEmpty())
        for (node in graph.nodes()) assertTrue(node.keys.containsAll(listOf("id", "label", "file_type", "source_file", "source_location", "norm_label", "community")))
        for (link in graph.links()) {
            assertTrue(link.keys.containsAll(listOf("source", "target", "relation", "confidence", "confidence_score", "source_file", "weight")))
            assertEquals("EXTRACTED", link.text("confidence"))
            assertEquals(1.0, link.getValue("confidence_score").jsonPrimitive.double)
        }
        val node = graph.nodes().single { it.text("id") == "fact_m1" }
        assertEquals(120, node.text("label").length)
        assertEquals(entry.text, node.text("text"))
        assertEquals(entry.id, node.text("memory_id"))
        assertEquals(entry.category.name, node.text("category"))
        assertEquals(entry.date, node.text("date"))
        assertEquals(3, node.getValue("hits").jsonPrimitive.int)
        assertEquals("me", graph.nodes().single { it.text("id") == "entity_me" }.text("label"))
    }

    @Test fun idsAndSerializedOrderStayStableAcrossInputOrdering() {
        val entries = listOf(fact("m2", "Mom uses Umbra", "Mom", "Umbra"), fact("m1", "Mom prefers tea", "mom", "me"))
        val notes = listOf(day to listOf("Talked to Mom"), day.minusDays(1) to listOf("Updated Umbra"))
        val goals = listOf(goal("g2"), goal("g1").copy(milestones = listOf(
            GoalMilestone("s2", "Sign the build", updatedAtMs = 1), GoalMilestone("s1", "Review the build", updatedAtMs = 1))))
        val graph = AgentGraph.of(entries, emptyList(), notes, goals)
        val reordered = AgentGraph.of(entries.reversed(), emptyList(), notes.reversed(), goals.reversed().map { it.copy(milestones = it.milestones.reversed()) })
        assertEquals(graph.toString(), reordered.toString())
        val ids = graph.nodes().map { it.text("id") }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(1, graph.nodes().count { it.text("id") == "entity_mom" })
        val added = AgentGraph.of(entries + fact("m3", "A new fact", "New entity"), emptyList(), notes, goals)
        assertTrue(added.nodes().map { it.text("id") }.containsAll(ids))
    }

    @Test fun replacementChainsKeepOldFactsAndPointFromNewToOld() {
        val oldest = fact("m1", "Lives in Taipei", "me").copy(until = day.toString(), supersededBy = "m2")
        val older = fact("m2", "Lives in Tainan", "me").copy(until = day.toString(), supersededBy = "m3")
        val current = fact("m3", "Lives in Kaohsiung", "me")
        val graph = AgentGraph.of(listOf(current), listOf(older, oldest), emptyList(), emptyList())
        assertEquals(listOf("fact_m2" to "fact_m1", "fact_m3" to "fact_m2"), graph.links()
            .filter { it.text("relation") == "replaces" }.map { it.text("source") to it.text("target") })
        for (id in listOf("fact_m1", "fact_m2")) assertEquals("no longer true", graph.nodes().single { it.text("id") == id }.text("status"))
        assertFalse(graph.nodes().single { it.text("id") == "fact_m3" }.containsKey("status"))
        assertEquals(3, graph.links().count { it.text("relation") == "about" && it.text("target") == "entity_me" })
        val missingReplacement = AgentGraph.of(emptyList(), listOf(oldest.copy(supersededBy = "forgotten")), emptyList(), emptyList())
        assertFalse(missingReplacement.links().any { it.text("relation") == "replaces" })
    }

    @Test fun notesMentionCjkNamesBySubstringAndLatinNamesWithoutMatchingOtherWords() {
        val entries = listOf(fact("m1", "媽媽 likes tea", "媽媽"), fact("m2", "Mom likes tea", "Mom"))
        val notes = listOf(day to listOf("提醒媽媽明天回診", "Called MOM"), day.minusDays(1) to listOf("A quiet moment"))
        val graph = AgentGraph.of(entries, emptyList(), notes, emptyList())
        val cjk = graph.nodes().single { it.text("label") == "媽媽" }.text("id")
        val mentions = graph.links().filter { it.text("relation") == "mentions" }
        assertTrue(mentions.any { it.text("source") == "note_$day" && it.text("target") == cjk })
        assertTrue(mentions.any { it.text("source") == "note_$day" && it.text("target") == "entity_mom" })
        assertFalse(mentions.any { it.text("source") == "note_${day.minusDays(1)}" })
        assertEquals("Notes $day", graph.nodes().single { it.text("id") == "note_$day" }.text("label"))
    }

    @Test fun communitiesCountFactsAndGoalsHaveDistinctMilestones() {
        val entries = listOf(
            fact("m1", "Mom works on Umbra", "Mom", "Umbra", "mom", category = MemoryCategory.PERSON),
            fact("m2", "Mom prefers Umbra", "Mom", "Umbra", category = MemoryCategory.PREFERENCE),
            fact("m3", "Ask Mom first", "Mom", category = MemoryCategory.FEEDBACK_RULE),
            fact("m4", "Send the build", "me", category = MemoryCategory.COMMITMENT))
        val graph = AgentGraph.of(entries, emptyList(), emptyList(), listOf(goal(), goal("g2")))
        val related = graph.links().single { it.text("relation") == "related_to" }
        assertEquals("entity_mom", related.text("source"))
        assertEquals("entity_umbra", related.text("target"))
        assertEquals(2.0, related.getValue("weight").jsonPrimitive.double)
        assertEquals(1, graph.nodes().single { it.text("id") == "entity_mom" }.getValue("community").jsonPrimitive.int)
        assertEquals(0, graph.nodes().single { it.text("id") == "fact_m3" }.getValue("community").jsonPrimitive.int)
        assertEquals(2, graph.nodes().single { it.text("id") == "fact_m4" }.getValue("community").jsonPrimitive.int)
        val steps = graph.links().filter { it.text("relation") == "has_step" }
        assertEquals(listOf("goal_g1", "goal_g2"), steps.map { it.text("source") })
        assertEquals(2, steps.map { it.text("target") }.distinct().size)
        val ids = graph.nodes().map { it.text("id") }.toSet()
        assertTrue(graph.links().all { it.text("source") in ids && it.text("target") in ids })
    }
}
