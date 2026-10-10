package app.umbra.core.orchestrator

import app.umbra.core.model.UmbraJson
import app.umbra.core.model.action.ActionResult
import app.umbra.core.model.action.ActionStatus
import app.umbra.core.session.PlanStep
import app.umbra.core.session.SessionEvent
import app.umbra.core.session.SessionStore
import app.umbra.core.session.SubagentBrief
import app.umbra.platform.logs.CrashReport
import app.umbra.runtime.omp.ContentBlock
import app.umbra.runtime.omp.HostToolCall
import app.umbra.runtime.omp.HostToolPayload
import app.umbra.runtime.omp.OmpEvent
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Saves bytes the timeline refers to (screenshots) and returns where they went. */
fun interface ArtifactSink {
    suspend fun save(sessionId: String, name: String, bytes: ByteArray): String?

    companion object {
        val None = ArtifactSink { _, _, _ -> null }
    }
}

/**
 * Writes what the agent does into the session's timeline (spec §24): messages, tool calls with their
 * results, plan changes, subagents, app crashes. Secrets are removed before anything is stored.
 *
 * Host tools are recorded by the tool handler itself, which sees structured results and screenshots;
 * omp's events for those tools are skipped so nothing appears twice.
 */
class TimelineRecorder(
    private val sessionId: String,
    private val store: SessionStore,
    private val hostToolNames: Set<String>,
    private val artifacts: ArtifactSink = ArtifactSink.None,
) {
    private var screenshots = 0

    suspend fun userMessage(text: String, imageCount: Int = 0) {
        store.append(sessionId, SessionEvent.UserMessage(Redaction.text(text), imageCount))
    }

    suspend fun notice(level: String, message: String, source: String? = null) {
        store.append(sessionId, SessionEvent.Notice(level, Redaction.text(message), source))
    }

    suspend fun failure(subsystem: String, message: String, recoverable: Boolean = true) {
        store.append(sessionId, SessionEvent.Failure(subsystem, Redaction.text(message), recoverable))
    }

    suspend fun recovery(subsystem: String, action: String, success: Boolean, detail: String? = null) {
        store.append(sessionId, SessionEvent.Recovery(subsystem, action, success, detail?.let(Redaction::text)))
    }

    suspend fun appEvent(report: CrashReport) {
        val kind = when (report.kind) {
            app.umbra.core.model.observation.LogKind.ANR -> "anr"
            app.umbra.core.model.observation.LogKind.NATIVE_CRASH -> "native_crash"
            else -> "crash"
        }
        store.append(sessionId, SessionEvent.AppEvent(kind, report.packageName, report.summary(), report.actionId))
    }

    // ---- host tools ----

    suspend fun hostToolCall(call: HostToolCall) {
        store.append(
            sessionId,
            SessionEvent.ToolCall(call.toolCallId.ifBlank { call.id }, call.toolName, source = "host", args = Redaction.args(call.toolName, call.arguments)),
        )
    }

    suspend fun hostToolResult(call: HostToolCall, payload: HostToolPayload, durationMs: Long) {
        val callId = call.toolCallId.ifBlank { call.id }
        val result = payload.details?.let {
            try {
                UmbraJson.decodeFromJsonElement(ActionResult.serializer(), it)
            } catch (_: Exception) {
                null
            }
        }
        val text = payload.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        store.append(
            sessionId,
            SessionEvent.ToolResult(
                callId = callId,
                tool = call.toolName,
                status = statusName(result?.status, payload.isError),
                summary = Redaction.text((result?.summary ?: text).take(SUMMARY_LIMIT)),
                backend = result?.backend,
                durationMs = result?.durationMs?.takeIf { it > 0 } ?: durationMs,
            ),
        )
        for (image in payload.content.filterIsInstance<ContentBlock.Image>()) {
            val bytes = try {
                Base64.getDecoder().decode(image.base64)
            } catch (_: IllegalArgumentException) {
                continue
            }
            val ext = if (image.mimeType.contains("png")) "png" else "jpg"
            val path = artifacts.save(sessionId, "shot-${++screenshots}-${callId.takeLast(8)}.$ext", bytes)
            store.append(sessionId, SessionEvent.Observation("Screenshot from ${call.toolName}", screenshotPath = path))
        }
    }

    // ---- omp events ----

    /** Records one omp event. Returns the new plan when the event changed it. */
    suspend fun omp(event: OmpEvent): List<PlanStep>? {
        when (event) {
            is OmpEvent.MessageEnd -> if (event.role == "assistant" && event.text.isNotBlank()) {
                store.append(sessionId, SessionEvent.AgentMessage(Redaction.text(event.text), event.messageId))
            }
            is OmpEvent.ToolStart -> if (event.toolName !in hostToolNames) {
                store.append(
                    sessionId,
                    SessionEvent.ToolCall(event.toolCallId, event.toolName, source = "omp", args = Redaction.args(event.toolName, event.args), intent = event.intent),
                )
            }
            is OmpEvent.ToolEnd -> if (event.toolName !in hostToolNames) {
                store.append(
                    sessionId,
                    SessionEvent.ToolResult(
                        callId = event.toolCallId,
                        tool = event.toolName,
                        status = if (event.isError) "failed" else "ok",
                        summary = Redaction.text(textOf(event.result).take(SUMMARY_LIMIT)),
                    ),
                )
                if (event.toolName == "todo") return PlanParser.from(event.result)
            }
            is OmpEvent.Notice -> store.append(sessionId, SessionEvent.Notice(event.level, Redaction.text(event.message), event.source))
            is OmpEvent.Ui -> if (event.notification.method == "notify") {
                event.notification.message?.let { store.append(sessionId, SessionEvent.Notice("info", Redaction.text(it), "omp")) }
            }
            is OmpEvent.ExtensionError -> store.append(sessionId, SessionEvent.Notice("warning", Redaction.text(event.error), event.extensionPath))
            is OmpEvent.FrameError -> store.append(sessionId, SessionEvent.Notice("warning", Redaction.text(event.message), "omp-protocol"))
            is OmpEvent.Subagent -> subagent(event)
            else -> Unit
        }
        return null
    }

    private suspend fun subagent(event: OmpEvent.Subagent) {
        val id = event.payload.str("id") ?: event.payload.str("agentId") ?: return
        val summary = event.payload.str("summary") ?: event.payload.str("task") ?: event.payload.str("message")
        store.append(sessionId, SessionEvent.Subagent(id, event.kind, summary?.let { Redaction.text(it).take(SUMMARY_LIMIT) }))
    }

    companion object {
        const val SUMMARY_LIMIT = 600

        fun statusName(status: ActionStatus?, isError: Boolean): String = when (status) {
            ActionStatus.OK -> "ok"
            ActionStatus.FAILED -> "failed"
            ActionStatus.DENIED -> "denied"
            ActionStatus.TIMEOUT -> "timeout"
            ActionStatus.NEEDS_USER -> "needs_user"
            ActionStatus.UNSUPPORTED -> "unsupported"
            ActionStatus.CANCELLED -> "cancelled"
            null -> if (isError) "failed" else "ok"
        }

        /** The text of an omp tool result (`{content:[{type:"text",text}], details}`). */
        fun textOf(result: JsonElement?): String {
            val content = (result as? JsonObject)?.get("content")
            return when (content) {
                is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString("\n")
                is JsonPrimitive -> content.content
                else -> (result as? JsonPrimitive)?.content.orEmpty()
            }
        }
    }
}

/** Reads the agent's own task list (omp's `todo` tool) as the session's plan. */
internal object PlanParser {
    fun from(result: JsonElement?): List<PlanStep>? {
        val details = (result as? JsonObject)?.get("details") as? JsonObject ?: return null
        val phases = details["phases"] as? JsonArray ?: return null
        val steps = ArrayList<PlanStep>()
        phases.forEachIndexed { pi, phase ->
            val p = phase as? JsonObject ?: return@forEachIndexed
            val phaseName = p.str("name").orEmpty()
            (p["tasks"] as? JsonArray)?.forEachIndexed { ti, task ->
                val t = task as? JsonObject ?: return@forEachIndexed
                val content = t.str("content") ?: return@forEachIndexed
                val label = if (phases.size > 1 && phaseName.isNotBlank()) "$phaseName: $content" else content
                steps += PlanStep("p$pi-t$ti", label, status(t.str("status")))
            }
        }
        return steps
    }

    private fun status(omp: String?) = when (omp) {
        "in_progress" -> "active"
        "completed" -> "done"
        "abandoned" -> "skipped"
        "blocked" -> "blocked"
        else -> "pending"
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
