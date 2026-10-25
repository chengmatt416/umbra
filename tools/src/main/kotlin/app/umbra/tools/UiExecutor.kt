package app.umbra.tools

import app.umbra.core.model.Point
import app.umbra.core.model.Rect
import app.umbra.core.model.action.ActionStatus
import app.umbra.core.model.action.ClipboardAction
import app.umbra.core.model.action.Direction
import app.umbra.core.model.action.Endpoint
import app.umbra.core.model.action.Environment
import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.action.GlobalAction
import app.umbra.core.model.action.NotificationAction
import app.umbra.core.model.action.Operation
import app.umbra.core.model.action.Target
import app.umbra.core.model.observation.NodeAction
import app.umbra.core.model.observation.NodeFlag
import app.umbra.core.model.observation.UiNode
import app.umbra.core.model.observation.UiSnapshot
import app.umbra.core.model.observation.UiTree
import app.umbra.platform.api.AndroidKeys
import app.umbra.platform.api.AppLaunch
import app.umbra.platform.api.Backend
import app.umbra.platform.api.NodeActionArgs
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellInput
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.SnapshotOptions
import app.umbra.platform.api.UiBackend
import app.umbra.platform.api.UiFeature
import app.umbra.platform.api.exec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Filters to the backends that report themselves usable, without hiding cancellation. */
internal suspend fun <T : Backend> List<T>.available(): List<T> = filter {
    try {
        it.isAvailable()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}

/**
 * Carries out interaction operations with the semantic-first strategy of spec §6/§7: act on the node
 * through the accessibility API, then inject a gesture at its position, then fall back to shell
 * input. Every try is recorded, so the agent (and the log) can see which path worked.
 */
internal class UiExecutor(private val env: PipelineEnv) {
    private val backends get() = env.backends

    suspend fun execute(c: OpContext): OpOutcome = when (val op = c.request.operation) {
        is Operation.Tap -> tap(c, taps = 1)
        is Operation.DoubleTap -> tap(c, taps = 2)
        is Operation.RightClick -> rightClick(c)
        is Operation.LongPress -> longPress(c, op)
        is Operation.Swipe -> swipe(c, op)
        is Operation.Scroll -> scroll(c, op)
        is Operation.Drag -> drag(c, op)
        is Operation.TypeText -> typeText(c, op)
        is Operation.SelectText -> selectText(c, op)
        is Operation.Clipboard -> clipboard(c, op)
        is Operation.PressKey -> pressKey(c, op)
        is Operation.Global -> global(c, op)
        is Operation.LaunchApp -> launchApp(c, op)
        is Operation.StopApp -> stopApp(c, op)
        is Operation.SwitchApp -> switchApp(c, op)
        is Operation.Notifications -> notifications(c, op)
        else -> OpOutcome.unsupported("${op.name} is not a UI operation")
    }

    // ---- geometry ----

    private fun viewport(c: OpContext, tree: UiTree? = null): Rect? {
        val t = tree ?: (c.resolved as? Resolved.Node)?.tree
            ?: c.snapshot?.activeWindow(c.displayId)
        t?.nodes?.firstOrNull { it.depth == 0 && !it.bounds.isEmpty }?.let { return it.bounds }
        return t?.nodes?.filter { it.isVisible }?.map { it.bounds }?.reduceOrNull { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
    }

    private fun visibleCenter(node: UiNode, tree: UiTree): Point {
        val vp = tree.nodes.firstOrNull { it.depth == 0 && !it.bounds.isEmpty }?.bounds
        return (vp?.let { node.bounds.intersection(it) } ?: node.bounds).center
    }

    private fun pointOf(c: OpContext): Point? = when (val r = c.resolved) {
        is Resolved.At -> r.point
        is Resolved.Node -> visibleCenter(r.node, r.tree)
        else -> null
    }

    private fun nodeOf(c: OpContext): UiNode? = (c.resolved as? Resolved.Node)?.node

    private fun label(c: OpContext): String = when (val r = c.resolved) {
        is Resolved.Node -> "${env.targeting.describe(r.node)} [${r.node.id}]"
        is Resolved.At -> "(${r.point.x}, ${r.point.y})"
        else -> "the screen"
    }

    private fun clickableAncestorOrSelf(node: UiNode, tree: UiTree): UiNode? {
        var n: UiNode? = node
        while (n != null) {
            if (n.isClickable && n.isEnabled) return n
            n = n.parentId?.let(tree::node)
        }
        return null
    }

    private suspend fun uis(c: OpContext): List<UiBackend> = backends.ui(c.environment).available()

    /** Shells that may inject input, least privileged first, only when the policy allows falling back to them. */
    private suspend fun inputShells(c: OpContext): List<ShellBackend> {
        if (c.environment != Environment.ANDROID) return emptyList()
        if (app.umbra.core.model.action.Fallback.SHELL !in c.policy.fallbacks) return emptyList()
        val shells = listOfNotNull(backends.shell(ShellIdentity.SHELL), backends.shell(ShellIdentity.ROOT).takeIf {
            app.umbra.core.model.action.Fallback.PRIVILEGED in c.policy.fallbacks
        })
        return shells.available()
    }

    private fun shellStrategy(shell: ShellBackend, name: String, command: String) = Strategy(name, shell.name) {
        val r = shell.exec(command, timeoutMs = 5_000)
        if (r.ok) StrategyOutcome.Done() else StrategyOutcome.Refused("'${command.take(60)}' exited ${r.exitCode}: ${r.combined.take(120)}")
    }

    private fun finish(c: OpContext, run: StrategyRun, done: String): OpOutcome =
        if (run.succeeded) {
            c.displayId?.let { c.state.activeDisplay = it }
            OpOutcome.ok(done, backend = run.backend)
        } else {
            val e = run.error!!
            OpOutcome.fail(e.code, e.message, e.retryable, status = if (e.code == ErrorCode.UNSUPPORTED) ActionStatus.UNSUPPORTED else ActionStatus.FAILED)
        }

    private fun requirePoint(c: OpContext, what: String): OpOutcome? {
        val node = nodeOf(c)
        if (node != null) {
            if (!node.isVisible) return OpOutcome.fail(ErrorCode.NOT_ACTIONABLE, "${label(c)} is not visible on screen; scroll to it first.", retryable = true)
            return null
        }
        if (pointOf(c) == null) return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "$what needs an element, a query, or x/y coordinates.")
        return null
    }

    // ---- taps ----

    private suspend fun tap(c: OpContext, taps: Int): OpOutcome {
        requirePoint(c, "tap")?.let { return it }
        val node = nodeOf(c)
        val point = pointOf(c)!!
        if (node != null && !node.isEnabled) {
            return OpOutcome.fail(ErrorCode.NOT_ACTIONABLE, "${label(c)} is disabled, so tapping it does nothing. Fill in the required fields or wait for it to become enabled.", retryable = true)
        }
        val tree = (c.resolved as? Resolved.Node)?.tree
        val strategies = buildList {
            for (ui in uis(c)) {
                if (taps == 1 && node != null && tree != null && ui === c.primaryUi && UiFeature.NODE_ACTIONS in ui.features) {
                    val target = clickableAncestorOrSelf(node, tree)
                    if (target != null) {
                        add(Strategy("node_click", ui.name) {
                            if (ui.performAction(target, NodeAction.CLICK)) StrategyOutcome.Done(if (target.id != node.id) "clicked ancestor ${target.id}" else null)
                            else StrategyOutcome.Refused("the node refused CLICK")
                        })
                    }
                }
                if (UiFeature.GESTURES in ui.features) {
                    add(Strategy(if (taps == 2) "gesture_double_tap" else "gesture_tap", ui.name) {
                        repeat(taps) { i -> if (i > 0) delay(60); c.checkDispatch(); ui.tap(c.displayId, point) }
                        StrategyOutcome.Done()
                    })
                }
            }
            for (shell in inputShells(c)) {
                add(shellStrategy(shell, "shell_input_tap", (1..taps).joinToString(" && ") { ShellInput.tap(c.displayId, point) }))
            }
        }
        return finish(c, runStrategies(env, c, strategies), (if (taps == 2) "Double-tapped " else "Tapped ") + label(c))
    }

    private suspend fun rightClick(c: OpContext): OpOutcome {
        val gui = backends.linuxGui ?: return OpOutcome.unsupported("Right click exists only on a Linux desktop; on Android use long_press.")
        requirePoint(c, "right_click")?.let { return it }
        val point = pointOf(c)!!
        val run = runStrategies(env, c, listOf(Strategy("right_click", gui.name) { gui.rightClick(c.displayId, point); StrategyOutcome.Done() }))
        return finish(c, run, "Right-clicked ${label(c)}")
    }

    private suspend fun longPress(c: OpContext, op: Operation.LongPress): OpOutcome {
        requirePoint(c, "long_press")?.let { return it }
        val node = nodeOf(c)
        val point = pointOf(c)!!
        val strategies = buildList {
            for (ui in uis(c)) {
                if (node != null && ui === c.primaryUi && UiFeature.NODE_ACTIONS in ui.features && (NodeAction.LONG_CLICK in node.actions || node.has(NodeFlag.LONG_CLICKABLE))) {
                    add(Strategy("node_long_click", ui.name) {
                        if (ui.performAction(node, NodeAction.LONG_CLICK)) StrategyOutcome.Done() else StrategyOutcome.Refused("the node refused LONG_CLICK")
                    })
                }
                if (UiFeature.GESTURES in ui.features) {
                    add(Strategy("gesture_long_press", ui.name) { ui.longPress(c.displayId, point, op.durationMs); StrategyOutcome.Done() })
                }
            }
            for (shell in inputShells(c)) add(shellStrategy(shell, "shell_input_long_press", ShellInput.longPress(c.displayId, point, op.durationMs)))
        }
        return finish(c, runStrategies(env, c, strategies), "Long-pressed ${label(c)}")
    }

    // ---- swipes, scrolling, dragging ----

    private suspend fun endpointPoint(c: OpContext, endpoint: Endpoint): Resolved {
        if (endpoint.point != null) return Resolved.At(endpoint.point!!)
        val element = endpoint.element ?: return Resolved.Unspecified
        return env.targeting.resolve(Target(c.environment, c.displayId, element = element), c.request.operation, c.snapshot, c.state, c.displayId, c.request.rationale)
    }

    private fun resolvedPoint(r: Resolved): Point? = when (r) {
        is Resolved.At -> r.point
        is Resolved.Node -> visibleCenter(r.node, r.tree)
        else -> null
    }

    private fun vector(direction: Direction, bounds: Rect, fraction: Float): Pair<Point, Point> {
        val f = fraction.coerceIn(0.1f, 0.95f)
        val cx = bounds.centerX
        val cy = bounds.centerY
        val dx = (bounds.width * f / 2).roundToInt()
        val dy = (bounds.height * f / 2).roundToInt()
        return when (direction) {
            Direction.UP -> Point(cx, cy + dy) to Point(cx, cy - dy)
            Direction.DOWN -> Point(cx, cy - dy) to Point(cx, cy + dy)
            Direction.LEFT -> Point(cx + dx, cy) to Point(cx - dx, cy)
            Direction.RIGHT -> Point(cx - dx, cy) to Point(cx + dx, cy)
        }
    }

    /** The area gestures act in: the target element if there is one, else the whole screen. */
    private fun area(c: OpContext): Rect? {
        nodeOf(c)?.let { n -> return viewport(c)?.let { n.bounds.intersection(it) } ?: n.bounds }
        return viewport(c)
    }

    private suspend fun swipe(c: OpContext, op: Operation.Swipe): OpOutcome {
        val (from, to) = if (op.to != null) {
            val start = pointOf(c) ?: viewport(c)?.center ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "swipe needs a start element or point when the screen size is unknown.")
            val end = resolvedPoint(endpointPoint(c, op.to!!)) ?: return OpOutcome.fail(ErrorCode.NOT_FOUND, "Could not find the swipe destination.", retryable = true)
            start to end
        } else {
            val dir = op.direction ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "swipe needs a direction or a destination.")
            val bounds = area(c) ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "swipe needs coordinates because the screen size is unknown.")
            vector(dir, bounds, op.distanceFraction)
        }
        val strategies = buildList {
            for (ui in uis(c)) if (UiFeature.GESTURES in ui.features) add(Strategy("gesture_swipe", ui.name) { ui.swipe(c.displayId, from, to, op.durationMs); StrategyOutcome.Done() })
            for (shell in inputShells(c)) add(shellStrategy(shell, "shell_input_swipe", ShellInput.swipe(c.displayId, from, to, op.durationMs)))
        }
        return finish(c, runStrategies(env, c, strategies), "Swiped from (${from.x}, ${from.y}) to (${to.x}, ${to.y})")
    }

    private suspend fun drag(c: OpContext, op: Operation.Drag): OpOutcome {
        requirePoint(c, "drag")?.let { return it }
        val from = pointOf(c)!!
        val to = resolvedPoint(endpointPoint(c, op.to)) ?: return OpOutcome.fail(ErrorCode.NOT_FOUND, "Could not find the drag destination.", retryable = true)
        val strategies = buildList {
            for (ui in uis(c)) if (UiFeature.GESTURES in ui.features) add(Strategy("gesture_drag", ui.name) { ui.drag(c.displayId, from, to, op.holdMs, op.durationMs); StrategyOutcome.Done() })
            for (shell in inputShells(c)) add(shellStrategy(shell, "shell_input_drag", ShellInput.drag(c.displayId, from, to, op.durationMs)))
        }
        return finish(c, runStrategies(env, c, strategies), "Dragged ${label(c)} to (${to.x}, ${to.y})")
    }

    private fun scrollContainer(c: OpContext): UiNode? {
        nodeOf(c)?.let { n ->
            if (n.isScrollable) return n
            val tree = (c.resolved as? Resolved.Node)?.tree
            // The agent named something inside the list: scroll the list.
            var p = n.parentId?.let { tree?.node(it) }
            while (p != null) { if (p.isScrollable) return p; p = p.parentId?.let { tree?.node(it) } }
        }
        val tree = c.snapshot?.activeWindow(c.displayId) ?: return null
        return tree.nodes.filter { it.isScrollable && it.isVisible }.maxByOrNull { it.bounds.area }
    }

    private suspend fun scroll(c: OpContext, op: Operation.Scroll): OpOutcome {
        var container = scrollContainer(c)
        var scrolls = 0
        var tree: UiTree? = c.snapshot?.activeWindow(c.displayId)
        val wanted = op.untilVisible

        fun found(t: UiTree?): UiNode? = wanted?.let { q -> t?.find(q)?.firstOrNull { it.isVisible } }
        found(tree)?.let { return OpOutcome.ok("${env.targeting.describe(it)} [${it.id}] is already visible", backend = c.primaryUi?.name) }

        var lastSig = tree?.let { uiSignature(UiSnapshot(listOf(it)), c.displayId).first }
        val limit = if (wanted == null) 1 else op.maxScrolls.coerceIn(1, 40)
        var backend: String? = null
        while (scrolls < limit) {
            val area = container?.bounds?.let { b -> viewport(c, tree)?.let { b.intersection(it) } ?: b } ?: viewport(c, tree)
            val strategies = buildList {
                val node = container
                val primary = c.primaryUi
                if (node != null && primary != null && primary in uis(c) && UiFeature.NODE_ACTIONS in primary.features) {
                    val action = scrollAction(node, op.direction)
                    if (action != null) add(Strategy("node_scroll", primary.name) {
                        if (primary.performAction(node, action)) StrategyOutcome.Done() else StrategyOutcome.Refused("the list cannot scroll ${op.direction.name.lowercase()} any further")
                    })
                }
                val gui = backends.linuxGui
                if (c.environment == Environment.LINUX_GUI && gui != null && area != null) {
                    // A desktop scrolls with the mouse wheel, not with a swipe.
                    add(Strategy("mouse_wheel", gui.name) {
                        gui.wheel(c.displayId, area.center, op.direction, max(1, (op.amount * 5).roundToInt()))
                        StrategyOutcome.Done()
                    })
                }
                if (area != null) {
                    // Content moves opposite to the finger: to reveal what is below, the finger goes up.
                    val (from, to) = vector(opposite(op.direction), area, op.amount)
                    for (ui in uis(c)) if (UiFeature.GESTURES in ui.features) add(Strategy("gesture_swipe", ui.name) { ui.swipe(c.displayId, from, to, 350); StrategyOutcome.Done() })
                    for (shell in inputShells(c)) add(shellStrategy(shell, "shell_input_swipe", ShellInput.swipe(c.displayId, from, to, 350)))
                }
            }
            if (strategies.isEmpty()) return OpOutcome.fail(ErrorCode.NOT_ACTIONABLE, "Nothing to scroll: no scrollable element was found and the screen size is unknown.")
            val run = runStrategies(env, c, strategies)
            if (!run.succeeded) {
                return if (scrolls > 0) OpOutcome.fail(ErrorCode.NOT_FOUND, "Scrolled $scrolls time(s) but ${wanted?.let { env.targeting.describeQuery(it) } ?: "the target"} did not appear, and the list can scroll no further.", retryable = false)
                else finish(c, run, "")
            }
            backend = run.backend
            scrolls++
            if (wanted == null) break
            delay(env.config.scrollSettleMs)
            val snap = fresh(c)
            tree = snap?.activeWindow(c.displayId)
            found(tree)?.let { hit ->
                (snap?.let { s -> s.windows.forEach { c.state.memory.remember(it) } })
                return OpOutcome.ok("Scrolled ${op.direction.name.lowercase()} $scrolls time(s); found ${env.targeting.describe(hit)} [${hit.id}]", backend = backend)
            }
            var sig = tree?.let { uiSignature(UiSnapshot(listOf(it)), c.displayId).first }
            if (sig != null && sig == lastSig) {
                // Nothing changed yet: the list may still be settling on a slow device. Look once more before calling it the end.
                delay(env.config.scrollSettleMs * 2)
                val again = fresh(c)
                tree = again?.activeWindow(c.displayId)
                found(tree)?.let { hit ->
                    again?.windows?.forEach { c.state.memory.remember(it) }
                    return OpOutcome.ok("Scrolled ${op.direction.name.lowercase()} $scrolls time(s); found ${env.targeting.describe(hit)} [${hit.id}]", backend = backend)
                }
                sig = tree?.let { uiSignature(UiSnapshot(listOf(it)), c.displayId).first }
                if (sig != null && sig == lastSig) {
                    return OpOutcome.fail(ErrorCode.NOT_FOUND, "Scrolled $scrolls time(s) and reached the end without finding ${env.targeting.describeQuery(wanted)}.", retryable = false)
                }
            }
            lastSig = sig
            container = tree?.let { t -> container?.let { old -> t.nodes.firstOrNull { it.isScrollable && it.id == old.id } } ?: t.nodes.filter { it.isScrollable && it.isVisible }.maxByOrNull { it.bounds.area } }
        }
        c.displayId?.let { c.state.activeDisplay = it }
        return OpOutcome.ok("Scrolled ${op.direction.name.lowercase()} ${if (wanted == null) "once" else "$scrolls time(s)"}", backend = backend)
    }

    private fun opposite(d: Direction) = when (d) {
        Direction.UP -> Direction.DOWN
        Direction.DOWN -> Direction.UP
        Direction.LEFT -> Direction.RIGHT
        Direction.RIGHT -> Direction.LEFT
    }

    private fun scrollAction(node: UiNode, d: Direction): NodeAction? {
        val directional = when (d) {
            Direction.DOWN -> NodeAction.SCROLL_DOWN
            Direction.UP -> NodeAction.SCROLL_UP
            Direction.LEFT -> NodeAction.SCROLL_LEFT
            Direction.RIGHT -> NodeAction.SCROLL_RIGHT
        }
        if (directional in node.actions) return directional
        val forward = d == Direction.DOWN || d == Direction.RIGHT
        return when {
            forward && NodeAction.SCROLL_FORWARD in node.actions -> NodeAction.SCROLL_FORWARD
            !forward && NodeAction.SCROLL_BACKWARD in node.actions -> NodeAction.SCROLL_BACKWARD
            node.isScrollable -> if (forward) NodeAction.SCROLL_FORWARD else NodeAction.SCROLL_BACKWARD
            else -> null
        }
    }

    private suspend fun fresh(c: OpContext): UiSnapshot? {
        val ui = c.primaryUi ?: return null
        return try {
            ui.snapshot(SnapshotOptions(displayId = c.displayId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // ---- text ----

    /** The field to type into: the named element, else the focused editable one, else the only editable one. */
    private fun editableTarget(c: OpContext): Pair<UiNode, UiTree>? {
        (c.resolved as? Resolved.Node)?.let { return it.node to it.tree }
        val tree = c.snapshot?.activeWindow(c.displayId) ?: return null
        val editable = tree.nodes.filter { it.isEditable && it.isVisible && it.isEnabled }
        val node = editable.firstOrNull { it.has(NodeFlag.FOCUSED) } ?: editable.singleOrNull() ?: return null
        return node to tree
    }

    private suspend fun typeText(c: OpContext, op: Operation.TypeText): OpOutcome {
        val target = editableTarget(c)
        val node = target?.first
        if (c.resolved is Resolved.Node && node != null && !node.isEditable) {
            return OpOutcome.fail(ErrorCode.NOT_ACTIONABLE, "${label(c)} is not a text field. Tap a text field first, or name one.", retryable = true)
        }
        val described = if (op.secret) "a secret value (${op.text.length} characters)" else "\"${op.text.take(60)}\"${if (op.text.length > 60) "…" else ""}"
        val where = node?.let { "${env.targeting.describe(it)} [${it.id}]" } ?: "the focused field"
        val ownBackend = c.primaryUi

        val strategies = buildList {
            if (node != null && ownBackend != null && UiFeature.NODE_ACTIONS in ownBackend.features) {
                val existing = node.text?.takeIf { it != node.hint && !node.isPassword }.orEmpty()
                val newText = if (op.clearFirst) op.text else existing + op.text
                add(Strategy("set_text", ownBackend.name) {
                    if (ownBackend.performAction(node, NodeAction.SET_TEXT, NodeActionArgs(text = newText))) StrategyOutcome.Done()
                    else StrategyOutcome.Refused("the field refused SET_TEXT")
                })
            }
            for (ui in uis(c)) {
                if (UiFeature.TEXT_INJECTION in ui.features) {
                    add(Strategy("focus_and_inject", ui.name) {
                        if (node != null && ui === ownBackend && UiFeature.NODE_ACTIONS in ui.features) {
                            ownBackend.performAction(node, NodeAction.FOCUS)
                        } else if (node != null && !node.has(NodeFlag.FOCUSED)) {
                            ui.tap(c.displayId, target.let { (n, t) -> visibleCenter(n, t) })
                        }
                        if (op.clearFirst) {
                            c.checkDispatch()
                            ui.key(c.displayId, "KEYCODE_MOVE_END")
                            repeat(minOf(node?.text?.length ?: 0, 200)) { c.checkDispatch(); ui.key(c.displayId, "KEYCODE_DEL") }
                        }
                        c.checkDispatch()
                        ui.typeText(c.displayId, op.text)
                        StrategyOutcome.Done()
                    })
                }
            }
            val clip = backends.clipboard
            if (!op.secret && clip != null && node != null && ownBackend != null && UiFeature.NODE_ACTIONS in ownBackend.features && clip.isAvailable()) {
                add(Strategy("clipboard_paste", clip.name) {
                    val before = tryOrNull { clip.get() }
                    c.checkDispatch()
                    clip.set(op.text)
                    try {
                        c.checkDispatch()
                        ownBackend.performAction(node, NodeAction.FOCUS)
                        if (op.clearFirst) {
                            c.checkDispatch()
                            ownBackend.performAction(node, NodeAction.SET_TEXT, NodeActionArgs(text = ""))
                        }
                        c.checkDispatch()
                        if (ownBackend.performAction(node, NodeAction.PASTE)) StrategyOutcome.Done() else StrategyOutcome.Refused("the field refused PASTE")
                    } finally {
                        if (before != null) clip.set(before)
                    }
                })
            }
            if (ShellInput.canInputText(op.text)) {
                for (shell in inputShells(c)) add(shellStrategy(shell, "shell_input_text", ShellInput.text(c.displayId, op.text)))
            }
        }
        if (strategies.isEmpty()) return OpOutcome.fail(ErrorCode.UNSUPPORTED, "No way to type is available: no text-capable UI backend. Enable the accessibility service.", status = ActionStatus.UNSUPPORTED)
        val run = runStrategies(env, c, strategies)
        if (!run.succeeded) return finish(c, run, "")
        if (op.submit) {
            val enter = pressKeyOn(c, "KEYCODE_ENTER")
            if (!enter) return OpOutcome.ok("Typed $described into $where, but could not press Enter to submit.", backend = run.backend)
        }
        c.displayId?.let { c.state.activeDisplay = it }
        return OpOutcome.ok("Typed $described into $where${if (op.submit) " and pressed Enter" else ""}", backend = run.backend)
    }

    private suspend fun pressKeyOn(c: OpContext, keycode: String): Boolean {
        val strategies = buildList {
            for (ui in uis(c)) if (UiFeature.KEY_EVENTS in ui.features) add(Strategy("key", ui.name) { ui.key(c.displayId, keycode); StrategyOutcome.Done() })
            for (shell in inputShells(c)) add(shellStrategy(shell, "shell_input_key", ShellInput.key(c.displayId, keycode)))
        }
        return runStrategies(env, c, strategies).succeeded
    }

    private suspend fun selectText(c: OpContext, op: Operation.SelectText): OpOutcome {
        val (node, _) = editableTarget(c) ?: return OpOutcome.fail(ErrorCode.NOT_FOUND, "No text field to select in. Name one, or focus one first.", retryable = true)
        val ui = c.primaryUi?.takeIf { UiFeature.NODE_ACTIONS in it.features } ?: return OpOutcome.unsupported("Selecting text needs the accessibility service.")
        val length = node.text?.length ?: 0
        val start = if (op.all) 0 else op.start ?: 0
        val end = if (op.all) length else op.end ?: length
        val run = runStrategies(env, c, listOf(Strategy("set_selection", ui.name) {
            if (ui.performAction(node, NodeAction.SET_SELECTION, NodeActionArgs(selectionStart = start, selectionEnd = end))) StrategyOutcome.Done()
            else StrategyOutcome.Refused("the field refused SET_SELECTION")
        }))
        return finish(c, run, "Selected ${if (op.all) "all text" else "characters $start-$end"} in ${env.targeting.describe(node)}")
    }

    private suspend fun clipboard(c: OpContext, op: Operation.Clipboard): OpOutcome {
        val clip = backends.clipboard
        return when (op.action) {
            ClipboardAction.GET -> {
                val gui = backends.linuxGui
                val text = when {
                    c.environment == Environment.LINUX_GUI && gui != null -> gui.clipboardGet()
                    clip != null && clip.isAvailable() -> clip.get()
                    else -> return OpOutcome.unavailable("The clipboard is not accessible.")
                }
                if (text == null) OpOutcome.ok("The clipboard is empty (or Android hid it from the app).")
                else OpOutcome.ok("Clipboard (${text.length} characters): ${text.take(2_000)}")
            }
            ClipboardAction.SET -> {
                val text = op.text ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "clipboard set needs text.")
                val gui = backends.linuxGui
                when {
                    c.environment == Environment.LINUX_GUI && gui != null -> { c.checkDispatch(); gui.clipboardSet(text) }
                    clip != null && clip.isAvailable() -> { c.checkDispatch(); clip.set(text) }
                    else -> return OpOutcome.unavailable("The clipboard is not accessible.")
                }
                OpOutcome.ok("Set the clipboard (${text.length} characters)")
            }
            ClipboardAction.COPY, ClipboardAction.CUT, ClipboardAction.PASTE -> {
                val (node, _) = editableTarget(c) ?: return OpOutcome.fail(ErrorCode.NOT_FOUND, "No text field to ${op.action.name.lowercase()} with. Name one, or focus one first.", retryable = true)
                val ui = c.primaryUi?.takeIf { UiFeature.NODE_ACTIONS in it.features } ?: return OpOutcome.unsupported("${op.action.name.lowercase()} needs the accessibility service.")
                val action = when (op.action) { ClipboardAction.COPY -> NodeAction.COPY; ClipboardAction.CUT -> NodeAction.CUT; else -> NodeAction.PASTE }
                val run = runStrategies(env, c, listOf(Strategy("node_${action.name.lowercase()}", ui.name) {
                    if (ui.performAction(node, action)) StrategyOutcome.Done() else StrategyOutcome.Refused("the field refused ${action.name}")
                }))
                finish(c, run, "${op.action.name.lowercase().replaceFirstChar { it.uppercase() }} in ${env.targeting.describe(node)}")
            }
        }
    }

    // ---- keys and global actions ----

    private suspend fun pressKey(c: OpContext, op: Operation.PressKey): OpOutcome {
        if (c.environment == Environment.LINUX_GUI) {
            val run = runStrategies(env, c, uis(c).filter { UiFeature.KEY_EVENTS in it.features }.map { ui -> Strategy("key", ui.name) { ui.key(c.displayId, op.key); StrategyOutcome.Done() } })
            return finish(c, run, "Pressed ${op.key}")
        }
        val canonical = AndroidKeys.normalize(op.key)
            ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "Unknown key '${op.key}'. Use names such as ENTER, BACK, HOME, TAB, DEL, ESCAPE, DPAD_DOWN, VOLUME_UP, or KEYCODE_* names.")
        val global = AndroidKeys.asGlobalAction(canonical)
        val strategies = buildList {
            for (ui in uis(c)) {
                if (global != null && (c.displayId == null || c.displayId == 0) && UiFeature.GLOBAL_ACTIONS in ui.features) {
                    add(Strategy("global_${global.name.lowercase()}", ui.name) {
                        if (ui.global(global)) StrategyOutcome.Done() else StrategyOutcome.Refused("the system refused the global action")
                    })
                }
                if (UiFeature.KEY_EVENTS in ui.features) add(Strategy("key_event", ui.name) { ui.key(c.displayId, canonical); StrategyOutcome.Done() })
            }
            for (shell in inputShells(c)) add(shellStrategy(shell, "shell_input_key", ShellInput.key(c.displayId, canonical)))
        }
        return finish(c, runStrategies(env, c, strategies), "Pressed ${canonical.removePrefix("KEYCODE_")}")
    }

    private suspend fun global(c: OpContext, op: Operation.Global): OpOutcome {
        val strategies = buildList {
            for (ui in uis(c)) if (UiFeature.GLOBAL_ACTIONS in ui.features) {
                add(Strategy("global_${op.action.name.lowercase()}", ui.name) {
                    if (ui.global(op.action)) StrategyOutcome.Done() else StrategyOutcome.Refused("the system refused the global action")
                })
            }
            val command = when (op.action) {
                GlobalAction.BACK -> ShellInput.key(null, "KEYCODE_BACK")
                GlobalAction.HOME -> ShellInput.key(null, "KEYCODE_HOME")
                GlobalAction.RECENTS -> ShellInput.key(null, "KEYCODE_APP_SWITCH")
                GlobalAction.NOTIFICATIONS -> "cmd statusbar expand-notifications"
                GlobalAction.QUICK_SETTINGS -> "cmd statusbar expand-settings"
                GlobalAction.POWER_DIALOG -> ShellInput.key(null, "KEYCODE_POWER", longPress = true)
                GlobalAction.LOCK_SCREEN -> ShellInput.key(null, "KEYCODE_SLEEP")
                GlobalAction.TAKE_SCREENSHOT -> null
            }
            if (command != null) for (shell in inputShells(c)) add(shellStrategy(shell, "shell_${op.action.name.lowercase()}", command))
        }
        return finish(c, runStrategies(env, c, strategies), "Performed ${op.action.name.lowercase().replace('_', ' ')}")
    }

    // ---- apps ----

    private suspend fun launchApp(c: OpContext, op: Operation.LaunchApp): OpOutcome {
        val apps = backends.apps?.takeIf { it.isAvailable() } ?: return OpOutcome.unavailable("Launching apps needs shell access (Shizuku or root) or the accessibility service.")
        val started = env.clock.nowMs()
        c.checkDispatch()
        val outcome = apps.launch(AppLaunch(op.packageName, op.activity, op.intentAction, op.uri, op.extras, c.displayId))
        if (!outcome.started) {
            return OpOutcome.fail(ErrorCode.NOT_FOUND, "Could not launch ${op.packageName}: ${outcome.message.ifBlank { "the system refused" }}. Check the package name with the packages tool.", retryable = false)
        }
        if (op.waitForForeground) {
            val deadline = env.clock.nowMs() + env.config.launchForegroundTimeoutMs
            while (true) {
                val fg = tryOrNull { apps.foreground(c.displayId) }.orEmpty()
                if (fg.any { it.packageName == op.packageName }) break
                val crash = env.logs?.crashes(started, op.packageName)?.firstOrNull()
                if (crash != null) return OpOutcome.fail(ErrorCode.NOT_ACTIONABLE, "${op.packageName} started but crashed immediately: ${crash.summary()}", retryable = false)
                if (env.clock.nowMs() >= deadline) {
                    val now = fg.mapNotNull { it.packageName }.distinct().joinToString().ifEmpty { "unknown" }
                    // Apps often hand over to another component or package (Settings opens its search app).
                    // If the process is alive, the launch worked; the agent sees where the screen is.
                    val alive = env.backends.processes?.let { p -> tryOrNull { p.isRunning(op.packageName) } } == true
                    if (alive) {
                        c.displayId?.let { c.state.activeDisplay = it }
                        return OpOutcome.ok("Launched ${op.packageName}${outcome.component?.let { " ($it)" } ?: ""}, but the foreground is $now (the app opened something else or is still starting). Observe to see the screen.", backend = apps.name)
                    }
                    return OpOutcome.fail(ErrorCode.TIMEOUT, "Launched ${op.packageName} but it did not reach the foreground within ${env.config.launchForegroundTimeoutMs}ms and is not running (foreground is $now).", retryable = true)
                }
                delay(150)
            }
        }
        c.displayId?.let { c.state.activeDisplay = it }
        val drawn = if (op.waitForForeground) awaitContent(c, op.packageName) else true
        return OpOutcome.ok(
            "Launched ${op.packageName}${outcome.component?.let { " ($it)" } ?: ""}${c.displayId?.takeIf { it != 0 }?.let { " on display $it" } ?: ""}" +
                if (drawn) "" else "; it is still starting, so give it a moment before acting",
            backend = apps.name,
        )
    }

    /** Waits until the app's window shows something to read or press; a resumed activity may not have drawn yet. */
    private suspend fun awaitContent(c: OpContext, packageName: String): Boolean {
        val deadline = env.clock.nowMs() + env.config.launchContentTimeoutMs
        while (true) {
            for (ui in uis(c)) {
                val snapshot = tryOrNull { ui.snapshot(SnapshotOptions(displayId = c.displayId)) } ?: continue
                val content = snapshot.windows.filter { it.packageName == packageName }.sumOf { w -> w.nodes.count { it.isVisible && (it.isInteractive || !it.label.isNullOrBlank()) } }
                if (content >= 3) return true
                break
            }
            if (env.clock.nowMs() >= deadline) return false
            delay(300)
        }
    }

    private suspend fun stopApp(c: OpContext, op: Operation.StopApp): OpOutcome {
        val apps = backends.apps?.takeIf { it.isAvailable() } ?: return OpOutcome.unavailable("Stopping apps needs shell access (Shizuku or root).")
        c.checkDispatch()
        apps.stop(op.packageName, op.force)
        return OpOutcome.ok("Stopped ${op.packageName}", backend = apps.name)
    }

    private suspend fun switchApp(c: OpContext, op: Operation.SwitchApp): OpOutcome {
        val apps = backends.apps?.takeIf { it.isAvailable() } ?: return OpOutcome.unavailable("Switching apps needs shell access (Shizuku or root).")
        c.checkDispatch()
        if (apps.bringToFront(op.packageName, c.displayId)) return OpOutcome.ok("Switched to ${op.packageName}", backend = apps.name)
        c.checkDispatch()
        val launched = apps.launch(AppLaunch(packageName = op.packageName, displayId = c.displayId))
        return if (launched.started) OpOutcome.ok("${op.packageName} was not running; launched it", backend = apps.name)
        else OpOutcome.fail(ErrorCode.NOT_FOUND, "${op.packageName} is not running and could not be launched: ${launched.message}")
    }

    // ---- notifications ----

    private suspend fun notifications(c: OpContext, op: Operation.Notifications): OpOutcome {
        val nb = backends.notifications?.takeIf { it.isAvailable() }
            ?: return OpOutcome.unavailable("Reading notifications needs the notification listener permission.")
        c.checkDispatch()
        return when (op.action) {
            NotificationAction.LIST -> {
                val list = nb.list()
                if (list.isEmpty()) OpOutcome.ok("No notifications.", backend = nb.name)
                else OpOutcome.ok(
                    "${list.size} notification(s):\n" + list.joinToString("\n") { n ->
                        "[${n.key}] ${n.packageName}: ${listOfNotNull(n.title, n.text).joinToString(" - ").take(160)}" +
                            (if (n.actions.isNotEmpty()) " actions=${n.actions.withIndex().joinToString(",") { "${it.index}:${it.value}" }}" else "") +
                            (if (n.canReply) " (replyable)" else "")
                    },
                    data = buildJsonObject { put("count", list.size) },
                    backend = nb.name,
                )
            }
            NotificationAction.OPEN -> bool(op.key, "open") { nb.open(it) }
            NotificationAction.DISMISS -> bool(op.key, "dismiss") { nb.dismiss(it) }
            NotificationAction.PRESS_ACTION -> bool(op.key, "press an action of") { nb.pressAction(it, op.actionIndex ?: 0) }
            NotificationAction.REPLY -> bool(op.key, "reply to") { nb.reply(it, op.text ?: return@bool false) }
        }
    }

    private suspend fun bool(key: String?, verb: String, block: suspend (String) -> Boolean): OpOutcome {
        val k = key ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "This needs the notification key from a list call.")
        return if (block(k)) OpOutcome.ok("Did $verb notification $k") else OpOutcome.fail(ErrorCode.NOT_FOUND, "Could not $verb notification $k; it may have been removed.", retryable = true)
    }
}
