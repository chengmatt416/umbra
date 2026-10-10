package app.umbra.platform.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.umbra.platform.vision.ImageCodec
import app.umbra.platform.vision.ImageFormat
import app.umbra.platform.vision.PixelImage
import java.io.ByteArrayOutputStream

/** [ImageCodec] on `Bitmap`. */
class AndroidImageCodec : ImageCodec {
    override fun decode(bytes: ByteArray): PixelImage {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("not a decodable image (${bytes.size} bytes)")
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return PixelImage(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    override fun encode(image: PixelImage, format: ImageFormat, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            val out = ByteArrayOutputStream()
            val ok = when (format) {
                ImageFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                ImageFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
            }
            check(ok) { "bitmap compression failed" }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
