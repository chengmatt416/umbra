package app.umbra.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one JSON configuration used for host-tool arguments, persistence and the wire formats we own.
 * Lenient on input (LLM-produced arguments are sloppy) and compact on output.
 */
val UmbraJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    isLenient = true
    coerceInputValues = true
}

/** Wall-clock abstraction so time-dependent logic can be tested with virtual time. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}

@Serializable
data class Point(val x: Int, val y: Int)

@Serializable
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val center: Point get() = Point(centerX, centerY)
    val area: Long get() = if (isEmpty) 0L else width.toLong() * height.toLong()
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(point: Point): Boolean =
        point.x in left until right && point.y in top until bottom

    fun intersection(other: Rect): Rect? {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (r > l && b > t) Rect(l, t, r, b) else null
    }

    companion object {
        val EMPTY = Rect(0, 0, 0, 0)
    }
}
