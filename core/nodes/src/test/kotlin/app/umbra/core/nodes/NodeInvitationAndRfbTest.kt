package app.umbra.core.nodes

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.*

class NodeInvitationAndRfbTest {
    private val now = 1_000_000L
    private fun invite() = NodeInvitation(name = "Matt 的電腦", fingerprint = "a".repeat(64), code = "K7Q2-9FXA-M3PD", expiresAtMs = now + 600_000,
        addresses = listOf(NodeAddress("192.168.1.20"), NodeAddress("fd7a:115c:a1e0::1", kind = "vpn")))

    @Test fun invitationRoundTripsUnicodeAndMultiplePinnedAddresses() {
        assertEquals(invite(), NodeInvitation.parse(invite().uri(), now))
        assertEquals("[fd7a:115c:a1e0::1]:47100", invite().addresses.last().label())
    }
    @Test fun refusesExpiredUnpinnedForeignAndOversizedInvitations() {
        assertFailsWith<IllegalArgumentException> { NodeInvitation.parse(invite().uri(), now + 600_000) }
        assertFailsWith<IllegalArgumentException> { invite().copy(fingerprint = "").validate(now) }
        assertFailsWith<IllegalArgumentException> { invite().copy(addresses = listOf(NodeAddress("evil/path"))).validate(now) }
        assertFailsWith<IllegalArgumentException> { NodeInvitation.parse("https://example.com/?data=test", now) }
        assertFailsWith<IllegalArgumentException> { NodeInvitation.parse("x".repeat(8193), now) }
    }
    @Test fun goAndKotlinUseTheSameIndependentPairingVector() {
        assertEquals("084d2795bba7c0717bf472e3a70e096f5c4b08911bf034c6e35cc62062cd69dc", Pairing.phoneProof("k7q2 9fxa m3pd", "a".repeat(64), "b".repeat(64)))
        assertEquals("c5fcfcc6fb94ab3ba2b5b771c7d681b167bf627955a87e093845316f7fe8a7b0", Pairing.nodeProof("K7Q2-9FXA-M3PD", "a".repeat(64), "b".repeat(64)))
    }
    private fun server(width: Int = 2, height: Int = 1, body: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write("RFB 003.008\n".toByteArray()); write(byteArrayOf(1, 1)); writeInt(0)
            writeShort(width); writeShort(height); write(ByteArray(16)); writeInt(4); write("Test".toByteArray()); body()
        }
        return bytes.toByteArray()
    }
    @Test fun decodesActualRawFramebufferAndEncodesPointerAndUnicodeKeys() {
        val input = server { writeByte(0); writeByte(0); writeShort(1); writeShort(0); writeShort(0); writeShort(2); writeShort(1); writeInt(0); write(byteArrayOf(3, 2, 1, 0, 6, 5, 4, 0)) }
        val output = ByteArrayOutputStream()
        val client = RfbClient(ByteArrayInputStream(input), output)
        assertEquals("Test", client.name)
        assertContentEquals(intArrayOf(0xff010203.toInt(), 0xff040506.toInt()), client.update())
        client.pointer(1, 0, 1); client.key(0x01004e2d, true)
        assertContentEquals(byteArrayOf(5, 1, 0, 1, 0, 0, 4, 1, 0, 0, 1, 0, 0x4e, 0x2d), output.toByteArray().takeLast(14).toByteArray())
        assertFailsWith<IllegalArgumentException> { client.pointer(2, 0) }
    }
    @Test fun copyRectHandlesOverlappingRows() {
        val input = server(1, 3) {
            writeByte(0); writeByte(0); writeShort(1); writeShort(0); writeShort(0); writeShort(1); writeShort(3); writeInt(0)
            repeat(3) { write(byteArrayOf((it + 1).toByte(), 0, 0, 0)) }
            writeByte(0); writeByte(0); writeShort(1); writeShort(0); writeShort(1); writeShort(1); writeShort(2); writeInt(1); writeShort(0); writeShort(0)
        }
        val client = RfbClient(ByteArrayInputStream(input), ByteArrayOutputStream()); client.update()
        assertContentEquals(intArrayOf(0xff000001.toInt(), 0xff000001.toInt(), 0xff000002.toInt()), client.update())
    }
    @Test fun rejectsUnboundedFramebuffersAndOutOfBoundsRectangles() {
        assertFailsWith<IllegalArgumentException> { RfbClient(ByteArrayInputStream(server(8192, 8192) {}), ByteArrayOutputStream()) }
        val client = RfbClient(ByteArrayInputStream(server { writeByte(0); writeByte(0); writeShort(1); writeShort(1); writeShort(0); writeShort(2); writeShort(1); writeInt(0) }), ByteArrayOutputStream())
        assertFailsWith<IllegalArgumentException> { client.update() }
    }
}
