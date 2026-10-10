package app.umbra.core.model.observation

import app.umbra.core.model.Rect
import app.umbra.core.model.action.ElementQuery
import kotlinx.serialization.Serializable

enum class NodeFlag {
    CLICKABLE, LONG_CLICKABLE, FOCUSABLE, FOCUSED, EDITABLE, SCROLLABLE,
    CHECKABLE, CHECKED, SELECTED, ENABLED, VISIBLE, PASSWORD, HEADING,
}

/** Semantic actions a node advertises (accessibility actions or AT-SPI actions, normalised). */
enum class NodeAction {
    CLICK, LONG_CLICK, FOCUS, CLEAR_FOCUS, SELECT, SET_TEXT, SET_SELECTION, COPY, CUT, PASTE,
    SCROLL_FORWARD, SCROLL_BACKWARD, SCROLL_UP, SCROLL_DOWN, SCROLL_LEFT, SCROLL_RIGHT,
    EXPAND, COLLAPSE, DISMISS, SHOW_ON_SCREEN,
}

/**
 * Normalised UI element, shared by Android accessibility/UiAutomation and Linux AT-SPI.
 * [id] is stable only within the snapshot it came from: `<windowId>:<child-index path>`.
 */
@Serializable
data class UiNode(
    val id: String,
    val depth: Int,
    val parentId: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val stateDescription: String? = null,
    val bounds: Rect = Rect.EMPTY,
    val flags: Set<NodeFlag> = emptySet(),
    val actions: Set<NodeAction> = emptySet(),
    val childCount: Int = 0,
) {
    fun has(flag: NodeFlag): Boolean = flag in flags

    val isClickable: Boolean get() = has(NodeFlag.CLICKABLE) || NodeAction.CLICK in actions
    val isEditable: Boolean get() = has(NodeFlag.EDITABLE) || NodeAction.SET_TEXT in actions
    val isScrollable: Boolean get() = has(NodeFlag.SCROLLABLE)
    val isPassword: Boolean get() = has(NodeFlag.PASSWORD)
    val isEnabled: Boolean get() = has(NodeFlag.ENABLED)
    val isVisible: Boolean get() = has(NodeFlag.VISIBLE) && !bounds.isEmpty

    /** Best human label: text, then content description, then hint, then state description. */
    val label: String?
        get() = sequenceOf(text, contentDescription, hint, stateDescription)
            .firstOrNull { !it.isNullOrBlank() }

    val isInteractive: Boolean
        get() = isClickable || has(NodeFlag.LONG_CLICKABLE) || isEditable || isScrollable ||
            has(NodeFlag.CHECKABLE)

    /** Short class name without package, e.g. `Button`. */
    val shortClass: String get() = className?.substringAfterLast('.') ?: "View"
}

/** One window's tree, in pre-order with depth annotations. */
@Serializable
data class UiTree(
    val displayId: Int,
    val windowId: Int,
    val packageName: String? = null,
    val title: String? = null,
    val layer: Int = 0,
    val isActive: Boolean = false,
    val isFocused: Boolean = false,
    val nodes: List<UiNode> = emptyList(),
    val capturedAtMs: Long = 0,
    val truncated: Boolean = false,
    /** accessibility | ui_automation | uiautomator_dump | atspi */
    val source: String = "accessibility",
) {
    private val byId: Map<String, UiNode> by lazy(LazyThreadSafetyMode.NONE) { nodes.associateBy { it.id } }

    fun node(id: String): UiNode? = byId[id]

    fun interactive(): List<UiNode> = nodes.filter { it.isInteractive && it.isVisible }

    /** All visible, non-blank labels in reading order. */
    fun visibleTexts(): List<String> =
        nodes.asSequence()
            .filter { it.isVisible }
            .sortedWith(ElementMatcher.readingOrder)
            .mapNotNull { it.text?.takeIf(String::isNotBlank) ?: it.contentDescription?.takeIf(String::isNotBlank) }
            .toList()

    fun containsText(text: String, exact: Boolean = false, ignoreCase: Boolean = true): Boolean =
        nodes.any { n ->
            n.isVisible && listOfNotNull(n.text, n.contentDescription).any { candidate ->
                if (exact) candidate.trim().equals(text.trim(), ignoreCase) else candidate.contains(text, ignoreCase)
            }
        }

    fun find(query: ElementQuery): List<UiNode> =
        nodes.filter { ElementMatcher.matches(it, query) }.sortedWith(ElementMatcher.readingOrder)
}

