package app.umbra.agent.ui.files

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class FileTooLarge : Exception()
internal class FileNotAllowed : Exception()
internal class FileChanged : Exception()

/** Refuse stale edits and recheck confinement before replacing the original in the same directory. */
internal fun atomicFileEdit(file: File, roots: List<Path>, expected: ByteArray, bytes: ByteArray) {
    if (bytes.size > MAX_FILE_BYTES) throw FileTooLarge()
    fun verify() {
        val path = file.toPath().toRealPath()
        if (path != file.toPath().toAbsolutePath().normalize() || roots.none { path.startsWith(it) } || !Files.isRegularFile(path)) throw FileNotAllowed()
        if (file.length() > MAX_FILE_BYTES) throw FileTooLarge()
        val current = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            var size = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > MAX_FILE_BYTES) throw FileTooLarge()
                digest.update(buffer, 0, count)
            }
            digest.digest()
        }
        if (!MessageDigest.isEqual(expected, current)) throw FileChanged()
    }
    verify()
    val permissions = try { Files.getPosixFilePermissions(file.toPath()) } catch (_: UnsupportedOperationException) { null }
    val temp = File.createTempFile(".umbra-edit-", ".tmp", file.parentFile)
    try {
        permissions?.let { Files.setPosixFilePermissions(temp.toPath(), it) }
        temp.outputStream().use { it.write(bytes); it.fd.sync() }
        verify()
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temp.delete() }
}
