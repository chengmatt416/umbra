package app.umbra.agent.ui.agents

import app.umbra.core.session.*
import kotlin.test.*

class AgentWorkTest {
    private fun task(id: String, owner: String?, parent: String?, kind: SessionKind, state: SessionState) =
        Session(id, id, state, 1, request = id, agentId = owner, spawnedBy = parent, kind = kind)

    @Test fun parentPageListsActiveCodingSubagentsAndSpecialistsThroughCompletedParents() {
        val main = task("main", "owner", null, SessionKind.AGENT, SessionState.COMPLETED)
        val completed = task("done", "owner", "main", SessionKind.CODING, SessionState.COMPLETED)
        val coding = task("coding", "owner", "main", SessionKind.CODING, SessionState.RUNNING)
        val child = task("child", null, "done", SessionKind.SUBAGENT, SessionState.WAITING_APPROVAL)
        val specialist = task("specialist", "other", "child", SessionKind.AGENT, SessionState.PAUSED)
        val unrelated = task("unrelated", "other", null, SessionKind.CODING, SessionState.RUNNING)
        val input = listOf(main, completed, coding, child, specialist, unrelated)
        assertEquals(listOf("coding", "child", "specialist"), agentWork("owner", "main", input).map { it.id })
        assertTrue(agentWork("owner", "main", input.map { it.copy(state = SessionState.COMPLETED) }).isEmpty())
    }

    @Test fun queuedWorkSurvivesRestartAndCyclesCannotLoopTheView() {
        val root = task("root", "owner", "child", SessionKind.AGENT, SessionState.RUNNING)
        val child = task("child", null, "root", SessionKind.SUBAGENT, SessionState.CREATED).copy(queuedAtMs = 1)
        assertEquals(listOf(child), agentWork("owner", "root", listOf(root, child)))
        assertTrue(agentWork("different", null, listOf(root, child)).isEmpty())
    }
}
