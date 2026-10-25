package app.umbra.platform.privileged

import app.umbra.platform.api.BoundedLineReader
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BoundedLineReaderTest {
    @Test
    fun byteLimitsApplyToUnicodeAndUnterminatedFrames() {
        assertEquals("éé", BoundedLineReader(ByteArrayInputStream("éé\n".toByteArray())).readLine(4))
        assertFailsWith<IOException> { BoundedLineReader(ByteArrayInputStream("ééé\n".toByteArray())).readLine(4) }
        assertFailsWith<IOException> { BoundedLineReader(ByteArrayInputStream(ByteArray(1024) { 65 })).readLine(100) }
        assertFailsWith<IOException> { BoundedLineReader(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), '\n'.code.toByte()))).readLine(4) }
    }

    @Test
    fun bufferedLinesCrLfAndEofArePreserved() {
        val reader = BoundedLineReader(ByteArrayInputStream("one\r\ntwo\nlast".toByteArray()))
        assertEquals("one", reader.readLine(10))
        assertEquals("two", reader.readLine(10))
        assertEquals("last", reader.readLine(10))
        assertNull(reader.readLine(10))
    }
}
