package app.umbra.core.control

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Guaranteed minimum + elastic sharing for one project or pool (spec §4.2), in logical agent slots. */
@Serializable
data class Budget(val guaranteed: Int = 0, val max: Int = 0, val weight: Int = 1)

/**
 * A project (spec §2.1): a named home for sessions, with repositories linked to it and its own budget. A session
 * has at most one owning project; any number of projects can be linked to it as context.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val description: String = "",
    val repositoryIds: List<String> = emptyList(),
    val budget: Budget = Budget(),
)

class ProjectRegistry(private val store: ControlStore) {
    private val docs = store.docs("projects", Project.serializer(), Project::id)
    private val journal = store.journal("workspaces")

    val all = docs.all

    fun list(): List<Project> = docs.list().sortedBy { it.createdAtMs }

    operator fun get(id: String): Project? = docs[id]

    fun create(name: String, description: String = ""): Project {
        val clean = name.trim().ifEmpty { "Untitled project" }
        val project = Project(newId("prj", store.clock.nowMs()), clean.take(80), store.clock.nowMs(), description.trim())
        docs.put(project)
        journal.append("project.created", buildJsonObject { put("project", project.id); put("name", project.name) })
        return project
    }

    fun rename(id: String, name: String): Project? =
        docs.update(id) { it.copy(name = name.trim().ifEmpty { it.name }.take(80)) }?.also { journal.append("project.renamed", buildJsonObject { put("project", id); put("name", it.name) }) }

    fun setBudget(id: String, budget: Budget): Project? = docs.update(id) { it.copy(budget = budget) }

    fun linkRepository(id: String, repositoryId: String): Project? =
        docs.update(id) { if (repositoryId in it.repositoryIds) it else it.copy(repositoryIds = it.repositoryIds + repositoryId) }
            ?.also { journal.append("project.repository_linked", buildJsonObject { put("project", id); put("repository", repositoryId) }) }

    fun unlinkRepository(id: String, repositoryId: String): Project? =
        docs.update(id) { it.copy(repositoryIds = it.repositoryIds - repositoryId) }

    /** Deletes the project record only; its sessions become independent (the caller moves them). */
    fun delete(id: String): Boolean = docs.delete(id).also { if (it) journal.append("project.deleted", buildJsonObject { put("project", id) }) }

    fun record(kind: String, data: kotlinx.serialization.json.JsonObject) = journal.append(kind, data)
}

/** Readable, sortable, collision-safe ids: `<prefix>-<time36>-<random>`. */
fun newId(prefix: String, nowMs: Long): String =
    "$prefix-${nowMs.toString(36)}-${java.lang.Long.toString((java.security.SecureRandom().nextLong() ushr 1) % 1_679_616, 36).padStart(4, '0')}"
