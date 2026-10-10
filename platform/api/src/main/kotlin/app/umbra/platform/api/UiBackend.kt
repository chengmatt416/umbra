package app.umbra.platform.api

import app.umbra.core.model.Point
import app.umbra.core.model.action.GlobalAction
import app.umbra.core.model.capability.UiBackendKind
import app.umbra.core.model.observation.NodeAction
import app.umbra.core.model.observation.UiNode
import app.umbra.core.model.observation.UiSnapshot

/** What a UI backend can do, so the pipeline skips strategies that cannot work instead of trying them. */
enum class UiFeature {
    /** Performs actions on nodes (click, set text, scroll) rather than only injecting input. */
    NODE_ACTIONS,
    GESTURES,
    KEY_EVENTS,
    GLOBAL_ACTIONS,

    /** Can read and drive displays other than the default one. */
    MULTI_DISPLAY,

    /** Types into the focused field without a node reference. */
    TEXT_INJECTION,
}

data class SnapshotOptions(
    /** Null = every display the backend can see. */
    val displayId: Int? = null,
    val maxNodes: Int = 2_000,
    val includeInvisible: Boolean = false,
)

data class NodeActionArgs(
    val text: String? = null,
    val selectionStart: Int? = null,
    val selectionEnd: Int? = null,
)

/**
 * Reads and drives a user interface: Android through the accessibility service, UiAutomation or a
 * root shell, and a Linux desktop through AT-SPI. One interface lets the pipeline use the same
 * semantic-first, gesture-second logic for both (spec §6, §13).
 */
interface UiBackend : Backend {
    val kind: UiBackendKind
    val features: Set<UiFeature>

    suspend fun snapshot(options: SnapshotOptions = SnapshotOptions()): UiSnapshot

    /**
     * Performs [action] on [node], which must come from the most recent snapshot.
     * Returns false when the node refused the action; throws [BackendException] with
     * `STALE_ELEMENT` when the node no longer exists.
     */
    suspend fun performAction(node: UiNode, action: NodeAction, args: NodeActionArgs = NodeActionArgs()): Boolean

    suspend fun tap(displayId: Int?, point: Point)

    suspend fun longPress(displayId: Int?, point: Point, durationMs: Long)

    suspend fun swipe(displayId: Int?, from: Point, to: Point, durationMs: Long)

    /** Press at [from], hold for [holdMs], move to [to] over [durationMs], release. */
    suspend fun drag(displayId: Int?, from: Point, to: Point, holdMs: Long, durationMs: Long)

    /** [key] is normalised by the pipeline: an Android `KEYCODE_*` name or an xdotool key spec. */
    suspend fun key(displayId: Int?, key: String)

    /** Types into whatever has input focus using injected input events. */
    suspend fun typeText(displayId: Int?, text: String)

    suspend fun global(action: GlobalAction): Boolean
}
