package app.umbra.agent.dashboards

import app.umbra.agent.agents.AgentStore
import app.umbra.agent.agents.AgentTimer
import app.umbra.agent.agents.Cron
import app.umbra.agent.cards.CardBlock
import app.umbra.core.model.UmbraJson
import app.umbra.runtime.omp.HostToolDefinition
import app.umbra.runtime.omp.HostToolPayload
import app.umbra.runtime.omp.SchemaBuilder
import app.umbra.runtime.omp.objectSchema
import app.umbra.tools.HostSideTool
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * Agent-owned dashboard tools. Scheduler access is injected so parsing, schemas and timer rules can be tested
 * without an AppContainer or Android service; the caller wires reschedule to the agent runtime.
 */
class DashboardTools(
    private val store: DashboardStore,
    private val agents: AgentStore,
    private val reschedule: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Tools close over their owner; knowing another agent's dashboard id never grants write access. */
    fun forAgent(agentId: String, sessionId: String? = null): List<HostSideTool> {
        require(agents.get(agentId) != null) { "Unknown agent" }
        return listOf(
            tool("dashboard_create", "Create a live page. $PURPOSE " +
                "Send block objects with lowercase type discriminators, not a CardSpec or Kotlin class name. " +
                "Examples: {\"type\":\"stats\",\"items\":[{\"label\":\"Spent\",\"value\":\"120\"}]} and " +
                "{\"type\":\"progress\",\"value\":0.6,\"label\":\"Build\"}. " +
                "Sources default to source1, source2 in order; bind.sourceId refers to that id. " +
                "JSON/table columns are source keys or CSV headers; a chart uses a label column followed by numeric values, " +
                "or a single numeric column for Columns/Line. JsonValue changes the first stat. " +
                "folder_stats fills your stats in order (number of files, total size, last change) and tasks fills (running, waiting, " +
                "done today, failed today): give one stat per figure you want shown, labelled in the user's language, or none for all. " +
                "Bind types are json_value, json_table, csv_table, text_tail, text_count, folder_list, folder_stats, tasks. " +
                "text_count uses RE2 syntax without lookarounds or backreferences. " +
                "TASKS bindings default to this agent. Paths must be under /workspace. " +
                "A cron has five fields and may run at most every five minutes; at most 50 agent timers. " +
                "agent_on_change wakes you for source changes even when all tiles are bound.", objectSchema {
                string("title", required = true)
                string("subtitle")
                string("icon", "A card icon name")
                objectArray("tiles", required = true) { tileSchema() }
                objectArray("sources") {
                    string("id", "Optional; defaults to source1, source2, etc.")
                    enum("kind", Source.Kind.entries.map { it.name }, required = true)
                    string("path", "Absolute guest path under /workspace for FILE and FOLDER")
                }
                string("refresh_cron", "Five-field local-time cron")
                string("refresh_instruction", "What you should read and update on each scheduled refresh")
                boolean("agent_on_change")
            }) { a ->
                val time = now()
                val sources = a.objects("sources").mapIndexed { index, source -> Source(
                    id = source.str("id") ?: "source${index + 1}",
                    kind = Source.Kind.valueOf(source.requiredString("kind")), path = source.str("path")) }.toMutableList()
                val tiles = a.objects("tiles", required = true).map { tile(it, agentId, time) }
                if (tiles.any { it.binding is Binding.Tasks } && sources.none { it.kind == Source.Kind.TASKS }) {
                    sources += Source(unique("source"), Source.Kind.TASKS)
                }
                val dashboard = Dashboard(unique("dashboard"), agentId, a.requiredString("title"), a.str("subtitle"), a.str("icon"),
                    tiles, sources, createdAtMs = time, agentOnChange = a.bool("agent_on_change") ?: false,
                    lastRefreshAtMs = time, history = listOf(Update(time, "agent", "Dashboard created")))
                val cron = a.str("refresh_cron")
                val saved = if (cron == null) store.save(dashboard) else scheduled(dashboard, cron, a.str("refresh_instruction"), create = true)
                HostToolPayload.text("Created ${saved.id}. Use dashboard_update(id=\"${saved.id}\", tiles=[{id or title, block or note}], note=\"what changed\"). " +
                    "Tile ids: ${saved.tiles.joinToString { "${it.id} (${it.title})" }}. Bound tiles update themselves.")
            },
            tool("dashboard_update", "Update your page's model-maintained content or annotate its tiles. $PURPOSE " +
                "Identify each existing tile by id or an unambiguous title. Blocks use the same lowercase type JSON as dashboard_create. " +
                "Added tiles use the existing source ids; remove_tiles contains tile ids or unambiguous titles. " +
                "Use note to explain the change in history; status PAUSED stops source watching.", objectSchema {
                string("id", required = true)
                objectArray("tiles") {
                    string("id")
                    string("title")
                    raw("block", DashboardSchemas.block)
                    string("note")
                }
                objectArray("add_tiles") { tileSchema() }
                stringArray("remove_tiles")
                enum("status", Dashboard.Status.entries.map { it.name })
                string("status_text")
                string("note")
            }) { a ->
                val id = a.requiredString("id")
                owned(agentId, id)
                val time = now()
                val patches = a.objects("tiles")
                val added = a.objects("add_tiles").map { tile(it, agentId, time) }
                val removed = a.strings("remove_tiles")
                val saved = requireNotNull(store.update(id) { current ->
                    require(current.agentId == agentId)
                    val removeIds = removed.map { findTile(current.tiles, it).id }.toSet()
                    val edits = patches.map { patch ->
                        val target = patch.str("id") ?: patch.str("title") ?: error("Each tile needs an id or title")
                        val original = findTile(current.tiles, target)
                        if (patch.str("id") != null && patch.str("title") != null) require(original.title == patch.str("title")) { "Tile id and title disagree" }
                        require(original.id !in removeIds) { "Cannot edit and remove the same tile" }
                        original.id to patch
                    }
                    require(edits.map { it.first }.distinct().size == edits.size) { "A tile may be updated only once per call" }
                    val byId = edits.toMap()
                    current.copy(tiles = current.tiles.filterNot { it.id in removeIds }.map { original ->
                        byId[original.id]?.let { patch -> original.copy(
                            block = patch["block"]?.let(::decodeBlock) ?: original.block,
                            note = if ("note" in patch) patch.str("note") else original.note, updatedAtMs = time)
                        } ?: original
                    } + added, status = a.str("status")?.let(Dashboard.Status::valueOf) ?: current.status,
                        statusText = if ("status_text" in a) a.str("status_text") else current.statusText,
                        updatedAtMs = time, lastRefreshAtMs = time,
                        history = listOf(Update(time, "agent", a.str("note") ?: "Dashboard updated")) + current.history)
                })
                HostToolPayload.text("Updated ${saved.id}; ${saved.tiles.size} tiles, ${saved.status}.")
            },
            tool("dashboard_list", "List your pages, source ids and source errors. $PURPOSE", objectSchema {}) { _ ->
                HostToolPayload.text(store.forAgent(agentId).joinToString("\n") { d ->
                    "${d.id} · ${d.title} · ${d.status} · last refresh ${d.lastRefreshAtMs ?: "never"}\n" +
                        d.sources.joinToString("\n") { "  ${it.id} · ${it.kind} · ${it.path.orEmpty()} · last seen ${it.lastSeenMs}" + (it.error?.let { e -> " · error: $e" } ?: "") }
                }.ifEmpty { "(no dashboards)" })
            },
            tool("dashboard_remove", "Delete your page and cancel its refresh timer. $PURPOSE", objectSchema {
                string("id", required = true)
            }) { a ->
                val dashboard = synchronized(agents) {
                    val dashboard = owned(agentId, a.requiredString("id"))
                    val before = agents.timers(agentId)
                    agents.setTimers(agentId, before.filterNot { it.id == dashboard.refreshTimerId || it.id == changeTimerId(dashboard.id) })
                    try { require(store.delete(dashboard.id)) { "Dashboard no longer exists" } }
                    catch (e: Exception) { agents.setTimers(agentId, before); throw e }
                    dashboard
                }
                reschedule()
                HostToolPayload.text("Removed ${dashboard.id} and its refresh timer.")
            },
            tool("dashboard_schedule", "Set or replace your page's refresh timer; omit cron to cancel it. $PURPOSE " +
                "Use a five-field local-time cron with at least five minutes between fires. At most 50 agent timers. " +
                "instruction tells you what to update when the timer wakes you; omit it to keep the existing instruction.", objectSchema {
                string("id", required = true)
                string("cron")
                string("instruction")
            }) { a ->
                val dashboard = owned(agentId, a.requiredString("id"))
                val saved = scheduled(dashboard, a.str("cron"), a.str("instruction"))
                HostToolPayload.text(if (saved.refreshTimerId == null) "Refresh timer cancelled for ${saved.id}." else "Scheduled ${saved.id}: ${saved.refreshCron}.")
            },
        )
    }

    /** The user's Remove on the dashboard page: the page and its refresh timer go, as with dashboard_remove. */
    fun remove(id: String) {
        synchronized(agents) {
            val dashboard = store.get(id) ?: return
            val before = agents.timers(dashboard.agentId)
            agents.setTimers(dashboard.agentId, before.filterNot { it.id == dashboard.refreshTimerId || it.id == changeTimerId(dashboard.id) })
            try { store.delete(dashboard.id) } catch (e: Exception) { agents.setTimers(dashboard.agentId, before); throw e }
        }
        reschedule()
    }

    /** The user's "Stop the scheduled refresh": the timer is cancelled; the page stays and still follows its sources. */
    fun unschedule(id: String) {
        val dashboard = store.get(id) ?: return
        if (dashboard.refreshTimerId != null || dashboard.refreshCron != null) scheduled(dashboard, null, null)
    }

    private fun scheduled(dashboard: Dashboard, cron: String?, instruction: String?, create: Boolean = false): Dashboard {
        val time = now()
        val next = cron?.let { DashboardSchedules.next(it, time) }
        val saved = synchronized(agents) {
            val current = if (create) dashboard else owned(dashboard.agentId, dashboard.id)
            val before = agents.timers(current.agentId)
            val previous = before.find { it.id == current.refreshTimerId }
            val remaining = before.filterNot { it.id == current.refreshTimerId }
            require(next == null || remaining.size < 50) { "This agent already has 50 timers; cancel some first" }
            val prefix = "Update dashboard \"${current.title}\" (${current.id}): "
            val text = instruction ?: previous?.note?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
                ?: "Refresh the model-maintained tiles from their sources with dashboard_update."
            require(next == null || text.isNotBlank() && text.length <= 2000) { "Refresh instruction must contain 1 to 2000 characters" }
            val timer = next?.let { AgentTimer(previous?.id ?: unique("dt"), prefix + text, it, cron!!.trim(), "agent", time) }
            agents.setTimers(current.agentId, remaining + listOfNotNull(timer))
            try {
                fun change(d: Dashboard) = d.copy(refreshCron = cron?.trim(), refreshTimerId = timer?.id, updatedAtMs = time,
                    history = if (create) d.history else listOf(Update(time, "agent", if (timer == null) "Refresh timer cancelled" else "Refresh schedule updated")) + d.history)
                if (create) store.save(change(current)) else requireNotNull(store.update(current.id, ::change)) { "Dashboard no longer exists" }
            } catch (e: Exception) { agents.setTimers(current.agentId, before); throw e }
        }
        reschedule()
        return saved
    }

    private fun tile(a: JsonObject, agentId: String, time: Long): Tile {
        val binding = a["bind"]?.let { element ->
            val obj = element as? JsonObject ?: error("bind must be an object")
            val normalized = if (obj.str("type") == "tasks" && "agentId" !in obj) JsonObject(obj + ("agentId" to JsonPrimitive(agentId))) else obj
            UmbraJson.decodeFromJsonElement(Binding.serializer(), normalized)
        }
        return Tile(unique("tile"), a.requiredString("title"), a.str("size")?.let(Tile.Size::valueOf) ?: Tile.Size.SMALL,
            decodeBlock(requireNotNull(a["block"]) { "block is missing" }), binding, time, a.str("note"))
    }

    private fun decodeBlock(element: JsonElement): CardBlock = UmbraJson.decodeFromJsonElement(CardBlock.serializer(), element)

    private fun owned(agentId: String, id: String): Dashboard = requireNotNull(store.get(id)?.takeIf { it.agentId == agentId }) { "Dashboard not found for this agent" }

    private fun findTile(tiles: List<Tile>, idOrTitle: String): Tile = tiles.find { it.id == idOrTitle }
        ?: tiles.filter { it.title == idOrTitle }.singleOrNull() ?: error("Tile not found or title is ambiguous: $idOrTitle")

    private fun tool(name: String, description: String, schema: JsonObject, run: suspend (JsonObject) -> HostToolPayload): HostSideTool =
        HostSideTool(HostToolDefinition(name, description, schema)) { call, _ ->
            try { run(call.arguments) } catch (e: Exception) {
                if (e is CancellationException) throw e
                HostToolPayload.error((e.message ?: "Dashboard operation failed").take(500))
            }
        }

    private fun SchemaBuilder.tileSchema() {
        string("title", required = true)
        enum("size", Tile.Size.entries.map { it.name })
        raw("block", DashboardSchemas.block, required = true)
        raw("bind", DashboardSchemas.binding)
        string("note")
    }

    private fun unique(prefix: String): String = prefix + UUID.randomUUID().toString().replace("-", "")

    private fun JsonObject.str(key: String): String? {
        val value = this[key] ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive && value.isString) { "$key must be a string" }
        return value.contentOrNull
    }

    private fun JsonObject.requiredString(key: String): String = requireNotNull(str(key)?.takeIf { it.isNotBlank() }) { "$key is missing" }

    private fun JsonObject.bool(key: String): Boolean? {
        val value = this[key] ?: return null
        return requireNotNull((value as? JsonPrimitive)?.booleanOrNull) { "$key must be a boolean" }
    }

    private fun JsonObject.objects(key: String, required: Boolean = false): List<JsonObject> {
        val value = this[key] ?: return if (required) error("$key is missing") else emptyList()
        require(value is JsonArray) { "$key must be an array" }
        return value.map { it as? JsonObject ?: error("$key must contain objects") }
    }

    private fun JsonObject.strings(key: String): List<String> {
        val value = this[key] ?: return emptyList()
        require(value is JsonArray) { "$key must be an array" }
        return value.map { require(it is JsonPrimitive && it.isString) { "$key must contain strings" }; it.content }
    }

    /** The host can use these names when applying its tool visibility policy. */
    companion object {
        /** Names of every tool returned by forAgent. */
        val NAMES = listOf("dashboard_create", "dashboard_update", "dashboard_list", "dashboard_remove", "dashboard_schedule")

        /** The one-shot timer that wakes the agent when a watched file under unbound tiles changed; removing goes with it. */
        fun changeTimerId(dashboardId: String) = "dash-$dashboardId"
        private const val PURPOSE = "Use a dashboard for something that changes over time and the user will return to; use a card for a one-off answer. Bound tiles update themselves without the model."
    }
}

