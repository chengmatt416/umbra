package app.umbra.agent.notifications

import app.umbra.agent.connectors.MessageSendPolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageSendPolicyTest {
    @Test fun `only an explicitly permitted known direct chat may bypass approval`() {
        assertTrue(MessageSendPolicy.maySendAutomatically(listOf("mom"), "mom", false))
        assertFalse(MessageSendPolicy.maySendAutomatically(listOf("mom"), "colleague", false))
        assertFalse(MessageSendPolicy.maySendAutomatically(listOf("group"), "group", true))
        assertFalse(MessageSendPolicy.maySendAutomatically(listOf("unknown"), "unknown", null))
        assertFalse(MessageSendPolicy.maySendAutomatically(emptyList(), "new chat", false))
    }
}
