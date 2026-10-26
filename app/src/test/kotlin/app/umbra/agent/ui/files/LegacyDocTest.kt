package app.umbra.agent.ui.files

import kotlin.test.*

class LegacyDocTest {
    @Test fun wordPieceTableReadsCompressedAndUnicodePieces() {
        val word = ByteArray(128)
        "Hello ".toByteArray(charset("windows-1252")).copyInto(word, 20)
        "世界\r".toByteArray(Charsets.UTF_16LE).copyInto(word, 40)
        val clx = ByteArray(5 + 28)
        clx[0] = 2; clx.put32(1, 28)
        clx.put32(5, 0); clx.put32(9, 6); clx.put32(13, 9)
        clx.put32(19, 0x40000000 or 40)
        clx.put32(27, 40)
        assertEquals("Hello 世界\n", LegacyDoc.pieceTable(word, clx))
        clx.put32(27, 1000)
        assertFailsWith<IllegalArgumentException> { LegacyDoc.pieceTable(word, clx) }
    }

    @Test fun syntheticWord97CompoundFileExtractsThroughFibClxAndPieceTable() {
        val word = ByteArray(4096)
        word.put16(0, 0xa5ec); word.put16(2, 0xc1)
        word.put16(10, 0x200)
        word.put16(32, 14); word.put16(62, 22); word.put16(152, 93)
        word.put32(418, 0); word.put32(422, 21)
        val text = "Tiny Word fixture\r"
        text.toByteArray().copyInto(word, 1024)
        val table = ByteArray(4096)
        table[0] = 2; table.put32(1, 16)
        table.put32(5, 0); table.put32(9, text.length)
        table.put32(15, 0x40000000 or 2048)
        val bytes = compound(word, table)
        assertEquals("Tiny Word fixture\n", LegacyDoc.text(bytes))
        assertContentEquals(word, CompoundFile(bytes).stream("WordDocument"))
        assertNull(CompoundFile(bytes).stream("Missing"))
        word.put16(10, 0x300)
        assertNull(LegacyDoc.text(compound(word, table)))
    }

    @Test fun syntheticMiniStreamAndCyclesAreHandledSafely() {
        // FAT, directory, mini FAT, root mini stream.
        val bytes = header(4)
        val fat = 512
        bytes.put32(fat, -3); bytes.put32(fat + 4, -2); bytes.put32(fat + 8, -2); bytes.put32(fat + 12, -2)
        bytes.put32(60, 2); bytes.put32(64, 1)
        directoryEntry(bytes, 1024, "Root Entry", 5, 3, 64)
        directoryEntry(bytes, 1152, "Small", 2, 0, 5)
        repeat(128) { bytes.put32(1536 + it * 4, -1) }
        bytes.put32(1536, -2)
        "small".toByteArray().copyInto(bytes, 2048)
        assertEquals("small", CompoundFile(bytes).stream("Small")?.toString(Charsets.UTF_8))
        bytes.put32(1536, 0)
        assertFailsWith<IllegalArgumentException> { CompoundFile(bytes).stream("Small") }
        assertNull(LegacyDoc.text(byteArrayOf(1, 2, 3)))
    }

    private fun compound(word: ByteArray, table: ByteArray): ByteArray {
        val bytes = header(18)
        bytes.put32(512, -3); bytes.put32(516, -2)
        for (id in 2..9) bytes.put32(512 + id * 4, if (id == 9) -2 else id + 1)
        for (id in 10..17) bytes.put32(512 + id * 4, if (id == 17) -2 else id + 1)
        directoryEntry(bytes, 1024, "Root Entry", 5, -2, 0)
        directoryEntry(bytes, 1152, "WordDocument", 2, 2, word.size)
        directoryEntry(bytes, 1280, "1Table", 2, 10, table.size)
        word.copyInto(bytes, 1536); table.copyInto(bytes, 5632)
        return bytes
    }

    private fun header(sectors: Int): ByteArray = ByteArray((sectors + 1) * 512).apply {
        byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte()).copyInto(this)
        put16(24, 0x3e); put16(26, 3); put16(28, 0xfffe); put16(30, 9); put16(32, 6)
        put32(44, 1); put32(48, 1); put32(56, 4096); put32(60, -2); put32(68, -2)
        repeat(109) { put32(76 + it * 4, -1) }; put32(76, 0)
        repeat(128) { put32(512 + it * 4, -1) }
    }

    private fun directoryEntry(bytes: ByteArray, offset: Int, name: String, type: Int, start: Int, size: Int) {
        val text = (name + '\u0000').toByteArray(Charsets.UTF_16LE)
        text.copyInto(bytes, offset)
        bytes.put16(offset + 64, text.size); bytes[offset + 66] = type.toByte()
        bytes.put32(offset + 68, -1); bytes.put32(offset + 72, -1); bytes.put32(offset + 76, -1)
        bytes.put32(offset + 116, start); bytes.put32(offset + 120, size)
    }
}
