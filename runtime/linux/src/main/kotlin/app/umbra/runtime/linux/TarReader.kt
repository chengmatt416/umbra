package app.umbra.runtime.linux

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * Reads a tar stream (POSIX ustar, with the GNU long-name and PAX extensions `docker export` and `tar` write) one
 * entry at a time. Used where no root `tar` is available to unpack the Linux environment (ADR 0012): the files then
 * belong to whoever extracts them, so owners are read but not applied.
 */
class TarReader(private val input: InputStream) {
    enum class Type { FILE, DIRECTORY, SYMLINK, HARDLINK, OTHER }

    class Entry(val name: String, val type: Type, val mode: Int, val size: Long, val linkName: String)

    private val header = ByteArray(BLOCK)
    private var remaining = 0L // bytes of the current entry's data not read yet
    private var padding = 0L

    /** The next entry, or null at the end of the archive. Skips whatever was left of the previous entry's data. */
    fun next(): Entry? {
        skip(remaining + padding)
        remaining = 0
        padding = 0
        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String> = emptyMap()
        while (true) {
            if (!readBlock(header)) return null
            if (header.all { it == 0.toByte() }) return null // the end: two zero blocks (the second is not needed)
            val type = header[156].toInt().toChar()
            val size = paxSize(pax) ?: number(124, 12)
            when (type) {
                'L' -> { longName = readString(size); continue }
                'K' -> { longLink = readString(size); continue }
                'x' -> { pax = parsePax(readBytes(size)); continue }
                'g' -> { readBytes(size); continue } // global PAX headers: nothing we use
            }
            val name = pax["path"] ?: longName ?: ustarName()
            val link = pax["linkpath"] ?: longLink ?: string(157, 100)
            remaining = size
            padding = (BLOCK - size % BLOCK) % BLOCK
            val kind = when (type) {
                '0', '\u0000', '7' -> if (name.endsWith("/")) Type.DIRECTORY else Type.FILE
                '5' -> Type.DIRECTORY
                '2' -> Type.SYMLINK
                '1' -> Type.HARDLINK
                else -> Type.OTHER
            }
            return Entry(name.trimEnd('/'), kind, number(100, 8).toInt() and 0xFFF, if (kind == Type.FILE) size else 0, link)
        }
    }

