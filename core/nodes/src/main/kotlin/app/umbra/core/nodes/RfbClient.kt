package app.umbra.core.nodes

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Bounded RFB 3.3/3.7/3.8 client. Runs only inside the node's certificate-pinned TLS tunnel. */
class RfbClient(input: InputStream, output: OutputStream, password: CharArray = charArrayOf()) {
    private val source = DataInputStream(input)
    private val sink = DataOutputStream(output)
    var width: Int = 0; private set
    var height: Int = 0; private set
    var name: String = ""; private set
    private var pixels = IntArray(0)

    init {
        val version = ByteArray(12).also(source::readFully).toString(Charsets.US_ASCII)
        require(version.matches(Regex("RFB 003\\.00[378]\\n"))) { "Unsupported remote screen protocol" }
        val minor = version.substring(8, 11).toInt()
        sink.write(version.toByteArray(Charsets.US_ASCII)); sink.flush()
        val security = if (minor == 3) source.readInt() else {
            val count = source.readUnsignedByte()
            require(count > 0) { reason() }
            val types = ByteArray(count).also(source::readFully)
            val selected = if (1.toByte() in types) 1 else if (2.toByte() in types) 2 else error("Unsupported VNC authentication; select the node's own screen backend")
            sink.writeByte(selected); sink.flush(); selected
        }
        when (security) {
            1 -> if (minor >= 8) require(source.readInt() == 0) { "VNC authentication failed" }
            2 -> {
                require(password.isNotEmpty()) { "This VNC backend requires a password" }
                val challenge = ByteArray(16).also(source::readFully)
                val key = ByteArray(8) { i -> if (i < password.size) Integer.reverse(password[i].code and 255).ushr(24).toByte() else 0 }
                try {
                    val cipher = Cipher.getInstance("DES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES")) }
                    sink.write(cipher.doFinal(challenge)); sink.flush()
                    require(source.readInt() == 0) { "VNC password was refused" }
                } finally { key.fill(0); password.fill('\u0000') }
            }
            else -> error("VNC security was refused")
        }
        sink.writeByte(1); sink.flush()
        resize(source.readUnsignedShort(), source.readUnsignedShort())
        source.readFully(ByteArray(16))
        name = reason()
        // Request 32-bit little-endian RGB; raw, CopyRect and DesktopSize are deliberately sufficient.
        sink.write(byteArrayOf(0, 0, 0, 0, 32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0))
        sink.writeByte(2); sink.writeByte(0); sink.writeShort(3); sink.writeInt(0); sink.writeInt(1); sink.writeInt(-223); sink.flush()
    }

    private fun reason(): String {
        val size = source.readInt()
        require(size in 0..65536) { "Invalid RFB text size" }
        return ByteArray(size).also(source::readFully).toString(Charsets.UTF_8)
    }

    private fun resize(w: Int, h: Int) {
        require(w in 1..8192 && h in 1..8192 && w.toLong() * h <= 16_000_000) { "Remote screen is too large" }
        width = w; height = h; pixels = IntArray(w * h)
    }

    fun update(): IntArray {
        sink.writeByte(3); sink.writeByte(0); sink.writeShort(0); sink.writeShort(0); sink.writeShort(width); sink.writeShort(height); sink.flush()
        while (true) {
            when (source.readUnsignedByte()) {
                0 -> {
                    source.readUnsignedByte()
                    val count = source.readUnsignedShort()
                    require(count <= 4096) { "Too many screen rectangles" }
                    repeat(count) {
                        val x = source.readUnsignedShort(); val y = source.readUnsignedShort()
                        val w = source.readUnsignedShort(); val h = source.readUnsignedShort()
                        val encoding = source.readInt()
                        if (encoding == -223) resize(w, h) else {
                            require(w > 0 && h > 0 && x.toLong() + w <= width && y.toLong() + h <= height) { "Invalid screen rectangle" }
                            when (encoding) {
                                0 -> repeat(h) { row -> repeat(w) { col ->
                                    val b = source.readUnsignedByte(); val g = source.readUnsignedByte(); val r = source.readUnsignedByte(); source.readUnsignedByte()
                                    pixels[(y + row) * width + x + col] = (0xff000000.toInt() or (r shl 16) or (g shl 8) or b)
                                } }
                                1 -> {
                                    val sx = source.readUnsignedShort(); val sy = source.readUnsignedShort()
                                    require(sx.toLong() + w <= width && sy.toLong() + h <= height) { "Invalid copy rectangle" }
                                    val rows = if (y > sy) (h - 1 downTo 0) else (0 until h)
                                    for (row in rows) pixels.copyInto(pixels, (y + row) * width + x, (sy + row) * width + sx, (sy + row) * width + sx + w)
                                }
                                else -> error("Unsupported screen encoding $encoding")
                            }
                        }
                    }
                    return pixels.copyOf()
                }
                2 -> Unit // bell
                3 -> { source.readFully(ByteArray(3)); reason() }
                else -> error("Unsupported remote screen message")
            }
        }
    }

    fun pointer(x: Int, y: Int, buttons: Int = 0) {
        require(x in 0 until width && y in 0 until height && buttons in 0..255) { "Pointer is outside the screen" }
        sink.writeByte(5); sink.writeByte(buttons); sink.writeShort(x); sink.writeShort(y); sink.flush()
    }

    fun key(keysym: Int, down: Boolean) {
        sink.writeByte(4); sink.writeByte(if (down) 1 else 0); sink.writeShort(0); sink.writeInt(keysym); sink.flush()
    }
}
