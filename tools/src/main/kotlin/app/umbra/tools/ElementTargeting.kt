package app.umbra.tools

import app.umbra.core.decision.Candidate
import app.umbra.core.decision.DecisionRouter
import app.umbra.core.model.Point
import app.umbra.core.model.action.ActionError
import app.umbra.core.model.action.ElementQuery
import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.action.Operation
import app.umbra.core.model.action.Target
import app.umbra.core.model.observation.ElementResolver
import app.umbra.core.model.observation.NodeFlag
import app.umbra.core.model.observation.Resolution
import app.umbra.core.model.observation.UiNode
import app.umbra.core.model.observation.UiSnapshot
import app.umbra.core.model.observation.UiTree
import app.umbra.core.model.observation.UiTreeRenderer

/** What a request's target turned into. */
internal sealed interface Resolved {
    data class Node(val node: UiNode, val tree: UiTree, val note: String? = null) : Resolved

    data class At(val point: Point) : Resolved

    /** The request names no target. Valid for operations that default to the focused element or the screen. */
    data object Unspecified : Resolved

    data class Failed(val error: ActionError, val candidates: List<UiNode> = emptyList()) : Resolved
}

/**
 * Turns the target of a request into a node or a point (spec §6): ids from earlier observations are
 * checked for staleness and relocated if the layout merely moved; queries are matched semantically,
 * and ambiguity goes to the fast decision layer before it goes back to the agent.
 */
internal class ElementTargeting(private val decisions: DecisionRouter?) {
    suspend fun resolve(
        target: Target,
        op: Operation,
        snapshot: UiSnapshot?,
        state: SessionToolState,
        displayId: Int?,
        rationale: String?,
        snapshotError: String? = null,
    ): Resolved {
        val element = target.element
        if (element == null) {
            return target.point?.let { Resolved.At(it) } ?: Resolved.Unspecified
        }
        if (snapshot == null) {
            return Resolved.Failed(
                ActionError(
                    ErrorCode.BACKEND_UNAVAILABLE,
                    "Cannot look up elements: the screen could not be read (${snapshotError ?: "no UI backend is connected"}). Use screen coordinates, or enable the accessibility service.",
                    retryable = snapshotError != null,
                ),
            )
        }
        element.id?.let { return byId(it, snapshot, state, displayId) }
        element.query?.let { return byQuery(it, op, snapshot, displayId, rationale) }
        return Resolved.Failed(ActionError(ErrorCode.INVALID_ARGUMENTS, "The element reference has neither an id nor a query."))
    }

    // ---- by id ----