/** Validates the complete daily pattern, including gaps that a check of only the next two fires would miss. */
internal object DashboardSchedules {
    /** Checks the repeating pattern before returning the first due time, including gaps across midnight. */
    fun next(expression: String, afterMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        require(expression.length <= 200)
        val fields = expression.trim().split(Regex("\\s+"))
        require(fields.size == 5 && fields.all { field -> field.split(',').all { it.matches(Regex("(\\*|[0-9]+(-[0-9]+)?)(/[0-9]+)?")) } }) { "Invalid five-field cron" }
        val cron = Cron(expression)
        val next = try { cron.next(afterMs, zone) } catch (e: IllegalStateException) { throw IllegalArgumentException("Cron never matches", e) }
        fun values(field: String, min: Int, max: Int): List<Int> = field.split(',').flatMap { part ->
            val pieces = part.split('/')
            val range = pieces[0]
            val step = pieces.getOrNull(1)?.toInt() ?: 1
            val bounds = when {
                range == "*" -> min to max
                '-' in range -> range.split('-').let { it[0].toInt() to it[1].toInt() }
                else -> range.toInt().let { it to if (pieces.size == 2) max else it }
            }
            (bounds.first..bounds.second step step).toList()
        }.distinct().sorted()
        val minutes = values(fields[0], 0, 59)
        val times = values(fields[1], 0, 23).flatMap { hour -> minutes.map { hour * 60 + it } }.sorted()
        require(times.zipWithNext().all { (a, b) -> b - a >= 5 }) { "A repeating timer may fire at most every 5 minutes" }
        if (times.first() + 1440 - times.last() < 5) {
            val days = values(fields[2], 1, 31)
            val months = values(fields[3], 1, 12)
            val weekdays = values(fields[4], 0, 7).map { it % 7 }
            fun matches(day: LocalDate): Boolean {
                val dom = day.dayOfMonth in days
                val dow = day.dayOfWeek.value % 7 in weekdays
                return day.monthValue in months && when {
                    fields[2] != "*" && fields[4] != "*" -> dom || dow
                    fields[2] != "*" -> dom
                    else -> dow
                }
            }
            var day = LocalDate.ofInstant(Instant.ofEpochMilli(next), zone)
            val end = day.plusYears(5)
            while (day.isBefore(end)) {
                require(!matches(day) || !matches(day.plusDays(1))) { "A repeating timer may fire at most every 5 minutes" }
                day = day.plusDays(1)
            }
        }
        return next
    }
}

