package app.umbra.agent.ui.files

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class AtomicFileEditTest {
    @Test fun atomicSaveRefusesStaleBytesAndEscapingSymlinks() {
        val root = Files.createTempDirectory("file-editor-").toRealPath()
        try {
            val workspace = Files.createDirectory(root.resolve("workspace"))
            val file = Files.writeString(workspace.resolve("note.txt"), "original").toFile()
            file.setExecutable(true)
            val permissions = runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()
            fun digest() = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            val initial = digest()
            atomicFileEdit(file, listOf(workspace), initial, "saved".toByteArray())
            assertEquals("saved", file.readText())
            permissions?.let { assertEquals(it, Files.getPosixFilePermissions(file.toPath())) }
            assertFailsWith<FileChanged> { atomicFileEdit(file, listOf(workspace), initial, "stale".toByteArray()) }
            assertEquals("saved", file.readText())
            assertFailsWith<FileNotAllowed> { atomicFileEdit(file, listOf(root.resolve("elsewhere")), digest(), byteArrayOf()) }
            val outside = Files.writeString(root.resolve("private.txt"), "private")
            Files.delete(file.toPath()); Files.createSymbolicLink(file.toPath(), outside)
            assertFailsWith<FileNotAllowed> { atomicFileEdit(file, listOf(workspace), digest(), "bad".toByteArray()) }
            assertEquals("private", Files.readString(outside))
            assertEquals(listOf("note.txt"), workspace.toFile().listFiles()!!.map { it.name })
        } finally { root.toFile().deleteRecursively() }
    }
}
