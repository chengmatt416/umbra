package app.umbra.runtime.omp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The rest of omp's RPC commands (rpc-types.ts), typed. Together with OmpClient's own they cover the whole
 * protocol a GUI needs: everything omp can do without its terminal UI.
 */

private fun params(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) when (v) {
        null -> Unit
        is String -> put(k, v)
        is Boolean -> put(k, v)
        is Int -> put(k, v)
        is Long -> put(k, v)
        is JsonArray -> put(k, v)
        is JsonObject -> put(k, v)
        else -> error("unsupported parameter type for $k")
    }
}

private suspend fun OmpClient.data(command: String, vararg pairs: Pair<String, Any?>, timeoutMs: Long? = null): JsonObject? =
    (if (timeoutMs != null) requestOk(command, params(*pairs), timeoutMs) else requestOk(command, params(*pairs))) as? JsonObject

// ---- state ----

suspend fun OmpClient.sessionState(): OmpSessionState = OmpSessionState.from(getState())

suspend fun OmpClient.models(): List<OmpModelInfo> = data("get_available_models")?.objects("models")?.map(OmpModelInfo::from).orEmpty()

/** Switches the model of this session; returns the model now in use. */
suspend fun OmpClient.switchModel(provider: String, modelId: String): OmpModelInfo? = setModel(provider, modelId)?.let(OmpModelInfo::from)

/** The next of the configured models (scoped models first). Null when there is no other. */
suspend fun OmpClient.cycleModel(): OmpModelInfo? = data("cycle_model")?.obj("model")?.let(OmpModelInfo::from)

suspend fun OmpClient.thinkingLevels(): List<String> = data("get_available_thinking_levels")?.strList("levels").orEmpty()

suspend fun OmpClient.cycleThinkingLevel(): String? = data("cycle_thinking_level")?.str("level")

/** Fast service tier (OpenAI priority, Anthropic speed); returns whether it is enabled and active. */
suspend fun OmpClient.setFastMode(enabled: Boolean): Pair<Boolean, Boolean> =
    data("set_fast_mode", "enabled" to enabled).let { (it?.bool("enabled") ?: enabled) to (it?.bool("active") ?: false) }

/** `all` or `one-at-a-time`. */
suspend fun OmpClient.setSteeringMode(mode: String) { requestOk("set_steering_mode", params("mode" to mode)) }

suspend fun OmpClient.setFollowUpMode(mode: String) { requestOk("set_follow_up_mode", params("mode" to mode)) }

/** `immediate` or `wait`: whether a steering message interrupts a running tool. */
suspend fun OmpClient.setInterruptMode(mode: String) { requestOk("set_interrupt_mode", params("mode" to mode)) }

suspend fun OmpClient.setAutoCompaction(enabled: Boolean) { requestOk("set_auto_compaction", params("enabled" to enabled)) }

suspend fun OmpClient.setAutoRetry(enabled: Boolean) { requestOk("set_auto_retry", params("enabled" to enabled)) }

suspend fun OmpClient.abortRetry() { requestOk("abort_retry") }

/** `off`, `streaming` or `idle`. */
suspend fun OmpClient.setCacheWarming(mode: String): String = data("set_cache_warming", "mode" to mode)?.str("mode") ?: mode

// ---- commands and queue ----

suspend fun OmpClient.slashCommands(): List<OmpSlashCommand> = data("get_available_commands")?.objects("commands")?.map(OmpSlashCommand::from).orEmpty()

/** Takes a queued message back; [queue] is `steering` or `followUp`. */
suspend fun OmpClient.removeQueuedMessage(message: String, queue: String): Boolean =
    data("remove_queued_message", "message" to message, "queue" to queue)?.bool("removed") ?: false

/** Moves a queued follow-up forward so it steers the running turn. */
suspend fun OmpClient.promoteQueuedMessage(message: String): Boolean = data("promote_queued_message", "message" to message)?.bool("promoted") ?: false

// ---- compaction, handoff, bash ----

/** Summarises the conversation so far to free the context; [instructions] steer the summary. */
suspend fun OmpClient.compact(instructions: String? = null): OmpCompaction? =
    OmpCompaction.from(data("compact", "customInstructions" to instructions, timeoutMs = 600_000))

/** Writes a handoff document and compacts in place; returns where it was saved. */
suspend fun OmpClient.handoff(instructions: String? = null): String? = data("handoff", "customInstructions" to instructions, timeoutMs = 600_000)?.str("savedPath")

/** `!command`: runs a shell command in the session's working directory and adds it to the conversation. */
suspend fun OmpClient.bash(command: String, timeoutMs: Long = 600_000): OmpBashResult =
    OmpBashResult.from(data("bash", "command" to command, timeoutMs = timeoutMs) ?: JsonObject(emptyMap()))

suspend fun OmpClient.abortBash() { requestOk("abort_bash") }

// ---- sessions ----

suspend fun OmpClient.sessionStats(): OmpSessionStats = OmpSessionStats.from(data("get_session_stats") ?: JsonObject(emptyMap()))

/** Writes the conversation as a standalone HTML page; returns its path. */
suspend fun OmpClient.exportHtml(outputPath: String? = null): String = data("export_html", "outputPath" to outputPath, timeoutMs = 120_000)?.str("path").orEmpty()

