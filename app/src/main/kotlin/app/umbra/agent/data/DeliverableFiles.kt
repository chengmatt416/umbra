package app.umbra.agent.data

import app.umbra.agent.AppContainer
import app.umbra.core.session.Session
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A published artifact leaves the task's workspace only as a private copy whose bytes still match its receipt. */
internal object DeliverableFiles {
    internal suspend fun copyVerifiedProof(container: AppContainer, session: Session, proof: JsonObject, parent: File): File {
        val path = proof["path"]?.jsonPrimitive?.content ?: error("Missing visual proof path")
        val artifacts = container.workspaces.sessionDirs(session.id).artifacts
        val host = container.workspaceLayout.toHost(path) ?: Path.of(path).let { if (it.isAbsolute) it else artifacts.resolve(it) }
        return copyVerified(host, listOf(artifacts), proof, parent, 8_000_000L)
    }

    /** Copies [artifact] of [session] into a fresh directory under [parent]; fails if it moved, grew or changed. */
    suspend fun copyVerified(container: AppContainer, session: Session, artifact: JsonObject, parent: File, maxBytes: Long = 500_000_000L): File {
        val path = artifact["path"]?.jsonPrimitive?.content ?: error("Missing artifact path")
        val layout = container.workspaceLayout
        val dirs = container.workspaces.sessionDirs(session.id)
        val host = layout.toHost(path) ?: Path.of(path).let { if (it.isAbsolute) it else (session.workspaceId?.let(layout::projectDir) ?: dirs.files).resolve(it) }
        val roots = listOf(dirs.root) + listOfNotNull(session.workspaceId?.let(layout::projectDir))
        return copyVerified(host, roots, artifact, parent, maxBytes)
    }

    internal fun copyVerified(host: Path, roots: List<Path>, artifact: JsonObject, parent: File, maxBytes: Long): File {
        require(maxBytes in 1..500_000_000L)
        val expected = artifact["sha256"]?.jsonPrimitive?.content
        require(expected?.matches(Regex("[a-fA-F0-9]{64}")) == true) { "Republish this artifact with a verification receipt before sharing it" }
        val expectedSize = artifact["sizeBytes"]?.jsonPrimitive?.longOrNull
        require(expectedSize != null && expectedSize in 1..maxBytes) { "The artifact size is unavailable or exceeds the sharing limit" }
        val real = host.toRealPath()
        require(roots.any { Files.exists(it) && real.startsWith(it.toRealPath()) } && Files.isRegularFile(real)) { "The artifact is outside this task's workspace" }
        require(Files.size(real) == expectedSize) { "The artifact changed since it was published" }
        val targetDir = File(parent, java.util.UUID.randomUUID().toString()).apply { check(mkdirs()) }
        val target = File(targetDir, real.fileName.toString().replace(Regex("[^A-Za-z0-9._-]"), "_").take(150).ifBlank { "artifact" })
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS).use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= maxBytes); digest.update(buffer, 0, n); output.write(buffer, 0, n) }
            } }
            check(total == expectedSize && digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)) { "The artifact changed since it was published. Ask the agent to verify and publish it again." }
            return target
        } catch (e: Exception) {
            target.delete(); targetDir.delete(); throw e
        }
    }
}
