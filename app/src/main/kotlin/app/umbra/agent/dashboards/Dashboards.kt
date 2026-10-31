package app.umbra.agent.dashboards

import app.umbra.agent.agents.AgentTimer
import app.umbra.agent.cards.CardBlock
import app.umbra.core.model.UmbraJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * A page an agent maintains for something the user will return to as it changes.
 *
 * @property id Stable deep-link identity, independent of its position in the agent's pages.
 * @property agentId The owner whose tools may edit this page.
 * @property title Short page heading, limited to 200 characters.
 * @property subtitle Optional context, limited to 1000 characters.
 * @property icon A card icon name, limited to 80 characters.
 * @property tiles Card blocks in the user's display order, at most 24.
 * @property sources Inputs and their last known health, at most 24.
 * @property status Only LIVE pages consume automatic source updates.
 * @property statusText A short explanation of the page's state.
 * @property createdAtMs Creation time, preserved across refreshes.
 * @property updatedAtMs Last persisted content or source-state change.
 * @property lastRefreshAtMs Last successful agent or binding refresh.
 * @property refreshCron The local-time schedule, when model refreshes are enabled.
 * @property refreshTimerId The owning agent's actual timer, so removal can cancel it.
 * @property history The newest 30 explanations, newest first.
 * @property agentOnChange Requests a model refresh even when every tile is bound.
 */
@Serializable
data class Dashboard(
    val id: String,
    val agentId: String,
    val title: String,
    val subtitle: String? = null,
    val icon: String? = null,
    val tiles: List<Tile> = emptyList(),
    val sources: List<Source> = emptyList(),
    val status: Status = Status.LIVE,
    val statusText: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long = createdAtMs,
    val lastRefreshAtMs: Long? = null,
    val refreshCron: String? = null,
    val refreshTimerId: String? = null,
    val history: List<Update> = emptyList(),
    val agentOnChange: Boolean = false,
) {
    /** Pausing a page stops its automatic source work without losing its last values. */
    @Serializable enum class Status { LIVE, PAUSED, ERROR }
}

/**
 * A card block with stable identity so file refreshes do not disturb the page layout.
 *
 * @property id Identity used by updates even when tiles have the same title.
 * @property title Heading preserved by binding refreshes, limited to 200 characters.
 * @property size Layout intent preserved by binding refreshes.
 * @property block The card design system's renderable content.
 * @property binding Optional locally computed content, independent of model refreshes.
 * @property updatedAtMs Last successful tile update.
 * @property note An agent annotation, limited to 2000 characters.
 */
@Serializable
data class Tile(
    val id: String,
    val title: String,
    val size: Size = Size.SMALL,
    val block: CardBlock,
    val binding: Binding? = null,
    val updatedAtMs: Long,
    val note: String? = null,
) {
    /** Layout intent; the card renderer still owns the presentation. */
    @Serializable enum class Size { SMALL, WIDE, TALL }
}

/**
 * Source state is persisted so a page can explain stale values after a restart.
 *
 * @property id Identity referenced by this page's bindings.
 * @property kind Determines which local reader, if any, refreshes the input.
 * @property path An absolute guest path under /workspace for file and folder inputs.
 * @property lastSeenMs Last successful computation from this input.
 * @property fingerprint Last successfully applied size/mtime or listing digest.
 * @property error A short failure reason; previous tile values remain visible.
 */
@Serializable
data class Source(
    val id: String,
    val kind: Kind,
    val path: String? = null,
    val lastSeenMs: Long = 0,
    val fingerprint: String? = null,
    val error: String? = null,
) {
    /** MODEL sources are refreshed by the agent; the other kinds can update bound tiles locally. */
    @Serializable enum class Kind { FILE, FOLDER, MODEL, TASKS }
}

/**
 * A short explanation of a change, kept newest first rather than growing an unbounded activity log.
 *
 * @property atMs When the change was applied.
 * @property by One of agent, file, folder or tasks, so the UI can explain who changed a value.
 * @property text The explanation, limited to 2000 characters.
 */
