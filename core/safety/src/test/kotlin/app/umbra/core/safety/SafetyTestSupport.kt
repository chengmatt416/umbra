package app.umbra.core.safety

import app.umbra.core.model.Rect
import app.umbra.core.model.action.ActionRequest
import app.umbra.core.model.action.Operation
import app.umbra.core.model.action.Privilege
import app.umbra.core.model.observation.NodeFlag
import app.umbra.core.model.observation.UiNode

fun button(label: String, vararg extra: NodeFlag) = UiNode(
    id = "1:0",
    depth = 1,
    className = "android.widget.Button",
    text = label,
    bounds = Rect(0, 0, 200, 80),
    flags = setOf(NodeFlag.CLICKABLE, NodeFlag.VISIBLE, NodeFlag.ENABLED) + extra,
)

fun passwordField() = UiNode(
    id = "1:1",
    depth = 1,
    className = "android.widget.EditText",
    hint = "Password",
    bounds = Rect(0, 100, 200, 180),
    flags = setOf(NodeFlag.EDITABLE, NodeFlag.PASSWORD, NodeFlag.VISIBLE, NodeFlag.ENABLED),
)

fun request(
    op: Operation,
    id: String = "a1",
    session: String? = "s1",
    requires: Privilege = Privilege.NONE,
) = ActionRequest(id = id, operation = op, sessionId = session, requires = requires)
