package app.umbra.agent.ui.files

/** Bounds-checked CFB streams are enough for Word's piece table, without executing embedded content. */
internal class CompoundFile(private val bytes: ByteArray) {
    private val sectorSize: Int
    private val miniSize: Int
    private val fat: List<Int>
    private val miniFat: List<Int>
    private val miniStream: ByteArray
    private val directory: List<Entry>
    private data class Entry(val name: String, val type: Int, val start: Int, val size: Int)

    init {
        require(bytes.size in 512..MAX_FILE_BYTES)
        require(bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte())))
        require(bytes.u16(28) == 0xfffe && bytes.u16(30) in setOf(9, 12) && bytes.u16(32) == 6)
        sectorSize = 1 shl bytes.u16(30)
        miniSize = 1 shl bytes.u16(32)
        require(bytes.i32(56) == 4096)
        val sectors = mutableListOf<Int>()
        repeat(109) { i -> bytes.i32(76 + i * 4).takeIf { it >= 0 }?.let { sectors += it } }
        var next = bytes.i32(68)
        val visited = mutableSetOf<Int>()
        repeat(bytes.i32(72).also { require(it in 0..bytes.size / sectorSize) }) {
            require(visited.add(next))
            val block = sector(next)
            repeat(sectorSize / 4 - 1) { i -> block.i32(i * 4).takeIf { it >= 0 }?.let { sectors += it } }
            next = block.i32(sectorSize - 4)
        }
        require(sectors.size == bytes.i32(44) && sectors.distinct().size == sectors.size)
        fat = sectors.flatMap { id -> val block = sector(id); List(sectorSize / 4) { block.i32(it * 4) } }
        val dirs = chain(bytes.i32(48), fat, sectorSize, ::sector)
        directory = (0 until dirs.size / 128).mapNotNull { i ->
            val p = i * 128
            val type = dirs[p + 66].toInt()
            if (type !in setOf(2, 5)) null else {
                val nameLength = dirs.u16(p + 64)
                require(nameLength in 2..64 && nameLength % 2 == 0)
                require(dirs.i32(p + 124) == 0)
                val length = dirs.i32(p + 120)
                require(length in 0..MAX_FILE_BYTES)
                Entry(dirs.copyOfRange(p, p + nameLength - 2).toString(Charsets.UTF_16LE), type, dirs.i32(p + 116), length)
            }
        }
        val root = directory.single { it.type == 5 }
        miniStream = if (root.size == 0) byteArrayOf() else chain(root.start, fat, sectorSize, ::sector).also { require(it.size >= root.size) }.copyOf(root.size)
        val miniCount = bytes.i32(64)
        require(miniCount in 0..bytes.size / sectorSize)
        val mini = if (miniCount == 0) byteArrayOf() else chain(bytes.i32(60), fat, sectorSize, ::sector)
        require(mini.size == miniCount * sectorSize)
        miniFat = List(mini.size / 4) { mini.i32(it * 4) }
    }

    private fun sector(id: Int): ByteArray {
        require(id >= 0 && id < bytes.size / sectorSize - 1)
        val offset = (id + 1) * sectorSize
        return bytes.copyOfRange(offset, offset + sectorSize)
    }

    private fun chain(start: Int, table: List<Int>, blockSize: Int, block: (Int) -> ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val visited = mutableSetOf<Int>()
        var next = start
        while (next != -2) {
            require(next in table.indices && visited.add(next) && out.size() <= MAX_FILE_BYTES - blockSize)
            out.write(block(next)); next = table[next]
        }
        return out.toByteArray()
    }

    fun stream(name: String): ByteArray? {
        val entry = directory.firstOrNull { it.name == name && it.type == 2 } ?: return null
        if (entry.size == 0) return byteArrayOf()
        val data = if (entry.size < 4096) chain(entry.start, miniFat, miniSize) { id ->
            require(id >= 0 && id < miniStream.size / miniSize)
            miniStream.copyOfRange(id * miniSize, (id + 1) * miniSize)
        } else chain(entry.start, fat, sectorSize, ::sector)
        require(data.size >= entry.size)
        return data.copyOf(entry.size)
    }
}

internal object LegacyDoc {
    fun text(bytes: ByteArray): String? = runCatching {
        val compound = CompoundFile(bytes)
        val word = compound.stream("WordDocument") ?: return null
        require(word.u16(0) == 0xa5ec && word.u16(2) >= 0xc1)
        val flags = word.u16(10)
        require(flags and 0x8100 == 0)
        val table = compound.stream(if (flags and 0x200 != 0) "1Table" else "0Table") ?: return null
        var p = 32
        p += 2 + word.u16(p) * 2
        p += 2 + word.u16(p) * 4
        require(word.u16(p) > 33)
        p += 2 + 33 * 8
        val offset = word.i32(p)
        val size = word.i32(p + 4)
        require(offset >= 0 && size > 0 && size <= table.size - offset)
        pieceTable(word, table.copyOfRange(offset, offset + size))
    }.getOrNull()?.takeIf { it.isNotBlank() }

    internal fun pieceTable(word: ByteArray, clx: ByteArray): String {
        var p = 0
        while (p < clx.size && clx[p] == 1.toByte()) p += 3 + clx.u16(p + 1)
        require(p < clx.size && clx[p] == 2.toByte())
        val length = clx.i32(p + 1)
        p += 5
        require(length >= 4 && (length - 4) % 12 == 0 && length <= clx.size - p)
        val pieces = (length - 4) / 12
        require(pieces <= 100_000)
        return buildString {
            repeat(pieces) { i ->
                val from = clx.i32(p + i * 4)
                val to = clx.i32(p + (i + 1) * 4)
                require(from >= 0 && to >= from && to - from <= MAX_FILE_BYTES / 2)
                val fc = clx.i32(p + (pieces + 1) * 4 + i * 8 + 2)
                val compressed = fc and 0x40000000 != 0
                val start = (fc and 0x3fffffff) / if (compressed) 2 else 1
                val count = (to - from) * if (compressed) 1 else 2
                require(start >= 0 && count <= word.size - start && this.length + count <= MAX_FILE_BYTES)
                append(word.copyOfRange(start, start + count).toString(if (compressed) charset("windows-1252") else Charsets.UTF_16LE))
            }
        }.replace('\r', '\n').replace('\u0007', '\t').filter { it == '\n' || it == '\t' || it.code >= 32 }
    }
}
