package app.umbra.core.control

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The git a commit gate needs, wherever the repository is (the phone's Linux environment, the host in tests). Every
 * call names the repository by its path; nothing here changes the working tree.
 */
interface GitPort {
    /** The commit [rev] names in [repo], or null when it names none. */
    suspend fun resolve(repo: String, rev: String): String?

    /** The current branch of [repo], or null when HEAD is detached. */
    suspend fun currentBranch(repo: String): String?

    /** The commit the remote's [ref] points at now, or null when the remote has no such ref. Throws when it cannot ask. */
    suspend fun lsRemote(repo: String, remote: String, ref: String): String?

    suspend fun isAncestor(repo: String, ancestor: String, descendant: String): Boolean

    /** One line per commit in [range] (`a..b`), newest first. */
    suspend fun log(repo: String, range: String, max: Int = 50): List<String>

    suspend fun diffStat(repo: String, from: String?, to: String): String

    /** The tree [commit] records: the content it would publish. */
    suspend fun tree(repo: String, commit: String): String

    /**
     * Updates the remote's [ref] to [commit] only if it still points at [expectedOld] (null: only if it does not exist).
     * Returns normally when the remote accepted; throws [LeaseRejected] when the remote's value differed; any other
     * exception means the outcome is not known.
     */
    suspend fun pushWithLease(repo: String, remote: String, commit: String, ref: String, expectedOld: String?)
}

/** The remote's ref was not where the push expected it: nothing was written. */
class LeaseRejected(message: String) : Exception(message)

/** The write was refused before anything was sent (by the credential broker, for instance): nothing was written. */
class WriteNotSent(message: String) : Exception(message)

@Serializable
enum class PromotionState {
    /** Checked, waiting for the user's authorization. */
    VALIDATED,

    /** Refused at validation (nothing to publish, history rewrite not allowed, remote unreachable…). */
    REFUSED,

    /** The durable intent is written; the write has not been sent. */
    PREPARED,

    /** The write was sent; its receipt is not in yet. Seen at start-up, it means the app died mid-write. */
    COMMITTING,

    /** The remote's ref is the candidate, as read back from the remote. */
    COMMITTED,

    /** The remote's ref moved before the write: nothing was written. */
    REJECTED,

    /** The write may or may not have happened; read the remote before anything is sent again. */
    OUTCOME_UNKNOWN,

    ABANDONED,
}

/** A single-use, short-lived permission to write one value to one ref, issued at authorization (§11). */
@Serializable
data class CommitCapability(
    val resource: String,
    val action: String,
    val expectedOld: String?,
    val newValue: String,
    val epoch: Long,
    val expiresAtMs: Long,
    val used: Boolean = false,
)

/** What the candidate is: the evidence shown before anything is published. */
@Serializable
data class ChangeEvidence(val commits: List<String>, val diffStat: String, val tree: String, val fastForward: Boolean)

@Serializable
data class Promotion(
    val id: String,
    val repo: String,
    val remote: String,
    val branch: String,
    val candidate: String,
    /** The remote's ref when it was validated (null: the branch does not exist there yet). */
    val expectedOld: String?,
    val state: PromotionState,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val evidence: ChangeEvidence? = null,
    val problems: List<String> = emptyList(),
    val authorizedBy: String? = null,
    val rewriteAllowed: Boolean = false,
    val capability: CommitCapability? = null,
    /** What the remote's ref was read as after the write (or during recovery). */
    val observed: String? = null,
    val note: String? = null,
) {
    val ref: String get() = "refs/heads/$branch"
    val resource: String get() = "$repo|$remote|$ref"
}

/** The fencing epoch of one target ref: an attempt from an older epoch cannot write. */
@Serializable
data class TargetFence(val id: String, val resource: String, val epoch: Long)

/**
 * The commit gate (architecture spec §11): a promotion of a repository's commit to a remote branch goes
 * VALIDATE → PREPARE → FENCE → COMMIT → VERIFY → FINALIZE, each step persisted before the next, every transition
 * in a hash-chained journal. The write is conditional (the remote ref must still be where validation saw it), uses
 * a single-use capability of the current fence epoch, never rewrites history unless the user allowed exactly that,
 * and a write whose outcome is unknown is settled by reading the remote, never by sending it again blindly.
 */
class CommitGate(
    private val store: ControlStore,
    private val git: GitPort,
    private val capabilityTtlMs: Long = 10 * 60_000,
    /** The repository's integration policy (the Repository Hub): may its remote history be rewritten at all? */
    private val rewritePermitted: (repo: String) -> Boolean = { true },
) {
    private val promotions = store.docs("promotions", Promotion.serializer(), Promotion::id)
    private val fences = store.docs("commit-fences", TargetFence.serializer(), TargetFence::id)
    private val journal = store.journal("promotions")

    val all: StateFlow<Map<String, Promotion>> = promotions.all
    val recent = journal.recent

    operator fun get(id: String): Promotion? = promotions[id]

    /** VALIDATE: what would be published, where, whether it is a fast-forward, and why not when it may not be. */
    suspend fun propose(repo: String, remote: String = "origin", branch: String? = null, by: String = "user"): Promotion {
        val now = store.clock.nowMs()
        val id = "promo-" + ControlStore.sha256("$repo|$remote|$branch|$now|${journal.headDigest()}").take(16)
        val problems = ArrayList<String>()
        val target = branch ?: git.currentBranch(repo) ?: run { problems += "HEAD is detached: name the branch to publish"; "" }
        val candidate = git.resolve(repo, "HEAD") ?: run { problems += "the repository has no commit"; "" }
        val old = if (problems.isEmpty()) runCatching { git.lsRemote(repo, remote, "refs/heads/$target") }.getOrElse {
            problems += "the remote “$remote” could not be read: ${it.message}"
            null
        } else null
        var evidence: ChangeEvidence? = null
        if (problems.isEmpty()) {
            if (old == candidate) problems += "the remote's $target already is this commit: nothing to publish"
            val known = old?.let { git.resolve(repo, it) }
            val ff = old == null || (known != null && git.isAncestor(repo, old, candidate))
            if (old != null && known == null) problems += "the remote's $target is at a commit this repository does not have: fetch first"
            evidence = ChangeEvidence(
                commits = git.log(repo, if (known != null) "$old..$candidate" else candidate),
                diffStat = git.diffStat(repo, known, candidate),
                tree = git.tree(repo, candidate),
                fastForward = ff,
            )
        }
        val p = Promotion(id, repo, remote, target, candidate, old, if (problems.isEmpty()) PromotionState.VALIDATED else PromotionState.REFUSED, now, now, evidence, problems)
        promotions.put(p)
        log("promotion.validated", p) { put("by", by); put("problems", problems.joinToString("; ")) }
        return p
    }

    /**
     * The user authorized [id]: PREPARE (durable intent and a capability), FENCE (a new epoch for the target), COMMIT
     * (the conditional write), VERIFY (read the remote back), FINALIZE (the receipt). A history rewrite needs
     * [allowRewrite], given for exactly this promotion.
     */
    suspend fun promote(id: String, authorizedBy: String, allowRewrite: Boolean = false): Promotion {
        var p = promotions[id] ?: throw IllegalArgumentException("no promotion $id")
        check(p.state == PromotionState.VALIDATED) { "promotion $id is ${p.state.name.lowercase()}, not validated" }
        val ff = p.evidence?.fastForward ?: false
        if (!ff && !allowRewrite) {
            return save(p.copy(state = PromotionState.REFUSED, problems = p.problems + "not a fast-forward: publishing would rewrite the remote's history, which was not allowed"), "promotion.refused")
        }
        if (!ff && !rewritePermitted(p.repo)) {
            return save(p.copy(state = PromotionState.REFUSED, problems = p.problems + "not a fast-forward, and this repository's integration policy is fast-forward only"), "promotion.refused")
        }
        // PREPARE: the intent is durable before anything is sent.
        val epoch = fence(p.resource)
        val capability = CommitCapability(p.resource, "git.push", p.expectedOld, p.candidate, epoch, store.clock.nowMs() + capabilityTtlMs)
        p = save(p.copy(state = PromotionState.PREPARED, authorizedBy = authorizedBy, rewriteAllowed = allowRewrite, capability = capability), "promotion.prepared")
        return commit(p)
    }

    private suspend fun commit(prepared: Promotion): Promotion {
        var p = prepared
        val cap = p.capability ?: throw IllegalStateException("no capability")
        // The gate on the write path: the capability must be this target's current epoch, unused and unexpired.
        val current = fences[fenceId(p.resource)]?.epoch ?: 0
        when {
            cap.epoch != current -> return save(p.copy(state = PromotionState.REJECTED, note = "a newer attempt (epoch $current) holds this target; this one (epoch ${cap.epoch}) may not write"), "promotion.fenced_out")
            cap.used -> return save(p.copy(state = PromotionState.REJECTED, note = "its capability was already used"), "promotion.rejected")
            store.clock.nowMs() > cap.expiresAtMs -> return save(p.copy(state = PromotionState.REJECTED, note = "its capability expired; validate again"), "promotion.rejected")
        }
        // Read the remote once more: a ref that moved since validation is not overwritten.
        val now = runCatching { git.lsRemote(p.repo, p.remote, p.ref) }.getOrElse {
            return save(p.copy(state = PromotionState.REJECTED, note = "the remote could not be read before writing: ${it.message}"), "promotion.rejected")
        }
        if (now != p.expectedOld) {
            return save(p.copy(state = PromotionState.REJECTED, observed = now, note = "the remote's ${p.branch} moved since validation (now ${now?.take(12) ?: "absent"}); validate again"), "promotion.rejected")
        }
        p = save(p.copy(state = PromotionState.COMMITTING, capability = cap.copy(used = true)), "promotion.committing")
        try {
            git.pushWithLease(p.repo, p.remote, p.candidate, p.ref, p.expectedOld)
        } catch (e: LeaseRejected) {
            return save(p.copy(state = PromotionState.REJECTED, note = "the remote refused the conditional write: ${e.message}"), "promotion.rejected")
        } catch (e: WriteNotSent) {
            return save(p.copy(state = PromotionState.REJECTED, note = "nothing was sent: ${e.message}"), "promotion.rejected")
        } catch (e: kotlinx.coroutines.CancellationException) {
            save(p.copy(state = PromotionState.OUTCOME_UNKNOWN, note = "interrupted while writing"), "promotion.outcome_unknown")
            throw e
        } catch (e: Exception) {
            p = save(p.copy(state = PromotionState.OUTCOME_UNKNOWN, note = "the write did not report back: ${e.message}"), "promotion.outcome_unknown")
            return verify(p.id)
        }
        return verify(p.id)
    }

    /**
     * VERIFY and FINALIZE: reads the remote's ref. The candidate there → committed (receipt). The old value there →
     * the write did not happen (it may be validated again). Anything else → someone else's commit: nothing is overwritten.
     */
    suspend fun verify(id: String): Promotion {
        val p = promotions[id] ?: throw IllegalArgumentException("no promotion $id")
        if (p.state != PromotionState.COMMITTING && p.state != PromotionState.OUTCOME_UNKNOWN) return p
        val seen = runCatching { git.lsRemote(p.repo, p.remote, p.ref) }.getOrElse {
            return save(p.copy(state = PromotionState.OUTCOME_UNKNOWN, note = "the remote could not be read to settle the outcome: ${it.message}"), "promotion.outcome_unknown")
        }
        return when (seen) {
            p.candidate -> save(p.copy(state = PromotionState.COMMITTED, observed = seen, note = "the remote's ${p.branch} is the candidate"), "promotion.committed")
            p.expectedOld -> save(p.copy(state = PromotionState.REJECTED, observed = seen, note = "the write did not happen; the remote is unchanged"), "promotion.not_written")
            else -> save(p.copy(state = PromotionState.REJECTED, observed = seen, note = "the remote's ${p.branch} is now ${seen?.take(12) ?: "absent"}, neither value: someone else wrote it"), "promotion.conflict")
        }
    }

    /** Checkpointed forward recovery at start-up: a write the app died in the middle of is settled by reading the remote. */
    suspend fun recover() {
        for (p in promotions.list()) {
            when (p.state) {
                PromotionState.PREPARED -> save(p.copy(state = PromotionState.ABANDONED, note = "prepared but never sent before the app stopped"), "promotion.abandoned")
                PromotionState.COMMITTING -> {
                    save(p.copy(state = PromotionState.OUTCOME_UNKNOWN, note = "the app stopped while writing"), "promotion.outcome_unknown")
                    runCatching { verify(p.id) }
                }
                else -> Unit
            }
        }
    }

    fun abandon(id: String, by: String = "user") {
        val p = promotions[id] ?: return
        if (p.state == PromotionState.VALIDATED || p.state == PromotionState.REFUSED || p.state == PromotionState.REJECTED) {
            save(p.copy(state = PromotionState.ABANDONED, note = "abandoned by $by"), "promotion.abandoned")
        }
    }

    fun verifyJournal(): ChainCheck = journal.verify()

    @Synchronized
    private fun fence(resource: String): Long {
        val next = (fences[fenceId(resource)]?.epoch ?: 0) + 1
        fences.put(TargetFence(fenceId(resource), resource, next))
        journal.append("promotion.fenced", buildJsonObject { put("resource", resource); put("epoch", next) })
        return next
    }

    private fun fenceId(resource: String) = "f-" + ControlStore.sha256(resource).take(24)

    private fun save(p: Promotion, kind: String): Promotion {
        val next = promotions.put(p.copy(updatedAtMs = store.clock.nowMs()))
        log(kind, next) {}
        return next
    }

    private inline fun log(kind: String, p: Promotion, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        journal.append(kind, buildJsonObject {
            put("id", p.id); put("repo", p.repo); put("remote", p.remote); put("branch", p.branch)
            put("candidate", p.candidate); put("expected_old", p.expectedOld ?: ""); put("state", p.state.name.lowercase())
            p.note?.let { put("note", it) }
            extra()
        })
    }
}
