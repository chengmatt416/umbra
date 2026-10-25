package app.umbra.core.orchestrator

import app.umbra.core.session.Session
import app.umbra.core.session.SessionState
import kotlin.test.*

class SpawnGuardTest {
    private fun s(id: String, parent: String? = null, state: SessionState = SessionState.RUNNING, at: Long = 0) =
        Session(id = id, title = id, createdAtMs = at, request = id, workspaceId = "w", spawnedBy = parent, state = state)

    @Test fun depthIsBoundedBelowTheRoot() {
        val chain = listOf(s("root"), s("a", "root"), s("b", "a"), s("c", "b"))
        assertNull(SpawnGuard.refusal(chain, "b", 1))            // c is level 3
        assertNotNull(SpawnGuard.refusal(chain, "c", 1))         // level 4 is refused
    }

    @Test fun unfinishedChildrenAndTreesAreBounded() {
        val parent = listOf(s("root")) + (1..4).map { s("k$it", "root") }
        assertNotNull(SpawnGuard.refusal(parent, "root", 1), "a fifth unfinished child")
        val finished = listOf(s("root")) + (1..4).map { s("k$it", "root", SessionState.COMPLETED) }
        assertNull(SpawnGuard.refusal(finished, "root", 1), "finished children free their places")
        // Six unfinished sessions spread over two parents fill the root's tree.
        val tree = listOf(s("root"), s("p1", "root"), s("p2", "root")) + (1..2).map { s("x$it", "p1") } + (1..2).map { s("y$it", "p2") }
        assertNotNull(SpawnGuard.refusal(tree, "p1", 1))
    }

    @Test fun startsPerHourAreBoundedEvenWhenEachFinishesQuickly() {
        val hour = 60 * 60_000L
        val now = 10 * hour
        val burst = listOf(s("root")) + (1..24).map { s("r$it", "root", SessionState.COMPLETED, at = now - 60_000L * it) }
        assertNotNull(SpawnGuard.refusal(burst, "root", now))
        val spread = listOf(s("root")) + (1..24).map { s("r$it", "root", SessionState.COMPLETED, at = now - hour - 60_000L * it) }
        assertNull(SpawnGuard.refusal(spread, "root", now))
    }

    @Test fun missingOrLoopingParentsAreRefused() {
        assertNotNull(SpawnGuard.refusal(emptyList(), "gone", 1))
        assertNotNull(SpawnGuard.refusal(listOf(s("a", "b"), s("b", "a")), "a", 1))
    }
}
