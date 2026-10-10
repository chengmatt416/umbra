package app.umbra.core.control

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How agent slots are shared (architecture spec §4.2, Guaranteed Minimum + Elastic Sharing): each project and the
 * pool of independent sessions get a guaranteed minimum, a maximum and a weight. A slot is one running agent
 * process. The device's measured capacity is a separate, harder limit: a guarantee it cannot meet is reported as a
 * shortfall, never forced (it would cost the phone its stability).
 */
@Serializable
data class ResourcePolicy(
    /** Slots this policy shares out. */
    val slots: Int = 4,
    /** Budgets by project id; [INDEPENDENT] is the pool of sessions without a project. */
    val budgets: Map<String, Budget> = emptyMap(),
) {
    fun budgetOf(projectId: String?): Budget = budgets[projectId ?: INDEPENDENT] ?: Budget(0, 0, 1)

    companion object {
        const val INDEPENDENT = "independent"
    }
}

/** A published revision: history is append-only, restoring an old one makes a new draft (§4.2). */
@Serializable
data class PolicyRevision(val id: String, val number: Int, val policy: ResourcePolicy, val publishedAtMs: Long, val by: String, val note: String? = null)

@Serializable
data class PolicyDraft(val id: String = "draft", val policy: ResourcePolicy, val basedOn: Int?, val updatedAtMs: Long)

/** What a simulation of a policy against the current work found. */
data class Simulation(
    val valid: Boolean,
    val problems: List<String>,
    /** Projects whose running sessions already exceed the new maximum: they converge at safe points, nothing is stopped. */
    val converging: List<String>,
    /** Guarantees the device's current capacity cannot meet. */
    val shortfalls: List<String>,
    val notes: List<String>,
)

/** The policy's state against what runs now: published, and either effective or still converging. */
data class PolicyStatus(val published: PolicyRevision?, val effective: Boolean, val converging: List<String>)

class ResourcePolicies(private val store: ControlStore) {
    private val revisions = store.docs("policy-revisions", PolicyRevision.serializer(), PolicyRevision::id)
    private val drafts = store.docs("policy-draft", PolicyDraft.serializer(), PolicyDraft::id)
    private val journal = store.journal("policies")

    val revisionsAll: StateFlow<Map<String, PolicyRevision>> = revisions.all
    val draftAll: StateFlow<Map<String, PolicyDraft>> = drafts.all

    fun published(): PolicyRevision? = revisions.list().maxByOrNull { it.number }

    fun current(): ResourcePolicy = published()?.policy ?: ResourcePolicy()

    fun draft(): PolicyDraft? = drafts["draft"]

    /** Starts (or replaces) the draft. Drafts never change what the scheduler does (§4.2, AC-021). */
    fun saveDraft(policy: ResourcePolicy): PolicyDraft =
        drafts.put(PolicyDraft("draft", policy, published()?.number, store.clock.nowMs()))

    fun discardDraft() { drafts.delete("draft") }

    /** An old revision becomes a new draft; the history is not rewritten. */
    fun restoreAsDraft(number: Int): PolicyDraft? = revisions.list().firstOrNull { it.number == number }?.let { saveDraft(it.policy) }

    /**
     * Tries [policy] against the work that runs now ([running] sessions per project id, null = independent) and the
     * device's capacity ([deviceSlots]), changing nothing.
     */
    fun simulate(policy: ResourcePolicy, running: Map<String?, Int>, deviceSlots: Int, knownProjects: Set<String>): Simulation {
        val problems = ArrayList<String>()
        val notes = ArrayList<String>()
        if (policy.slots < 1) problems += "at least one slot is needed"
        val guaranteed = policy.budgets.values.sumOf { it.guaranteed }
        if (guaranteed > policy.slots) problems += "the guarantees add up to $guaranteed, more than the ${policy.slots} slots: overcommit"
        policy.budgets.forEach { (id, b) ->
            if (b.guaranteed < 0 || b.max < 0 || b.weight < 1) problems += "$id: guarantees and maxima cannot be negative, the weight is at least 1"
            if (b.max in 1 until b.guaranteed) problems += "$id: the maximum (${b.max}) is below the guarantee (${b.guaranteed})"
            if (id != ResourcePolicy.INDEPENDENT && id !in knownProjects) notes += "$id is not a project any more; its budget is ignored"
        }
        val converging = running.mapNotNull { (project, n) ->
            val max = policy.budgetOf(project).max
            if (max in 1 until n) "${project ?: ResourcePolicy.INDEPENDENT}: $n running, the new maximum is $max" else null
        }
        val shortfalls = if (guaranteed > deviceSlots) listOf("the device can run $deviceSlots agents now; guarantees of $guaranteed cannot all be met") else emptyList()
        notes += "Unused guaranteed slots can be borrowed by others and are given back as their sessions finish; running work is never stopped to apply a policy."
        return Simulation(problems.isEmpty(), problems, converging, shortfalls, notes)
    }

    /** Publishes the draft as the next revision (when its simulation is valid) and clears it. */
    @Synchronized
    fun publish(simulation: Simulation, by: String = "user", note: String? = null): PolicyRevision {
        val d = draft() ?: throw IllegalStateException("there is no draft to publish")
        check(simulation.valid) { "the draft is not valid: ${simulation.problems.joinToString("; ")}" }
        val number = (published()?.number ?: 0) + 1
        val rev = PolicyRevision("policy-r$number", number, d.policy, store.clock.nowMs(), by, note)
        revisions.put(rev)
        drafts.delete("draft")
        journal.append("policy.published", buildJsonObject { put("revision", number); put("by", by); put("slots", d.policy.slots) })
        return rev
    }

    fun status(running: Map<String?, Int>): PolicyStatus {
        val p = published()
        val converging = running.mapNotNull { (project, n) ->
            val max = (p?.policy ?: ResourcePolicy()).budgetOf(project).max
            if (max in 1 until n) (project ?: ResourcePolicy.INDEPENDENT) else null
        }
        return PolicyStatus(p, converging.isEmpty(), converging)
    }

    /**
     * Admission (§4.2): may one more session of [projectId] start, given what runs? Null = yes; otherwise why not.
     * A maximum of 0 means "no limit of its own".
     */
    fun admit(projectId: String?, running: Map<String?, Int>): String? {
        val b = current().budgetOf(projectId)
        val mine = running[projectId] ?: 0
        if (b.max > 0 && mine >= b.max) return "${projectId ?: "independent sessions"} already run $mine of their maximum ${b.max} agents (resource policy revision ${published()?.number ?: 0})"
        return null
    }
}