/** Continues another session file of this session directory. False when an extension cancelled it. */
suspend fun OmpClient.switchSession(sessionPath: String): Boolean = !(data("switch_session", "sessionPath" to sessionPath)?.bool("cancelled") ?: false)

/** Goes back to an entry, keeping the old path as a branch; returns the text of the message there (to edit and resend). */
suspend fun OmpClient.branch(entryId: String): String? = data("branch", "entryId" to entryId)?.let { if (it.bool("cancelled") == true) null else it.str("text").orEmpty() }

/** Copies the conversation up to [entryId] (or all of it) into a new session and continues there. */
suspend fun OmpClient.fork(entryId: String? = null): Boolean = !(data("fork", "entryId" to entryId)?.bool("cancelled") ?: false)

/** The user messages of the current branch, for picking a branch point. */
suspend fun OmpClient.branchMessages(): List<Pair<String, String>> =
    data("get_branch_messages")?.objects("messages")?.map { (it.str("entryId").orEmpty()) to (it.str("text").orEmpty()) }.orEmpty()

suspend fun OmpClient.lastAssistantText(): String? = data("get_last_assistant_text")?.str("text")

suspend fun OmpClient.setSessionName(name: String) { requestOk("set_session_name", params("name" to name)) }

suspend fun OmpClient.tree(): OmpTree {
    val d = data("get_tree") ?: JsonObject(emptyMap())
    return OmpTree(d.objects("tree").map(OmpTreeNode::from), d.str("leafId"))
}

/** The whole current branch. */
suspend fun OmpClient.messages(): List<JsonObject> = data("get_messages", timeoutMs = 120_000)?.objects("messages").orEmpty()

suspend fun OmpClient.messagesPage(cursor: String? = null, limit: Int? = null): OmpMessagesPage {
    val d = data("get_messages_page", "cursor" to cursor, "limit" to limit) ?: JsonObject(emptyMap())
    return OmpMessagesPage(d.objects("messages"), d.int("totalMessages") ?: 0, d.str("nextCursor"))
}

// ---- todos, goals, subagents ----

/** Replaces the agent's todo list. */
suspend fun OmpClient.setTodos(phases: List<OmpTodoPhase>): List<OmpTodoPhase> =
    data("set_todos", "phases" to JsonArray(phases.map { it.toJson() }))?.objects("todoPhases")?.map(OmpTodoPhase::from).orEmpty()

/** Goal mode: [op] is `get`, `create`, `resume`, `pause` or `drop`. Returns the goal afterwards (null: none). */
suspend fun OmpClient.goal(op: String, objective: String? = null, tokenBudget: Long? = null): OmpGoal? =
    OmpGoal.from(data("goal", "op" to op, "objective" to objective, "token_budget" to tokenBudget)?.obj("goal"))

suspend fun OmpClient.subagents(): List<OmpSubagent> = data("get_subagents")?.objects("subagents")?.map(OmpSubagent::from).orEmpty()

/** A subagent's transcript, read from [fromByte] on (pass the previous [OmpSubagentMessages.nextByte] to read only what is new). */
suspend fun OmpClient.subagentMessages(subagentId: String? = null, sessionFile: String? = null, fromByte: Long? = null): OmpSubagentMessages {
    val d = data("get_subagent_messages", "subagentId" to subagentId, "sessionFile" to sessionFile, "fromByte" to fromByte) ?: JsonObject(emptyMap())
    return OmpSubagentMessages(d.str("sessionFile").orEmpty(), d.long("nextByte") ?: 0, d.bool("reset") ?: false, d.objects("messages"))
}

suspend fun OmpClient.cancelSubagent(subagentId: String): Boolean = data("cancel_subagent", "subagentId" to subagentId)?.bool("cancelled") ?: false

suspend fun OmpClient.steerSubagent(subagentId: String, message: String) { requestOk("steer_subagent", params("subagentId" to subagentId, "message" to message)) }

// ---- accounts ----

suspend fun OmpClient.loginProviders(): List<OmpLoginProvider> = data("get_login_providers")?.objects("providers")?.map {
    OmpLoginProvider(it.str("id").orEmpty(), it.str("name").orEmpty(), it.bool("available") ?: false, it.bool("authenticated") ?: false)
}.orEmpty()

/**
 * Signs in to a provider. omp asks the host to open a URL (an `open_url` UI notification) and waits for the
 * provider to call back on loopback, so this can take as long as the user needs.
 */
suspend fun OmpClient.login(providerId: String, timeoutMs: Long = 15 * 60_000): String =
    data("login", "providerId" to providerId, timeoutMs = timeoutMs)?.str("providerId") ?: providerId

// ---- composer ----

/** Ghost text for the composer: the rest of the word at [cursor], or null. */
suspend fun OmpClient.predictWord(text: String, cursor: Int): String? = data("predict_word", "text" to text, "cursor" to cursor)?.str("suffix")

private fun JsonObject.objects(key: String): List<JsonObject> = this[key].objects()

internal fun JsonObject.textOf(key: String): String? = (this[key] as? JsonPrimitive)?.content
