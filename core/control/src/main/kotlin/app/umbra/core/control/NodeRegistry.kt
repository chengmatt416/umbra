package app.umbra.core.control

import app.umbra.core.nodes.JobState
import app.umbra.core.nodes.JobStatus
import app.umbra.core.nodes.NodeInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How far a node is trusted (architecture spec §10.1). Pairing proves identity, not hardware or intent. */
@Serializable
enum class NodeTrust(val label: String) {
    /** A computer the user owns: may run jobs with the data the policy lets out. */
    OWNED("Owned"),

    /** Paired, but given nothing until the user raises it. */
    LIMITED("Limited"),
}

@Serializable
data class PairedNode(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    /** The node certificate pinned at pairing: no other certificate is accepted for it. */
    val fingerprint: String,
    val trust: NodeTrust = NodeTrust.LIMITED,
    val pairedAtMs: Long,
    val lastSeenMs: Long? = null,
    val info: NodeInfo? = null,
    /** Set when the user revoked the node: the phone refuses it from then on, whatever the node does. */
    val revocation: NodeRevocation? = null,
)

/**
 * A revoked node (architecture spec §13.1, AC-017). The phone's side is enforced at once: its certificate is no
 * longer accepted and no work, data or lease renewal goes to it. The node's side is only what the node says when the
 * phone reaches it: until then the revocation is pending, and even an acknowledgment is the node's word, not proof
 * that anything on it stopped.
 */
@Serializable
data class NodeRevocation(
    val atMs: Long,
    val reason: String,
    val by: String = "user",
    /** Jobs the phone last knew running there; they end with their leases only if the node keeps its contract. */
    val runningJobs: List<String> = emptyList(),
    val leasesEndByMs: Long? = null,
    /** What had gone to the node from this phone: the exposure the revocation cannot take back. */
    val filesSent: Int = 0,
    val bytesSent: Long = 0,
    val anchorsThere: Int = 0,
    val acknowledgedAtMs: Long? = null,
    /** The node's own account when it acknowledged: jobs it said were still running, and when their leases end. */
    val nodeReport: String? = null,
    val lastAttemptMs: Long? = null,
    val lastAttemptError: String? = null,
) {
    val pending: Boolean get() = acknowledgedAtMs == null
}

/** The phone's record of one job on a node: the phone's side of what the node reports at rejoin. */
@Serializable
data class NodeJob(
    val id: String,
    val nodeId: String,
    val argv: List<String>,
    val state: JobState,
    val startedAtMs: Long,
    val leaseUntilMs: Long,
    val policyRevision: Int,
    val epoch: String? = null,
    val exit: Int? = null,
    val endedAtMs: Long? = null,
    val outputTail: String = "",
    val manifestFiles: Int = 0,
    val manifestBytes: Long = 0,
    val note: String? = null,
    /** When the phone last heard about it from the node. */
    val reconciledAtMs: Long? = null,
)

/**
 * Placement policy, versioned (architecture spec §10.1): which trust a node needs to get work, how much data may leave
 * for one job, how long a job may run without the phone (its offline lease), and that no secret goes with it.
 */
@Serializable
data class NodePolicy(
    val id: String = "policy",
    val revision: Int = 1,
    val minimumTrust: NodeTrust = NodeTrust.OWNED,
    val maxUploadBytes: Long = 50L * 1024 * 1024,
    val offlineLeaseMinutes: Int = 10,
    val secretsAllowed: Boolean = false,
)

/** Paired nodes, their jobs and the placement policy, persisted; every change journaled. */
class NodeRegistry(private val store: ControlStore) {
    private val nodes = store.docs("nodes", PairedNode.serializer(), PairedNode::id)
    private val jobs = store.docs("node-jobs", NodeJob.serializer(), NodeJob::id)
    private val policies = store.docs("node-policy", NodePolicy.serializer(), NodePolicy::id)
    private val journal = store.journal("nodes")

