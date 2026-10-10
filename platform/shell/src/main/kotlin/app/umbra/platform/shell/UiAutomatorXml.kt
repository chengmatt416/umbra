package app.umbra.platform.shell

import app.umbra.core.model.Rect
import app.umbra.core.model.observation.NodeAction
import app.umbra.core.model.observation.NodeFlag
import app.umbra.core.model.observation.UiNode
import app.umbra.core.model.observation.UiSnapshot
import app.umbra.core.model.observation.UiTree
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * Reads the XML `uiautomator dump --windows` writes: `<displays><display id><window ...><node .../>`.
 * The plain form (`<hierarchy><node/></hierarchy>`, one window of the default display) is read too.
 * Node ids are `<windowId>:<child index path>`, the same scheme the accessibility backend uses.
 */
object UiAutomatorXml {
    fun parse(xml: String, capturedAtMs: Long, maxNodesPerWindow: Int = Int.MAX_VALUE): UiSnapshot {
        val handler = Handler(capturedAtMs, maxNodesPerWindow)
        val factory = SAXParserFactory.newInstance()
        // The dump is machine generated; there is never a DTD to resolve.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
        return UiSnapshot(handler.trees, capturedAtMs)
    }

    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    internal fun bounds(text: String?): Rect {
        val m = text?.let(BOUNDS::find) ?: return Rect.EMPTY
        val (l, t, r, b) = m.destructured
        return Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    private class Builder(
        val node: UiNode,
        /** Position in the window's node list, or -1 when the node was cut off by the node limit. */
        val listIndex: Int,
        var children: Int = 0,
    )

    private class Handler(val capturedAtMs: Long, val maxNodes: Int) : DefaultHandler() {
        val trees = ArrayList<UiTree>()
        private var displayId = 0
        private var window: WindowContext? = null

        private class WindowContext(
            val id: Int, val title: String?, val layer: Int, val active: Boolean, val focused: Boolean,
            val nodes: ArrayList<UiNode> = ArrayList(), var truncated: Boolean = false,
        ) {
            /** Open ancestors from the root: the builders being filled, innermost last. */
            val stack = ArrayList<Pair<String, Builder>>()
            var package_: String? = null
        }

        override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
            when (qName) {
                "display" -> displayId = a.getValue("id")?.toIntOrNull() ?: 0
                "hierarchy" -> a.getValue("display-id")?.toIntOrNull()?.let { displayId = it }
                "window" -> window = WindowContext(
                    id = a.getValue("id")?.toIntOrNull() ?: 0,
                    title = a.getValue("title")?.takeIf { it.isNotEmpty() },
                    layer = a.getValue("layer")?.toIntOrNull() ?: 0,
                    active = a.getValue("active") == "true",
                    focused = a.getValue("focused") == "true",
                )
                "node" -> {
                    val w = window ?: WindowContext(0, null, 0, active = true, focused = true).also { window = it }
                    val parent = w.stack.lastOrNull()?.second
                    val index = parent?.let { it.children++ } ?: 0
                    val path = if (parent == null) "0" else "${w.stack.last().first}.$index"
                    val id = "${w.id}:$path"
                    if (w.package_ == null) w.package_ = a.getValue("package")?.takeIf { it.isNotEmpty() }
                    val node = toNode(id, w.stack.size, parent?.node?.id, a)
                    val listIndex = if (w.nodes.size < maxNodes) { w.nodes += node; w.nodes.lastIndex } else { w.truncated = true; -1 }
                    w.stack += path to Builder(node, listIndex)
                }
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "node" -> window?.let { w ->
                    val (_, builder) = w.stack.removeAt(w.stack.lastIndex)
                    // The node was added before its children were seen; now its child count is known.
                    if (builder.listIndex >= 0) w.nodes[builder.listIndex] = builder.node.copy(childCount = builder.children)
                }
                "window" -> flush()
                "hierarchy" -> flush()
            }
        }

        private fun flush() {
            val w = window ?: return
            window = null
            trees += UiTree(
                displayId = displayId, windowId = w.id, packageName = w.package_, title = w.title, layer = w.layer,
                isActive = w.active, isFocused = w.focused, nodes = w.nodes, capturedAtMs = capturedAtMs,
                truncated = w.truncated, source = "uiautomator_dump",
            )
        }

        private fun toNode(id: String, depth: Int, parentId: String?, a: Attributes): UiNode {
            fun b(name: String) = a.getValue(name) == "true"
            val cls = a.getValue("class")
            val editable = cls != null && (cls.endsWith("EditText") || cls.endsWith("AutoCompleteTextView"))
            val flags = buildSet {
                add(NodeFlag.VISIBLE) // uiautomator only dumps nodes that are visible to the user
                if (b("enabled")) add(NodeFlag.ENABLED)
                if (b("clickable")) add(NodeFlag.CLICKABLE)
                if (b("long-clickable")) add(NodeFlag.LONG_CLICKABLE)
                if (b("focusable") || editable) add(NodeFlag.FOCUSABLE)
                if (b("focused")) add(NodeFlag.FOCUSED)
                if (b("scrollable")) add(NodeFlag.SCROLLABLE)
                if (b("checkable")) add(NodeFlag.CHECKABLE)
                if (b("checked")) add(NodeFlag.CHECKED)
                if (b("selected")) add(NodeFlag.SELECTED)
                if (b("password")) add(NodeFlag.PASSWORD)
                if (editable) add(NodeFlag.EDITABLE)
            }
            val actions = buildSet {
                if (NodeFlag.CLICKABLE in flags) add(NodeAction.CLICK)
                if (NodeFlag.LONG_CLICKABLE in flags) add(NodeAction.LONG_CLICK)
                if (NodeFlag.SCROLLABLE in flags) { add(NodeAction.SCROLL_FORWARD); add(NodeAction.SCROLL_BACKWARD) }
                if (editable) add(NodeAction.SET_TEXT)
            }
            return UiNode(
                id = id, depth = depth, parentId = parentId, className = cls?.takeIf { it.isNotEmpty() },
                packageName = a.getValue("package")?.takeIf { it.isNotEmpty() },
                resourceId = a.getValue("resource-id")?.takeIf { it.isNotEmpty() },
                text = a.getValue("text")?.takeIf { it.isNotEmpty() },
                contentDescription = a.getValue("content-desc")?.takeIf { it.isNotEmpty() },
                hint = a.getValue("hint")?.takeIf { it.isNotEmpty() },
                bounds = bounds(a.getValue("bounds")), flags = flags, actions = actions,
            )
        }
    }
}
