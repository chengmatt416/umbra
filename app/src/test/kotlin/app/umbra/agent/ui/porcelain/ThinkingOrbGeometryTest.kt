package app.umbra.agent.ui.porcelain

import app.umbra.core.model.UmbraJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThinkingOrbGeometryTest {
    @Test
    fun nativeWorkingPresetMatchesTheUpstreamGeometry() {
        val golden = javaClass.getResourceAsStream("/orb-working-20.json")!!.bufferedReader().use { it.readText() }
        val expected = UmbraJson.parseToJsonElement(golden).jsonArray
        val actual = ThinkingOrbGeometry.frame(20, 1.25)
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (raw, dot) ->
            val e = raw.jsonObject
            listOf("x" to dot.x, "y" to dot.y, "z" to dot.z, "r" to dot.radius, "white" to dot.white).forEach { (key, value) ->
                assertEquals(e.getValue(key).jsonPrimitive.double, value, 0.00001, key)
            }
            assertEquals(e["a"]?.jsonPrimitive?.double ?: 1.0, dot.alpha, 0.00001)
        }
        assertTrue(actual != ThinkingOrbGeometry.frame(20, 2.0))
        assertTrue(ThinkingOrbGeometry.frame(64, 1.25).size > actual.size)
    }
}
