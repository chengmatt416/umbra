package app.umbra.platform.vision

import app.umbra.core.model.Rect

/**
 * ARGB pixels in a plain array. Everything the agent does with screenshots (downscale, annotate,
 * diff) works on this type, so it is testable on the JVM and identical on Android; only decoding and
 * encoding are platform specific ([ImageCodec]).
 */
class PixelImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "empty image ${width}x$height" }
        require(pixels.size == width * height) { "pixel count ${pixels.size} != ${width}x$height" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    operator fun set(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }

    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun copy() = PixelImage(width, height, pixels.copyOf())

    /** Fills the clipped rectangle with [argb], alpha-blended over what is there. */
    fun fillRect(left: Int, top: Int, right: Int, bottom: Int, argb: Int) {
        val l = left.coerceIn(0, width)
        val t = top.coerceIn(0, height)
        val r = right.coerceIn(0, width)
        val b = bottom.coerceIn(0, height)
        for (y in t until b) {
            val row = y * width
            for (x in l until r) pixels[row + x] = blend(pixels[row + x], argb)
        }
    }

    /** Outline of [rect] drawn inward with the given line [thickness]. */
    fun strokeRect(left: Int, top: Int, right: Int, bottom: Int, thickness: Int, argb: Int) {
        fillRect(left, top, right, top + thickness, argb)
        fillRect(left, bottom - thickness, right, bottom, argb)
        fillRect(left, top + thickness, left + thickness, bottom - thickness, argb)
        fillRect(right - thickness, top + thickness, right, bottom - thickness, argb)
    }

    fun crop(rect: Rect): PixelImage {
        val l = rect.left.coerceIn(0, width - 1)
        val t = rect.top.coerceIn(0, height - 1)
        val r = rect.right.coerceIn(l + 1, width)
        val b = rect.bottom.coerceIn(t + 1, height)
        val out = IntArray((r - l) * (b - t))
        for (y in t until b) System.arraycopy(pixels, y * width + l, out, (y - t) * (r - l), r - l)
        return PixelImage(r - l, b - t, out)
    }

    companion object {
        fun filled(width: Int, height: Int, argb: Int) = PixelImage(width, height, IntArray(width * height) { argb })

        /** Source-over blend of [src] on an opaque [dst]; the result is opaque. */
        fun blend(dst: Int, src: Int): Int {
            val a = src ushr 24
            if (a == 255) return src or (0xFF shl 24)
            if (a == 0) return dst
            val inv = 255 - a
            val r = (((src shr 16) and 0xFF) * a + ((dst shr 16) and 0xFF) * inv) / 255
            val g = (((src shr 8) and 0xFF) * a + ((dst shr 8) and 0xFF) * inv) / 255
            val b = ((src and 0xFF) * a + (dst and 0xFF) * inv) / 255
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

enum class ImageFormat(val mime: String) { PNG("image/png"), JPEG("image/jpeg") }

/** Platform image codec: `ImageIO` on the JVM, `Bitmap` on Android. */
interface ImageCodec {
    fun decode(bytes: ByteArray): PixelImage

    /** [quality] is 1..100 and only matters for JPEG. */
    fun encode(image: PixelImage, format: ImageFormat, quality: Int = 80): ByteArray
}
