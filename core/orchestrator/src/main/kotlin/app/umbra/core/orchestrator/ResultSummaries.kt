package app.umbra.core.orchestrator

object ResultSummaries {
    const val LIMIT = 2_000

    fun from(state: ConversationState): String? {
        val turn = state.items.drop(state.items.indexOfLast { it is ChatItem.User } + 1)
        val answer = turn.filterIsInstance<ChatItem.Assistant>().lastOrNull { !it.streaming && it.text.isNotBlank() } ?: return null
        val text = Redaction.text(answer.text).trim()
        return if (text.length <= LIMIT) text else text.take(LIMIT - 1).trimEnd() + "…"
    }
}
