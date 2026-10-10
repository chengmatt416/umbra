package app.umbra.runtime.omp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/*
 * Typed views of what omp's RPC protocol returns (src/modes/rpc/wire/rpc-wire.generated.ts in omp 18.6.1). They keep
 * the fields a client shows or acts on; anything else stays available in the raw JSON where a view keeps it.
 */

/** A model omp can use (`ModelInfo`). */
data class OmpModelInfo(
    val provider: String,
    val id: String,
    val name: String,
    val api: String?,
    val reasoning: Boolean,
    val input: List<String>,
    val contextWindow: Long?,
    val maxTokens: Long?,
    /** USD per million tokens. */
    val costInput: Double?,
    val costOutput: Double?,
    /** Thinking efforts the model takes, when it reports them. */
    val efforts: List<String>,
) {
    val key: String get() = "$provider/$id"
    val takesImages: Boolean get() = "image" in input

    companion object {
        fun from(o: JsonObject): OmpModelInfo = OmpModelInfo(
            provider = o.str("provider").orEmpty(),
            id = o.str("id").orEmpty(),
            name = o.str("name") ?: o.str("id").orEmpty(),
            api = o.str("api"),
            reasoning = o.bool("reasoning") ?: false,
            input = o.strList("input"),
            contextWindow = o.long("contextWindow"),
            maxTokens = o.long("maxTokens"),
            costInput = (o.obj("cost")?.get("input") as? JsonPrimitive)?.doubleOrNull,
            costOutput = (o.obj("cost")?.get("output") as? JsonPrimitive)?.doubleOrNull,
            efforts = o.obj("thinking")?.strList("efforts").orEmpty(),
        )
    }
}

data class OmpTodoItem(val content: String, val status: String, val blocker: String? = null, val details: String? = null, val notes: List<String> = emptyList()) {
    fun toJson(): JsonObject = JsonObject(
        buildMap {
            put("content", JsonPrimitive(content))
            put("status", JsonPrimitive(status))
            blocker?.let { put("blocker", JsonPrimitive(it)) }
            details?.let { put("details", JsonPrimitive(it)) }
            if (notes.isNotEmpty()) put("notes", JsonArray(notes.map(::JsonPrimitive)))
        },
    )

    companion object {
        /** `pending`, `in_progress`, `completed`, `abandoned`, `blocked`. */
        val STATUSES = listOf("pending", "in_progress", "completed", "abandoned", "blocked")

        fun from(o: JsonObject) = OmpTodoItem(o.str("content").orEmpty(), o.str("status") ?: "pending", o.str("blocker"), o.str("details"), o.strList("notes"))
    }
}

data class OmpTodoPhase(val name: String, val tasks: List<OmpTodoItem>) {
    fun toJson(): JsonObject = JsonObject(mapOf("name" to JsonPrimitive(name), "tasks" to JsonArray(tasks.map { it.toJson() })))

    companion object {
        fun from(o: JsonObject) = OmpTodoPhase(o.str("name").orEmpty(), o.arr("tasks")?.mapNotNull { (it as? JsonObject)?.let(OmpTodoItem::from) }.orEmpty())
    }
}