    private fun byId(id: String, snapshot: UiSnapshot, state: SessionToolState, displayId: Int?): Resolved {
        val remembered = state.memory.get(id)
        val current = snapshot.node(id)
        if (current != null) {
            val tree = treeOf(snapshot, id)
            if (remembered == null || remembered.matches(current)) {
                return Resolved.Node(current, tree, note = if (remembered == null) "element $id was not seen in an earlier observation" else null)
            }
            relocate(remembered, snapshot, displayId)?.let { (node, t) ->
                return Resolved.Node(node, t, "element $id changed; using ${node.id} which matches what you observed (\"${remembered.label ?: remembered.className}\")")
            }
            return Resolved.Failed(
                ActionError(
                    ErrorCode.STALE_ELEMENT,
                    "Element $id is now ${describe(current)}, not ${remembered.label?.let { "\"$it\"" } ?: remembered.className ?: "what you observed"}; the screen changed since you looked. Observe again.",
                    retryable = true,
                ),
            )
        }
        if (remembered != null) {
            relocate(remembered, snapshot, displayId)?.let { (node, t) ->
                return Resolved.Node(node, t, "element $id is gone; using ${node.id} which matches what you observed")
            }
        }
        return Resolved.Failed(ActionError(ErrorCode.STALE_ELEMENT, "Element $id no longer exists; the screen changed since you looked. Observe again.", retryable = true))
    }

    private fun relocate(fp: Fingerprint, snapshot: UiSnapshot, displayId: Int?): Pair<UiNode, UiTree>? {
        val trees = snapshot.windows.filter { displayId == null || it.displayId == displayId }
        val all = trees.flatMap { t -> t.nodes.map { it to t } }
        val node = fp.relocate(all.map { it.first }) ?: return null
        return all.first { it.first === node }
    }

    // ---- by query ----

    private suspend fun byQuery(query: ElementQuery, op: Operation, snapshot: UiSnapshot, displayId: Int?, rationale: String?): Resolved {
        if (query.isEmpty) return Resolved.Failed(ActionError(ErrorCode.INVALID_ARGUMENTS, "The element query is empty. Give text, desc, id, class or another field."))
        val prefer = when (op) {
            is Operation.TypeText, is Operation.SelectText -> NodeFlag.EDITABLE
            is Operation.Scroll -> NodeFlag.SCROLLABLE
            else -> NodeFlag.CLICKABLE
        }
        return when (val r = ElementResolver.resolve(snapshot, query, prefer, displayId)) {
            is Resolution.Unique -> Resolved.Node(r.node, treeOf(snapshot, r.node.id))
            Resolution.NotFound -> Resolved.Failed(notFoundError(query, snapshot, displayId))
            is Resolution.Ambiguous -> disambiguate(query, op, r.candidates, snapshot, displayId, rationale)
        }
    }

    private suspend fun disambiguate(
        query: ElementQuery,
        op: Operation,
        candidates: List<UiNode>,
        snapshot: UiSnapshot,
        displayId: Int?,
        rationale: String?,
    ): Resolved {
        val top = candidates.take(MAX_CANDIDATES)
        val tree = treeOf(snapshot, top.first().id)
        val router = decisions
        if (router != null && top.size >= 2) {
            val screen = UiTreeRenderer.render(tree, UiTreeRenderer.Options(maxNodes = 30, onlyInteractive = true, includeBounds = false))
            val intent = rationale ?: "${op.name.replace('_', ' ')} the element matching ${describeQuery(query)}"
            val routed = router.chooseTarget(intent, screen, top.map { Candidate(it.id, describeWithPlace(it, tree)) })
            if (routed.trusted) {
                val picked = top.first { it.id == routed.value!!.id }
                return Resolved.Node(picked, tree, "${top.size} elements matched ${describeQuery(query)}; picked ${picked.id} (${routed.engine}, confidence ${"%.2f".format(routed.confidence)})")
            }
        }
        return Resolved.Failed(
            ActionError(
                ErrorCode.AMBIGUOUS,
                "${candidates.size} elements match ${describeQuery(query)}. Choose one by id (or add index/resource_id):\n" +
                    top.joinToString("\n") { "  [${it.id}] ${describeWithPlace(it, tree)}" },
                retryable = true,
            ),
            top,
        )
    }

    fun notFoundError(query: ElementQuery, snapshot: UiSnapshot, displayId: Int?): ActionError {
        val trees = snapshot.windows.filter { displayId == null || it.displayId == displayId }
        val all = trees.flatMap { it.nodes }.filter { it.isVisible }
        val words = listOfNotNull(query.text, query.textContains, query.contentDescription)
            .flatMap { it.lowercase().split(Regex("\\W+")) }.filter { it.length >= 3 }.toSet()
        val similar = all.filter { n -> n.label?.lowercase()?.let { l -> words.any { it in l } } == true }.take(5)
        val interactive = all.count { it.isInteractive }
        val message = buildString {
            append("No element matches ${describeQuery(query)}.")
            if (similar.isNotEmpty()) {
                append(" Similar: ").append(similar.joinToString("; ") { "[${it.id}] ${describe(it)}" }).append('.')
            } else if (interactive == 0) {
                append(" The screen exposes no interactive elements to the accessibility tree; take a screenshot with annotate=true or use coordinates.")
            } else {
                append(" The screen has $interactive interactive elements; observe it to see them.")
            }
        }
        return ActionError(ErrorCode.NOT_FOUND, message, retryable = true)
    }

    /** The window a node id (`<windowId>:<path>`) belongs to; ids come from the snapshot, so it exists. */
    private fun treeOf(snapshot: UiSnapshot, nodeId: String): UiTree =
        snapshot.window(nodeId.substringBefore(':').toInt()) ?: error("snapshot has no window for $nodeId")

    // ---- descriptions ----

    fun describe(n: UiNode): String = buildString {
        append(n.shortClass)
        n.label?.let { append(" \"").append(if (n.isPassword) "[hidden]" else it.take(60)).append('"') }
        n.resourceId?.let { append(" id=").append(it.substringAfterLast('/')) }
    }

    private fun describeWithPlace(n: UiNode, tree: UiTree): String {
        val maxX = tree.nodes.maxOfOrNull { it.bounds.right }?.coerceAtLeast(1) ?: 1
        val maxY = tree.nodes.maxOfOrNull { it.bounds.bottom }?.coerceAtLeast(1) ?: 1
        val v = when {
            n.bounds.centerY < maxY / 3 -> "top"
            n.bounds.centerY < 2 * maxY / 3 -> "middle"
            else -> "bottom"
        }
        val h = when {
            n.bounds.centerX < maxX / 3 -> "left"
            n.bounds.centerX < 2 * maxX / 3 -> "center"
            else -> "right"
        }
        return "${describe(n)} at the $v $h of the screen"
    }

    fun describeQuery(q: ElementQuery): String = buildList {
        q.text?.let { add("text=\"$it\"") }
        q.textContains?.let { add("text containing \"$it\"") }
        q.contentDescription?.let { add("desc=\"$it\"") }
        q.resourceId?.let { add("id=$it") }
        q.className?.let { add("class=$it") }
        q.packageName?.let { add("package=$it") }
        q.clickable?.let { add("clickable=$it") }
        q.editable?.let { add("editable=$it") }
        q.scrollable?.let { add("scrollable=$it") }
        q.checked?.let { add("checked=$it") }
        q.enabled?.let { add("enabled=$it") }
        if (q.index > 0) add("index=${q.index}")
    }.joinToString(", ").ifEmpty { "(empty query)" }

    private companion object {
        const val MAX_CANDIDATES = 8
    }
}
