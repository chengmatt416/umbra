package app.umbra.core.control

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ResourcePolicyTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun aDraftChangesNothingUntilPublishedAndOvercommitIsRejected() {
        val policies = ResourcePolicies(ControlStore(dir))
        val running = mapOf<String?, Int>("p1" to 2)
        assertNull(policies.admit("p1", running), "no policy: no project maximum")

        policies.saveDraft(ResourcePolicy(slots = 4, budgets = mapOf("p1" to Budget(guaranteed = 3, max = 2), ResourcePolicy.INDEPENDENT to Budget(2, 0))))
        assertNull(policies.admit("p1", running), "a draft does not change admission (AC-021)")
        val bad = policies.simulate(policies.draft()!!.policy, running, deviceSlots = 4, knownProjects = setOf("p1"))
        assertFalse(bad.valid)
        assertTrue(bad.problems.any { "overcommit" in it } && bad.problems.any { "below the guarantee" in it }, "${bad.problems}")
        assertFailsWith<IllegalStateException> { policies.publish(bad) }

        policies.saveDraft(ResourcePolicy(slots = 4, budgets = mapOf("p1" to Budget(guaranteed = 1, max = 1))))
        val ok = policies.simulate(policies.draft()!!.policy, running, deviceSlots = 2, knownProjects = setOf("p1"))
        assertTrue(ok.valid)
        assertEquals(1, ok.converging.size, "p1 runs 2 against a new maximum of 1: it converges, nothing is stopped")
        val rev = policies.publish(ok)
        assertEquals(1, rev.number)
        assertNotNull(policies.admit("p1", running), "now the maximum holds new sessions back")
        assertNull(policies.admit(null, running), "independent sessions have no maximum of their own")
        assertFalse(policies.status(running).effective)
        assertTrue(policies.status(mapOf("p1" to 1)).effective)
    }

    @Test
    fun restoringAnOldRevisionMakesANewDraftAndKeepsHistory() {
        val policies = ResourcePolicies(ControlStore(dir))
        policies.saveDraft(ResourcePolicy(slots = 3))
        policies.publish(policies.simulate(policies.draft()!!.policy, emptyMap(), 3, emptySet()))
        policies.saveDraft(ResourcePolicy(slots = 5))
        policies.publish(policies.simulate(policies.draft()!!.policy, emptyMap(), 3, emptySet()))
        policies.restoreAsDraft(1)
        assertEquals(3, policies.draft()!!.policy.slots)
        assertEquals(2, ResourcePolicies(ControlStore(dir)).published()!!.number, "restoring publishes nothing by itself")
    }
}
