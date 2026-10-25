package app.umbra.core.orchestrator

import app.umbra.core.session.EventRecord
import app.umbra.core.session.Session
import app.umbra.core.session.SessionEvent
import app.umbra.core.session.SessionState
import app.umbra.core.session.SessionKind
import app.umbra.core.session.ProactiveTurnMode
import kotlinx.serialization.json.*

/** Receipts are derived from recorded tool outcomes, never a model-provided `verified=true`. */
object CompletionEvidence {
    const val KEY = "deliverable.bundle.v1"
    /** A completed parent turn may still be waiting for a delegated task's report. */
    fun pendingDelegates(sessionId: String, sessions: List<Session>): List<String> {
        val children = sessions.groupBy { it.spawnedBy }
        val seen = mutableSetOf(sessionId)
        val pending = mutableListOf<String>()
        var frontier = listOf(sessionId)
        repeat(32) {
            val next = frontier.flatMap { children[it].orEmpty() }.filterNot {
                it.kind == SessionKind.AGENT && (it.routineRunId != null || it.turnMode == ProactiveTurnMode.READ_ONLY_DISCOVERY)
            }
            if (next.isEmpty()) return pending
            for (child in next) {
                if (!seen.add(child.id)) return pending + "Unresolved delegation cycle"
                if (!child.state.isTerminal) pending += child.id
                if (pending.size >= 200) return pending
            }
            frontier = next.map { it.id }
        }
        return if (frontier.any { children[it].orEmpty().isNotEmpty() }) pending + "Delegation depth exceeds the verification limit" else pending
    }

    /** A finished child still needs to deliver its report and let the parent settle a fresh turn. */
    fun awaitingDelegateReport(session: Session): Boolean =
        (((session.attachments[KEY] as? JsonObject)?.get("verification") as? JsonObject)?.get("pendingDelegates") as? JsonArray)?.isNotEmpty() == true

    fun awaitingDelegates(session: Session, sessions: List<Session>): Boolean =
        session.state == SessionState.COMPLETED &&
            (awaitingDelegateReport(session) || pendingDelegates(session.id, sessions).isNotEmpty())

