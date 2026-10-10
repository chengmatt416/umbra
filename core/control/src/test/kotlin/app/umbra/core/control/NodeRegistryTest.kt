package app.umbra.core.control

import app.umbra.core.model.Clock
import app.umbra.core.nodes.JobState
import app.umbra.core.nodes.JobStatus
import app.umbra.core.nodes.NodeInfo
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class NodeRegistryTest {
    @TempDir
    lateinit var dir: Path

    private var now = 1_000L
    private fun registry() = NodeRegistry(ControlStore(dir, Clock { now }))
    private val info = NodeInfo("n1", "pc", "Linux", "aarch64", 8, 16_000, mapOf("git" to "2.4"), "epoch-1", 0, "/w", 1_000_000)

    @Test
    fun placementFollowsTrustDataAndPreflight() {
        val r = registry()
        r.paired(PairedNode("n1", "pc", "10.0.0.2", 47100, "ab".repeat(32), pairedAtMs = now))
        assertTrue("trusted limited" in r.placementProblem("n1", 10).orEmpty(), "a new node gets nothing")
        r.setTrust("n1", NodeTrust.OWNED)
        assertTrue("preflight" in r.placementProblem("n1", 10).orEmpty())
        r.seen("n1", info)
        assertNull(r.placementProblem("n1", 10))
        assertTrue("at most" in r.placementProblem("n1", 60L * 1024 * 1024).orEmpty())
        val p = r.updatePolicy { it.copy(maxUploadBytes = 100L * 1024 * 1024) }
        assertEquals(2, p.revision)
        assertNull(r.placementProblem("n1", 60L * 1024 * 1024))
        assertEquals(setOf("ab".repeat(32)), r.pins())
    }

    @Test
    fun rejoinReconcilesWhatTheNodeSaysAndNeverInventsTheRest() {
        val r = registry()
        r.paired(PairedNode("n1", "pc", "h", 1, "cd".repeat(32), NodeTrust.OWNED, now))
        r.started(NodeJob("j1", "n1", listOf("make"), JobState.RUNNING, now, now + 60_000, 1))
        r.started(NodeJob("j2", "n1", listOf("sleep", "60"), JobState.RUNNING, now, now + 1_000, 1))
        r.started(NodeJob("j3", "n1", listOf("true"), JobState.RUNNING, now, now + 1_000, 1))
        now = 90_000
        r.reconcile("n1", listOf(
            JobStatus("j1", JobState.EXITED, 0, 1_000, 30_000, 61_000, "epoch-1", "built"),
            JobStatus("j2", JobState.LEASE_EXPIRED, null, 1_000, 2_100, 2_000, "epoch-1", note = "its lease ran out"),
        ))
        val jobs = registry().allJobs.value
        assertEquals(JobState.EXITED, jobs.getValue("j1").state)
        assertEquals("built", jobs.getValue("j1").outputTail)
        assertEquals(JobState.LEASE_EXPIRED, jobs.getValue("j2").state)
        assertEquals(JobState.LOST, jobs.getValue("j3").state, "a job the node does not report is lost, not assumed finished")
        r.seen("n1", info)
        r.seen("n1", info.copy(epoch = "epoch-2"))
        assertTrue(r.recent.value.any { it.kind == "nodes.epoch_changed" })
    }
}
