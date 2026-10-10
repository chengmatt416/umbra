package app.umbra.core.nodes

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
data class ManifestFile(val path: String, val bytes: Long, val sha256: String)

/**
 * What may leave the phone for one job (architecture spec §10.1): the allowlist it was built from, the files it
 * resolved to with their sizes and hashes, a ceiling, no secrets, and what the node does with them afterwards.
 */
@Serializable
data class DataManifest(
    val allowed: List<String>,
    val files: List<ManifestFile>,
    val maxBytes: Long,
    val secrets: List<String> = emptyList(),
    /** `delete-after-job`: the node removes the job's files once the phone has its result. */
    val retention: String = RETENTION_DELETE,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }

    companion object {
        const val RETENTION_DELETE = "delete-after-job"
    }
}

class ManifestException(message: String) : Exception(message)

object Manifests {
    /**
     * Resolves [allowed] (paths relative to [root]: a file, or a folder taken whole) into a manifest. Refuses anything
     * outside [root] (`..`, absolute paths, symlinks that resolve elsewhere) and anything over [maxBytes] in all.
     * Symlinks inside the tree are followed only when their target stays inside [root].
     */
    fun build(root: Path, allowed: List<String>, maxBytes: Long): DataManifest {
        val base = root.toRealPath()
        val files = LinkedHashMap<String, ManifestFile>()
        for (entry in allowed) {
            val rel = checkRelative(entry)
            val start = base.resolve(rel).normalize()
            if (!start.startsWith(base)) throw ManifestException("“$entry” is outside the job's folder")
            if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) throw ManifestException("“$entry” does not exist")
            val real = start.toRealPath()
            if (!real.startsWith(base)) throw ManifestException("“$entry” resolves outside the job's folder (a link)")
            val paths = if (Files.isDirectory(real)) Files.walk(real).use { s -> s.filter { Files.isRegularFile(it) }.toList() } else listOf(real)
            for (p in paths) {
                val target = p.toRealPath()
                if (!target.startsWith(base)) throw ManifestException("“${base.relativize(p)}” is a link to outside the job's folder")
                val name = base.relativize(p).joinToString("/")
                if (name in files) continue
                files[name] = ManifestFile(name, Files.size(target), sha256(target))
            }
        }
        val manifest = DataManifest(allowed, files.values.toList(), maxBytes)
        if (manifest.totalBytes > maxBytes) throw ManifestException("the files come to ${manifest.totalBytes} bytes, over the ceiling of $maxBytes")
        return manifest
    }

    /** A relative path with no `..`, no absolute start, no empty or `.` parts: the node uses the same check. */
    fun checkRelative(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (path.startsWith("/") || parts.isEmpty() || parts.any { it == ".." }) throw ManifestException("“$path” is not a plain relative path")
        return parts.joinToString("/")
    }

    fun sha256(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
