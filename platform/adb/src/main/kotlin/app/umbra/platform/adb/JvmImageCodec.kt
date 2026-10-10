package app.umbra.platform.adb

import app.umbra.platform.vision.ImageCodec
import app.umbra.platform.vision.ImageFormat
import app.umbra.platform.vision.PixelImage
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * [ImageCodec] on `javax.imageio` for development machines. It lives in this host-only module because
 * AWT does not exist on Android and must not end up in the app's dex; the app has its own codec
 * on `Bitmap`.
 */
class JvmImageCodec : ImageCodec {
    override fun decode(bytes: ByteArray): PixelImage {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IllegalArgumentException("not a decodable image (${bytes.size} bytes)")
        val w = image.width
        val h = image.height
        val pixels = IntArray(w * h)
        image.getRGB(0, 0, w, h, pixels, 0, w)
        return PixelImage(w, h, pixels)
    }

    override fun encode(image: PixelImage, format: ImageFormat, quality: Int): ByteArray {
        val type = if (format == ImageFormat.JPEG) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
        val buffered = BufferedImage(image.width, image.height, type)
        buffered.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        val out = ByteArrayOutputStream()
        when (format) {
            ImageFormat.PNG -> ImageIO.write(buffered, "png", out)
            ImageFormat.JPEG -> {
                val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality.coerceIn(1, 100) / 100f
                }
                writer.output = ImageIO.createImageOutputStream(out)
                writer.write(null, IIOImage(buffered, null, null), params)
                writer.dispose()
            }
        }
        return out.toByteArray()
    }
}
