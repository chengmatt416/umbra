package app.umbra.platform.api

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounds UTF-8 wire bytes before allocating a full line. Socket callers must also set a finite read timeout. */
class BoundedLineReader(input: InputStream, private val strictUtf8: Boolean = true) {
    private val input = input.buffered(16 * 1024)

    fun readLine(maxBytes: Int, frameTimeoutMs: Long = 10_000): String? {
        require(maxBytes > 0 && frameTimeoutMs > 0)
        var next = input.read() // An idle socket timeout is distinguishable from a stalled partial frame.
        if (next == -1) return null
        val deadline = System.nanoTime() + frameTimeoutMs * 1_000_000
        val bytes = ByteArrayOutputStream(minOf(maxBytes, 4096))
        while (next != -1 && next != '\n'.code) {
            if (bytes.size() >= maxBytes) throw IOException("Wire frame exceeds $maxBytes bytes")
            if (System.nanoTime() >= deadline) throw IOException("Wire frame took too long")
            bytes.write(next)
            next = try { input.read() } catch (e: SocketTimeoutException) { throw IOException("Incomplete wire frame timed out", e) }
        }
        val raw = bytes.toByteArray()
        val length = if (raw.lastOrNull() == '\r'.code.toByte()) raw.size - 1 else raw.size
        if (!strictUtf8) return String(raw, 0, length, Charsets.UTF_8)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw, 0, length)).toString()
    }
}
