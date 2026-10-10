package app.umbra.tools

import app.umbra.core.model.Point
import app.umbra.core.model.Rect
import app.umbra.core.model.observation.Marker
import app.umbra.core.model.observation.UiNode
import app.umbra.core.model.observation.UiTree
import java.util.concurrent.ConcurrentHashMap

/**
 * What the agent saw of an element when it last observed it. An element id is only valid within one
 * snapshot, so before acting on an id the pipeline checks that the node now at that id is still the
 * element the agent meant (spec §6 "stale element detection").
 */
data class Fingerprint(
    val className: String?,
    val resourceId: String?,
    val label: String?,
    val bounds: Rect,
) {
    /** True when [node] could be the element this was taken from. Layout may have moved it. */
    fun matches(node: UiNode): Boolean {
        if (resourceId != null && node.resourceId != null && resourceId != node.resourceId) return false
        if (className != null && node.className != null && className != node.className) return false
        val nodeLabel = node.label
        if (label != null && nodeLabel != null && !label.trim().equals(nodeLabel.trim(), ignoreCase = true)) return false
        // Nothing distinctive to compare (an unlabelled container): fall back to position.
        if (resourceId == null && label == null) {
            val inter = bounds.intersection(node.bounds)?.area ?: return false
            val union = bounds.area + node.bounds.area - inter
            return union > 0 && inter.toFloat() / union >= 0.5f
        }
        return true
    }

    private fun distanceTo(node: UiNode): Long {
        val dx = (bounds.centerX - node.bounds.centerX).toLong()
        val dy = (bounds.centerY - node.bounds.centerY).toLong()
        return dx * dx + dy * dy
    }

    /** The unique node in [candidates] that matches, or the nearest when several do and one is clearly closest. */
    fun relocate(candidates: List<UiNode>): UiNode? {
        val matching = candidates.filter { matches(it) && it.isVisible }
        if (matching.size == 1) return matching.single()
        if (matching.isEmpty()) return null
        val sorted = matching.sortedBy { distanceTo(it) }
        // Several look alike (three "OK" buttons): only trust the nearest if it is far closer than the rest.
        return if (distanceTo(sorted[1]) > 4 * distanceTo(sorted[0]) + 100) sorted[0] else null
    }

    companion object {
        fun of(node: UiNode) = Fingerprint(node.className, node.resourceId, node.label, node.bounds)
    }
}

/** Marks drawn on the last annotated screenshot of a display. */
data class MarkSet(val markers: List<Marker>, val scale: Float)

class ElementMemory(private val capacity: Int = 1_000) {
    private val prints = object : LinkedHashMap<String, Fingerprint>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Fingerprint>?) = size > capacity
    }
    private val marks = ConcurrentHashMap<Int, MarkSet>()

    /** Remembers everything the agent could refer to in [tree]: interactive or labelled nodes. */
    fun remember(tree: UiTree) {
        synchronized(prints) {
            for (n in tree.nodes) {
                if (n.isVisible && (n.isInteractive || !n.label.isNullOrBlank())) prints[n.id] = Fingerprint.of(n)
            }
        }
    }

    fun get(id: String): Fingerprint? = synchronized(prints) { prints[id] }

    /** Forget a window's ids once they can no longer be trusted (the window is gone). */
    fun forgetWindow(windowId: Int) {
        synchronized(prints) { prints.keys.removeAll { it.startsWith("$windowId:") } }
    }

    fun rememberMarks(displayId: Int, set: MarkSet) {
        marks[displayId] = set
    }

    fun marks(displayId: Int): MarkSet? = marks[displayId]

    fun mark(displayId: Int, index: Int): Marker? = marks[displayId]?.markers?.firstOrNull { it.index == index }

    val size: Int get() = synchronized(prints) { prints.size }
}

/** Per-session state of the tool layer: one instance per omp process. */
class SessionToolState(val sessionId: String?, val createdAtMs: Long = System.currentTimeMillis()) {
    val memory = ElementMemory()

    /** Signature of the last rendered observation per display, so an unchanged screen can be reported cheaply. */
    val lastObservation = ConcurrentHashMap<Int, Int>()

    /** Display the agent last acted on, used when a call names none. */
    @Volatile
    var activeDisplay: Int = 0
}

internal fun Point.clampTo(bounds: Rect): Point =
    Point(x.coerceIn(bounds.left, maxOf(bounds.left, bounds.right - 1)), y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - 1)))
