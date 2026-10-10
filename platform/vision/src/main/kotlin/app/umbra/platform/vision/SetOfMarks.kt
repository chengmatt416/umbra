package app.umbra.platform.vision

import app.umbra.core.model.Rect
import app.umbra.core.model.observation.ElementMatcher
import app.umbra.core.model.observation.Marker
import app.umbra.core.model.observation.NodeFlag
import app.umbra.core.model.observation.UiNode
import app.umbra.core.model.observation.UiTree
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Set-of-marks visual targeting (spec §7): numbered boxes drawn over the interactive elements of a
 * screenshot, so a model that sees the picture can name a target by number instead of guessing pixel
 * coordinates. The numbering and the box geometry are computed here, from the same tree the semantic
 * path uses, so a mark always resolves back to a node or a precise point.
 */
object SetOfMarks {
    data class Options(
        val maxMarkers: Int = 60,
        /** Elements smaller than this in either direction (display pixels) are not worth marking. */
        val minSidePx: Int = 12,
        /** Two marks whose boxes overlap more than this are duplicates; the labelled one survives. */
        val duplicateIou: Float = 0.85f,
    )

    /** Chooses and numbers the elements to mark (numbers start at 1, in reading order). */
    fun markersFor(tree: UiTree, displayWidth: Int, displayHeight: Int, options: Options = Options()): List<Marker> {
        val screen = Rect(0, 0, displayWidth, displayHeight)
        val candidates = tree.nodes
            .filter { it.isInteractive && it.isVisible && it.isEnabled }
            .mapNotNull { node -> node.bounds.intersection(screen)?.let { node to it } }
            .filter { (_, b) -> b.width >= options.minSidePx && b.height >= options.minSidePx }

        // A list wrapping its rows is not a target of its own: tapping its middle hits some row, and it would be named
        // after its first one. Scrolling a list needs no mark; a scroller with nothing marked inside it keeps its own.
        val rows = candidates.filter { (n, _) -> !isBareScroller(n) }
        val withoutWrappers = candidates.filter { (n, _) -> !isBareScroller(n) || rows.none { (inner, _) -> inner.id != n.id && inner.id.startsWith(n.id + ".") } }

        // Drop near-duplicates (a clickable wrapper around one clickable child has identical bounds).
        val kept = ArrayList<Pair<UiNode, Rect>>()
        for (c in withoutWrappers.sortedByDescending { score(it.first) }) {
            if (kept.none { iou(it.second, c.second) >= options.duplicateIou }) kept += c
        }
        // Too many: keep the most informative, then restore reading order for numbering.
        val chosen = kept.sortedByDescending { score(it.first) }.take(options.maxMarkers)
        return chosen
            .sortedWith { a, b -> ElementMatcher.readingOrder.compare(a.first, b.first) }
            .mapIndexed { i, (node, bounds) -> Marker(index = i + 1, nodeId = node.id, bounds = bounds, label = labelOf(node, tree)?.take(40)) }
    }

    /** Interactive only because it scrolls: no click, no text entry, no state of its own, and no label of its own. */
    private fun isBareScroller(n: UiNode): Boolean =
        n.isScrollable && !n.isClickable && !n.isEditable && !n.has(NodeFlag.CHECKABLE) && n.label.isNullOrBlank() && n.resourceId?.contains("pager", ignoreCase = true) != true

    /** A clickable row usually holds its title in a non-clickable child; the mark is named after the first such text. */
    private fun labelOf(node: UiNode, tree: UiTree): String? {
        node.label?.let { return it }
        val children = tree.nodes.filter { it.parentId == node.id }
        for (child in children) {
            labelOf(child, tree)?.let { return it }
        }
        return null
    }

    private fun score(n: UiNode): Int =
        (if (!n.label.isNullOrBlank()) 100 else 0) + (if (n.isEditable) 30 else 0) + (if (n.isClickable) 20 else 0) +
            (if (n.resourceId != null) 5 else 0)

    private fun iou(a: Rect, b: Rect): Float {
        val inter = a.intersection(b)?.area ?: return 0f
        val union = a.area + b.area - inter
        return if (union <= 0) 0f else inter.toFloat() / union
    }

    // ---- drawing ----

    private val PALETTE = intArrayOf(
        0xE53935, 0x1E88E5, 0x43A047, 0xFB8C00, 0x8E24AA, 0x00ACC1, 0xD81B60,
    ).map { it or (0xFF shl 24) }.toIntArray()