    fun bundle(session: Session, events: List<EventRecord>, summary: String, pendingDelegates: List<String> = emptyList()): JsonObject {
        val turnStart = events.indexOfLast { (it.event as? SessionEvent.UserMessage)?.source == "user" }
        val historyComplete = turnStart >= 0
        val turn = events.drop((turnStart + 1).coerceAtLeast(0))
        val calls = turn.mapNotNull { r -> (r.event as? SessionEvent.ToolCall)?.let { it.callId to (r.seq to it) } }.toMap()
        val results = turn.mapNotNull { r -> (r.event as? SessionEvent.ToolResult)?.let { Triple(r.seq, it, calls[it.callId]?.second) } }
        val checks = results.filter { (_, result, call) ->
            call != null && (result.tool in setOf("verify", "android_wait_for", "android_run_plan") ||
                result.tool in setOf("bash", "android_shell", "priv_exec") && command(call)?.let(::isCheckCommand) == true)
        }.groupBy { (_, result, call) -> command(call) ?: result.tool }.mapValues { it.value.last() }.values.sortedBy { it.first }
        val successful = checks.filter { (_, result, _) ->
            val metadata = result.data as? JsonObject
            result.status == "ok" && listOf("truncated", "cancelled", "timedOut").none { metadata?.get(it) == JsonPrimitive(true) } &&
                metadata?.get("verificationPassed") != JsonPrimitive(false) && (metadata?.get("exitCode")?.jsonPrimitive?.intOrNull?.let { it == 0 } != false)
        }
        val failed = checks.filterNot { it in successful }
        val lastMutation = results.lastOrNull { (_, result, call) -> call != null && mutates(result.tool, call) }?.first ?: -1
        val freshChecks = successful.any { it.first >= lastMutation }
        val mutationAt = turn.find { it.seq == lastMutation }?.ts ?: (events.getOrNull(turnStart)?.ts ?: 0)
        val children = session.attachments.filterKeys { it.startsWith("child.verification.") }.values.mapNotNull { it as? JsonObject }
            .filter { (it["atMs"] as? JsonPrimitive)?.longOrNull?.let { at -> at >= mutationAt } == true }
        val childChecks = children.filter { it["verified"] == JsonPrimitive(true) }
        val failedChildren = children.any { it["verified"] != JsonPrimitive(true) }
        val visualWork = results.any { (_, result, _) -> result.tool in UI_WRITES }
        val proof = turn.lastOrNull { it.event is SessionEvent.Observation && hasProof(it.event as SessionEvent.Observation) && it.seq >= lastMutation }
            ?.event as? SessionEvent.Observation
        val turnSeq = events.getOrNull(turnStart)?.seq
        val declared = (session.attachments[KEY] as? JsonObject)?.takeIf {
            turnSeq != null && (it["turnStartSeq"] as? JsonPrimitive)?.longOrNull == turnSeq
        }
        val proposedRisks = ((declared?.get("declaredResidualRisks") as? JsonArray)
            ?: ((declared?.get("verification") as? JsonObject)?.get("residualRisks") as? JsonArray)).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        val risks = proposedRisks.toMutableList()
        if (!historyComplete) risks += "The originating user turn is absent from the available history; verification requires a fresh check."
        if (successful.isEmpty() && childChecks.isEmpty()) risks += "No independent verification check was recorded for this turn."
        if (failedChildren) risks += "A delegated task has no successful verification receipt."
        if (pendingDelegates.isNotEmpty()) risks += "Delegated work is still active or its completion cannot be established."
        if (failed.isNotEmpty()) risks += "Some recorded checks failed or did not finish."
        if (successful.isNotEmpty() && !freshChecks) risks += "Changes occurred after the last successful check."
        if (visualWork && proof == null) risks += "The final UI state has no integrity-checked visual proof. Capture the final screen again."
        return buildJsonObject {
            put("sessionId", session.id); put("summary", Redaction.text(summary))
            put("turnStartSeq", turnSeq?.let(::JsonPrimitive) ?: JsonNull)
            put("artifacts", declared?.get("artifacts") ?: JsonArray(emptyList()))
            put("declaredResidualRisks", JsonArray(proposedRisks.map { JsonPrimitive(Redaction.text(it).take(1000)) }))
            put("verification", buildJsonObject {
                put("verified", historyComplete && pendingDelegates.isEmpty() && (freshChecks || childChecks.isNotEmpty()) && failed.isEmpty() && !failedChildren && (!visualWork || proof != null))
                if (pendingDelegates.isNotEmpty()) put("pendingDelegates", JsonArray(pendingDelegates.take(200).map { JsonPrimitive(Redaction.text(it)) }))
                put("checksRun", JsonArray(checks.map { (_, result, call) -> JsonPrimitive("${result.status}: ${result.tool} · ${(command(call) ?: result.summary).take(500)}") } +
                    childChecks.map { JsonPrimitive("Verified delegated task: ${it["sessionId"]?.jsonPrimitive?.content.orEmpty()}") }))
                proof?.screenshotPath?.let { put("visualProofSheetPath", it) }
                proof?.let { put("visualProof", buildJsonObject {
                    put("path", it.screenshotPath); put("sha256", it.screenshotSha256)
                    put("sizeBytes", it.screenshotSizeBytes); put("mimeType", it.screenshotMimeType)
                }) }
                put("residualRisks", JsonArray(risks.distinct().take(30).map { JsonPrimitive(Redaction.text(it).take(1000)) }))
            })
            declared?.get("gitSummary")?.let { put("gitSummary", it) }
        }
    }
    private fun hasProof(observation: SessionEvent.Observation): Boolean =
        !observation.screenshotPath.isNullOrBlank() && observation.screenshotSha256?.matches(Regex("[a-fA-F0-9]{64}")) == true &&
            observation.screenshotSizeBytes?.let { it in 1..8_000_000L } == true && observation.screenshotMimeType in setOf("image/png", "image/jpeg")
    private fun command(call: SessionEvent.ToolCall?): String? = (call?.args as? JsonObject)?.get("command")?.let { (it as? JsonPrimitive)?.content }
    private fun isCheckCommand(command: String): Boolean {
        // Compound scripts, redirects and interpreter strings cannot masquerade as a simple check.
        if (CommandSyntax.unsupported(command)) return false
        val words = command.trim().split(Regex("\\s+"))
        val executable = words.firstOrNull()?.substringAfterLast('/') ?: return false
        return when (executable) {
            "gradle", "gradlew" -> words.drop(1).any { it.substringAfterLast(':').matches(Regex("(?:test|lint)[A-Za-z]*|check")) }
            "pytest", "mypy", "pyright", "ruff", "tsc" -> true
            "python", "python3" -> words.getOrNull(1) == "-m" && words.getOrNull(2) in setOf("pytest", "unittest")
            "npm", "pnpm", "yarn", "bun" -> words.drop(1).let { if (it.firstOrNull() == "run") it.drop(1) else it }.firstOrNull() in setOf("test", "typecheck", "lint", "check")
            "cargo" -> words.getOrNull(1) in setOf("test", "check", "clippy")
            "go" -> words.getOrNull(1) in setOf("test", "vet")
            else -> false
        }
    }
    private fun mutates(tool: String, call: SessionEvent.ToolCall): Boolean = when {
        tool in UI_WRITES || tool in setOf("write", "edit", "apply_patch", "messages_send", "email_send", "calendar_write", "calendar_update", "calendar_delete", "tasks_write") -> true
        tool in setOf("bash", "android_shell", "priv_exec") -> command(call)?.let { !isCheckCommand(it) } ?: true
        else -> false
    }
    private object CommandSyntax { fun unsupported(text: String) = text.any { it in ";|&<>`\n\r" } || "$(" in text }
    private val UI_WRITES = setOf("android_run_plan", "android_tap", "android_type", "android_double_tap", "android_long_press", "android_scroll", "android_launch", "android_switch", "android_key", "gui_click", "gui_type", "gui_key", "browser")
}
