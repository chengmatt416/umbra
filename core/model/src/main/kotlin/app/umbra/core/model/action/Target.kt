package app.umbra.core.model.action

import app.umbra.core.model.Point
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The four tool families of spec §14. */
@Serializable
enum class Environment {
    @SerialName("android") ANDROID,
    @SerialName("linux_cli") LINUX_CLI,
    @SerialName("linux_gui") LINUX_GUI,
    @SerialName("privileged") PRIVILEGED,
}

/**
 * Privilege an action needs (spec §16 "required permission level"). The levels are not a strict
 * chain: see [app.umbra.core.model.capability.CapabilitySnapshot.canProvide] for how they map to
 * what the device actually offers.
 */
@Serializable
enum class Privilege {
    @SerialName("none") NONE,
    @SerialName("accessibility") ACCESSIBILITY,
    @SerialName("shell") SHELL,
    @SerialName("root") ROOT,
}

/** What to act on: environment, display, application, a semantic element, or a raw coordinate. */
@Serializable
data class Target(
    val environment: Environment = Environment.ANDROID,
    /** Android display id, or X11 display number for [Environment.LINUX_GUI]. Null = default display. */
    val display: Int? = null,
    /** Android package name, or Linux window class / title fragment. */
    val app: String? = null,
    val element: ElementRef? = null,
    val point: Point? = null,
) {
    companion object {
        val ANDROID_DEFAULT = Target()
    }
}

/** Either a concrete node id from the latest observation, or a query resolved at execution time. */
@Serializable
data class ElementRef(
    val id: String? = null,
    val query: ElementQuery? = null,
)

/**
 * Semantic element query (spec §6 "semantic node targeting"). All present fields must match.
 * [index] picks the n-th match in reading order (top-to-bottom, left-to-right).
 */
@Serializable
data class ElementQuery(
    val text: String? = null,
    val textContains: String? = null,
    val contentDescription: String? = null,
    /** Matches the full id or its suffix, so `login` matches `app.pkg:id/login`. */
    val resourceId: String? = null,
    /** Matches the full class name or its suffix, so `Button` matches `android.widget.Button`. */
    val className: String? = null,
    val packageName: String? = null,
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
    val scrollable: Boolean? = null,
    val checked: Boolean? = null,
    val enabled: Boolean? = null,
    val index: Int = 0,
) {
    val isEmpty: Boolean
        get() = text == null && textContains == null && contentDescription == null &&
            resourceId == null && className == null && packageName == null &&
            clickable == null && editable == null && scrollable == null &&
            checked == null && enabled == null
}

/** Destination for drag-like operations. */
@Serializable
data class Endpoint(
    val element: ElementRef? = null,
    val point: Point? = null,
)
