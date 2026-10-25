package app.umbra.core.orchestrator

import app.umbra.core.session.SessionStore
import app.umbra.core.workspace.WorkspaceManager
import app.umbra.runtime.omp.HostToolDefinition
import app.umbra.runtime.omp.HostToolPayload
import app.umbra.runtime.omp.objectSchema
import app.umbra.tools.HostSideTool
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Claims are linked to concrete workspace files and the session's independently recorded tool outcomes. */
class DeliverableTools(private val store: SessionStore, private val workspaces: WorkspaceManager, private val environment: AgentEnvironment) {
    fun forSession(sessionId: String) = listOf(HostSideTool(HostToolDefinition("deliverable_publish",
        "Publish the result bundle with concrete workspace files and residual risks. The system derives verification from actual tool outcomes; you cannot set verified yourself. Run checks after your last changes and capture final visual proof for UI work.", objectSchema {
            string("summary", required = true); stringArray("residual_risks", "Anything unverified, fees, refund limits, prerequisites or other restrictions")
            objectArray("artifacts") {
                enum("kind", listOf("DOCUMENT", "SPREADSHEET", "SLIDES", "CODE_PATCH", "VISUAL_PROOF", "WEB_PROTOTYPE", "DATA_CHART"), required = true)
                string("title", required = true); string("path", required = true); string("mime_type", required = true)
            }
            string("git_summary", "A concise description of the actual diff, if relevant")
        })) { call, _ -> withContext(Dispatchers.IO) {
        val session = requireNotNull(store.get(sessionId))
        val history = store.tail(sessionId, 10_000)
        val turnStart = history.lastOrNull { (it.event as? app.umbra.core.session.SessionEvent.UserMessage)?.source == "user" }?.seq
            ?: error("The originating turn is unavailable. Start a fresh task before publishing artifacts.")
        val args = call.arguments
        val summary = args["summary"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it.length <= 4000 } ?: error("Supply a result summary of at most 4000 characters")
        val dirs = workspaces.sessionDirs(sessionId)
        val roots = listOf(dirs.root) + listOfNotNull(session.workspaceId?.let { environment.workspaces.projectDir(it) })
        val realRoots = roots.filter { Files.exists(it) }.map { it.toRealPath() }
        val items = (args["artifacts"] as? JsonArray).orEmpty()
        require(items.size <= 20)
        val artifacts = items.map { item ->
            val a = item as? JsonObject ?: error("Each artifact must be an object")
            val path = a["path"]?.jsonPrimitive?.content ?: error("An artifact path is required")
            val host = environment.workspaces.toHost(path) ?: Path.of(path).let { if (it.isAbsolute) it else (session.workspaceId?.let { id -> environment.workspaces.projectDir(id) } ?: dirs.files).resolve(it) }
            val real = host.toRealPath()
            require(realRoots.any { real.startsWith(it) } && Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) { "Artifacts must be regular files inside this task's workspace" }
            val size = Files.size(real)
            require(size in 1..500_000_000L) { "Artifact files must be nonempty and at most 500 MB" }
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(65536); var count = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; count += n; require(count <= 500_000_000L); digest.update(buffer, 0, n) }
                require(count == size && Files.size(real) == size) { "Artifact changed while it was being verified" }
            }
            val kind = a["kind"]?.jsonPrimitive?.content ?: error("kind is required")
            require(kind in setOf("DOCUMENT", "SPREADSHEET", "SLIDES", "CODE_PATCH", "VISUAL_PROOF", "WEB_PROTOTYPE", "DATA_CHART"))
            buildJsonObject {
                put("kind", kind); put("title", Redaction.text(a["title"]?.jsonPrimitive?.content.orEmpty()).take(200))
                put("path", path); put("mimeType", a["mime_type"]?.jsonPrimitive?.content.orEmpty().take(100)); put("sizeBytes", size)
                put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
            }
        }
        val risks = (args["residual_risks"] as? JsonArray).orEmpty().map { JsonPrimitive(Redaction.text(it.jsonPrimitive.content).take(1000)) }
        require(risks.size <= 30)
        val proposed = buildJsonObject {
            put("sessionId", sessionId); put("turnStartSeq", turnStart); put("summary", Redaction.text(summary)); put("artifacts", JsonArray(artifacts))
            put("declaredResidualRisks", JsonArray(risks))
            put("verification", buildJsonObject { put("residualRisks", JsonArray(risks)) })
            args["git_summary"]?.jsonPrimitive?.content?.let { put("gitSummary", Redaction.text(it).take(4000)) }
        }
        store.update(sessionId) { it.copy(attachments = it.attachments + (CompletionEvidence.KEY to proposed)) }
        val delegates = CompletionEvidence.pendingDelegates(sessionId, store.list(app.umbra.core.session.SessionFilter(limit = Int.MAX_VALUE)))
        val receipt = CompletionEvidence.bundle(session.copy(attachments = session.attachments + (CompletionEvidence.KEY to proposed)), history, summary, delegates)
        store.update(sessionId) { it.copy(attachments = it.attachments + (CompletionEvidence.KEY to receipt)) }
        HostToolPayload.text(receipt.toString())
    } })
}
