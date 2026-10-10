package app.umbra.platform.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageOps {
    /** Factor that shrinks the longer edge to [maxEdge]; 1 when the image already fits. */
    fun scaleFor(width: Int, height: Int, maxEdge: Int): Float {
        val longest = max(width, height)
        return if (longest <= maxEdge) 1f else maxEdge.toFloat() / longest
    }

    /** Area-averaging downscale: sharp enough to read text, and no aliasing on thin UI lines. */
    fun downscale(src: PixelImage, maxEdge: Int): PixelImage {
        val scale = scaleFor(src.width, src.height, maxEdge)
        if (scale >= 1f) return src
        val w = max(1, (src.width * scale).roundToInt())
        val h = max(1, (src.height * scale).roundToInt())
        return resize(src, w, h)
    }

    fun resize(src: PixelImage, w: Int, h: Int): PixelImage {
        if (w == src.width && h == src.height) return src
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = y * src.height / h
            val y1 = max(y0 + 1, (y + 1) * src.height / h)
            for (x in 0 until w) {
                val x0 = x * src.width / w
                val x1 = max(x0 + 1, (x + 1) * src.width / w)
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0
                for (yy in y0 until min(y1, src.height)) {
                    val row = yy * src.width
                    for (xx in x0 until min(x1, src.width)) {
                        val p = src.pixels[row + xx]
                        a += p ushr 24
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        n++
                    }
                }
                out[y * w + x] = if (n == 0) 0 else ((a / n).toInt() shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            }
        }
        return PixelImage(w, h, out)
    }

    /**
     * Fraction (0..1) of pixels that differ by more than [threshold] in any colour channel. Images of
     * different size count as entirely different. Compared on a common grid of at most
     * [maxSamples] points so it stays cheap on a phone.
     */
    fun diffRatio(a: PixelImage, b: PixelImage, threshold: Int = 24, maxSamples: Int = 40_000): Float {
        if (a.width != b.width || a.height != b.height) return 1f
        val total = a.width.toLong() * a.height
        val step = max(1, Math.sqrt(total.toDouble() / maxSamples).toInt())
        var different = 0
        var sampled = 0
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                val p = a[x, y]
                val q = b[x, y]
                if (abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) > threshold ||
                    abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) > threshold ||
                    abs((p and 0xFF) - (q and 0xFF)) > threshold
                ) {
                    different++
                }
                sampled++
                x += step
            }
            y += step
        }
        return if (sampled == 0) 0f else different.toFloat() / sampled
    }

    /** Mean luminance 0..255 over a sample; used to pick label colours and to spot black screens. */
    fun meanLuma(img: PixelImage, maxSamples: Int = 10_000): Float {
        val total = img.width.toLong() * img.height
        val step = max(1, Math.sqrt(total.toDouble() / maxSamples).toInt())
        var sum = 0.0
        var n = 0
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val p = img[x, y]
                sum += 0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) 0f else (sum / n).toFloat()
    }
}
