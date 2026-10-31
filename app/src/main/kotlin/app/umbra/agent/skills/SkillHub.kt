package app.umbra.agent.skills

import app.umbra.agent.AppContainer
import app.umbra.agent.integration.gitSecretName
import app.umbra.core.control.GitOperation
import app.umbra.core.model.UmbraJson
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class SkillRepo(
    val fullName: String,
    val description: String = "",
    val stars: Long = 0,
    val updatedAt: String? = null,
    val license: String? = null,
    val htmlUrl: String = "https://github.com/$fullName",
    val defaultBranch: String = "main",
)

@Serializable
data class SkillEntry(
    val repo: String,
    val ref: String,
    val path: String,
    val name: String,
    val description: String,
    val sha: String,
    val fileCount: Int,
    val totalSize: Long,
    val stars: Long = 0,
    val updatedAt: String? = null,
    val license: String? = null,
    val htmlUrl: String = "",
)

@Serializable
data class RemoteFile(val path: String, val url: String, val size: Long, val sha: String, val mode: String = "100644")

@Serializable
data class SkillFrontMatter(val name: String, val description: String, val license: String? = null)

/** The small scalar subset used by SKILL.md, without interpreting YAML tags or executing anything. */
object SkillFrontMatterParser {
    fun parse(text: String): SkillFrontMatter? {
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").lines()
        if (lines.firstOrNull()?.trim() != "---") return null
        val end = (1 until lines.size).firstOrNull { lines[it].trim() in setOf("---", "...") } ?: return null
        val fields = mutableMapOf<String, String>()
        var i = 1
        while (i < end) {
            val match = Regex("^(name|description|license):\\s*(.*)$").matchEntire(lines[i++]) ?: continue
            val key = match.groupValues[1]
            if (key in fields) return null
            val value = withoutComment(match.groupValues[2].trim())
            if (value.matches(Regex("[>|][+-]?"))) {
                if (key != "description") return null
                val block = mutableListOf<String>()
                while (i < end && (lines[i].isBlank() || lines[i].firstOrNull()?.isWhitespace() == true)) block += lines[i++]
                val indent = block.filter { it.isNotBlank() }.minOfOrNull { it.takeWhile(Char::isWhitespace).length } ?: 0
                val parts = block.map { it.drop(indent) }
                fields[key] = (if (value.startsWith('>')) parts.joinToString(" ") else parts.joinToString("\n")).trim()
            } else {
                val scalar = when {
                    value.startsWith('"') -> runCatching {
                        require(value.endsWith('"'))
                        UmbraJson.parseToJsonElement(value).jsonPrimitive.content
                    }.getOrNull() ?: return null
                    value.startsWith('\'') -> if (value.length >= 2 && value.endsWith('\'')) value.drop(1).dropLast(1).replace("''", "'") else return null
                    else -> value
                }
                fields[key] = scalar
            }
        }
        val name = fields["name"]?.takeIf { it.isNotBlank() } ?: return null
        val description = fields["description"]?.takeIf { it.isNotBlank() } ?: return null
        return SkillFrontMatter(name, description, fields["license"]?.takeIf { it.isNotBlank() })
    }

    private fun withoutComment(value: String): String {
        var quote: Char? = null
        var escaped = false
        value.forEachIndexed { index, char ->
            if (escaped) escaped = false
            else if (quote == '"' && char == '\\') escaped = true
            else if (char == quote) quote = null
            else if (quote == null && char in "\"'") quote = char
            else if (quote == null && char == '#' && (index == 0 || value[index - 1].isWhitespace())) return value.take(index).trimEnd()
        }
        return value
    }
}

class SkillRateLimitException(val resetAtMs: Long?, val retryAfterMs: Long?) : IOException("GitHub rate limit reached" +
    (resetAtMs?.let { "; resets at ${java.time.Instant.ofEpochMilli(it)}" } ?: ""))

@Serializable
internal data class SkillTreeNode(val path: String, val type: String, val mode: String, val sha: String = "", val size: Long = 0)

