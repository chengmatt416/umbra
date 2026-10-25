package app.umbra.agent.agents.proactive

import app.umbra.agent.agents.AgentProfile
import java.nio.file.Files
import kotlin.test.*

class SkillsAndTemplatesTest {
    @Test fun synthesizedToolsBindReviewTestsAndApprovalToTheExactSourceVersion() {
        val store = SkillStore(Files.createTempDirectory("transform-review-").toFile())
        val source = "return input.items.length;"
        val skill = skill().copy(executionTarget = ExecutionTarget.SANDBOX_TRANSFORM, synthesizedScript = source, scriptLanguage = "javascript",
            testCases = listOf(TransformTestCase(kotlinx.serialization.json.buildJsonObject { put("items", kotlinx.serialization.json.JsonArray(emptyList())) }, kotlinx.serialization.json.JsonPrimitive(0))))
        store.propose(skill, "Count the supplied JSON items.")
        store.beginTest(skill.id, "test"); store.recordTest(skill.id, "test", true)
        assertFailsWith<IllegalArgumentException> { store.approve(skill.id) }
        store.recordCodeReview(skill.id, 1, SkillStore.hash(source)); store.approve(skill.id)
        assertEquals(source, store.transformForRun(skill.id).synthesizedScript)
        store.disable(skill.id)
        assertFailsWith<IllegalArgumentException> { store.transformForRun(skill.id) }
        store.propose(skill.copy(synthesizedScript = "return 42;"), "Return the constant.")
        assertNull(store.get(skill.id)?.codeReviewSha256)
        assertFailsWith<IllegalArgumentException> { store.recordCodeReview(skill.id, 1, SkillStore.hash(source)) }
        assertFailsWith<IllegalArgumentException> { store.approve(skill.id) }
    }
    @Test fun synthesizedToolParametersAndCompleteSourceAreBounded() {
        SynthesizedTools.validateSource("return input.map(x => x * 2);")
        listOf("return fetch('https://example.com');", "return process.env;", "return document.cookie;", "return 1;".repeat(300)).forEach { source ->
            assertFailsWith<IllegalArgumentException> { SynthesizedTools.validateSource(source) }
        }
        val declared = skill().copy(parameters = listOf(SkillParam("count", "integer", "Count")))
        SynthesizedTools.validateParameters(declared, kotlinx.serialization.json.buildJsonObject { put("count", kotlinx.serialization.json.JsonPrimitive(3)) })
        assertFailsWith<IllegalArgumentException> { SynthesizedTools.validateParameters(declared, kotlinx.serialization.json.buildJsonObject { put("count", kotlinx.serialization.json.JsonPrimitive("3")) }) }
        assertFailsWith<IllegalArgumentException> { SynthesizedTools.validateParameters(declared, kotlinx.serialization.json.JsonObject(emptyMap())) }
    }
    private fun skill() = UmbraSkill("skill1", "Extract totals", "Read totals", executionTarget = ExecutionTarget.CONNECTOR_API,
        verificationChecks = listOf("Check the total against the source"), approvalBoundary = "Stop before sending", updatedAtMs = 1)
    @Test fun everySkillVersionRequiresItsOwnSuccessfulTestAndUserApproval() {
        val root = Files.createTempDirectory("skills-test-").toFile()
        val store = SkillStore(root)
        val draft = store.propose(skill(), "Read the authorized source, extract totals, verify them.")
        assertFailsWith<IllegalArgumentException> { store.approve(draft.id) }
        store.beginTest(draft.id, "test1"); store.recordTest(draft.id, "test1", true); store.approve(draft.id)
        assertTrue(store.instructionsForRun(draft.id, emptyMap()).contains("Stop before sending"))
        store.proposePatch(draft.id, "Use the new selector, then verify the total.")
        store.applyReviewedPatch(draft.id)
        assertFailsWith<IllegalArgumentException> { store.instructionsForRun(draft.id, emptyMap()) }
        assertEquals(2, SkillStore(root).get(draft.id)?.version)
        assertNull(store.get(draft.id)?.testedVersion)
    }
    @Test fun exportStripsRuntimePermissionsContactsRunsAndPrivatePaths() {
        val profile = AgentProfile("private-agent", "Helper", sessionId = "private-session", autoReply = listOf("sms:private-chat"), createdAtMs = 1)
        val routine = AgentRoutine("private-routine", profile.id, "Daily review", RoutineTrigger.EventMatch("email", "private@company.com", "private keyword"), instruction = "Read /workspace/private-project/report.txt",
            enabled = true, authorizedAtMs = 123, createdAtMs = 2, recentRuns = listOf(RoutineRunRecord("private-run", 3, idempotencyKey = "private-key")))
        val exported = AgentTemplates.export(profile, "Use sms:private-chat and private@company.com", listOf(SkillTemplate(skill(), "Read /home/agent/private/file")), listOf(routine))
        listOf("private-agent", "private-session", "private-routine", "private-run", "private-key", "sms:private-chat", "private@company.com", "private-project", "/home/agent/private").forEach { assertFalse(exported.contains(it), it) }
        val parsed = AgentTemplates.parse(exported)
        assertTrue(parsed.routines.single().recentRuns.isEmpty())
        assertFalse(parsed.routines.single().enabled)
        assertNull(parsed.routines.single().authorizedAtMs)
        assertNull((parsed.routines.single().trigger as RoutineTrigger.EventMatch).senderOrChannelFilter)
        assertNull(parsed.skills.single().skill.approvedVersion)
    }
    @Test fun malformedTemplateDefinitionsAreRejectedBeforeImportCreatesAnAgent() {
        val bundle = AgentTemplateBundle(name = "Example", avatar = "a1", template = "blank", soul = "Role", requiredConnectors = emptyList(),
            skills = listOf(SkillTemplate(skill(), "Read and verify")))
        fun parse(candidate: AgentTemplateBundle) = AgentTemplates.parse(app.umbra.core.model.UmbraJson.encodeToString(AgentTemplateBundle.serializer(), candidate))
        parse(bundle)
        assertFailsWith<IllegalArgumentException> { parse(bundle.copy(skills = listOf(SkillTemplate(skill().copy(parameters = listOf(SkillParam("bad-name", description = "Invalid"))), "Read")))) }
        assertFailsWith<IllegalArgumentException> { parse(bundle.copy(routines = listOf(AgentRoutine("r", "template", "Invalid cadence", RoutineTrigger.CronSchedule("* * * * *", "UTC"), instruction = "Read", createdAtMs = 0)))) }
        assertFailsWith<IllegalArgumentException> { parse(bundle.copy(routines = listOf(AgentRoutine("r", "template", "Missing skill", RoutineTrigger.Manual, skillId = "missing", instruction = "Read", createdAtMs = 0)))) }
    }
}