data class OmpContextUsage(val tokens: Long, val contextWindow: Long, val percent: Double) {
    companion object {
        fun from(o: JsonObject?) = o?.let { OmpContextUsage(it.long("tokens") ?: 0, it.long("contextWindow") ?: 0, (it["percent"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) }
    }
}

/** A session goal (goal mode): the objective and how much it has used. */
data class OmpGoal(
    val id: String,
    val objective: String,
    /** `active`, `paused`, `budget-limited`, `complete`, `dropped`. */
    val status: String,
    val tokensUsed: Long,
    val timeUsedSeconds: Long,
    val tokenBudget: Long?,
) {
    companion object {
        fun from(o: JsonObject?) = o?.let {
            OmpGoal(it.str("id").orEmpty(), it.str("objective").orEmpty(), it.str("status") ?: "active", it.long("tokensUsed") ?: 0, it.long("timeUsedSeconds") ?: 0, it.long("tokenBudget"))
        }
    }
}

/** `get_state`: everything about the live session a client shows next to the conversation. */
data class OmpSessionState(
    val sessionId: String,
    val model: OmpModelInfo?,
    val thinkingLevel: String?,
    val isStreaming: Boolean,
    val isCompacting: Boolean,
    val steeringMode: String,
    val followUpMode: String,
    val interruptMode: String,
    val sessionFile: String?,
    val sessionName: String?,
    val autoCompaction: Boolean,
    val fastModeEnabled: Boolean,
    val fastModeActive: Boolean,
    val tokensPerSecond: Double?,
    val messageCount: Int,
    val queuedSteering: List<String>,
    val queuedFollowUp: List<String>,
    val hasPendingAsyncWork: Boolean,
    val isSettled: Boolean,
    val todos: List<OmpTodoPhase>,
    val contextUsage: OmpContextUsage?,
    val goal: OmpGoal?,
    val goalEnabled: Boolean,
) {
    companion object {
        fun from(o: JsonObject): OmpSessionState {
            val queued = o.obj("queuedMessages")
            val goalState = o.obj("goal")
            return OmpSessionState(
                sessionId = o.str("sessionId").orEmpty(),
                model = o.obj("model")?.let(OmpModelInfo::from),
                thinkingLevel = o.str("thinkingLevel"),
                isStreaming = o.bool("isStreaming") ?: false,
                isCompacting = o.bool("isCompacting") ?: false,
                steeringMode = o.str("steeringMode") ?: "one-at-a-time",
                followUpMode = o.str("followUpMode") ?: "one-at-a-time",
                interruptMode = o.str("interruptMode") ?: "immediate",
                sessionFile = o.str("sessionFile"),
                sessionName = o.str("sessionName"),
                autoCompaction = o.bool("autoCompactionEnabled") ?: true,
                fastModeEnabled = o.bool("fastModeEnabled") ?: false,
                fastModeActive = o.bool("fastModeActive") ?: false,
                tokensPerSecond = (o["tokensPerSecond"] as? JsonPrimitive)?.doubleOrNull,
                messageCount = o.int("messageCount") ?: 0,
                queuedSteering = queued?.strList("steering").orEmpty(),
                queuedFollowUp = queued?.strList("followUp").orEmpty(),
                hasPendingAsyncWork = o.bool("hasPendingAsyncWork") ?: false,
                isSettled = o.bool("isSettled") ?: true,
                todos = o.arr("todoPhases")?.mapNotNull { (it as? JsonObject)?.let(OmpTodoPhase::from) }.orEmpty(),
                contextUsage = OmpContextUsage.from(o.obj("contextUsage")),
                goal = OmpGoal.from(goalState?.obj("goal")),
                goalEnabled = goalState?.bool("enabled") ?: false,
            )
        }
    }
}

/** A slash command the session accepts right now (builtin, skill, extension, custom, MCP prompt or file). */
data class OmpSlashCommand(
    val name: String,
    val source: String,
    val aliases: List<String>,
    val description: String?,
    val inputHint: String?,
    val subcommands: List<Subcommand>,
) {
    data class Subcommand(val name: String, val description: String?, val usage: String?)

    companion object {
        fun from(o: JsonObject) = OmpSlashCommand(
            name = o.str("name").orEmpty(),
            source = o.str("source") ?: "builtin",
            aliases = o.strList("aliases"),
            description = o.str("description"),
            inputHint = o.obj("input")?.str("hint"),
            subcommands = o.arr("subcommands")?.mapNotNull { s ->
                (s as? JsonObject)?.let { Subcommand(it.str("name").orEmpty(), it.str("description"), it.str("usage")) }
            }.orEmpty(),
        )
    }
}

data class OmpSessionStats(
    val userMessages: Int,
    val assistantMessages: Int,
    val toolCalls: Int,
    val totalMessages: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val totalTokens: Long,
    val cost: Double,
    val premiumRequests: Int,
    val contextUsage: OmpContextUsage?,
    val sessionFile: String?,
) {
    companion object {
        fun from(o: JsonObject): OmpSessionStats {
            val t = o.obj("tokens")
            return OmpSessionStats(
                o.int("userMessages") ?: 0, o.int("assistantMessages") ?: 0, o.int("toolCalls") ?: 0, o.int("totalMessages") ?: 0,
                t?.long("input") ?: 0, t?.long("output") ?: 0, t?.long("cacheRead") ?: 0, t?.long("cacheWrite") ?: 0, t?.long("total") ?: 0,
                (o["cost"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, o.int("premiumRequests") ?: 0,
                OmpContextUsage.from(o.obj("contextUsage")), o.str("sessionFile"),
            )
        }
    }
}

/**
 * One node of the session tree (`get_tree`): an entry, what it says in one line, and the branches below it. Every
 * message the user or the agent wrote is a point the conversation can go back to ([OmpClient.branch]) or be copied
 * from into a new session ([OmpClient.fork]).
 */
data class OmpTreeNode(
    val id: String,
    val parentId: String?,
    /** The entry type: `message`, `compaction`, `branch_summary`, `model_change`, `label`, … */
    val type: String,
    /** For messages: `user`, `assistant`, `toolResult`, `bashExecution`, … */
    val role: String?,
    val preview: String,
    val label: String?,
    val timestamp: String?,
    val children: List<OmpTreeNode>,
) {
    companion object {
        fun from(node: JsonObject): OmpTreeNode {
            val entry = node.obj("entry") ?: JsonObject(emptyMap())
            val message = entry.obj("message")
            val preview = when (entry.str("type")) {
                "message" -> message?.let(::previewOf).orEmpty()
                "compaction" -> "Compacted: " + (entry.str("shortSummary") ?: entry.str("summary")?.take(120)).orEmpty()
                "branch_summary" -> "Branch summary: " + entry.str("summary")?.take(120).orEmpty()
                "model_change" -> "Model: ${entry.str("provider").orEmpty()}/${entry.str("modelId").orEmpty()}"
                "thinking_level_change" -> "Thinking: ${entry.str("thinkingLevel").orEmpty()}"
                else -> entry.str("type").orEmpty()
            }
            return OmpTreeNode(
                id = entry.str("id").orEmpty(),
                parentId = entry.str("parentId"),
                type = entry.str("type").orEmpty(),
                role = message?.str("role"),
                preview = preview.replace('\n', ' ').trim().take(200),
                label = node.str("label"),
                timestamp = entry.str("timestamp"),
                children = node.arr("children")?.mapNotNull { (it as? JsonObject)?.let(::from) }.orEmpty(),
            )
        }

        /** One line for a transcript message. */
        fun previewOf(message: JsonObject): String = when (message.str("role")) {
            "bashExecution" -> "! " + message.str("command").orEmpty()
            "pythonExecution" -> "python: " + message.str("code").orEmpty()
            "branchSummary", "compactionSummary" -> message.str("summary").orEmpty()
            "fileMention" -> "@" + message.arr("files")?.mapNotNull { (it as? JsonObject)?.str("path") }?.joinToString(", @").orEmpty()
            "toolResult" -> "${message.str("toolName").orEmpty()} result"
            "assistant" -> {
                val text = OmpEvent.messageText(message)
                if (text.isNotBlank()) text else message.arr("content")?.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "toolCall" }?.str("name") }?.joinToString(", ")?.let { "→ $it" }.orEmpty()
            }
            else -> OmpEvent.messageText(message)
        }
    }
}

data class OmpTree(val roots: List<OmpTreeNode>, val leafId: String?) {
    /** The entries from the root to the current leaf: the branch the conversation is on. */
    fun currentPath(): Set<String> {
        val parents = HashMap<String, String?>()
        fun walk(n: OmpTreeNode) { parents[n.id] = n.parentId; n.children.forEach(::walk) }
        roots.forEach(::walk)
        val path = HashSet<String>()
        var at = leafId
        while (at != null && path.add(at)) at = parents[at]
        return path
    }
}

data class OmpSubagent(
    val id: String,
    val index: Int,
    val agent: String,
    /** `pending`, `running`, `completed`, `failed`, `aborted`. */
    val status: String,
    val description: String?,
    val task: String?,
    val sessionFile: String?,
    val lastUpdate: Long,
) {
    companion object {
        fun from(o: JsonObject) = OmpSubagent(
            o.str("id").orEmpty(), o.int("index") ?: 0, o.str("agent").orEmpty(), o.str("status") ?: "running",
            o.str("description"), o.str("task") ?: o.str("assignment"), o.str("sessionFile"), o.long("lastUpdate") ?: 0,
        )
    }
}

data class OmpSubagentMessages(val sessionFile: String, val nextByte: Long, val reset: Boolean, val messages: List<JsonObject>)

data class OmpLoginProvider(val id: String, val name: String, val available: Boolean, val authenticated: Boolean)

data class OmpBashResult(
    val output: String,
    val exitCode: Int?,
    val cancelled: Boolean,
    val truncated: Boolean,
    val timedOut: Boolean,
    val workingDir: String?,
) {
    companion object {
        fun from(o: JsonObject) = OmpBashResult(
            o.str("output").orEmpty(), o.int("exitCode"), o.bool("cancelled") ?: false, o.bool("truncated") ?: false, o.bool("timedOut") ?: false, o.str("workingDir"),
        )
    }
}

data class OmpCompaction(val summary: String, val shortSummary: String?, val tokensBefore: Long) {
    companion object {
        fun from(o: JsonObject?) = o?.let { OmpCompaction(it.str("summary").orEmpty(), it.str("shortSummary"), it.long("tokensBefore") ?: 0) }
    }
}

/** A page of the transcript (`get_messages_page`). */
data class OmpMessagesPage(val messages: List<JsonObject>, val totalMessages: Int, val nextCursor: String?)

internal fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