    /**
     * Draws [markers] (in display pixels) on [image] (display pixels times [scale]) and returns a copy.
     */
    fun draw(image: PixelImage, markers: List<Marker>, scale: Float = 1f): PixelImage {
        val out = image.copy()
        // Digit height follows the image so labels stay legible after downscaling, but never dominate.
        val unit = max(2, (min(out.width, out.height) / 220f).roundToInt())
        val line = max(1, unit / 2 + 1)
        for (m in markers) {
            val color = PALETTE[(m.index - 1).mod(PALETTE.size)]
            val l = (m.bounds.left * scale).roundToInt()
            val t = (m.bounds.top * scale).roundToInt()
            val r = (m.bounds.right * scale).roundToInt()
            val b = (m.bounds.bottom * scale).roundToInt()
            out.strokeRect(l, t, max(r, l + 1), max(b, t + 1), line, color)
            drawTag(out, m.index.toString(), l, t, unit, color)
        }
        return out
    }

    /** Number plate in the box's top-left corner (pushed inside the image if needed). */
    private fun drawTag(img: PixelImage, text: String, boxLeft: Int, boxTop: Int, unit: Int, color: Int) {
        val pad = unit
        val w = Digits.width(text, unit) + 2 * pad
        val h = Digits.HEIGHT * unit + 2 * pad
        val x = boxLeft.coerceIn(0, max(0, img.width - w))
        val y = (boxTop - 0).coerceIn(0, max(0, img.height - h))
        img.fillRect(x, y, x + w, y + h, color)
        Digits.draw(img, text, x + pad, y + pad, unit, 0xFFFFFFFF.toInt())
    }

    /**
     * Coordinate ticks along the top and left edges, every [stepPx] display pixels, labelled with the
     * display coordinate, plus faint grid lines. Lets a model read a position off the picture when the
     * tree has nothing to mark (games, canvases, web views without semantics).
     */
    fun drawGrid(image: PixelImage, scale: Float, stepPx: Int, displayWidth: Int, displayHeight: Int): PixelImage {
        require(stepPx > 0) { "grid step must be positive" }
        val out = image.copy()
        val unit = max(2, (min(out.width, out.height) / 260f).roundToInt())
        val lineColor = 0x55FF00FF
        var gx = stepPx
        while (gx < displayWidth) {
            val x = (gx * scale).roundToInt()
            out.fillRect(x, 0, x + 1, out.height, lineColor)
            val label = gx.toString()
            val w = Digits.width(label, unit) + 2 * unit
            out.fillRect(x - w / 2, 0, x + w - w / 2, Digits.HEIGHT * unit + 2 * unit, 0xCC000000.toInt())
            Digits.draw(out, label, x - w / 2 + unit, unit, unit, 0xFFFFFFFF.toInt())
            gx += stepPx
        }
        var gy = stepPx
        while (gy < displayHeight) {
            val y = (gy * scale).roundToInt()
            out.fillRect(0, y, out.width, y + 1, lineColor)
            val label = gy.toString()
            val w = Digits.width(label, unit) + 2 * unit
            val h = Digits.HEIGHT * unit + 2 * unit
            out.fillRect(0, y - h / 2, w, y + h - h / 2, 0xCC000000.toInt())
            Digits.draw(out, label, unit, y - h / 2 + unit, unit, 0xFFFFFFFF.toInt())
            gy += stepPx
        }
        return out
    }
}

/** 5x7 digit font, enough for mark numbers and grid coordinates. */
internal object Digits {
    const val WIDTH = 5
    const val HEIGHT = 7
    private const val GAP = 1

    private val GLYPHS: Map<Char, Array<String>> = mapOf(
        '0' to arrayOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to arrayOf(".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."),
        '4' to arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
    )

    fun width(text: String, unit: Int): Int = text.length * (WIDTH + GAP) * unit - GAP * unit

    fun draw(img: PixelImage, text: String, x: Int, y: Int, unit: Int, argb: Int) {
        var cx = x
        for (ch in text) {
            val glyph = GLYPHS[ch]
            if (glyph != null) {
                for ((row, bits) in glyph.withIndex()) {
                    for ((col, bit) in bits.withIndex()) {
                        if (bit == '#') img.fillRect(cx + col * unit, y + row * unit, cx + (col + 1) * unit, y + (row + 1) * unit, argb)
                    }
                }
            }
            cx += (WIDTH + GAP) * unit
        }
    }
}
