package app.umbra.platform.android

import app.umbra.core.model.Rect

/** Input values are never part of an interaction event. Consumers must opt in while teaching. */
data class AccessibilityInteraction(val atMs: Long, val packageName: String, val windowTitle: String,
    val resourceId: String?, val className: String?, val bounds: Rect, val action: String,
    val label: String, val description: String, val secretInput: Boolean, val displayId: Int)