@Serializable
data class Update(val atMs: Long, val by: String, val text: String)

/** The part of a tile Umbra can compute without waking a model. Paths are relative to a JSON document. */
@Serializable
sealed interface Binding {
    /**
     * Reads a single value into the first stat, progress value or gauge value.
     * @property sourceId The file source supplying the document.
     * @property path Dotted keys and bracket indices, with an optional dollar root.
     */
    @Serializable @SerialName("json_value")
    data class JsonValue(val sourceId: String, val path: String) : Binding

    /**
     * Selects object keys in order for a table, list or comparison chart.
     * @property sourceId The file source supplying the document.
     * @property path The array's location within the document.
     * @property columns Literal object keys in display order.
     */
    @Serializable @SerialName("json_table")
    data class JsonTable(val sourceId: String, val path: String, val columns: List<String>) : Binding

    /**
     * Uses header names; chart bindings use the first selected column as labels and the rest as numbers.
     * @property sourceId The file source supplying CSV or TSV text.
     * @property columns Selected headers in display order, or all headers when omitted.
     * @property limit Maximum data rows to display, from 1 to 200.
     */
    @Serializable @SerialName("csv_table")
    data class CsvTable(val sourceId: String, val columns: List<String>? = null, val limit: Int = 100) : Binding

    /**
     * Keeps a bounded end of a log in a code block.
     * @property sourceId The file source supplying UTF-8 text.
     * @property lines Maximum lines to retain, from 1 to 1000.
     */
    @Serializable @SerialName("text_tail")
    data class TextTail(val sourceId: String, val lines: Int = 20) : Binding

    /**
     * Counts matching lines rather than individual regex matches.
     * @property sourceId The file source supplying UTF-8 text.
     * @property regex An RE2 line search pattern, limited to 1000 characters.
     */
    @Serializable @SerialName("text_count")
    data class TextCount(val sourceId: String, val regex: String) : Binding

    /**
     * Lists direct child files, leaving recursive scans to an explicit agent task.
     * @property sourceId The folder source supplying the listing.
     * @property limit Maximum entries to display, from 1 to 200.
     * @property sort The ordering to apply before limiting the listing.
     */
    @Serializable @SerialName("folder_list")
    data class FolderList(val sourceId: String, val limit: Int = 30, val sort: FolderSort = FolderSort.NEWEST) : Binding

    /**
     * Summarizes direct child files by count, bytes and newest modification.
     * @property sourceId The folder source supplying the listing.
     */
    @Serializable @SerialName("folder_stats")
    data class FolderStats(val sourceId: String) : Binding

    /**
     * Uses only sessions owned by this agent, with terminal counts restricted to the local day.
     * @property agentId The dashboard owner, so a binding cannot expose another agent's task list.
     */
    @Serializable @SerialName("tasks")
    data class Tasks(val agentId: String) : Binding
}

/** Stable ordering choices for a folder's direct child files. */
@Serializable
enum class FolderSort { NEWEST, NAME, SIZE }

/**
 * One page's ordered tiles, source health and actual timer time for the UI.
 *
 * @property dashboard The page these display values belong to.
 * @property sources Persisted health and guest paths, without exposing host paths.
 * @property nextRefreshAtMs The actual timer's next time, rather than a guessed cron occurrence.
 * @property tiles Blocks in their stored display order.
 */
data class DashboardPath(
    val dashboard: Dashboard,
    val sources: List<Source>,
    val nextRefreshAtMs: Long?,
    val tiles: List<Tile>,
)

/**
 * One atomic JSON file per agent under the supplied agents directory. Reads report corrupt state instead of
 * overwriting it; synchronized transforms let source events and model updates preserve each other's edits.
 */