/** All windows of one or more displays at one instant. */
@Serializable
data class UiSnapshot(
    val windows: List<UiTree> = emptyList(),
    val capturedAtMs: Long = 0,
) {
    fun window(id: Int): UiTree? = windows.firstOrNull { it.windowId == id }

    fun activeWindow(displayId: Int? = null): UiTree? =
        windows.filter { displayId == null || it.displayId == displayId }
            .let { list -> list.firstOrNull { it.isActive } ?: list.firstOrNull { it.isFocused } ?: list.maxByOrNull { it.layer } }

    /** Resolve a node by its snapshot id (`<windowId>:<path>`). */
    fun node(id: String): UiNode? {
        val windowId = id.substringBefore(':', "").toIntOrNull() ?: return null
        return window(windowId)?.node(id)
    }

    fun containsText(text: String, exact: Boolean = false, ignoreCase: Boolean = true, displayId: Int? = null): Boolean =
        windows.any { (displayId == null || it.displayId == displayId) && it.containsText(text, exact, ignoreCase) }
}

object ElementMatcher {
    /** Top-to-bottom, then left-to-right; invisible nodes last. */
    val readingOrder: Comparator<UiNode> =
        compareBy<UiNode> { if (it.isVisible) 0 else 1 }
            .thenBy { it.bounds.top }
            .thenBy { it.bounds.left }
            .thenBy { it.depth }

    fun matches(node: UiNode, q: ElementQuery): Boolean {
        if (q.isEmpty) return false
        q.text?.let { wanted ->
            val ok = listOfNotNull(node.text, node.contentDescription).any { it.trim().equals(wanted.trim(), ignoreCase = true) }
            if (!ok) return false
        }
        q.textContains?.let { wanted ->
            val ok = listOfNotNull(node.text, node.contentDescription, node.hint).any { it.contains(wanted, ignoreCase = true) }
            if (!ok) return false
        }
        q.contentDescription?.let { wanted ->
            if (node.contentDescription?.trim()?.equals(wanted.trim(), ignoreCase = true) != true) return false
        }
        q.resourceId?.let { wanted -> if (!resourceIdMatches(node.resourceId, wanted)) return false }
        q.className?.let { wanted ->
            val cn = node.className ?: return false
            if (!(cn == wanted || cn.endsWith(".$wanted"))) return false
        }
        q.packageName?.let { if (node.packageName != it) return false }
        q.clickable?.let { if (node.isClickable != it) return false }
        q.editable?.let { if (node.isEditable != it) return false }
        q.scrollable?.let { if (node.isScrollable != it) return false }
        q.checked?.let { if (node.has(NodeFlag.CHECKED) != it) return false }
        q.enabled?.let { if (node.isEnabled != it) return false }
        return true
    }

    private fun resourceIdMatches(actual: String?, wanted: String): Boolean {
        if (actual == null) return false
        if (actual == wanted) return true
        if (wanted.contains('/') || wanted.contains(':')) return actual.endsWith(wanted)
        return actual.endsWith("/$wanted") || actual.endsWith(":$wanted")
    }
}

/** Outcome of resolving a query or id against a tree. */
sealed interface Resolution {
    data class Unique(val node: UiNode) : Resolution
    data class Ambiguous(val candidates: List<UiNode>) : Resolution
    data object NotFound : Resolution
}

/**
 * Picks a node for a semantic query. When several nodes match and one is clearly the better
 * target (exact label, interactive, visible, enabled), it wins; otherwise the caller gets the
 * candidates so Laya or OMP can choose instead of the executor guessing.
 */
object ElementResolver {
    fun resolve(tree: UiTree, query: ElementQuery, prefer: NodeFlag? = null): Resolution =
        resolve(tree.find(query), query, prefer)

    fun resolve(snapshot: UiSnapshot, query: ElementQuery, prefer: NodeFlag? = null, displayId: Int? = null): Resolution {
        val all = snapshot.windows
            .filter { displayId == null || it.displayId == displayId }
            .flatMap { it.find(query) }
        return resolve(all, query, prefer)
    }

    fun resolve(matches: List<UiNode>, query: ElementQuery, prefer: NodeFlag? = null): Resolution {
        if (matches.isEmpty()) return Resolution.NotFound
        if (query.index > 0) {
            return matches.getOrNull(query.index)?.let(Resolution::Unique) ?: Resolution.NotFound
        }
        if (matches.size == 1) return Resolution.Unique(matches.single())
        val ranked = matches.map { it to score(it, query, prefer) }.sortedByDescending { it.second }
        val best = ranked[0]
        val second = ranked[1]
        return if (best.second - second.second >= CLEAR_WINNER_MARGIN) {
            Resolution.Unique(best.first)
        } else {
            Resolution.Ambiguous(ranked.map { it.first })
        }
    }

    internal fun score(node: UiNode, query: ElementQuery, prefer: NodeFlag?): Int {
        var s = 0
        val label = node.label
        if (query.text != null && label != null && label.trim().equals(query.text.trim(), ignoreCase = true)) s += 40
        if (node.isVisible) s += 30
        if (node.isEnabled) s += 20
        if (node.isInteractive) s += 20
        if (prefer != null && node.has(prefer)) s += 40
        if (node.isPassword && prefer != NodeFlag.PASSWORD) s -= 5
        return s
    }

    private const val CLEAR_WINNER_MARGIN = 35
}
