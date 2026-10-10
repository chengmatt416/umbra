package app.umbra.core.model.observation

import app.umbra.core.model.Rect

/** Builds nodes tersely for tests; visible and enabled by default. */
fun testNode(
    id: String,
    text: String? = null,
    className: String = "android.widget.TextView",
    resourceId: String? = null,
    contentDescription: String? = null,
    hint: String? = null,
    bounds: Rect = Rect(0, 0, 100, 40),
    depth: Int = 1,
    flags: Set<NodeFlag> = emptySet(),
): UiNode = UiNode(
    id = id,
    depth = depth,
    className = className,
    packageName = "com.example.app",
    resourceId = resourceId,
    text = text,
    contentDescription = contentDescription,
    hint = hint,
    bounds = bounds,
    flags = setOf(NodeFlag.VISIBLE, NodeFlag.ENABLED) + flags,
)

fun testTree(vararg nodes: UiNode, windowId: Int = 1, displayId: Int = 0): UiTree = UiTree(
    displayId = displayId,
    windowId = windowId,
    packageName = "com.example.app",
    title = "Login",
    isActive = true,
    isFocused = true,
    nodes = nodes.toList(),
)
