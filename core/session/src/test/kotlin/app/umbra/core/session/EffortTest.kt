package app.umbra.core.session

import app.umbra.core.model.UmbraJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EffortTest {
    @Test
    fun clampTakesTheNearestLighterLevelTheModelHas() {
        assertEquals("high", Effort.clamp("xhigh", listOf("low", "high")))
        assertEquals("low", Effort.clamp("medium", listOf("low", "high")))
        assertEquals("high", Effort.clamp("high", listOf("low", "high")))
        // Nothing lighter: the model's lightest.
        assertEquals("low", Effort.clamp("minimal", listOf("low", "high")))
        // A model nobody described, and omp's own judge, are left to omp.
        assertEquals("xhigh", Effort.clamp("xhigh", emptyList()))
        assertEquals("auto", Effort.clamp("auto", listOf("low")))
    }

    @Test
    fun aSessionSavedBeforeKindsAndEffortStillReadsAsACodingSession() {
        val old = """{"id":"s1","title":"t","createdAtMs":1,"request":"r"}"""
        val s = UmbraJson.decodeFromString(Session.serializer(), old)
        assertEquals(SessionKind.CODING, s.kind)
        assertNull(s.effort)
        assertNull(s.agentId)
        val back = UmbraJson.decodeFromString(Session.serializer(), UmbraJson.encodeToString(Session.serializer(), s.copy(effort = Effort("high", auto = true), kind = SessionKind.AGENT, agentId = "mia")))
        assertEquals(Effort("high", auto = true), back.effort)
        assertEquals(SessionKind.AGENT, back.kind)
    }
}
