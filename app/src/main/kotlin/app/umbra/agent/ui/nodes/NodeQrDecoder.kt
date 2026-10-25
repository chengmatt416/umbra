package app.umbra.agent.ui.nodes

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

/** Confined to the camera worker. Copy the live plane before its Image can be released. */
internal class NodeQrDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    fun decode(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): String? {
        require(width > 0 && height > 0 && width.toLong() * height <= 4_000_000)
        require(rowStride > 0 && pixelStride > 0 && rowStride.toLong() >= (width - 1L) * pixelStride + 1)
        val offset = buffer.position()
        require(offset + (height - 1L) * rowStride + (width - 1L) * pixelStride < buffer.limit()) { "Incomplete camera luminance plane" }
        val data = ByteArray(width * height)
        for (row in 0 until height) for (col in 0 until width)
            data[row * width + col] = buffer.get(offset + row * rowStride + col * pixelStride)
        var frame = data
        var frameWidth = width
        var frameHeight = height
        repeat(4) { rotation ->
            val luma = PlanarYUVLuminanceSource(frame, frameWidth, frameHeight, 0, 0, frameWidth, frameHeight, false)
            try { return reader.decodeWithState(BinaryBitmap(HybridBinarizer(luma))).text }
            catch (_: ReaderException) { /* Dense patterns can defeat the detector in one orientation. */ }
            finally { reader.reset() }
            if (rotation < 3) {
                val rotated = ByteArray(frame.size)
                for (row in 0 until frameHeight) for (col in 0 until frameWidth)
                    rotated[(frameWidth - 1 - col) * frameHeight + row] = frame[row * frameWidth + col]
                frame = rotated
                val previousWidth = frameWidth
                frameWidth = frameHeight
                frameHeight = previousWidth
            }
        }
        return null
    }
}
