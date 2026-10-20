package app.umbra.agent.ui.porcelain

import app.umbra.core.model.UmbraJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThinkingOrbGeometryTest {
    @Test
    fun everyActivityAndBothSizesMatchUpstreamDotsAndConnectingEdges() {
        val golden = javaClass.getResourceAsStream("/orb-activity-frames.json")!!.bufferedReader().use { it.readText() }
        for (raw in UmbraJson.parseToJsonElement(golden).jsonArray) {
            val e = raw.jsonObject
            val state = ThinkingOrbState.valueOf(e.getValue("state").jsonPrimitive.content.uppercase())
            val actual = ThinkingOrbGeometry.frame(state, e.getValue("size").jsonPrimitive.int, e.getValue("seconds").jsonPrimitive.double)
            val dots = e.getValue("dots").jsonArray
            assertEquals(dots.size, actual.dots.size, "$state dot count")
            dots.zip(actual.dots).forEach { (rawDot, dot) ->
                val d = rawDot.jsonObject
                listOf("x" to dot.x, "y" to dot.y, "z" to dot.z, "r" to dot.radius, "white" to dot.white).forEach { (key, value) ->
                    assertEquals(d.getValue(key).jsonPrimitive.double, value, 0.00001, "$state $key")
                }
                assertEquals(d["a"]?.jsonPrimitive?.double ?: 1.0, dot.alpha, 0.00001, "$state alpha")
            }
            val edges = e.getValue("lines").jsonArray
            assertEquals(edges.size, actual.edges.size, "$state edge count")
            edges.zip(actual.edges).forEach { (rawEdge, edge) ->
                val d = rawEdge.jsonObject
                listOf("x1" to edge.x1, "y1" to edge.y1, "x2" to edge.x2, "y2" to edge.y2, "white" to edge.white, "a" to edge.alpha, "w" to edge.width).forEach { (key, value) ->
                    assertEquals(d.getValue(key).jsonPrimitive.double, value, 0.00001, "$state edge $key")
                }
            }
        }
    }

    @Test
    fun activityFramesStayFiniteAndMoveAcrossLongRunningSessions() {
        for (state in ThinkingOrbState.entries) for (size in listOf(20, 64)) {
            assertTrue(ThinkingOrbGeometry.frame(state, size, 1.0) != ThinkingOrbGeometry.frame(state, size, 2.0), "$state animates")
            for (time in listOf(0.0, 1.25, 1_000_000.0)) {
                val frame = ThinkingOrbGeometry.frame(state, size, time)
                assertTrue(frame.dots.isNotEmpty())
                frame.dots.forEach { dot ->
                    assertTrue(listOf(dot.x, dot.y, dot.z, dot.radius, dot.white, dot.alpha).all { it.isFinite() })
                    assertTrue(dot.radius >= 0.3 && dot.alpha in 0.02..1.0)
                }
            }
        }
    }

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
