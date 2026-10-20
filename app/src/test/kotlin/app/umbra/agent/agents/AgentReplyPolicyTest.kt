package app.umbra.agent.agents

import app.umbra.core.orchestrator.ChatItem
import app.umbra.core.orchestrator.ConversationState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentReplyPolicyTest {
    private fun state(vararg messages: String) = ConversationState(items = messages.mapIndexed { i, text ->
        ChatItem.User("u$i", i.toLong(), text, emptyList(), false, false)
    })

    @Test
    fun telegramRequestsKeepTheirChannelAcrossDelegatedReportsAndTranscriptReloads() {
        assertTrue(repliesOnTelegram(state("[Telegram] Build this")))
        assertTrue(repliesOnTelegram(state("[Telegram] Build this", "[Report from #build · finished · Build · 2 min]\nDone.")))
        assertTrue(repliesOnTelegram(state("[Telegram] Build this", "[Message m1 from #build]\nQuestion")))
        assertTrue(repliesOnTelegram(state("[Telegram] First request", "[Wake · Tue 10:00 · heartbeat]\nTimers due:")))
    }

    @Test
    fun aNewLocalRequestRestoresPhoneNotifications() {
        assertFalse(repliesOnTelegram(state()))
        assertFalse(repliesOnTelegram(state("The conversation of the agent Mia.")))
        assertFalse(repliesOnTelegram(state("[Telegram] First request", "Next request from the app")))
        assertFalse(repliesOnTelegram(state("[Wake · Tue 10:00 · heartbeat]\nTimers due:")))
        assertTrue(repliesOnTelegram(state("Request from the app", "[Telegram] Next request")))
    }
}
