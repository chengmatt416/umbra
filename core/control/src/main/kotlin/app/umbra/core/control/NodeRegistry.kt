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
)

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

    fun pins(): Set<String> = nodes.list().map { it.fingerprint }.toSet()

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
        nodes.delete(id)
        journal.append("nodes.unpaired_here", buildJsonObject { put("node", id) })
    }

    /** Why [nodeId] may not get a job with [uploadBytes] of data now, or null when the policy places it. */
    fun placementProblem(nodeId: String, uploadBytes: Long): String? {
        val n = nodes[nodeId] ?: return "no such node"
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
