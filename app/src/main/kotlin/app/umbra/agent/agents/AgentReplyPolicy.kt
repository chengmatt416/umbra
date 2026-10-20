package app.umbra.agent.agents

import app.umbra.core.orchestrator.ChatItem
import app.umbra.core.orchestrator.ConversationState
import app.umbra.core.orchestrator.SessionBus

/** Reports and background wakes inherit the last user's channel, including after transcript reloads. */
internal fun repliesOnTelegram(state: ConversationState): Boolean = state.items.asReversed().asSequence()
    .filterIsInstance<ChatItem.User>()
    .firstOrNull { !SessionBus.HEADER.containsMatchIn(it.text) && !it.text.startsWith("[Wake") && !it.text.startsWith("The conversation of the agent") }
    ?.text?.startsWith("[Telegram] ") == true
