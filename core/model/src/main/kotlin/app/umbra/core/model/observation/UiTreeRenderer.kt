package app.umbra.core.model.observation

/**
 * Compact text rendering of a UI tree for the agent. The id in brackets is what the agent passes
 * back as an element id. Secrets are never rendered: password fields show `[hidden]`.
 */
object UiTreeRenderer {
    data class Options(
        val maxNodes: Int = 250,
        val onlyInteractive: Boolean = false,
        val includeBounds: Boolean = true,
        val maxLabelChars: Int = 80,
    )

    fun render(tree: UiTree, options: Options = Options()): String {
        val header = buildString {
            append("window ").append(tree.windowId)
            tree.packageName?.let { append(' ').append(it) }
            tree.title?.takeIf(String::isNotBlank)?.let { append(" \"").append(clip(it, options.maxLabelChars)).append('"') }
            append(" [display ").append(tree.displayId)
            if (tree.isActive) append(", active")
            if (tree.isFocused) append(", focused")
            append(", source ").append(tree.source).append(']')
        }
        val candidates = tree.nodes.asSequence()
            .filter { it.isVisible }
            .filter { !options.onlyInteractive || it.isInteractive }
            .filter { it.isInteractive || !it.label.isNullOrBlank() || it.childCount == 0 && !options.onlyInteractive && it.className?.contains("Image") == true }
        val shown = candidates.take(options.maxNodes).toList()
        val sb = StringBuilder(header)
        for (n in shown) {
            sb.append('\n').append("  ".repeat(minOf(n.depth, 6)))
            sb.append('[').append(n.id).append("] ").append(n.shortClass)
            n.label?.let { l ->
                sb.append(' ').append('"').append(if (n.isPassword) "[hidden]" else clip(l, options.maxLabelChars)).append('"')
            }
            n.resourceId?.let { sb.append(" id=").append(it.substringAfterLast('/')) }
            val tags = tags(n)
            if (tags.isNotEmpty()) sb.append(" {").append(tags.joinToString(",")).append('}')
            if (options.includeBounds) {
                val b = n.bounds
                sb.append(" (").append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom).append(')')
            }
        }
        val omitted = candidates.drop(options.maxNodes).count()
        if (omitted > 0 || tree.truncated) {
            sb.append("\n  … ").append(if (omitted > 0) "$omitted more nodes omitted" else "tree truncated by source")
        }
        return sb.toString()
    }

    private fun tags(n: UiNode): List<String> = buildList {
        if (n.isClickable) add("click")
        if (n.has(NodeFlag.LONG_CLICKABLE)) add("long")
        if (n.isEditable) add("edit")
        if (n.isScrollable) add("scroll")
        if (n.has(NodeFlag.CHECKABLE)) add(if (n.has(NodeFlag.CHECKED)) "checked" else "unchecked")
        if (n.has(NodeFlag.SELECTED)) add("selected")
        if (n.has(NodeFlag.FOCUSED)) add("focused")
        if (n.isPassword) add("password")
        if (!n.isEnabled) add("disabled")
    }

    private fun clip(s: String, max: Int): String {
        val oneLine = s.replace('\n', ' ').trim()
        return if (oneLine.length <= max) oneLine else oneLine.take(max - 1) + "…"
    }
}