    val all: StateFlow<Map<String, PairedNode>> = nodes.all
    val allJobs: StateFlow<Map<String, NodeJob>> = jobs.all
    val policyFlow: StateFlow<Map<String, NodePolicy>> = policies.all
    val recent = journal.recent

    fun policy(): NodePolicy = policies["policy"] ?: NodePolicy()

    /** A new revision of the policy; jobs record the revision they ran under. */
    fun updatePolicy(change: (NodePolicy) -> NodePolicy): NodePolicy {
        val current = policy()
        val next = change(current).copy(id = "policy", revision = current.revision + 1)
        policies.put(next)
        journal.append("nodes.policy", buildJsonObject { put("revision", next.revision); put("max_upload", next.maxUploadBytes); put("offline_minutes", next.offlineLeaseMinutes); put("min_trust", next.minimumTrust.name.lowercase()) })
        return next
    }

    operator fun get(id: String): PairedNode? = nodes[id]

    /** The certificates the phone accepts: a revoked node's is not among them. */
    fun pins(): Set<String> = nodes.list().filter { it.revocation == null }.map { it.fingerprint }.toSet()

    fun paired(node: PairedNode) {
        nodes.put(node)
        journal.append("nodes.paired", buildJsonObject { put("node", node.id); put("name", node.name); put("host", "${node.host}:${node.port}"); put("fingerprint", node.fingerprint) })
    }

    fun seen(id: String, info: NodeInfo?) {
        val before = nodes[id] ?: return
        nodes.update(id) { it.copy(lastSeenMs = store.clock.nowMs(), info = info ?: it.info) }
        if (info != null && before.info?.epoch != null && before.info.epoch != info.epoch) {
            journal.append("nodes.epoch_changed", buildJsonObject { put("node", id); put("previous", before.info.epoch); put("now", info.epoch) })
        }
    }

    fun setTrust(id: String, trust: NodeTrust) {
        nodes.update(id) { it.copy(trust = trust) }
        journal.append("nodes.trust", buildJsonObject { put("node", id); put("trust", trust.name.lowercase()) })
    }

    /** Forgets the node here. The node still lists this phone until it is unpaired there too. */
    fun unpair(id: String) {
        val n = nodes[id]
        nodes.delete(id)
        journal.append("nodes.unpaired_here", buildJsonObject {
            put("node", id)
            n?.revocation?.let { put("revocation", if (it.pending) "pending" else "acknowledged") }
        })
    }

    /**
     * Revokes [id] on this phone, now: from here on the phone sends it nothing and accepts nothing from it. What it
     * still had (running jobs, files, anchors) is recorded with the revocation, because revoking does not take it back.
     */
    fun revoke(id: String, reason: String, anchorsThere: Int, by: String = "user"): NodeRevocation {
        val n = nodes[id] ?: throw IllegalArgumentException("no node $id")
        n.revocation?.let { return it }
        val mine = jobs.list().filter { it.nodeId == id }
        val running = mine.filter { it.state == JobState.RUNNING }
        val r = NodeRevocation(
            atMs = store.clock.nowMs(), reason = reason.ifBlank { "revoked by the user" }, by = by,
            runningJobs = running.map { it.id }, leasesEndByMs = running.maxOfOrNull { it.leaseUntilMs },
            filesSent = mine.sumOf { it.manifestFiles }, bytesSent = mine.sumOf { it.manifestBytes }, anchorsThere = anchorsThere,
        )
        nodes.update(id) { it.copy(revocation = r) }
        journal.append("nodes.revoked", buildJsonObject {
            put("node", id); put("fingerprint", n.fingerprint); put("reason", r.reason); put("by", by)
            put("running_jobs", running.size); put("bytes_sent", r.bytesSent); put("anchors_there", anchorsThere)
        })
        return r
    }

