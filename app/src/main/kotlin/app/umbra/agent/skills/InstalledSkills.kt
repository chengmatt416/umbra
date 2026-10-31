package app.umbra.agent.skills

import android.content.Context
import android.content.SharedPreferences
import app.umbra.agent.AppContainer
import app.umbra.agent.agents.proactive.EvidenceRanker
import app.umbra.core.model.UmbraJson
import app.umbra.core.workspace.WorkspaceLayout
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
enum class SkillScope { GLOBAL, PROJECT }

@Serializable
data class SkillSource(val repo: String, val ref: String, val path: String, val sha: String, val installedAtMs: Long)

@Serializable
data class InstalledSkill(
    val name: String,
    val description: String,
    val scope: SkillScope,
    val projectId: String? = null,
    val folder: String,
    val source: SkillSource? = null,
    val hasScripts: Boolean = false,
    val scripts: List<String> = emptyList(),
) {
    val sourceRepo: String? get() = source?.repo
    val sourceUrl: String? get() = source?.repo?.let { "https://github.com/$it" }
}

sealed interface Target {
    data object Global : Target
    data class Project(val id: String) : Target
}

class InstalledSkills(
    private val layout: WorkspaceLayout,
    private val hub: SkillHub = SkillHub(),
    private val preferences: SharedPreferences? = null,
) {
    private val root = layout.hostRoot.toAbsolutePath().normalize()
    private val lock = locks.getOrPut(root.toString()) { Mutex() }

    suspend fun scan(projectId: String? = null): List<InstalledSkill> = withContext(Dispatchers.IO) {
        projectId?.let(::validateName)
        lock.withLock {
            fun available(target: Target): List<InstalledSkill> = try { scanTarget(target) }
                catch (_: java.io.IOException) { emptyList() }
                catch (_: IllegalArgumentException) { emptyList() }
            buildList {
                addAll(available(Target.Global))
                projectId?.let { addAll(available(Target.Project(it))) }
            }
        }
    }

    suspend fun install(entry: SkillEntry, target: Target, name: String = entry.name, replace: Boolean = false): InstalledSkill =
        install(entry, hub.files(entry.repo, entry.ref, entry.path), target, name, replace)

    suspend fun install(entry: SkillEntry, files: List<RemoteFile>, target: Target, name: String = entry.name, replace: Boolean = false): InstalledSkill = withContext(Dispatchers.IO) {
        validateName(name)
        require(entry.path.isEmpty() || SkillHub.safeRelative(entry.path)) { "Invalid skill folder" }
        require(files.size <= SkillHub.MAX_INSTALL_FILES && files.any { it.path == "SKILL.md" }) { "Invalid skill file list" }
        require(files.map { it.path }.distinct().size == files.size) { "Duplicate skill file paths" }
        var declaredSize = 0L
        files.forEach { file ->
            require(SkillHub.safeRelative(file.path) && file.path.split('/').none { it == ".git" } && file.path != METADATA) { "Unsafe skill file path" }
            require(file.mode in setOf("100644", "100755")) { "Skills cannot contain symlinks or submodules" }
            require(file.size in 0..SkillHub.MAX_INSTALL_BYTES - declaredSize) { "Skill exceeds 20 MB" }
            declaredSize += file.size
            val repoPath = listOf(entry.path, file.path).filter { it.isNotEmpty() }.joinToString("/")
            require(file.url == SkillHub.rawUrl(entry.repo, entry.ref, repoPath)) { "Skill download does not match its source" }
        }
        require(files.first { it.path == "SKILL.md" }.sha == entry.sha) { "SKILL.md changed; refresh the repository" }
        lock.withLock {
            val parent = targetFolder(target)
            ensureDirectory(parent)
            val destination = parent.resolve(name)
            requireNoSymlinks(destination)
            require(!Files.exists(destination, NOFOLLOW_LINKS) || replace) { "Skill $name is already installed; use replace" }
            if (Files.exists(destination, NOFOLLOW_LINKS)) require(Files.isDirectory(destination, NOFOLLOW_LINKS)) { "The install target is not a folder" }
            val staging = Files.createTempDirectory(parent, ".install-")
            var backup: Path? = null
            var promoted = false
            try {
                var actualSize = 0L
                for (file in files) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val bytes = hub.download(entry.repo, file, SkillHub.MAX_INSTALL_BYTES - actualSize)
                    actualSize += bytes.size
                    val output = staging.resolve(file.path)
                    ensureDirectory(output.parent)
                    writeNew(output, bytes)
                }
                val markdown = staging.resolve("SKILL.md")
                val text = readText(markdown, SkillHub.MAX_MARKDOWN_BYTES)
                val front = requireNotNull(SkillFrontMatterParser.parse(text)) { "SKILL.md requires a name and description" }
                // Keep the selectable omp name and the installed folder name consistent after a user rename.
                if (front.name != name) {
                    val renamed = text.replaceFirst(Regex("(?m)^name:[^\\r\\n]*"), "name: " +
                        UmbraJson.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(name)))
                    Files.delete(markdown)
                    writeNew(markdown, renamed.toByteArray())
                }
                val source = SkillSource(entry.repo, entry.ref, entry.path, entry.sha, System.currentTimeMillis())
                writeNew(staging.resolve(METADATA), UmbraJson.encodeToString(SkillSource.serializer(), source).toByteArray())
                val scripts = scriptFiles(staging)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                requireNoSymlinks(destination)
                if (Files.exists(destination, NOFOLLOW_LINKS)) {
                    require(replace) { "Skill $name is already installed; use replace" }
                    backup = Files.createTempDirectory(parent, ".backup-")
                    Files.delete(backup)
                    Files.move(destination, backup, ATOMIC_MOVE)
                }
                try {
                    Files.move(staging, destination, ATOMIC_MOVE)
                    promoted = true
                } catch (e: Exception) {
                    backup?.let { Files.move(it, destination, ATOMIC_MOVE); backup = null }
                    throw e
                }
                InstalledSkill(name, front.description, scope(target), (target as? Target.Project)?.id,
                    requireNotNull(layout.toGuest(destination)), source, scripts.isNotEmpty(), scripts)
            } finally {
                deleteTree(staging)
                if (promoted) backup?.let(::deleteTree)
            }
        }
    }

    suspend fun remove(name: String, target: Target) = withContext(Dispatchers.IO) {
        validateName(name)
        lock.withLock {
            val folder = targetFolder(target).resolve(name)
            requireNoSymlinks(folder)
            deleteTree(folder)
        }
    }

    fun always(): Set<String> = preferences?.getStringSet("always", emptySet())?.toSet().orEmpty()

    fun setAlways(names: Set<String>) {
        names.forEach(::validateName)
        requireNotNull(preferences) { "Skill preferences are not configured" }.edit().putStringSet("always", names.toSet()).apply()
    }

    fun autoPick(task: String, installed: List<InstalledSkill>, max: Int = 6): List<String> =
        Companion.autoPick(task, installed, max, always())

    private fun targetFolder(target: Target): Path = when (target) {
        Target.Global -> root
        is Target.Project -> {
            validateName(target.id)
            root.resolve("projects").resolve(target.id).also {
                requireNoSymlinks(it)
                require(Files.isDirectory(it, NOFOLLOW_LINKS)) { "Project ${target.id} does not exist" }
            }
        }
    }.resolve(".omp/skills")

    private fun scanTarget(target: Target): List<InstalledSkill> {
        val parent = targetFolder(target)
        requireNoSymlinks(parent)
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) return emptyList()
        return Files.newDirectoryStream(parent).use { folders ->
            folders.filter { !it.fileName.toString().startsWith('.') && Files.isDirectory(it, NOFOLLOW_LINKS) }.sorted().mapNotNull { folder ->
                try {
                    requireNoSymlinks(folder)
                    val front = SkillFrontMatterParser.parse(readText(folder.resolve("SKILL.md"), SkillHub.MAX_MARKDOWN_BYTES)) ?: return@mapNotNull null
                    validateName(front.name)
                    val source = if (Files.exists(folder.resolve(METADATA), NOFOLLOW_LINKS)) runCatching {
                        UmbraJson.decodeFromString(SkillSource.serializer(), readText(folder.resolve(METADATA), 64 * 1024L))
                    }.getOrNull() else null
                    val scripts = scriptFiles(folder)
                    InstalledSkill(front.name, front.description, scope(target), (target as? Target.Project)?.id,
                        requireNotNull(layout.toGuest(folder)), source, scripts.isNotEmpty(), scripts)
                } catch (e: CancellationException) { throw e }
                catch (_: java.io.IOException) { null }
                catch (_: IllegalArgumentException) { null }
            }
        }
    }

    private fun scriptFiles(folder: Path): List<String> = Files.walk(folder).use { paths ->
        paths.filter { path ->
            require(!Files.isSymbolicLink(path)) { "Skills cannot contain symlinks" }
            Files.isRegularFile(path, NOFOLLOW_LINKS)
        }.filter { path ->
            val relative = folder.relativize(path).toString().replace('\\', '/')
            relative.startsWith("scripts/") || Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                val prefix = java.nio.ByteBuffer.allocate(2)
                while (prefix.hasRemaining() && channel.read(prefix) > 0) { }
                prefix.position() == 2 && prefix[0] == '#'.code.toByte() && prefix[1] == '!'.code.toByte()
            }
        }.map { folder.relativize(it).toString().replace('\\', '/') }.sorted().iterator().asSequence().toList()
    }

    private fun requireNoSymlinks(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(root)) { "Skill path escapes the workspace" }
        var current = normalized.root
        for (part in normalized) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "Skill paths cannot follow symlinks" }
        }
    }

    private fun ensureDirectory(path: Path) {
        requireNoSymlinks(path)
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            if (path != root) ensureDirectory(path.parent)
            try { Files.createDirectory(path) } catch (e: java.nio.file.FileAlreadyExistsException) {
                requireNoSymlinks(path)
            }
        }
        require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Skill path is not a directory" }
    }

    private fun writeNew(path: Path, bytes: ByteArray) {
        requireNoSymlinks(path)
        Files.newByteChannel(path, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    private fun readText(path: Path, limit: Long): String {
        requireNoSymlinks(path)
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= limit) { "Invalid skill metadata file" }
        return Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= limit) { "Skill metadata is too large" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun deleteTree(path: Path) {
        requireNoSymlinks(path)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    companion object {
        private const val METADATA = ".umbra-skill.json"
        private val locks = ConcurrentHashMap<String, Mutex>()

        fun forContainer(container: AppContainer, hub: SkillHub = SkillHub.forContainer(container)) = InstalledSkills(
            container.workspaceLayout, hub, container.context.getSharedPreferences("skills", Context.MODE_PRIVATE))

        fun validateName(name: String) {
            require(name.matches(Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{0,99}")) && name != "." && name != "..") { "Use a skill name with letters, numbers, dots, dashes or underscores; no globs" }
        }

        /** All always-enabled skills survive the cap; the remaining slots go to meaningful keyword matches. */
        fun autoPick(task: String, installed: List<InstalledSkill>, max: Int = 6, always: Set<String> = emptySet()): List<String> {
            require(max >= 0)
            val skills = installed.sortedByDescending { it.scope == SkillScope.PROJECT }.distinctBy { it.name }
            val required = skills.filter { it.name in always }.map { it.name }.sorted()
            val terms = EvidenceRanker.tokens(task) - stopWords
            val ranked = skills.filter { it.name !in always }.mapNotNull { skill ->
                val words = EvidenceRanker.tokens("${skill.name} ${skill.description}") - stopWords
                val overlap = terms.intersect(words)
                val score = overlap.size.toDouble() / minOf(terms.size, words.size).coerceAtLeast(1)
                val nameMatch = overlap.intersect(EvidenceRanker.tokens(skill.name)).isNotEmpty()
                if (overlap.isEmpty() || (!nameMatch && (overlap.size < 2 || score < .2))) null else skill.name to score
            }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            return required + ranked.take((max - required.size).coerceAtLeast(0)).map { it.first }
        }

        private fun scope(target: Target) = if (target == Target.Global) SkillScope.GLOBAL else SkillScope.PROJECT
        private val stopWords = setOf("an", "as", "at", "be", "by", "do", "if", "in", "is", "it", "of", "on", "or", "to", "we", "the", "and", "for", "with", "this", "that", "from", "into", "are", "you", "your", "use", "using", "please", "help", "task", "skill", "skills", "when", "then", "can", "code")
    }
}