class DashboardStore(
    private val root: File,
    private val timers: (String) -> List<AgentTimer> = { emptyList() },
) {
    private val changes = MutableStateFlow(0L)

    /** Emits after every persisted mutation so screens and watchers can reload. */
    val revision: StateFlow<Long> = changes.asStateFlow()

    /** All agents' pages, ordered by creation time and identity. */
    @Synchronized fun all(): List<Dashboard> = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.matches(ID) }
        .flatMap { forAgent(it.name) }.sortedWith(compareBy<Dashboard> { it.createdAtMs }.thenBy { it.id })

    /** Reads one agent's pages without treating invalid JSON as an empty store. */
    @Synchronized fun forAgent(agentId: String): List<Dashboard> {
        val file = file(agentId)
        if (!file.exists()) return emptyList()
        require(file.length() <= MAX_BYTES) { "Dashboard state is too large" }
        return UmbraJson.decodeFromString(ListSerializer(Dashboard.serializer()), file.readText()).also { pages ->
            require(pages.size <= 20 && pages.map { it.id }.distinct().size == pages.size)
            pages.forEach { require(it.agentId == agentId); validate(it) }
        }
    }

    /** Dashboard ids are global, so callers can resolve a deep link without knowing its agent. */
    @Synchronized fun get(id: String): Dashboard? = all().firstOrNull { it.id == id }

    /** Saves a new page or replaces one in place; identities cannot be moved between agents. */
    @Synchronized fun save(dashboard: Dashboard): Dashboard {
        val saved = dashboard.copy(history = dashboard.history.sortedByDescending { it.atMs }.take(30))
        validate(saved)
        val owner = get(saved.id)
        require(owner == null || owner.agentId == saved.agentId) { "Dashboard belongs to another agent" }
        val pages = forAgent(saved.agentId)
        require(pages.any { it.id == saved.id } || pages.size < 20) { "At most 20 dashboards per agent" }
        val next = if (pages.any { it.id == saved.id }) pages.map { if (it.id == saved.id) saved else it } else pages + saved
        write(saved.agentId, next)
        return saved
    }

    /** Applies a change to the latest page under the same lock as persistence. */
    @Synchronized fun update(id: String, change: (Dashboard) -> Dashboard): Dashboard? {
        val current = get(id) ?: return null
        val next = change(current)
        require(next.id == current.id && next.agentId == current.agentId) { "Dashboard identity cannot change" }
        return if (current == next) current else save(next)
    }

    /** Removes a page; timer cancellation belongs to the caller that owns the scheduler. */
    @Synchronized fun delete(id: String): Boolean {
        val current = get(id) ?: return false
        write(current.agentId, forAgent(current.agentId).filterNot { it.id == id })
        return true
    }

    /** Supplies source health and timer state without asking the UI to know the storage layout. */
    fun paths(agentId: String): List<DashboardPath> {
        val pages = forAgent(agentId)
        val scheduled = timers(agentId).associateBy { it.id }
        return pages.map { DashboardPath(it, it.sources, scheduled[it.refreshTimerId]?.nextMs, it.tiles) }
    }

    private fun file(agentId: String): File {
        require(agentId.matches(ID)) { "Invalid agent id" }
        val base = File(root, agentId)
        val file = File(base, "dashboards.json")
        require(listOf(root, base, file).none { Files.isSymbolicLink(it.toPath()) }) { "Linked dashboard state is not allowed" }
        return file
    }

    private fun write(agentId: String, pages: List<Dashboard>) {
        val target = file(agentId)
        val bytes = UmbraJson.encodeToString(ListSerializer(Dashboard.serializer()), pages).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Dashboard state is too large" }
        require(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create dashboard directory" }
        val temp = Files.createTempFile(target.parentFile!!.toPath(), ".dashboards-", ".tmp")
        try {
            Files.write(temp, bytes)
            Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            changes.value++
        } finally { Files.deleteIfExists(temp) }
    }

    private fun validate(d: Dashboard) {
        require(d.id.matches(ID) && d.agentId.matches(ID)) { "Invalid dashboard identity" }
        require(d.title.isNotBlank() && d.title.length <= 200 && (d.subtitle?.length ?: 0) <= 1000)
        require((d.icon?.length ?: 0) <= 80 && (d.statusText?.length ?: 0) <= 1000)
        require((d.refreshCron?.length ?: 0) <= 200 && (d.refreshTimerId?.length ?: 0) <= 100)
        require(d.tiles.size <= 24 && d.tiles.map { it.id }.distinct().size == d.tiles.size) { "At most 24 unique tiles per dashboard" }
        require(d.sources.size <= 24 && d.sources.map { it.id }.distinct().size == d.sources.size) { "At most 24 unique sources per dashboard" }
        d.sources.forEach { s ->
            require(s.id.matches(ID))
            require((s.path?.length ?: 0) <= 4096 && (s.error?.length ?: 0) <= 300 && (s.fingerprint?.length ?: 0) <= 300)
            if (s.kind == Source.Kind.FILE || s.kind == Source.Kind.FOLDER) requireWorkspacePath(requireNotNull(s.path))
        }
        d.tiles.forEach { t ->
            require(t.id.matches(ID) && t.title.isNotBlank() && t.title.length <= 200 && (t.note?.length ?: 0) <= 2000)
            validateText(UmbraJson.encodeToJsonElement(CardBlock.serializer(), t.block))
            t.binding?.let { binding ->
                val source = binding.sourceId()?.let { id -> requireNotNull(d.sources.find { it.id == id }) { "Unknown binding source: $id" } }
                val kind = when (binding) {
                    is Binding.FolderList, is Binding.FolderStats -> Source.Kind.FOLDER
                    is Binding.Tasks -> Source.Kind.TASKS
                    else -> Source.Kind.FILE
                }
                require(if (binding is Binding.Tasks) binding.agentId == d.agentId && d.sources.any { it.kind == kind } else source?.kind == kind) { "Binding has an incompatible source" }
                require(DashboardValues.accepts(t.block, binding)) { "Binding has an incompatible block" }
                when (binding) {
                    is Binding.TextTail -> require(binding.lines in 1..1000)
                    is Binding.TextCount -> DashboardValues.pattern(binding.regex)
                    is Binding.FolderList -> require(binding.limit in 1..200)
                    is Binding.CsvTable -> { require(binding.limit in 1..200); binding.columns?.let(::validateColumns) }
                    is Binding.JsonTable -> { require(binding.path.length <= 1000); validateColumns(binding.columns) }
                    is Binding.JsonValue -> require(binding.path.length <= 1000)
                    else -> Unit
                }
            }
        }
        require(d.history.size <= 30)
        d.history.forEach { require(it.by in setOf("agent", "file", "folder", "tasks") && it.text.length <= 2000) }
    }

    private fun validateColumns(columns: List<String>) {
        require(columns.size in 1..24 && columns.distinct().size == columns.size && columns.all { it.isNotBlank() && it.length <= 200 })
    }

    private fun validateText(element: JsonElement) {
        when (element) {
            is JsonPrimitive -> require(element.content.length <= 20_000) { "Block text is too long" }
            is JsonObject -> { require(element.size <= 100); element.values.forEach(::validateText) }
            is JsonArray -> { require(element.size <= 1000); element.forEach(::validateText) }
        }
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9_-]{1,100}")
        const val MAX_BYTES = 4_000_000
    }
}

/** Rejects ambiguous guest paths before the app's mapper can resolve them. */
internal fun requireWorkspacePath(path: String) {
    require((path == "/workspace" || path.startsWith("/workspace/")) &&
        path.split('/').none { it == "." || it == ".." } && path.none { it.isISOControl() || it == '\\' }) {
        "Source paths must be absolute paths under /workspace without dot segments"
    }
}

/** Task bindings follow the session flow rather than naming a file source. */
internal fun Binding.sourceId(): String? = when (this) {
    is Binding.JsonValue -> sourceId
    is Binding.JsonTable -> sourceId
    is Binding.CsvTable -> sourceId
    is Binding.TextTail -> sourceId
    is Binding.TextCount -> sourceId
    is Binding.FolderList -> sourceId
    is Binding.FolderStats -> sourceId
    is Binding.Tasks -> null
}
