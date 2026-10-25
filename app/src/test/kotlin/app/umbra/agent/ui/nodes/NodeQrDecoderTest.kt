package app.umbra.agent.ui.nodes

import app.umbra.core.nodes.NodeInvitation
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.ByteBuffer
import kotlin.test.*

class NodeQrDecoderTest {
    private val now = 1_792_802_057_242L
    private val uri = "umbra-node://pair?data=" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        """{"version":1,"name":"QR fixture","fingerprint":"${"a".repeat(64)}","code":"0123456789AB","expiresAtMs":${now + 300_000},"addresses":[{"host":"127.0.0.1","port":443}] }""".toByteArray())

    @Test fun realInvitationsDecodeFromOffsetPaddedPixelPlanesAtEveryRightAngle() {
        val code = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 480, 480)
        val decoder = NodeQrDecoder()
        for (rotation in 0..3) {
            val stride = code.width * 2 + 17
            val offset = 23
            val bytes = ByteArray(offset + stride * (code.height - 1) + (code.width - 1) * 2 + 1)
            for (y in 0 until code.height) for (x in 0 until code.width) {
                val (sx, sy) = when (rotation) {
                    0 -> x to y
                    1 -> y to (code.width - 1 - x)
                    2 -> (code.width - 1 - x) to (code.height - 1 - y)
                    else -> (code.height - 1 - y) to x
                }
                bytes[offset + y * stride + x * 2] = if (code[sx, sy]) 0 else 255.toByte()
            }
            val buffer = ByteBuffer.wrap(bytes).apply { position(offset) }
            val decoded = decoder.decode(buffer, code.width, code.height, stride, 2)
            assertEquals(uri, decoded, "Rotation $rotation")
            assertEquals("QR fixture", NodeInvitation.parse(requireNotNull(decoded), now).name)
            assertEquals(offset, buffer.position())
        }
    }
    @Test fun invalidFramesDoNotPoisonTheDecoderOrReadPastThePlane() {
        val decoder = NodeQrDecoder()
        assertNull(decoder.decode(ByteBuffer.wrap(ByteArray(100 * 100) { 255.toByte() }), 100, 100, 100, 1))
        assertFailsWith<IllegalArgumentException> { decoder.decode(ByteBuffer.wrap(ByteArray(99)), 10, 10, 10, 1) }
        assertFailsWith<IllegalArgumentException> { decoder.decode(ByteBuffer.wrap(ByteArray(100)), 10, 10, 9, 1) }
    }
}
