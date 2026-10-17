package app.umbra.agent.ui.porcelain

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Folded Orb: a continuous folded ceramic ribbon (a twisted band around a ring), lit from the upper left and
 * turning slowly. Same geometry and shading as the design reference. It turns faster while the agent works and
 * stands still when the system asks for less motion, when battery saver is on, or when the phone runs hot.
 */
@Composable
fun FoldedOrb(modifier: Modifier = Modifier, working: Boolean = false) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var angle by remember { mutableFloatStateOf(0.42f) }
    LaunchedEffect(working, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!motionAllowed(context)) return@repeatOnLifecycle
            // While the agent works the orb keeps turning; at rest it settles after a few seconds, so an idle
            // screen draws nothing (battery, and a still UI for accessibility tools).
            val until = if (working) Long.MAX_VALUE else System.currentTimeMillis() + IDLE_TURN_MS
            var last = 0L
            while (System.currentTimeMillis() < until) {
                withFrameMillis { now ->
                    if (now - last >= 60) {
                        last = now
                        angle += if (working) 0.016f else 0.007f
                    }
                }
            }
        }
    }
    Canvas(modifier) { drawOrb(angle) }
}

/** Reduced motion, battery saver and thermal state all stop the animation (§3.1). */
fun motionAllowed(context: Context): Boolean {
    val scale = try { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (_: Exception) { 1f }
    if (scale == 0f) return false
    val power = context.getSystemService(PowerManager::class.java) ?: return true
    if (power.isPowerSaveMode) return false
    return power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE
}

private const val IDLE_TURN_MS = 6_000L
private const val N = 54
private const val M = 12

private class Face(val pts: Array<FloatArray>, val z: Float, val shade: Float)

private fun geo(u: Double, v: Double): DoubleArray {
    val t = u + 0.7 + 0.2 * sin(u)
    val rad = 1.1 + v * cos(t)
    return doubleArrayOf(rad * cos(u), v * sin(t) + 0.18 * sin(2 * u), rad * sin(u))
}

private fun DrawScope.drawOrb(angle: Float) {
    val w = size.width
    val h = size.height
    if (w < 5 || h < 5) return
    val scale = min(w * 0.28f, h * 0.34f)
    val cx = w * 0.5f
    val cy = h * 0.48f
    val a = angle.toDouble()
    val tilt = 0.48
    fun rot(p: DoubleArray): FloatArray {
        val x = p[0] * cos(a) + p[2] * sin(a)
        val z = -p[0] * sin(a) + p[2] * cos(a)
        return floatArrayOf(x.toFloat(), (p[1] * cos(tilt) - z * sin(tilt)).toFloat(), (p[1] * sin(tilt) + z * cos(tilt)).toFloat())
    }
    val faces = ArrayList<Face>(N * M)
    for (i in 0 until N) for (j in 0 until M) {
        val u0 = i.toDouble() / N * 2 * PI
        val u1 = (i + 1).toDouble() / N * 2 * PI
        val v0 = (j.toDouble() / M - 0.5) * 1.5
        val v1 = ((j + 1).toDouble() / M - 0.5) * 1.5
        val p0 = rot(geo(u0, v0))
        val p1 = rot(geo(u1, v0))
        val p3 = rot(geo(u0, v1))
        val p2 = rot(geo(u1, v1))
        val ux = p1[0] - p0[0]; val uy = p1[1] - p0[1]; val uz = p1[2] - p0[2]
        val vx = p3[0] - p0[0]; val vy = p3[1] - p0[1]; val vz = p3[2] - p0[2]
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val l = hypot(hypot(nx, ny), nz).takeIf { it > 0f } ?: 1f
        nx /= l; ny /= l; nz /= l
        if (nz < 0) { nx = -nx; ny = -ny; nz = -nz }
        val diff = max(0f, -0.5f * nx - 0.6f * ny + 0.6f * nz)
        val shade = 0.48f + 0.48f * diff + if (j == 0 || j == M - 1) 0.08f else 0f
        faces += Face(arrayOf(p0, p1, p2, p3), (p0[2] + p1[2] + p2[2] + p3[2]) / 4, shade)
    }
    faces.sortBy { it.z }

    // The soft contact shadow under the orb.
    withTransform({
        translate(cx, cy + scale * 0.84f)
        scale(1f, 0.18f, pivot = Offset.Zero)
    }) {
        drawCircle(Brush.radialGradient(listOf(pcHue(0x2E334A37), pcHue(0x00334A37)), center = Offset.Zero, radius = scale * 1.65f), radius = scale * 1.6f, center = Offset.Zero)
    }
    val path = Path()
    val stroke = Stroke(width = 0.6f)
    // the glaze (165, 188, 168) under Porcelain; under Liquid the same glaze in the theme's hue
    val glaze = pcHue(0xFFA5BCA8)
    val gr = (glaze.red * 255).roundToInt(); val gg = (glaze.green * 255).roundToInt(); val gb = (glaze.blue * 255).roundToInt()
    for (f in faces) {
        fun c(x: Int) = min(255, max(0, (x * f.shade + 43).roundToInt()))
        val color = Color(c(gr), c(gg), c(gb))
        path.reset()
        f.pts.forEachIndexed { i, p ->
            val x = cx + p[0] * scale
            val y = cy + p[1] * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
        drawPath(path, color, style = stroke)
    }
}

/** The small glazed sphere of the Capsule (`.cap-dot`). */
@Composable
fun CapsuleDot(modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Box(
        modifier
            .size(size)
            .shadow(3.dp, CircleShape, spotColor = pcHue(0x89A2C2A7))
            .clip(CircleShape)
            .background(Brush.radialGradient(0f to pcHue(0xFFEDF7E7), 0.32f to pcHue(0xFF87B28E), 0.65f to pcHue(0xFF49634D), 1f to pcHue(0xFF273E2F), center = Offset(size.value * 0.9f, size.value * 0.75f))),
    )
}
