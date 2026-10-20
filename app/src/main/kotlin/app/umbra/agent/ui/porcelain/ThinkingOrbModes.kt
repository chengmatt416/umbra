package app.umbra.agent.ui.porcelain

import app.umbra.agent.ui.porcelain.ThinkingOrbGeometry.Dot
import app.umbra.agent.ui.porcelain.ThinkingOrbGeometry.Edge
import app.umbra.agent.ui.porcelain.ThinkingOrbGeometry.Frame
import kotlin.math.*

/** Native geometry from thinking-orbs de85557, Jakub Antalik (MIT; assets/licenses/thinking-orbs.txt). */
internal object ThinkingOrbModes {
    private data class Point(val x: Double, val y: Double, val z: Double)
    private fun project(yaw: Double, tilt: Double, size: Int, scale: Double): (Point) -> Point {
        val sy = sin(yaw); val cy = cos(yaw); val st = sin(tilt); val ct = cos(tilt)
        return { p ->
            val x = p.x * cy + p.z * sy
            val z = -p.x * sy + p.z * cy
            Point(size / 2.0 + x * scale, size / 2.0 - (p.y * ct - z * st) * scale, p.y * st + z * ct)
        }
    }
    private fun hash(a: Double, b: Double): Double = frac(sin(a * 12.9898 + b * 78.233) * 43758.5453)
    private fun frac(value: Double) = value - floor(value)
    private fun rounded(value: Double) = floor(value + 0.5).toInt()
    private fun count(value: Int, scale: Double) = max(2, rounded(value * sqrt(scale)))
    private fun radius(size: Int) = (size / 300.0).pow(0.6)
    private fun unit(p: Point): Point {
        val length = max(1e-6, sqrt(p.x * p.x + p.y * p.y + p.z * p.z))
        return Point(p.x / length, p.y / length, p.z / length)
    }
    private fun fibonacci(i: Int, n: Int): Point {
        val y = 1 - 2 * (i + 0.5) / n
        val r = sqrt(1 - y * y)
        val a = i * PI * (3 - sqrt(5.0))
        return Point(r * cos(a), y, r * sin(a))
    }
    private fun noise(x: Double, y: Double): Double {
        val xi = floor(x); val yi = floor(y)
        val fx = (x - xi).let { it * it * (3 - 2 * it) }
        val fy = (y - yi).let { it * it * (3 - 2 * it) }
        val a = hash(xi, yi); val b = hash(xi + 1, yi); val c = hash(xi, yi + 1); val d = hash(xi + 1, yi + 1)
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
    }
    private fun dot(p: Point, r: Double, white: Double, alpha: Double = 1.0) = Dot(p.x, p.y, p.z, max(0.3, r), white, alpha)

    fun frame(state: ThinkingOrbState, size: Int, seconds: Double): Frame {
        require(size == 20 || size == 64)
        val small = size == 20
        val frame = when (state) {
            ThinkingOrbState.SEARCHING -> globe(size, seconds * if (small) 2.665 else 2.015, small)
            ThinkingOrbState.SOLVING -> solving(size, seconds * if (small) 1.95 else 1.82, small)
            ThinkingOrbState.COMPOSING -> ribbon(size, seconds * if (small) 3.12 else 2.34, small)
            ThinkingOrbState.CONNECTING -> web(size, seconds * if (small) 6.63 else 3.315, small)
            ThinkingOrbState.WORKING -> return Frame(ThinkingOrbGeometry.frame(size, seconds))
        }
        return Frame(frame.dots.filter { it.alpha >= 0.02 }.sortedBy { it.z }, frame.edges.filter { it.alpha >= 0.02 })
    }