    /** The node answered the revocation notice: [report] is its own account, recorded as such. */
    fun revocationAcknowledged(id: String, report: List<JobStatus>) {
        val now = store.clock.nowMs()
        val running = report.filter { it.state == JobState.RUNNING }
        val text = if (running.isEmpty()) "the node reports no job running"
        else "the node reports ${running.size} job(s) still running, leases ending by ${running.maxOf { it.leaseUntilMs }}"
        nodes.update(id) { n -> n.copy(revocation = n.revocation?.copy(acknowledgedAtMs = now, nodeReport = text, lastAttemptMs = now, lastAttemptError = null)) }
        journal.append("nodes.revocation_acknowledged", buildJsonObject { put("node", id); put("node_report", text) })
    }

    /** The notice could not be delivered: the revocation stays pending. */
    fun revocationUnreachable(id: String, error: String) {
        val now = store.clock.nowMs()
        nodes.update(id) { n -> n.copy(revocation = n.revocation?.copy(lastAttemptMs = now, lastAttemptError = error.take(200))) }
        journal.append("nodes.revocation_pending", buildJsonObject { put("node", id); put("error", error.take(200)) })
    }

    /** Why [nodeId] may not get a job with [uploadBytes] of data now, or null when the policy places it. */
    fun placementProblem(nodeId: String, uploadBytes: Long): String? {
        val n = nodes[nodeId] ?: return "no such node"
        if (n.revocation != null) return "${n.name} is revoked: nothing is placed on it"
        val p = policy()
        if (n.trust.ordinal > p.minimumTrust.ordinal) return "${n.name} is trusted ${n.trust.label.lowercase()}; the policy (revision ${p.revision}) places work on ${p.minimumTrust.label.lowercase()} nodes only"
        if (uploadBytes > p.maxUploadBytes) return "the job's files come to $uploadBytes bytes; the policy lets at most ${p.maxUploadBytes} leave the phone"
        if (n.info == null) return "run the preflight first: its capabilities are not known"
        return null
    }

    fun started(job: NodeJob) {
        jobs.put(job)
        journal.append("nodes.job_started", buildJsonObject { put("node", job.nodeId); put("job", job.id); put("argv", job.argv.joinToString(" ").take(200)); put("lease_until", job.leaseUntilMs); put("policy", job.policyRevision) })
    }

    fun output(jobId: String, text: String) {
        jobs.update(jobId) { it.copy(outputTail = (it.outputTail + text).takeLast(8_000)) }
    }

    /** What the node reported (an exit event, or its list at rejoin) becomes the phone's record. */
    fun reconcile(nodeId: String, reported: List<JobStatus>) {
        val now = store.clock.nowMs()
        for (s in reported) {
            val mine = jobs[s.job]
            if (mine == null || mine.nodeId != nodeId) continue
            val next = mine.copy(state = s.state, exit = s.exit, endedAtMs = s.endedAtMs, leaseUntilMs = s.leaseUntilMs, epoch = s.epoch, note = s.note,
                outputTail = if (s.outputTail.length >= mine.outputTail.length) s.outputTail else mine.outputTail, reconciledAtMs = now)
            if (next.state != mine.state) journal.append("nodes.job_reconciled", buildJsonObject { put("node", nodeId); put("job", s.job); put("from", mine.state.name.lowercase()); put("to", s.state.name.lowercase()); s.exit?.let { put("exit", it) } })
            jobs.put(next)
        }
        // Jobs the node no longer knows (forgotten, or a node that lost its folder): said so, not invented.
        val known = reported.map { it.job }.toSet()
        for (j in jobs.list()) if (j.nodeId == nodeId && j.id !in known && j.state == JobState.RUNNING) {
            jobs.put(j.copy(state = JobState.LOST, note = "the node no longer knows this job", reconciledAtMs = now))
            journal.append("nodes.job_lost", buildJsonObject { put("node", nodeId); put("job", j.id) })
        }
    }

    fun leaseRenewed(jobId: String, until: Long) {
        jobs.update(jobId) { it.copy(leaseUntilMs = until) }
    }

    fun forgetJob(jobId: String) {
        jobs.delete(jobId)
    }
}
