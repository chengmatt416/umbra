package app.umbra.agent.agents.proactive

import app.umbra.core.model.Rect
import kotlin.test.*

class DemonstrationTest {
    private fun step(secret: Boolean = false) = DemonstrationStep(0, "example.app", "Login", "example.app:id/value", "EditText", Rect.EMPTY,
        "TYPE", if (secret) "[SECRET_INPUT_HANDOFF]" else "[INPUT_PARAMETER]", "", secret)
    @Test fun extractionKeepsSecretsOutAndCannotEnableAnUntestedDraft() {
        val demo = Demonstration("demo1", "owner", 0, 10, listOf(step(), step(true)), "completed")
        val (skill, instructions) = demo.draft(20)
        assertEquals(listOf("input_1"), skill.parameters.map { it.name })
        assertEquals("owner", skill.ownerAgentId)
        assertEquals(UmbraSkill.Status.DRAFT, skill.status)
        assertNull(skill.testedVersion)
        assertNull(skill.approvedVersion)
        assertContains(instructions, "{{input_1}}")
        assertContains(instructions, "Hand control to the user")
        assertContains(instructions, "Demonstrations do not grant approval")
        assertFailsWith<IllegalArgumentException> { demo.copy(incomplete = true).draft(20) }
        assertFailsWith<IllegalArgumentException> { demo.copy(status = "interrupted").draft(20) }
        assertFailsWith<IllegalArgumentException> { demo.copy(finishedAtMs = null).draft(20) }
    }
}
