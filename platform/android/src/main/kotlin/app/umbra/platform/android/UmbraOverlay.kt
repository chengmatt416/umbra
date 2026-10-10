package app.umbra.platform.android

/**
 * The agent's own floating controls are drawn in a window with this title. The accessibility backend
 * leaves it out of every snapshot, so the agent never sees its own stop button and approval prompt
 * as part of the screen it is working on (and cannot be tempted to press them).
 */
object UmbraOverlay {
    const val WINDOW_TITLE = "umbra-overlay"
}
