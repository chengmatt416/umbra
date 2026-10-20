package app.umbra.agent.ui.porcelain

import app.umbra.core.orchestrator.Block
import app.umbra.core.orchestrator.ChatItem
import app.umbra.core.orchestrator.ConversationState
import app.umbra.core.orchestrator.ToolRun
import kotlin.test.Test
import kotlin.test.assertEquals

class ThinkingOrbStateTest {
    private fun streaming(block: Block) = ConversationState(live = true, working = true, items = listOf(
        ChatItem.Assistant("answer", 1, listOf(block), null, null, 0, 0, 0.0, null, null, true),
    ))

    @Test
    fun thinkingWritingAndConnectingFollowTheActualTurn() {
        assertEquals(ThinkingOrbState.CONNECTING, thinkingOrbState(ConversationState()))
        assertEquals(ThinkingOrbState.SOLVING, thinkingOrbState(streaming(Block.Thinking("reasoning", false))))
        assertEquals(ThinkingOrbState.COMPOSING, thinkingOrbState(streaming(Block.Text("An answer"))))
        assertEquals(ThinkingOrbState.CONNECTING, thinkingOrbState(streaming(Block.Text("An answer")).copy(compacting = true)))
        assertEquals(ThinkingOrbState.CONNECTING, thinkingOrbState(ConversationState(retry = ConversationState.Retry(1, 3, "offline"))))
    }

    @Test
    fun onlyRunningToolsSelectTheActivityAndTriageTakesPriority() {
        val done = ToolRun("old", "write", null, null, null, "Saved", false, false)
        val read = ToolRun("new", "grep", null, null, null, null, false, true)
        val state = streaming(Block.Thinking("reasoning", false)).copy(tools = linkedMapOf("old" to done, "new" to read))
        assertEquals(ThinkingOrbState.SEARCHING, thinkingOrbState(state))
        assertEquals(ThinkingOrbState.COMPOSING, thinkingOrbState(state.copy(tools = mapOf("new" to read.copy(name = "edit")))))
        assertEquals(ThinkingOrbState.CONNECTING, thinkingOrbState(state.copy(tools = mapOf("new" to read.copy(name = "coding_start")))))
        assertEquals(ThinkingOrbState.WORKING, thinkingOrbState(state.copy(tools = mapOf("new" to read.copy(name = "bash")))))
        assertEquals(ThinkingOrbState.SEARCHING, thinkingOrbState(state.copy(tools = mapOf("new" to read.copy(name = "bash"))), reviewing = true))
        assertEquals(ThinkingOrbState.SOLVING, thinkingOrbState(state.copy(tools = mapOf("old" to done))))
        assertEquals(ThinkingOrbState.SEARCHING, thinkingOrbStateForTool("memory_list"))
        assertEquals(ThinkingOrbState.SEARCHING, thinkingOrbStateForTool("messages_read"))
        assertEquals(ThinkingOrbState.CONNECTING, thinkingOrbStateForTool("messages_send"))
    }
}
