package app.umbra.core.workspace

import java.nio.file.Path

/**
 * The same files seen from three places: the app (host path), the Linux guest (guest path) and
 * Android shell-identity tools (staging path, because the shell uid cannot read app-private data).
 *
 * ```
 * <host>/projects/<id>/                  guest: <guest>/projects/<id>/
 * <host>/sessions/<sid>/{files,artifacts,logs}
 * <host>/.meta/workspaces.json
 * ```
 * The user never sees any of this; the UI shows workspace names and relative paths.
 */
class WorkspaceLayout(
    val hostRoot: Path,
    /** Where the guest sees [hostRoot], for example `/workspace`. */
    val guestRoot: String = "/workspace",
    /** World-readable staging directory for APKs handed to shell-identity helpers. */
    val stagingRoot: String = "/data/local/tmp/umbra",
) {
    init {
        require(guestRoot.startsWith("/") && !guestRoot.endsWith("/") || guestRoot == "/") { "guestRoot must be absolute: $guestRoot" }
    }

    val projectsDir: Path get() = hostRoot.resolve("projects")
    val sessionsDir: Path get() = hostRoot.resolve("sessions")
    val metaDir: Path get() = hostRoot.resolve(".meta")
    val registryFile: Path get() = metaDir.resolve("workspaces.json")

    fun projectDir(id: String): Path = projectsDir.resolve(id)
    fun sessionDir(sessionId: String): Path = sessionsDir.resolve(sessionId)

    fun guestProjectDir(id: String): String = "$guestRoot/projects/$id"
    fun guestSessionDir(sessionId: String): String = "$guestRoot/sessions/$sessionId"

    /** Host path -> guest path, or null if the path is outside the workspace root. */
    fun toGuest(host: Path): String? {
        val normalized = host.toAbsolutePath().normalize()
        val root = hostRoot.toAbsolutePath().normalize()
        if (!normalized.startsWith(root)) return null
        val relative = root.relativize(normalized).toString().replace('\\', '/')
        return if (relative.isEmpty()) guestRoot else "$guestRoot/$relative"
    }

    /** Guest path -> host path, or null if the path is outside the guest workspace root. */
    fun toHost(guest: String): Path? {
        val normalized = normalizeGuest(guest) ?: return null
        if (normalized != guestRoot && !normalized.startsWith("$guestRoot/")) return null
        val relative = normalized.removePrefix(guestRoot).trimStart('/')
        return if (relative.isEmpty()) hostRoot else hostRoot.resolve(relative)
    }

    /** All spellings of the roots, for risk classification of paths in commands. */
    fun knownRoots(): List<String> = listOf(hostRoot.toAbsolutePath().normalize().toString(), guestRoot)

    private fun normalizeGuest(path: String): String? {
        if (!path.startsWith("/")) return null
        val parts = ArrayDeque<String>()
        for (p in path.split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast() else return null
                else -> parts.addLast(p)
            }
        }
        return "/" + parts.joinToString("/")
    }
}
