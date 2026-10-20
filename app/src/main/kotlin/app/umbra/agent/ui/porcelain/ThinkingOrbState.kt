package app.umbra.agent.ui.porcelain

import app.umbra.core.orchestrator.Block
import app.umbra.core.orchestrator.ChatItem
import app.umbra.core.orchestrator.ConversationState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.umbra.agent.R

enum class ThinkingOrbState { WORKING, SEARCHING, SOLVING, COMPOSING, CONNECTING }

internal fun thinkingOrbState(conversation: ConversationState?, reviewing: Boolean = false): ThinkingOrbState {
    if (reviewing) return ThinkingOrbState.SEARCHING
    if (conversation == null) return ThinkingOrbState.WORKING
    if (conversation.compacting || conversation.retry != null) return ThinkingOrbState.CONNECTING
    val tool = conversation.tools.values.lastOrNull { it.running }?.name
    if (tool != null) return thinkingOrbStateForTool(tool)
    val message = conversation.items.lastOrNull() as? ChatItem.Assistant
    return when {
        message?.streaming == true && message.blocks.lastOrNull() is Block.Thinking -> ThinkingOrbState.SOLVING
        message?.streaming == true && message.blocks.lastOrNull() is Block.Text -> ThinkingOrbState.COMPOSING
        !conversation.live -> ThinkingOrbState.CONNECTING
        else -> ThinkingOrbState.SOLVING
    }
}

internal fun thinkingOrbStateForTool(tool: String): ThinkingOrbState = when {
    tool in setOf("read", "grep", "find", "glob", "search", "web_search", "fetch", "notifications_query", "messages_list", "messages_read", "memory_list", "timer_list", "rule_list", "queue_list") -> ThinkingOrbState.SEARCHING
    tool in setOf("write", "edit") || tool.startsWith("memory_") || tool.startsWith("timer_") || tool.startsWith("rule_") || tool.startsWith("queue_") -> ThinkingOrbState.COMPOSING
    tool == "coding_start" || tool == "messages_send" || tool.startsWith("session_") -> ThinkingOrbState.CONNECTING
    else -> ThinkingOrbState.WORKING
}

@Composable
internal fun thinkingOrbLabel(state: ThinkingOrbState): String = stringResource(when (state) {
    ThinkingOrbState.WORKING -> R.string.orb_working
    ThinkingOrbState.SEARCHING -> R.string.ag_work_review
    ThinkingOrbState.SOLVING -> R.string.orb_thinking
    ThinkingOrbState.COMPOSING -> R.string.orb_composing
    ThinkingOrbState.CONNECTING -> R.string.orb_connecting
})
