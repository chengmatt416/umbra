package app.umbra.agent.ui.porcelain

import kotlin.math.*

/** Native port of thinking-orbs' working state at de85557 (Jakub Antalik, MIT; assets/licenses). */
internal object ThinkingOrbGeometry {
    data class Dot(val x: Double, val y: Double, val z: Double, val radius: Double, val white: Double, val alpha: Double)
    private fun hash(a: Double, b: Double): Double {
        val value = sin(a * 12.9898 + b * 78.233) * 43758.5453
        return value - floor(value)
    }

    fun frame(size: Int, seconds: Double): List<Dot> {
        require(size == 20 || size == 64)
        val small = size == 20
        val t = seconds * if (small) 3.9 else 1.885
        val count = if (small) 0.238 else 1.0
        val radiusMultiplier = if (small) 2.4 else 1.0
        val orbitCount = floor(12 * count + 0.5).toInt().coerceAtLeast(1)
        val ghostCount = floor(40 * count + 0.5).toInt().coerceAtLeast(1)
        val radiusScale = (size / 300.0).pow(0.6) * radiusMultiplier
        val r = size / 2.0 * 0.82
        val sy = sin(t * 0.12)
        val cy = cos(t * 0.12)
        val st = sin(0.3)
        val ct = cos(0.3)
        val dots = ArrayList<Dot>(orbitCount * (ghostCount + 3))
        for (orb in 0 until orbitCount) {
            val h1 = hash(orb.toDouble(), 1.7)
            val h2 = hash(orb.toDouble(), 5.2)
            val h3 = hash(orb.toDouble(), 8.9)
            val ro = r * (0.45 + 0.52 * h1)
            val th = h1 * 2 * PI
            val phi = acos(2 * h2 - 1)
            val nx = sin(phi) * cos(th)
            val ny = cos(phi)
            val nz = sin(phi) * sin(th)
            val length = max(1e-6, sqrt(ny * ny + nx * nx))
            val ux = -ny / length
            val uy = nx / length
            val vx = -nz * uy
            val vy = nz * ux
            val vz = nx * uy - ny * ux
            fun dot(angle: Double, ghost: Boolean) {
                val x = (ux * cos(angle) + vx * sin(angle)) * ro
                val y = (uy * cos(angle) + vy * sin(angle)) * ro
                val z = vz * sin(angle) * ro
                val x1 = x * cy + z * sy
                val z1 = -x * sy + z * cy
                val y1 = y * ct - z1 * st
                val z2 = y * st + z1 * ct
                val depth = (z2 / ro + 1) / 2
                val radius = (if (ghost) 0.9 else 1.2 + 1.6 * depth) * radiusScale
                dots += Dot(size / 2.0 + x1, size / 2.0 - y1, z2, max(0.3, radius), if (ghost) 0.72 else 0.3 - 0.22 * depth, if (ghost) 0.5 * (0.4 + 0.6 * depth) else 1.0)
            }
            for (k in 0 until ghostCount) dot(k.toDouble() / ghostCount * 2 * PI, true)
            val speed = (0.25 + 0.55 * h3) * if (h3 > 0.5) 1 else -1
            for (m in 0 until 3) dot(t * speed + m / 3.0 * 2 * PI + h2 * 6, false)
        }
        return dots.filter { it.alpha >= 0.02 }.sortedBy { it.z }
    }
}
