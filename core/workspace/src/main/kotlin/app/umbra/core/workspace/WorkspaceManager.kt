package app.umbra.core.workspace

import app.umbra.core.model.Clock
import app.umbra.core.model.UmbraJson
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/** What [WorkspaceManager.cleanup] is allowed to remove. */
data class CleanupPolicy(
    /** Scratch workspaces untouched for this long and without sessions are deleted. */
    val scratchMaxAgeMs: Long = TimeUnit.DAYS.toMillis(7),
    /** Session directories whose session no longer exists are deleted. */
    val removeOrphanSessionDirs: Boolean = true,
    /** If the artifacts of one session exceed this, oldest files are deleted first. */
    val maxArtifactBytesPerSession: Long = 2L * 1024 * 1024 * 1024,
)

data class CleanupReport(
    val deletedWorkspaces: List<String>,
    val deletedSessionDirs: List<String>,
    val trimmedArtifactFiles: Int,
    val freedBytes: Long,
)

/**
 * Application-managed workspaces (spec §22): project directories, per-session files and artifacts,
 * permissions, cleanup, many concurrent projects. The registry is a single JSON file replaced
 * atomically; directories on disk are the source of truth for content.
 */
class WorkspaceManager(
    private val layout: WorkspaceLayout,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private val json = UmbraJson
    private val listSerializer = ListSerializer(Workspace.serializer())

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun create(
        name: String,
        kind: WorkspaceKind = WorkspaceKind.PROJECT,
        origin: String? = null,
        permissions: WorkspacePermissions = WorkspacePermissions.DEFAULT,
        sessionId: String? = null,
    ): Workspace = io {
        mutex.withLock {
            val registry = load().toMutableList()
            val id = uniqueId(slug(name), registry.map { it.id }.toSet())
            Files.createDirectories(layout.projectDir(id))
            val now = clock.nowMs()
            val ws = Workspace(
                id = id,
                name = name.trim().ifEmpty { id },
                kind = kind,
                createdAtMs = now,
                permissions = permissions,
                origin = origin,
                sessionIds = setOfNotNull(sessionId),
            )
            registry += ws
            save(registry)
            ws
        }
    }

    suspend fun get(id: String): Workspace? = io { mutex.withLock { load().firstOrNull { it.id == id } } }

    suspend fun list(): List<Workspace> = io { mutex.withLock { load().sortedByDescending { it.lastUsedAtMs } } }

    suspend fun touch(id: String): Workspace = update(id) { it.copy(lastUsedAtMs = clock.nowMs()) }

    suspend fun rename(id: String, name: String): Workspace = update(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    suspend fun setPermissions(id: String, permissions: WorkspacePermissions): Workspace = update(id) { it.copy(permissions = permissions) }

    suspend fun setPinned(id: String, pinned: Boolean): Workspace = update(id) { it.copy(pinned = pinned) }

    suspend fun attachSession(id: String, sessionId: String): Workspace =
        update(id) { it.copy(sessionIds = it.sessionIds + sessionId, lastUsedAtMs = clock.nowMs()) }

    suspend fun detachSession(id: String, sessionId: String): Workspace = update(id) { it.copy(sessionIds = it.sessionIds - sessionId) }

    /** Deletes the workspace and its files. Refuses while sessions are attached unless [force]. */
    suspend fun delete(id: String, force: Boolean = false): Boolean = io {
        mutex.withLock {
            val registry = load().toMutableList()
            val ws = registry.firstOrNull { it.id == id } ?: return@io false
            if (ws.sessionIds.isNotEmpty() && !force) throw WorkspaceInUseException(id, ws.sessionIds)
            deleteRecursively(layout.projectDir(id))
            registry.removeAll { it.id == id }
            save(registry)
            true
        }
    }

    /** Host path of a file inside a workspace. Never escapes the workspace, even through symlinks. */
    suspend fun resolve(id: String, relative: String): Path = io {
        get(id) ?: throw WorkspaceNotFoundException(id)
        confine(layout.projectDir(id), relative)
    }

    fun guestPath(id: String, relative: String = ""): String {
        val base = layout.guestProjectDir(id)
        val clean = cleanRelative(relative)
        return if (clean.isEmpty()) base else "$base/$clean"
    }

    /** Creates (if needed) and returns the per-session directories. */
    suspend fun sessionDirs(sessionId: String): SessionDirs = io {
        require(sessionId.matches(ID_REGEX)) { "invalid session id: $sessionId" }
        val root = layout.sessionDir(sessionId)
        val dirs = SessionDirs(root, root.resolve("files"), root.resolve("artifacts"), root.resolve("logs"))
        listOf(dirs.files, dirs.artifacts, dirs.logs).forEach { Files.createDirectories(it) }
        dirs
    }

    /** Roots to hand to the risk classifier: projects and session dirs, in host and guest spelling. */
    suspend fun riskRoots(sessionId: String?): List<String> = io {
        val roots = mutableListOf<String>()
        val workspaces = mutex.withLock { load() }
        for (ws in workspaces) {
            if (sessionId == null || sessionId in ws.sessionIds || ws.sessionIds.isEmpty()) {
                roots += layout.projectDir(ws.id).toAbsolutePath().normalize().toString()
                roots += layout.guestProjectDir(ws.id)
            }
        }
        if (sessionId != null && sessionId.matches(ID_REGEX)) {
            roots += layout.sessionDir(sessionId).toAbsolutePath().normalize().toString()
            roots += layout.guestSessionDir(sessionId)
        }
        roots
    }

    /** Total size of regular files under the workspace. */
    suspend fun usageBytes(id: String): Long = io {
        val dir = layout.projectDir(id)
        if (!Files.isDirectory(dir)) 0L else directorySize(dir)
    }

    suspend fun cleanup(policy: CleanupPolicy = CleanupPolicy(), liveSessionIds: Set<String>): CleanupReport = io {
        mutex.withLock {
            val now = clock.nowMs()
            var freed = 0L
            val deletedWorkspaces = mutableListOf<String>()
            val registry = load().toMutableList()
            val stale = registry.filter { ws ->
                ws.kind == WorkspaceKind.SCRATCH && !ws.pinned && ws.sessionIds.isEmpty() &&
                    now - ws.lastUsedAtMs >= policy.scratchMaxAgeMs
            }
            for (ws in stale) {
                freed += directorySize(layout.projectDir(ws.id))
                deleteRecursively(layout.projectDir(ws.id))
                registry.removeAll { it.id == ws.id }
                deletedWorkspaces += ws.id
            }
            if (stale.isNotEmpty()) save(registry)

            val deletedSessionDirs = mutableListOf<String>()
            if (policy.removeOrphanSessionDirs && Files.isDirectory(layout.sessionsDir)) {
                Files.newDirectoryStream(layout.sessionsDir).use { entries ->
                    for (entry in entries) {
                        val sid = entry.fileName.toString()
                        if (sid !in liveSessionIds && Files.isDirectory(entry)) {
                            freed += directorySize(entry)
                            deleteRecursively(entry)
                            deletedSessionDirs += sid
                        }
                    }
                }
            }

            var trimmed = 0
            for (sid in liveSessionIds.filter { it.matches(ID_REGEX) }) {
                val artifacts = layout.sessionDir(sid).resolve("artifacts")
                if (!Files.isDirectory(artifacts)) continue
                val files = Files.walk(artifacts).use { s ->
                    s.filter { Files.isRegularFile(it) }
                        .map { FileInfo(it, Files.getLastModifiedTime(it).toMillis(), Files.size(it)) }
                        .toList()
                }.sortedBy { it.modifiedMs }
                var total = files.sumOf { it.size }
                for (file in files) {
                    if (total <= policy.maxArtifactBytesPerSession) break
                    Files.deleteIfExists(file.path)
                    total -= file.size
                    freed += file.size
                    trimmed++
                }
            }
            CleanupReport(deletedWorkspaces, deletedSessionDirs, trimmed, freed)
        }
    }

    // ---- internals ----

    private suspend fun update(id: String, transform: (Workspace) -> Workspace): Workspace = io {
        mutex.withLock {
            val registry = load().toMutableList()
            val index = registry.indexOfFirst { it.id == id }
            if (index < 0) throw WorkspaceNotFoundException(id)
            val updated = transform(registry[index]).copy(id = id)
            registry[index] = updated
            save(registry)
            updated
        }
    }

    private fun load(): List<Workspace> {
        val file = layout.registryFile
        if (!Files.exists(file)) return emptyList()
        return try {
            json.decodeFromString(listSerializer, Files.readString(file))
        } catch (e: Exception) {
            // A damaged registry must not make every project unreachable: rebuild it from disk.
            rebuildFromDisk()
        }
    }

    private fun rebuildFromDisk(): List<Workspace> {
        val dir = layout.projectsDir
        if (!Files.isDirectory(dir)) return emptyList()
        val now = clock.nowMs()
        return Files.newDirectoryStream(dir).use { entries ->
            entries.filter { Files.isDirectory(it) && it.fileName.toString().matches(ID_REGEX) }
                .map { Workspace(id = it.fileName.toString(), name = it.fileName.toString(), createdAtMs = now) }
        }
    }

    private fun save(registry: List<Workspace>) {
        Files.createDirectories(layout.metaDir)
        val tmp = layout.metaDir.resolve("workspaces.json.tmp")
        val bytes = json.encodeToString(listSerializer, registry).toByteArray(Charsets.UTF_8)
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            ch.write(java.nio.ByteBuffer.wrap(bytes))
            ch.force(true)
        }
        Files.move(tmp, layout.registryFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun slug(name: String): String {
        val s = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
        return s.ifEmpty { "workspace" }
    }

    private fun uniqueId(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    private fun cleanRelative(relative: String): String {
        val parts = ArrayDeque<String>()
        for (p in relative.replace('\\', '/').split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) throw PathEscapeException(relative) else parts.removeLast()
                else -> parts.addLast(p)
            }
        }
        return parts.joinToString("/")
    }

    /** Resolves [relative] under [base]; rejects `..` escapes and symlinks that point outside. */
    private fun confine(base: Path, relative: String): Path {
        val clean = cleanRelative(relative)
        val target = if (clean.isEmpty()) base else base.resolve(clean)
        val realBase = if (Files.exists(base)) base.toRealPath() else base.toAbsolutePath().normalize()
        // Walk up to the deepest existing ancestor and make sure its real path is inside the base.
        var probe: Path? = target
        while (probe != null && !Files.exists(probe)) probe = probe.parent
        val realProbe = probe?.toRealPath() ?: realBase
        if (!realProbe.startsWith(realBase)) throw PathEscapeException(relative)
        return target
    }

    private fun directorySize(dir: Path): Long =
        try {
            Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
        } catch (e: IOException) {
            0L
        }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private class FileInfo(val path: Path, val modifiedMs: Long, val size: Long)

    private companion object {
        val ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")
    }
}
