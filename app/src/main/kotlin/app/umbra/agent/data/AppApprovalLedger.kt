package app.umbra.agent.data

import app.umbra.core.model.UmbraJson
import app.umbra.core.orchestrator.Redaction
import app.umbra.core.safety.ApprovalLedger
import app.umbra.core.safety.ApprovalLedgerEntry
import app.umbra.core.session.SessionEvent
import app.umbra.core.session.SessionStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every approval request and decision, forever on this phone (spec §28 "review approvals"). One JSON
 * line per entry in a private file, and a copy in the timeline of the session it belongs to so the
 * session screen shows what was asked and answered. Descriptions are scrubbed of secrets first.
 */
class AppApprovalLedger(private val file: File, private val sessions: SessionStore) : ApprovalLedger {
    private val lock = Any()

    override suspend fun record(entry: ApprovalLedgerEntry) {
        val clean = entry.copy(summary = Redaction.text(entry.summary))
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                file.parentFile?.mkdirs()
                file.appendText(UmbraJson.encodeToString(ApprovalLedgerEntry.serializer(), clean) + "\n")
            }
        }
        if (clean.sessionId != "-") {
            try {
                sessions.append(
                    clean.sessionId,
                    SessionEvent.Approval(clean.requestId, clean.kind, clean.summary, clean.level.name.lowercase(), clean.categories.map { it.name.lowercase() }),
                )
            } catch (_: NoSuchElementException) {
                // The session was deleted while the question was open.
            }
        }
    }

    /** The newest [limit] entries, newest first. */
    fun read(limit: Int = 300): List<ApprovalLedgerEntry> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        file.useLines { lines ->
            lines.toList().takeLast(limit).mapNotNull { line ->
                try {
                    UmbraJson.decodeFromString(ApprovalLedgerEntry.serializer(), line)
                } catch (_: Exception) {
                    null
                }
            }.asReversed()
        }
    }

    fun clear() = synchronized(lock) { file.delete() }
}