/** Derives exact block and binding schemas from the same serial descriptors used to decode tool arguments. */
@OptIn(ExperimentalSerializationApi::class)
internal object DashboardSchemas {
    /** Uses the card serializers so tool field names and discriminators cannot drift from the renderer. */
    val block: JsonObject by lazy { sealedSchema(CardBlock.serializer().descriptor) }
    /** Describes bindings while letting task bindings inherit the tool's agent identity. */
    val binding: JsonObject by lazy { sealedSchema(Binding.serializer().descriptor, tasksDefault = true) }

    private fun sealedSchema(descriptor: SerialDescriptor, tasksDefault: Boolean = false): JsonObject = buildJsonObject {
        val variants = descriptor.getElementDescriptor(1)
        put("oneOf", JsonArray((0 until variants.elementsCount).map { index ->
            val subtype = variants.getElementDescriptor(index)
            val type = subtype.serialName
            val schema = schema(subtype)
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val required = (schema["required"] as? JsonArray).orEmpty().filterNot { tasksDefault && type == "tasks" && it == JsonPrimitive("agentId") }
            JsonObject(schema + mapOf(
                "properties" to JsonObject(properties + ("type" to buildJsonObject { put("const", type); put("type", "string") })),
                "required" to JsonArray(listOf(JsonPrimitive("type")) + required)))
        }))
    }

