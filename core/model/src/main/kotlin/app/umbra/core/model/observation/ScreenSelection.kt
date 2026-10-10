package app.umbra.core.model.observation

import app.umbra.core.model.Point
import app.umbra.core.model.Rect

/**
 * What the user circled on a screenshot (architecture spec §3.1, Snapshot selection): the region to crop, and the
 * on-screen elements inside it as the accessibility tree reported them at the same instant.
 */
data class ScreenSelection(
    /** The crop, in screen pixels, clamped to the screen. */
    val region: Rect,
    /** Elements inside the selection, in reading order. Password fields are kept but their contents never are. */
    val nodes: List<UiNode>,
    /** Packages the selected elements belong to, most elements first. */
    val apps: List<String>,
    /** The stroke was a tap: the smallest element under it was selected. */
    val tapped: Boolean,
)

/** Turns a lasso stroke into a [ScreenSelection]. Pure, so the same stroke always selects the same things. */
object Lasso {
    /** A stroke smaller than this in both directions is a tap. */
    const val TAP_PX = 32

    /** Pixels added around the stroke, so a loop drawn tight around a word still keeps the whole word. */
    const val PAD_PX = 12

    /** Whether [p] lies inside [polygon] (even-odd rule). Fewer than three points enclose nothing. */
    fun contains(polygon: List<Point>, p: Point): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > p.y) != (b.y > p.y)) {
                val x = (b.x - a.x).toDouble() * (p.y - a.y) / (b.y - a.y).toDouble() + a.x
                if (p.x < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    fun bounds(stroke: List<Point>): Rect {
        require(stroke.isNotEmpty()) { "an empty stroke selects nothing" }
        return Rect(stroke.minOf { it.x }, stroke.minOf { it.y }, stroke.maxOf { it.x } + 1, stroke.maxOf { it.y } + 1)
    }

    /**
     * Selects what [stroke] encloses on a [screen] of that size, from [snapshot] (taken with the screenshot). An element
     * is inside when most of it lies in the stroke's bounds and its centre lies inside the loop; the containers that
     * hold the whole screen are therefore never "selected" by a small loop. A tap selects the smallest element under it.
     */
    fun select(snapshot: UiSnapshot, stroke: List<Point>, screen: Rect): ScreenSelection {
        val raw = bounds(stroke)
        val candidates = snapshot.windows.flatMap { w -> w.nodes.filter { it.isVisible && w.displayId == 0 } }
        if (raw.width < TAP_PX && raw.height < TAP_PX) {
            val at = raw.center
            val hit = candidates.filter { it.bounds.contains(at) }.minWithOrNull(compareBy<UiNode> { it.bounds.area }.thenByDescending { it.depth })
            val region = (hit?.bounds ?: pad(raw, PAD_PX * 4)).intersection(screen) ?: screen
            val inside = candidates.filter { n -> meaningful(n) && region.intersection(n.bounds)?.let { it.area * 2 >= n.bounds.area } == true }
            return ScreenSelection(region, ordered(inside), appsOf(inside), tapped = true)
        }
        val region = pad(raw, PAD_PX).intersection(screen) ?: screen
        // A loop encloses a shape; a stroke that encloses next to nothing (a diagonal drag) selects its rectangle.
        val loop = stroke.size >= 3 && raw.width >= TAP_PX && raw.height >= TAP_PX && area(stroke) * 5 >= raw.area
        val inside = candidates.filter { n ->
            meaningful(n) &&
                (region.intersection(n.bounds)?.let { it.area * 2 >= n.bounds.area } == true) &&
                (!loop || contains(stroke, n.bounds.center) || n.bounds.area <= TAP_PX * TAP_PX)
        }
        return ScreenSelection(region, ordered(inside), appsOf(inside), tapped = false)
    }

    /**
     * The selection as text for the agent: where it is, and every element inside it with its role. Password fields
     * say only that they are there.
     */
    fun describe(selection: ScreenSelection, screen: Rect, appName: (String) -> String = { it }, maxChars: Int = 4_000, treeAvailable: Boolean = true): String {
        val r = selection.region
        val app = selection.apps.firstOrNull()?.let(appName) ?: "an app"
        val head = buildString {
            append(if (selection.tapped) "The user pointed at an element" else "The user circled part of the screen")
            append(" in ").append(app)
            append(": region ").append(r.left).append(',').append(r.top).append('–').append(r.right).append(',').append(r.bottom)
            append(" of a ").append(screen.width).append('×').append(screen.height).append(" screen.")
        }
        if (!treeAvailable) return "$head\nNo accessibility tree could be read with it (Umbra's accessibility service is off); the picture is all there is."
        if (selection.nodes.isEmpty()) return "$head\nThe accessibility tree reports no text or controls inside it; the picture is all there is."
        return "$head\nText and controls inside it, top to bottom:" + elements(selection.nodes, maxChars - head.length)
    }

    /** [nodes] as one line each (role and words), cut at [maxChars]. Password fields say only that they are there. */
    fun elements(nodes: List<UiNode>, maxChars: Int = 4_000): String {
        val out = StringBuilder()
        for (n in nodes) {
            val line = "\n- " + line(n)
            if (out.length + line.length > maxChars) { out.append("\n- … (${nodes.size} elements in all)"); break }
            out.append(line)
        }
        return out.toString()
    }

    /** The elements of [tree] worth reading, in reading order (for Live Context). */
    fun readable(tree: UiTree): List<UiNode> = ordered(tree.nodes.filter { it.isVisible && meaningful(it) })

    private fun line(n: UiNode): String {
        if (n.isPassword) return "${n.shortClass} (password field, contents withheld)"
        val role = when {
            n.isEditable -> "Text field"
            n.has(NodeFlag.CHECKABLE) -> if (n.has(NodeFlag.CHECKED)) "${n.shortClass} (checked)" else "${n.shortClass} (not checked)"
            n.isClickable -> n.shortClass.takeIf { it != "View" && it != "TextView" } ?: "Button"
            else -> null
        }
        val words = listOfNotNull(n.text?.takeIf { it.isNotBlank() }, n.contentDescription?.takeIf { it.isNotBlank() && it != n.text })
            .joinToString(" · ") { "“" + it.replace('\n', ' ').take(300) + "”" }
        val hint = n.hint?.takeIf { it.isNotBlank() && n.text.isNullOrBlank() }?.let { " (hint “$it”)" }.orEmpty()
        return listOfNotNull(role, words.ifEmpty { null }).joinToString(" ") + hint
    }

    /** Elements worth telling the agent about: something to read, or something to use. */
    private fun meaningful(n: UiNode): Boolean = n.isPassword || n.isEditable || !n.label.isNullOrBlank()

    private fun ordered(nodes: List<UiNode>): List<UiNode> =
        nodes.distinctBy { Triple(it.bounds, it.text, it.contentDescription) }.sortedWith(ElementMatcher.readingOrder)

    private fun appsOf(nodes: List<UiNode>): List<String> =
        nodes.mapNotNull { it.packageName }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    private fun pad(r: Rect, by: Int) = Rect(r.left - by, r.top - by, r.right + by, r.bottom + by)

    /** The area the closed stroke encloses (shoelace formula). */
    private fun area(stroke: List<Point>): Long {
        var twice = 0L
        for (i in stroke.indices) {
            val a = stroke[i]
            val b = stroke[(i + 1) % stroke.size]
            twice += a.x.toLong() * b.y - b.x.toLong() * a.y
        }
        return kotlin.math.abs(twice) / 2
    }
}