@Serializable
private data class SkillTree(val tree: List<SkillTreeNode>, val truncated: Boolean = false)

@Serializable
private data class RepositoryDocument(
    @SerialName("full_name") val fullName: String,
    val description: String? = null,
    @SerialName("stargazers_count") val stars: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    val license: JsonObject? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("default_branch") val defaultBranch: String = "main",
) {
    fun repo() = SkillRepo(fullName, description.orEmpty(), stars, updatedAt,
        license?.get("spdx_id")?.jsonPrimitive?.contentOrNull?.takeUnless { it == "NOASSERTION" }, htmlUrl, defaultBranch)
}

@Serializable
private data class RepositorySearch(val items: List<RepositoryDocument>)

/** Credentials are supplied per repository; a repo-scoped credential is never used for search or another repo. */
class SkillHub(
    http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).build(),
    private val token: (String?) -> String? = { null },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private data class Cached(val atMs: Long, val repos: List<SkillRepo>)
    private val cache = linkedMapOf<String, Cached>()

    suspend fun search(query: String): List<SkillRepo> = withContext(Dispatchers.IO) {
        val key = query.trim()
        synchronized(cache) { cache[key]?.takeIf { nowMs() - it.atMs in 0 until CACHE_MS } }?.let { return@withContext it.repos }
        val results = searchQueries(key).flatMap { q ->
            val url = api("search", "repositories").newBuilder().addQueryParameter("q", q)
                .addQueryParameter("sort", "stars").addQueryParameter("order", "desc").addQueryParameter("per_page", "30").build()
            UmbraJson.decodeFromString(RepositorySearch.serializer(), get(url, null, MAX_API_BYTES).decodeToString()).items.map { it.repo() }
        }
        merge(results).also { repos -> synchronized(cache) {
            cache[key] = Cached(nowMs(), repos)
            while (cache.size > 32) cache.remove(cache.keys.first())
        } }
    }

    suspend fun repository(repo: String): SkillRepo = withContext(Dispatchers.IO) {
        val parts = repoParts(repo)
        UmbraJson.decodeFromString(RepositoryDocument.serializer(), get(api("repos", parts[0], parts[1]), repo, MAX_API_BYTES).decodeToString()).repo()
    }

    suspend fun skills(repo: String, ref: String? = null): List<SkillEntry> = skills(repository(repo), ref)

    suspend fun skills(repo: SkillRepo, ref: String? = null): List<SkillEntry> = withContext(Dispatchers.IO) {
        val selectedRef = ref ?: repo.defaultBranch
        val tree = tree(repo.fullName, selectedRef)
        skillPaths(tree).take(30).mapNotNull { path ->
            entry(repo, selectedRef, tree, path)
        }
    }

    suspend fun skill(repo: String, folder: String, ref: String? = null): SkillEntry = withContext(Dispatchers.IO) {
        require(folder.isEmpty() || safeRelative(folder)) { "Invalid skill folder" }
        val repository = repository(repo)
        val selectedRef = ref ?: repository.defaultBranch
        val tree = tree(repo, selectedRef)
        val path = if (folder.isEmpty()) "SKILL.md" else "$folder/SKILL.md"
        require(path in skillPaths(tree)) { "No skill at $folder" }
        requireNotNull(entry(repository, selectedRef, tree, path)) { "SKILL.md requires a name and description" }
    }

    private suspend fun entry(repo: SkillRepo, ref: String, tree: List<SkillTreeNode>, path: String): SkillEntry? {
        val node = tree.first { it.path == path }
        val bytes = download(repo.fullName, RemoteFile("SKILL.md", rawUrl(repo.fullName, ref, path), node.size, node.sha, node.mode), MAX_MARKDOWN_BYTES)
        val front = SkillFrontMatterParser.parse(bytes.decodeToString()) ?: return null
        val folder = path.substringBeforeLast('/', "")
        val blobs = tree.filter { it.type == "blob" && under(it.path, folder) }
        return SkillEntry(repo.fullName, ref, folder, front.name, front.description, node.sha, blobs.size,
            blobs.sumOf { it.size }, repo.stars, repo.updatedAt, front.license ?: repo.license,
            "https://github.com/${repo.fullName}/tree/" + encodePath(ref) + if (folder.isEmpty()) "" else "/${encodePath(folder)}")
    }

    /** The skill's SKILL.md as text, to read before installing; refused if it no longer matches what was listed. */
    suspend fun markdown(entry: SkillEntry): String = withContext(Dispatchers.IO) {
        val path = if (entry.path.isEmpty()) "SKILL.md" else "${entry.path}/SKILL.md"
        val bytes = get(rawUrl(entry.repo, entry.ref, path).toHttpUrl(), entry.repo, MAX_MARKDOWN_BYTES)
        require(blobSha(bytes) == entry.sha) { "SKILL.md changed since it was listed; refresh the repository" }
        bytes.decodeToString()
    }

    suspend fun files(repo: String, ref: String, folder: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        installFiles(tree(repo, ref), folder).map { node ->
            RemoteFile(relative(node.path, folder), rawUrl(repo, ref, node.path), node.size, node.sha, node.mode)
        }
    }

    private suspend fun tree(repo: String, ref: String): List<SkillTreeNode> {
        val parts = repoParts(repo)
        require(ref.isNotBlank() && ref.none { it.isISOControl() }) { "A ref is required" }
        val url = api("repos", parts[0], parts[1], "git", "trees", ref).newBuilder().addQueryParameter("recursive", "1").build()
        val result = UmbraJson.decodeFromString(SkillTree.serializer(), get(url, repo, MAX_API_BYTES).decodeToString())
        require(!result.truncated) { "GitHub returned a truncated tree; choose a smaller repository" }
        return result.tree
    }

    internal suspend fun download(repo: String, file: RemoteFile, limit: Long = MAX_INSTALL_BYTES): ByteArray {
        val url = file.url.toHttpUrl()
        val parts = repoParts(repo)
        require(url.scheme == "https" && url.host == "raw.githubusercontent.com" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.pathSegments.take(2) == parts && url.query == null && url.fragment == null) { "Invalid skill download URL" }
        require(file.mode in setOf("100644", "100755") && file.size in 0..limit) { "Invalid skill file" }
        val bytes = get(url, repo, minOf(limit, file.size))
        require(bytes.size.toLong() == file.size) { "Skill file size changed: ${file.path}" }
        require(blobSha(bytes) == file.sha) { "Skill file changed: ${file.path}; refresh the repository" }
        return bytes
    }

    private suspend fun get(url: HttpUrl, repo: String?, limit: Long): ByteArray {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val request = Request.Builder().url(url).header("User-Agent", "Umbra-Skills").apply {
            if (url.host == "api.github.com") {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
            }
            token(repo)?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        }.build()
        http.newCall(request).execute().use { response ->
            if (response.code == 403 || response.code == 429) {
                val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                val retry = response.header("Retry-After")?.toLongOrNull()?.times(1000)
                if (response.code == 429 || response.header("X-RateLimit-Remaining") == "0" || reset != null || retry != null) {
                    throw SkillRateLimitException(reset ?: retry?.let { nowMs() + it }, retry)
                }
            }
            if (!response.isSuccessful) throw IOException("GitHub request failed (HTTP ${response.code})")
            val body = response.body
            require(body.contentLength() <= limit) { "GitHub response exceeds size limit" }
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= limit) { "GitHub response exceeds size limit" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }
    }

    companion object {
        const val MAX_INSTALL_FILES = 300
        const val MAX_INSTALL_BYTES = 20L * 1024 * 1024
        const val MAX_MARKDOWN_BYTES = 1024L * 1024
        private const val MAX_API_BYTES = 32L * 1024 * 1024
        private const val CACHE_MS = 10L * 60 * 1000

        fun forContainer(container: AppContainer) = SkillHub(token = { repo ->
            if (repo == null || container.gitCredentials.suspension() != null) null else {
                container.gitCredentials.forRemote("https://github.com/$repo")?.takeIf { GitOperation.READ in it.operations }
                    ?.let { container.gitSecrets.get(gitSecretName(it.id)) }
            }
        })

        /**
         * GitHub allows ten anonymous searches a minute and has no OR between topics: the opening list (cached for ten
         * minutes) asks all four skill topics, a typed search the two widest, so typing does not hit the limit at once.
         */
        fun searchQueries(query: String): List<String> {
            val q = query.trim()
            val topics = if (q.isEmpty()) listOf("agent-skills", "claude-skills", "claude-code-skills", "codex-skills") else listOf("agent-skills", "claude-skills")
            return topics.map { listOfNotNull(q.takeIf(String::isNotEmpty)?.let { "$it in:name,description,readme" }, "topic:$it").joinToString(" ") }
        }

        fun merge(repos: List<SkillRepo>): List<SkillRepo> = repos.sortedByDescending { it.stars }
            .distinctBy { it.fullName.lowercase() }.sortedWith(compareByDescending<SkillRepo> { it.stars }.thenBy { it.fullName })

        internal fun skillPaths(tree: List<SkillTreeNode>): List<String> = tree.filter {
            it.type == "blob" && it.mode in setOf("100644", "100755") && it.path.substringAfterLast('/') == "SKILL.md" &&
                it.path.split('/').none { part -> part in setOf("node_modules", ".git", "vendor") } && safeRelative(it.path)
        }.map { it.path }.sorted()

        internal fun installFiles(tree: List<SkillTreeNode>, folder: String): List<SkillTreeNode> {
            require(folder.isEmpty() || safeRelative(folder)) { "Invalid skill folder" }
            val selected = tree.filter { under(it.path, folder) }
            require(selected.all { safeRelative(it.path) && it.type in setOf("blob", "tree") &&
                (it.type == "tree" && it.mode == "040000" || it.type == "blob" && it.mode in setOf("100644", "100755")) }) { "Skills cannot contain symlinks, submodules or unsafe paths" }
            val blobs = selected.filter { it.type == "blob" }
            require(blobs.size <= MAX_INSTALL_FILES) { "Skill exceeds $MAX_INSTALL_FILES files" }
            require(blobs.map { it.path }.distinct().size == blobs.size) { "Duplicate skill paths" }
            var total = 0L
            blobs.forEach {
                require(it.size in 0..MAX_INSTALL_BYTES - total) { "Skill exceeds 20 MB" }
                total += it.size
            }
            require(blobs.any { relative(it.path, folder) == "SKILL.md" }) { "Skill folder has no SKILL.md" }
            return blobs.sortedBy { it.path }
        }

        internal fun safeRelative(path: String) = path.isNotEmpty() && !path.startsWith('/') && '\\' !in path &&
            path.none { it.isISOControl() } && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
        private fun under(path: String, folder: String) = folder.isEmpty() || path.startsWith("$folder/")
        internal fun relative(path: String, folder: String) = if (folder.isEmpty()) path else path.removePrefix("$folder/")
        private fun repoParts(repo: String): List<String> {
            require(repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) && repo.split('/').none { it == "." || it == ".." }) { "Use a GitHub owner/repository" }
            return repo.split('/')
        }
        private fun api(vararg segments: String) = "https://api.github.com".toHttpUrl().newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
        internal fun rawUrl(repo: String, ref: String, path: String): String {
            val parts = repoParts(repo)
            require(safeRelative(path) && ref.isNotBlank()) { "Invalid skill path or ref" }
            return "https://raw.githubusercontent.com".toHttpUrl().newBuilder().apply {
                parts.forEach { addPathSegment(it) }; addPathSegment(ref); path.split('/').forEach { addPathSegment(it) }
            }.build().toString()
        }
        private fun encodePath(value: String) = value.split('/').joinToString("/") { segment ->
            "https://github.com".toHttpUrl().newBuilder().addPathSegment(segment).build().encodedPath.removePrefix("/")
        }
        internal fun blobSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").apply {
            update("blob ${bytes.size}\u0000".toByteArray()); update(bytes)
        }.digest().joinToString("") { "%02x".format(it) }
    }
}
