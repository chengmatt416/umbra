package app.umbra.runtime.linux

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Archives made by the system's own `tar` (bsdtar on macOS, GNU tar on Linux), read back by [TarReader]. */
class TarReaderTest {
    @TempDir
    lateinit var dir: File

    private fun tar(source: File, vararg args: String): File {
        val out = File(dir, "a-${System.nanoTime()}.tar")
        val p = ProcessBuilder(listOf("tar") + args + listOf("-cf", out.path, "-C", source.path, ".")).redirectErrorStream(true)
        p.environment()["COPYFILE_DISABLE"] = "1"
        val proc = p.start()
        val log = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        check(proc.waitFor() == 0) { log }
        return out
    }

    private fun mode(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))

    @Test
    fun filesFoldersLinksModesAndLongNamesComeBackAsTheyWere() {
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "usr/bin").mkdirs()
        File(src, "usr/bin/tool").apply { writeText("#!/bin/sh\necho hi\n"); setExecutable(true, false) }
        File(src, "etc").mkdirs()
        File(src, "etc/passwd").writeText("root:x:0:0::/root:/bin/bash\n")
        Files.createSymbolicLink(File(src, "bin").toPath(), File("usr/bin").toPath())
        val deep = "x".repeat(60) + "/" + "y".repeat(60) + "/" + "z".repeat(80) + ".txt" // longer than 100 bytes
        File(src, deep).apply { parentFile.mkdirs(); writeText("deep") }
        File(src, "big.bin").writeBytes(ByteArray(1_000_003) { (it % 251).toByte() })
        File(src, "locked").mkdirs()
        File(src, "locked/inside").writeText("still extracted")
        Files.setPosixFilePermissions(File(src, "locked").toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))

        val archive = tar(src)
        Files.setPosixFilePermissions(File(src, "locked").toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        val out = File(dir, "out")
        val count = archive.inputStream().buffered().use { TarReader.extract(it, out) }

        assertTrue(count >= 9, "entries: $count")
        assertEquals("#!/bin/sh\necho hi\n", File(out, "usr/bin/tool").readText())
        assertTrue(mode(File(out, "usr/bin/tool")).startsWith("rwx"), mode(File(out, "usr/bin/tool")))
        assertEquals("usr/bin", Files.readSymbolicLink(File(out, "bin").toPath()).toString())
        assertEquals("#!/bin/sh\necho hi\n", File(out, "bin/tool").readText(), "the link works")
        assertEquals("deep", File(out, deep).readText())
        assertTrue(File(out, "big.bin").readBytes().contentEquals(ByteArray(1_000_003) { (it % 251).toByte() }))
        assertEquals("still extracted", File(out, "locked/inside").readText(), "a read-only folder is filled before its mode is applied")
        assertEquals("r-x", mode(File(out, "locked")).substring(3, 6))
    }

    @Test
    fun aPaxArchiveWithAVeryLongNameIsRead() {
        val src = File(dir, "pax").apply { mkdirs() }
        val name = (1..12).joinToString("/") { "segment-$it-" + "q".repeat(20) } + "/file"
        File(src, name).apply { parentFile.mkdirs(); writeText("pax") }
        val archive = try { tar(src, "--format", "pax") } catch (e: IllegalStateException) { tar(src, "--format=pax") }
        val out = File(dir, "pax-out")
        archive.inputStream().buffered().use { TarReader.extract(it, out) }
        assertEquals("pax", File(out, name).readText())
    }

    // ---- archives built by hand, so the checks do not depend on which tar made them ----

    private fun entry(name: String, type: Char, data: ByteArray = ByteArray(0), link: String = "", mode: String = "0000644"): ByteArray {
        val h = header(name, data.size, type, link, mode)
        val pad = (512 - data.size % 512) % 512
        return h + data + ByteArray(pad)
    }

    private fun archive(vararg entries: ByteArray): File = File(dir, "hand-${System.nanoTime()}.tar").apply {
        writeBytes(entries.fold(ByteArray(0)) { a, b -> a + b } + ByteArray(1024))
    }

    @Test
    fun nothingCanBeWrittenOutsideTheFolderThroughALink() {
        val outside = File(dir, "outside").apply { mkdirs() }
        val evil = archive(entry("escape", '2', link = outside.path), entry("escape/planted.txt", '0', "bad".toByteArray()))
        assertFailsWith<IOException> { evil.inputStream().use { TarReader.extract(it, File(dir, "evil-out")) } }
        assertTrue(!File(outside, "planted.txt").exists(), "nothing landed outside")
    }

    @Test
    fun aHardLinkCannotCopyAFileFromOutsideIn() {
        val secret = File(dir, "secret.txt").apply { writeText("private") }
        val dotdot = archive(entry("copy.txt", '1', link = "../secret.txt"))
        assertFailsWith<IOException> { dotdot.inputStream().use { TarReader.extract(it, File(dir, "out-a")) } }
        val viaLink = archive(entry("up", '2', link = dir.path), entry("copy.txt", '1', link = "up/secret.txt"))
        assertFailsWith<IOException> { viaLink.inputStream().use { TarReader.extract(it, File(dir, "out-b")) } }
        assertTrue(!File(dir, "out-a/copy.txt").exists() && !File(dir, "out-b/copy.txt").exists())
        assertEquals("private", secret.readText())
    }

    @Test
    fun aFolderReplacedByALinkDoesNotPassItsModeThrough() {
        val outside = File(dir, "victim").apply { mkdirs() }
        TarReader.setMode(outside, "700".toInt(8))
        // A world-writable folder entry, then the same name as a link to a folder outside.
        val evil = archive(entry("shared", '5', mode = "0000777"), entry("shared", '2', link = outside.path))
        runCatching { evil.inputStream().use { TarReader.extract(it, File(dir, "out-c")) } }
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(outside.toPath())), "the outside folder kept its own mode")
    }

    /** A minimal ustar header: a regular file ('0'), hard link ('1'), symbolic link ('2') or folder ('5'). */
    private fun header(name: String, size: Int, type: Char = '0', link: String = "", mode: String = "0000644"): ByteArray {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        "$mode\u0000".toByteArray().copyInto(h, 100)
        "0000000\u0000".toByteArray().copyInto(h, 108)
        "0000000\u0000".toByteArray().copyInto(h, 116)
        String.format("%011o\u0000", size).toByteArray().copyInto(h, 124)
        "00000000000\u0000".toByteArray().copyInto(h, 136)
        h[156] = type.code.toByte()
        link.toByteArray().copyInto(h, 157)
        "ustar\u000000".toByteArray().copyInto(h, 257)
        "        ".toByteArray().copyInto(h, 148)
        val sum = h.sumOf { it.toInt() and 0xFF }
        String.format("%06o\u0000 ", sum).toByteArray().copyInto(h, 148)
        return h
    }
}
