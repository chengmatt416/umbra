package app.umbra.core.control

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Session-scoped Resume Contract (architecture spec §8.1): whether one session may be resumed by itself after the
 * app or the phone restarted, until when, and what work it is expected to continue. It grants nothing: permissions,
 * the screen, root and credentials are checked again as always, and an emergency lockdown still wins.
 */
@Serializable
data class ResumeContract(
    val sessionId: String,
    val resumeAfterRestart: Boolean,
    /** Null: until the task completes. */
    val expiresAtMs: Long? = null,
    /** In the user's words, handed to the agent when it resumes ("builds and tests only"). */
    val allowedWork: String = "",
    val createdAtMs: Long,
)

class ResumeContracts(private val store: ControlStore) {
    private val docs = store.docs("resume-contracts", ResumeContract.serializer(), ResumeContract::sessionId)
    private val journal = store.journal("continuity")

    val all: StateFlow<Map<String, ResumeContract>> = docs.all

    operator fun get(sessionId: String): ResumeContract? = docs[sessionId]

    fun set(sessionId: String, resume: Boolean, expiresAtMs: Long?, allowedWork: String): ResumeContract =
        docs.put(ResumeContract(sessionId, resume, expiresAtMs, allowedWork.trim().take(500), store.clock.nowMs())).also {
            journal.append("contract.set", buildJsonObject { put("session", sessionId); put("resume", resume); expiresAtMs?.let { e -> put("expires", e) } })
        }

    fun revoke(sessionId: String) {
        if (docs.delete(sessionId)) journal.append("contract.revoked", buildJsonObject { put("session", sessionId) })
    }

    /** Null when the contract lets the session resume now; otherwise why not. */
    fun eligibility(sessionId: String): String? {
        val c = docs[sessionId] ?: return "no resume contract"
        if (!c.resumeAfterRestart) return "the contract does not allow resuming after a restart"
        c.expiresAtMs?.let { if (store.clock.nowMs() > it) return "the contract expired" }
        return null
    }

    fun record(kind: String, sessionId: String, detail: String) = journal.append(kind, buildJsonObject { put("session", sessionId); put("detail", detail) })

    val recent = journal.recent
}
