package app.umbra.core.control

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How a repository's commits may be published through the commit gate (architecture spec §2.1, §11). */
@Serializable
enum class IntegrationPolicy(val label: String) {
    /** Only fast-forwards: a remote's history is never rewritten from here. */
    FAST_FORWARD_ONLY("Fast-forward only"),

    /** A rewrite is possible, and only with its own permission for that one promotion. */
    REWRITE_WITH_PERMISSION("Rewrite with its own permission"),
}

@Serializable
data class PreflightCheck(val name: String, val passed: Boolean, val detail: String)

@Serializable
data class RepositoryPreflight(val checks: List<PreflightCheck>, val atMs: Long, val branch: String? = null, val head: String? = null) {
    val ok: Boolean get() = checks.all { it.passed }
}

/**
 * A repository as the hub knows it (architecture spec §2.1): its canonical location (a checkout in the Linux
 * environment's workspace), where it came from, its integration policy, and the projects it is linked to. A
 * repository is not a project and not a session; linking is many-to-many.
 */
@Serializable
data class RepositoryRecord(
    val id: String,
    val name: String,
    /** The checkout, as a path in the Linux environment (`/workspace/…`). */
    val location: String,
    /** Where it was cloned from, when the hub cloned it. */
    val origin: String? = null,
    val policy: IntegrationPolicy = IntegrationPolicy.FAST_FORWARD_ONLY,
    val projectIds: List<String> = emptyList(),
    val addedAtMs: Long,
    val preflight: RepositoryPreflight? = null,
)

class RepositoryException(message: String) : Exception(message)

/**
 * The global Repository Hub (architecture spec §2.1): the one place repositories are registered or cloned, checked
 * (preflight), linked to projects and given an integration policy, which the commit gate enforces. The hub never
 * deletes a checkout: forgetting a repository only removes its record.
 */
class RepositoryHub(private val store: ControlStore) {
    private val repos = store.docs("repositories", RepositoryRecord.serializer(), RepositoryRecord::id)
    private val journal = store.journal("repositories")

    val all: StateFlow<Map<String, RepositoryRecord>> = repos.all
    val recent = journal.recent

    operator fun get(id: String): RepositoryRecord? = repos[id]

    fun byLocation(location: String): RepositoryRecord? = repos.list().firstOrNull { it.location == location.trimEnd('/') }

    /** May the commit gate publish a rewrite of [location]'s remote history? Unregistered repositories: only with permission. */
    fun rewritePermitted(location: String): Boolean = (byLocation(location)?.policy ?: IntegrationPolicy.REWRITE_WITH_PERMISSION) == IntegrationPolicy.REWRITE_WITH_PERMISSION

    /** Registers the checkout at [location] (it must be a git repository: [git] checks). */
    suspend fun register(location: String, git: CommandGit, name: String? = null, origin: String? = null, by: String = "user"): RepositoryRecord {
        val path = location.trimEnd('/')
        require(path.startsWith("/")) { "a repository's location is an absolute path" }
        byLocation(path)?.let { return it }
        val top = git.ask(path, "rev-parse", "--show-toplevel")
        if (top.exit != 0) throw RepositoryException("$path is not a git repository: ${top.stderr.trim().take(200)}")
        val id = "repo-" + ControlStore.sha256(path).take(12)
        val r = repos.put(RepositoryRecord(id, name ?: path.substringAfterLast('/'), path, origin, addedAtMs = store.clock.nowMs()))
        journal.append("repository.registered", buildJsonObject { put("id", id); put("location", path); origin?.let { put("origin", it) }; put("by", by) })
        return r
    }

    /**
     * Clones [url] into `[root]/[name]` and registers it. Only URLs git itself names (https, ssh, git@host:path, a
     * local path) are taken; one that starts with a dash is refused, so it cannot be read as an option.
     */
    suspend fun clone(url: String, root: String, name: String, git: CommandGit, by: String = "user"): RepositoryRecord {
        val u = url.trim()
        if (u.isEmpty() || u.startsWith("-") || !(u.startsWith("https://") || u.startsWith("ssh://") || u.startsWith("git@") || u.startsWith("/") || u.startsWith("file://"))) {
            throw RepositoryException("“$u” is not a repository URL this hub clones (https://, ssh://, git@host:path, or a local path)")
        }
        if (!name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) throw RepositoryException("“$name” is not a folder name (letters, digits, . _ -)")
        val dest = root.trimEnd('/') + "/" + name
        val r = git.clone(u, dest)
        if (r.exit != 0) throw RepositoryException("git clone failed: ${r.stderr.trim().lines().lastOrNull().orEmpty().take(300)}")
        return register(dest, git, name, u, by)
    }

