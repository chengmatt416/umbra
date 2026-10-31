package app.umbra.core.orchestrator

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class SessionSkillsTest {
    @TempDir(factory = UuidTempDirFactory::class)
    lateinit var dir: Path

    @Test fun nullSkillsLeaveArgumentsUnchanged() {
        assertEquals(emptyList(), AgentSession.skillArguments(null))
        assertEquals(emptyList(), AgentSession.skillArguments(null, listOf("--no-skills")))
        assertNull(SessionSpec().skills)
    }

    @Test fun emptySkillsDisableDiscoveryAndSelectedSkillsUseOneCommaSeparatedFlag() {
        assertEquals(listOf("--no-skills"), AgentSession.skillArguments(emptyList()))
        assertEquals(listOf("--skills", "android,review"), AgentSession.skillArguments(listOf("android", "review", "android")))
        assertEquals(emptyList(), AgentSession.skillArguments(emptyList(), listOf("--no-skills")))
        assertEquals(emptyList(), AgentSession.skillArguments(listOf("android"), listOf("--no-skills")))
        listOf("*", "a?", "[abc]", "a,b", "../a", "--no-tools", "a\nb").forEach { name ->
            assertFailsWith<IllegalArgumentException> { AgentSession.skillArguments(listOf(name)) }
        }
    }

    @Test fun directCreationAndQueuedSubmissionPreserveTheSelection(): Unit = runBlocking {
        // Admission always waits: the fixture never launches omp or contacts a model endpoint.
        OrchestratorRig(dir, File("/unused/omp"), "http://127.0.0.1:1", admission = { _, _ -> "Waiting for test" }).use { rig ->
            val direct = rig.supervisor.create("Direct", skills = listOf("android"))
            assertEquals(listOf("android"), rig.store.get(direct.id)?.skills)
            for (skills in listOf(null, emptyList(), listOf("android", "review"))) {
                val queued = rig.supervisor.submit("Queued task", spec = SessionSpec(skills = skills))
                assertNotNull(queued.queuedAtMs)
                assertEquals(skills, rig.store.get(queued.id)?.skills)
            }
        }
    }
}