    /** The current entry's data. Reading past its end returns -1. */
    val data: InputStream = object : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = input.read()
            if (b < 0) throw EOFException("the archive ended inside an entry")
            remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("the archive ended inside an entry")
            remaining -= n
            return n
        }
    }

    private fun ustarName(): String {
        val name = string(0, 100)
        val magic = String(header, 257, 5, Charsets.US_ASCII)
        val prefix = if (magic == "ustar") string(345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun string(offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    /** An octal field, or the base-256 form GNU tar uses for values that do not fit. */
    private fun number(offset: Int, length: Int): Long {
        if (header[offset].toInt() and 0x80 != 0) {
            var v = (header[offset].toLong() and 0x7F)
            for (i in offset + 1 until offset + length) v = (v shl 8) or (header[i].toLong() and 0xFF)
            return v
        }
        val text = String(header, offset, length, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
        return if (text.isEmpty()) 0 else text.toLong(8)
    }

    private fun paxSize(pax: Map<String, String>): Long? = pax["size"]?.toLongOrNull()

    private fun parsePax(bytes: ByteArray): Map<String, String> {
        // Records are "<length> <key>=<value>\n", the length counting the whole record in bytes.
        val out = HashMap<String, String>()
        var pos = 0
        while (pos < bytes.size) {
            var space = pos
            while (space < bytes.size && bytes[space] != ' '.code.toByte()) space++
            val length = String(bytes, pos, space - pos, Charsets.US_ASCII).toIntOrNull() ?: break
            if (length <= 0 || pos + length > bytes.size) break
            val record = String(bytes, space + 1, pos + length - space - 2, Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            pos += length
        }
        return out
    }

    private fun readString(size: Long): String = String(readBytes(size), Charsets.UTF_8).trimEnd('\u0000')

    private fun readBytes(size: Long): ByteArray {
        require(size in 0..(16L shl 20)) { "an extended header of $size bytes" }
        val bytes = ByteArray(size.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) throw EOFException("the archive ended inside a header")
            read += n
        }
        skip((BLOCK - size % BLOCK) % BLOCK)
        return bytes
    }

    private fun readBlock(into: ByteArray): Boolean {
        var read = 0
        while (read < BLOCK) {
            val n = input.read(into, read, BLOCK - read)
            if (n < 0) {
                if (read == 0) return false
                throw EOFException("the archive ended inside a header")
            }
            read += n
        }
        return true
    }

    private fun skip(count: Long) {
        var left = count
        val scratch = ByteArray(8192)
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) throw EOFException("the archive ended inside an entry")
            left -= n
        }
    }

    companion object {
        const val BLOCK = 512

        /**
         * Extracts [input] into [destination] as the current user. Nothing may land outside [destination]: names
         * with `..` are refused, and so is a file whose folder resolves (through links) to somewhere else. Folder
         * modes are applied last so a read-only folder can still be filled. Returns the number of entries written.
         */
        fun extract(input: InputStream, destination: File): Int {
            val root = destination.canonicalFile.also { it.mkdirs() }
            val reader = TarReader(input)
            val folders = ArrayList<Pair<File, Int>>()
            var count = 0
            while (true) {
                val e = reader.next() ?: break
                val name = e.name.removePrefix("./").trimStart('/')
                if (name.isEmpty() || name == ".") continue
                if (name.split('/').any { it == ".." }) throw IOException("the archive names a path outside its folder: ${e.name}")
                val target = File(root, name)
                val parent = target.parentFile ?: root
                parent.mkdirs()
                if (!inside(root, parent.canonicalFile)) {
                    throw IOException("the archive reaches outside its folder through a link: ${e.name}")
                }
                val path = target.toPath()
                when (e.type) {
                    Type.DIRECTORY -> {
                        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                            Files.deleteIfExists(path)
                            Files.createDirectory(path)
                        }
                        folders += target to e.mode
                    }
                    Type.FILE -> {
                        Files.deleteIfExists(path)
                        Files.newOutputStream(path).use { out -> reader.data.copyTo(out, 256 * 1024) }
                        setMode(target, e.mode or 0b110_000_000)
                    }
                    Type.SYMLINK -> {
                        Files.deleteIfExists(path)
                        Files.createSymbolicLink(path, Paths.get(e.linkName))
                    }
                    Type.HARDLINK -> {
                        // A copy: hard links between app files are not allowed everywhere on Android. The source must
                        // be inside the folder too, or the archive could read files from elsewhere into it.
                        val link = e.linkName.removePrefix("./").trimStart('/')
                        if (link.split('/').any { it == ".." }) throw IOException("the archive links to a path outside its folder: ${e.linkName}")
                        val source = File(root, link)
                        if (!inside(root, source.canonicalFile)) throw IOException("the archive links outside its folder through a link: ${e.linkName}")
                        Files.deleteIfExists(path)
                        Files.copy(source.toPath(), path)
                    }
                    Type.OTHER -> continue // devices and fifos: the environment binds the real /dev
                }
                count++
            }
            // A folder a later entry replaced (with a link, for instance) keeps no mode from the archive: changing the
            // mode through a link would change something outside the folder.
            for ((folder, mode) in folders.asReversed()) {
                if (Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS) && inside(root, folder.canonicalFile)) setMode(folder, mode or 0b111_000_000)
            }
            return count
        }

        private fun inside(root: File, f: File): Boolean = f.path == root.path || f.path.startsWith(root.path + File.separator)

        fun setMode(file: File, mode: Int) {
            val perms = HashSet<PosixFilePermission>()
            val bits = listOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
            )
            for ((i, p) in bits.withIndex()) if (mode and (1 shl (8 - i)) != 0) perms += p
            Files.setPosixFilePermissions(file.toPath(), perms)
        }
    }
}