    /** The preflight: is it a repository, on which branch, is it clean, do its remotes answer, how big is it. */
    suspend fun preflight(id: String, git: CommandGit): RepositoryRecord {
        val r = repos[id] ?: throw RepositoryException("no repository $id")
        val checks = ArrayList<PreflightCheck>()
        val isRepo = git.ask(r.location, "rev-parse", "--git-dir").exit == 0
        checks += PreflightCheck("Repository", isRepo, if (isRepo) r.location else "${r.location} is not a git repository any more")
        var branch: String? = null
        var head: String? = null
        if (isRepo) {
            branch = git.ask(r.location, "symbolic-ref", "--quiet", "--short", "HEAD").takeIf { it.exit == 0 }?.stdout?.trim()
            head = git.ask(r.location, "rev-parse", "--short", "HEAD").takeIf { it.exit == 0 }?.stdout?.trim()
            checks += PreflightCheck("Branch", branch != null, branch?.let { "on $it at ${head ?: "no commit"}" } ?: "detached HEAD: name a branch to publish")
            val status = git.ask(r.location, "status", "--porcelain")
            val dirty = status.stdout.lines().count { it.isNotBlank() }
            checks += PreflightCheck("Working tree", status.exit == 0 && dirty == 0, if (dirty == 0) "clean" else "$dirty uncommitted change(s): a promotion publishes commits, not these")
            val remotes = git.ask(r.location, "remote").stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (remotes.isEmpty()) checks += PreflightCheck("Remotes", false, "no remote: nothing to publish to")
            for (remote in remotes) {
                val ls = git.ask(r.location, "ls-remote", "--heads", "--", remote)
                checks += PreflightCheck("Remote “$remote”", ls.exit == 0, if (ls.exit == 0) "${ls.stdout.lines().count { it.isNotBlank() }} branch(es) answer" else ls.stderr.trim().lines().lastOrNull().orEmpty().take(200))
            }
            val size = git.ask(r.location, "count-objects", "-vH").stdout.lines().firstOrNull { it.startsWith("size-pack") }?.substringAfter(':')?.trim()
            checks += PreflightCheck("Size", true, "packed ${size ?: "?"}")
        }
        val next = repos.put(r.copy(preflight = RepositoryPreflight(checks, store.clock.nowMs(), branch, head)))
        journal.append("repository.preflight", buildJsonObject { put("id", id); put("ok", next.preflight!!.ok); put("failed", checks.filterNot { it.passed }.joinToString { it.name }) })
        return next
    }

    fun setPolicy(id: String, policy: IntegrationPolicy, by: String = "user") {
        repos.update(id) { it.copy(policy = policy) } ?: throw RepositoryException("no repository $id")
        journal.append("repository.policy", buildJsonObject { put("id", id); put("policy", policy.name.lowercase()); put("by", by) })
    }

    fun link(id: String, projectId: String) {
        repos.update(id) { if (projectId in it.projectIds) it else it.copy(projectIds = it.projectIds + projectId) } ?: throw RepositoryException("no repository $id")
        journal.append("repository.linked", buildJsonObject { put("id", id); put("project", projectId) })
    }

    fun unlink(id: String, projectId: String) {
        repos.update(id) { it.copy(projectIds = it.projectIds - projectId) }
        journal.append("repository.unlinked", buildJsonObject { put("id", id); put("project", projectId) })
    }

    /** Forgets the record; the checkout and its files stay where they are. */
    fun forget(id: String, by: String = "user") {
        val r = repos[id] ?: return
        repos.delete(id)
        journal.append("repository.forgotten", buildJsonObject { put("id", id); put("location", r.location); put("by", by) })
    }
}