    private fun globe(size: Int, t: Double, small: Boolean): Frame {
        val density = if (small) 0.105 else 0.42
        val rings = count(17, density); val longitude = count(44, density)
        val rs = radius(size); val mul = if (small) 1.75 else 1.15
        val project = project(t * 0.5, 0.4 + 0.06 * sin(t * 0.35), size, size / 2.0 * 0.82)
        val scan = t * (0.5 + 1.2 * if (small) 4.335 else 4.08)
        val dots = ArrayList<Dot>()
        for (li in 0..rings) {
            val lat = -PI / 2 + li.toDouble() / rings * PI
            val n = max(1, rounded(abs(cos(lat)) * longitude))
            for (lj in 0 until n) {
                val lon = lj.toDouble() / n * 2 * PI
                val p = project(Point(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon)))
                val depth = (p.z + 1) / 2
                val d = atan2(sin(lon + t * 0.5 - scan), cos(lon + t * 0.5 - scan))
                val boost = exp(-d * d / 0.18) * max(0.0, p.z)
                dots += dot(p, (0.6 * mul + 1.7 * mul * depth + boost) * rs, 0.62 - 0.54 * depth, 0.45 + 0.55 * min(1.0, boost))
            }
        }
        return Frame(dots)
    }

    private data class Move(val axis: Int, val lo: Double, val angle: Double)
    private val moves = List(14) { i ->
        Move(min(2, floor(hash(i.toDouble(), 2.3) * 3).toInt()), -1 + 0.5 * min(3, floor(hash(i.toDouble(), 5.9) * 4).toInt()), (if (hash(i.toDouble(), 7.7) < 0.5) 1 else -1) * PI / 2)
    }

    private fun solving(size: Int, t: Double, small: Boolean): Frame {
        val density = if (small) 0.088 else 0.35
        val rings = count(15, density); val longitude = count(40, density)
        val rs = radius(size); val mul = if (small) 1.9 else 1.05
        val project = project(t * 0.55, 0.35 + 0.1 * sin(t * 0.9), size, size / 2.0 * 0.82)
        val amount = DoubleArray(moves.size)
        val tc = t % (2 * moves.size * 0.42 + 1.2)
        var active = -1
        if (tc < 2 * moves.size * 0.42) {
            val slot = floor(tc / 0.42).toInt()
            val ep = 1 - (1 - min(1.0, (tc - slot * 0.42) / 0.42 / 0.7)).pow(3)
            active = if (slot < moves.size) slot else 2 * moves.size - 1 - slot
            for (i in 0 until active) amount[i] = 1.0
            amount[active] = if (slot < moves.size) ep else 1 - ep
        }
        val cosines = DoubleArray(moves.size) { cos(moves[it].angle * amount[it]) }
        val sines = DoubleArray(moves.size) { sin(moves[it].angle * amount[it]) }
        val dots = ArrayList<Dot>()
        for (li in 0..rings) {
            val lat = -PI / 2 + li.toDouble() / rings * PI
            val n = max(1, rounded(abs(cos(lat)) * longitude))
            for (lj in 0 until n) {
                val lon = lj.toDouble() / n * 2 * PI
                var x = cos(lat) * cos(lon); var y = sin(lat); var z = cos(lat) * sin(lon)
                var turning = false
                for ((i, m) in moves.withIndex()) {
                    if (amount[i] <= 0) continue
                    val coord = when (m.axis) { 0 -> x; 1 -> y; else -> z }
                    if (coord < m.lo || coord >= m.lo + 0.5) continue
                    if (i == active) turning = true
                    val ca = cosines[i]; val sa = sines[i]
                    when (m.axis) {
                        0 -> { val next = y * ca - z * sa; z = y * sa + z * ca; y = next }
                        1 -> { val next = x * ca + z * sa; z = -x * sa + z * ca; x = next }
                        else -> { val next = x * ca - y * sa; y = x * sa + y * ca; x = next }
                    }
                }
                val p = project(Point(x, y, z)); val depth = (p.z + 1) / 2
                dots += dot(p, (0.6 + 1.7 * depth + if (turning) 0.3 else 0.0) * mul * rs, 0.62 - 0.54 * depth - if (turning) 0.14 else 0.0)
            }
        }
        return Frame(dots)
    }

    private fun ribbon(size: Int, t: Double, small: Boolean): Frame {
        val density = if (small) 0.051 else 0.25
        val lanes = max(1, rounded(count(5, density) * if (small) 4.94 else 3.9))
        val segments = count(88, density)
        val ghostN = max(1, rounded(150 * density))
        val rs = radius(size); val mul = if (small) 1.073 else 0.85
        val r = size / 2.0 * 0.78
        val project = project(0.0, 0.3, size, 1.0)
        val bandSin = sin(0.55); val bandCos = cos(0.55)
        val dots = ArrayList<Dot>()
        for (i in 0 until ghostN) {
            val d = fibonacci(i, ghostN)
            val p = project(Point(d.x * r, d.y * r, d.z * r))
            dots += dot(p, 0.8 * rs, 0.78, 0.1 + 0.22 * (p.z / r + 1) / 2)
        }
        for (w in 0 until lanes) {
            val laneOff = (w - (lanes - 1) / 2.0) * 0.075
            val edge = abs(w - (lanes - 1) / 2.0) / max(1.0, (lanes - 1) / 2.0)
            for (k in 0 until segments) {
                val a = k.toDouble() / segments * 2 * PI
                val off = laneOff + 0.16 * sin(a * 3 - t * 1.7 + w * 0.22) + 0.07 * sin(a * 5 + t * 1.1)
                val d = unit(Point(cos(a), bandCos * sin(a) - bandSin * off, bandSin * sin(a) + bandCos * off))
                val p = project(Point(d.x * r, d.y * r, d.z * r)); val depth = (p.z / r + 1) / 2
                dots += dot(p, (1.1 + 1.7 * depth) * mul * (1 - 0.25 * edge) * rs, 0.52 - 0.44 * depth + 0.18 * edge, 0.4 + 0.6 * depth)
            }
        }
        return Frame(dots)
    }

    private fun web(size: Int, t: Double, small: Boolean): Frame {
        val density = if (small) 0.25 else 1.35
        val n = max(1, rounded(30 * density)); val signals = max(1, rounded(5 * density))
        val mul = if (small) 1.52 else 0.95
        val rs = radius(size); val project = project(t * 0.12, 0.32, size, size / 2.0 * 0.8)
        val nodes = List(n) { i ->
            val d = fibonacci(i, n)
            unit(Point(d.x + 0.6 * (noise(i * 0.31 + 9, t * 0.24) - 0.5), d.y + 0.6 * (noise(i * 0.53 + 27, t * 0.21) - 0.5), d.z + 0.6 * (noise(i * 0.77 + 55, t * 0.27) - 0.5)))
        }
        val edges = ArrayList<Edge>(); val dots = ArrayList<Dot>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val a = nodes[i]; val b = nodes[j]
                val dist = sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2) + (a.z - b.z).pow(2))
                if (dist >= 0.72) continue
                val p = project(a); val q = project(b)
                edges += Edge(p.x, p.y, q.x, q.y, 0.42, (1 - dist / 0.72) * (0.3 + 0.55 * ((p.z + q.z) / 2 + 1) / 2), max(0.6, 0.8 * rs))
            }
            val p = project(nodes[i]); val depth = (p.z + 1) / 2
            dots += dot(p, (1.4 + 1.8 * depth) * mul * (1 + 0.25 * sin(t * 1.4 + i * 2.7)) * rs, 0.55 - 0.45 * depth)
        }
        for (s in 0 until signals) {
            val seg = floor(t * 0.55 + s * 7.31)
            val a = floor(hash(seg, s * 3.1 + 1.7) * n).toInt(); val b = floor(hash(seg, s * 5.7 + 4.2) * n).toInt()
            if (a == b) continue
            val f = frac(t * 0.55 + s * 7.31)
            val p = project(unit(Point(nodes[a].x + (nodes[b].x - nodes[a].x) * f, nodes[a].y + (nodes[b].y - nodes[a].y) * f, nodes[a].z + (nodes[b].z - nodes[a].z) * f)))
            val depth = (p.z + 1) / 2
            dots += dot(p, (1.4 * 1.5 + 1.8 * depth) * mul * rs, 0.05, 0.5 + 0.5 * depth)
        }
        return Frame(dots, edges)
    }
}
