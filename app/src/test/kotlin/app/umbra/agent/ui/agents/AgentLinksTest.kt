package app.umbra.agent.ui.agents

import app.umbra.core.orchestrator.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class AgentLinksTest {
    @Test
    fun wakeDigestsDoNotCreateChatMessagesOrEmptyDateSeparators() {
        val wake = ChatItem.User("wake", 1, "[Wake · Mia · heartbeat]\nTimers due:\n- Check inbox", emptyList(), false, false)
        assertTrue(chatLines(ConversationState(items = listOf(wake))) { _, _ -> null }.isEmpty())
        val message = ChatItem.User("user", 172_800_001, "Please check this", emptyList(), false, false)
        val reply = ChatItem.Assistant("reply", 172_800_002, listOf(Block.Text("Checked.")), null, null, 0, 0, 0.0, null, null, false)
        val lines = chatLines(ConversationState(items = listOf(wake, message, reply))) { _, _ -> null }
        assertEquals(1, lines.filterIsInstance<Line.Day>().size)
        assertEquals("Please check this", lines.filterIsInstance<Line.Mine>().single().text)
        assertEquals("Checked.", lines.filterIsInstance<Line.Theirs>().single().text)
        assertTrue(lines.none { it is Line.Note })
    }

    @Test
    fun delegatedWorkRemainsVisibleAfterTheAssistantYieldsItsProcessSlot() {
        fun session(id: String, kind: app.umbra.core.session.SessionKind, state: app.umbra.core.session.SessionState, agent: String?) =
            app.umbra.core.session.Session(id, id, state, 1, request = id, kind = kind, agentId = agent)
        val running = session("running", app.umbra.core.session.SessionKind.CODING, app.umbra.core.session.SessionState.RUNNING, "mia")
        val completed = session("done", app.umbra.core.session.SessionKind.CODING, app.umbra.core.session.SessionState.COMPLETED, "mia")
        val other = running.copy(id = "other", agentId = "other")
        val assistant = session("assistant", app.umbra.core.session.SessionKind.AGENT, app.umbra.core.session.SessionState.COMPLETED, "mia")
        assertEquals(running, activeDelegatedWork("mia", listOf(assistant, completed, other, running)))
        assertNull(activeDelegatedWork("mia", listOf(assistant, completed, other)))
    }

    @Test
    fun successfulDelegationLinksToTheExactSessionEvenAfterTheTranscriptReloads() {
        val args = buildJsonObject { put("task", "Build app") }
        val assistant = ChatItem.Assistant("a", 1, listOf(Block.ToolCall("call", "coding_start", args, null)), null, null, 0, 0, 0.0, null, null, false)
        val run = ToolRun("call", "coding_start", args, null, null, "[Session: s-work-1] Started the coding session #build.", false, false)
        val state = ConversationState(items = listOf(assistant), tools = mapOf("call" to run))
        val line = chatLines(state, { _, _ -> "Started coding" }).filterIsInstance<Line.Note>().single()
        assertEquals("s-work-1", line.sessionRef)
        assertTrue(chatLines(state.copy(tools = mapOf("call" to run.copy(isError = true))), { _, _ -> "Started coding" }).isEmpty())
    }

    @Test
    fun reportsPreserveStableSessionIdsAndHideTheMetadataInTheCardBody() {
        val text = "[Report from #build · finished · Build app · 2 min]\n[Session: s-work-1]\nDone."
        val item = ChatItem.User("u", 1, text, emptyList(), false, false)
        val report = chatLines(ConversationState(items = listOf(item)), { _, _ -> null }).filterIsInstance<Line.Report>().single()
        assertEquals("s-work-1", report.sessionRef)
        assertEquals("Done.", report.body)
        assertEquals("#build", report.who)
    }
}