    private fun schema(d: SerialDescriptor): JsonObject {
        val result = buildJsonObject {
            when (d.kind) {
                PrimitiveKind.STRING, PrimitiveKind.CHAR -> put("type", "string")
                PrimitiveKind.BOOLEAN -> put("type", "boolean")
                PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> put("type", "number")
                PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> put("type", "integer")
                SerialKind.ENUM -> { put("type", "string"); put("enum", JsonArray((0 until d.elementsCount).map { JsonPrimitive(d.getElementName(it)) })) }
                StructureKind.LIST -> { put("type", "array"); put("items", schema(d.getElementDescriptor(0))) }
                StructureKind.CLASS, StructureKind.OBJECT -> {
                    put("type", "object")
                    put("properties", buildJsonObject { for (i in 0 until d.elementsCount) put(d.getElementName(i), schema(d.getElementDescriptor(i))) })
                    put("required", JsonArray((0 until d.elementsCount).filterNot { d.isElementOptional(it) }.map { JsonPrimitive(d.getElementName(it)) }))
                    put("additionalProperties", false)
                }
                else -> error("Unsupported dashboard schema: ${d.serialName}")
            }
        }
        return if (d.isNullable) buildJsonObject {
            put("anyOf", JsonArray(listOf(result, buildJsonObject { put("type", "null") })))
        } else result
    }
}
