package app.umbra.agent.notifications

import app.umbra.agent.connectors.MessageApis
import app.umbra.core.model.UmbraJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

class MessageApiParsingTest {
    @Test fun `incoming and outgoing API records preserve chat identity and default to asking for unknown groups`() {
        val result = UmbraJson.parseToJsonElement("""{"data":[
            {"guid":"m1","text":"Hello","dateCreated":1700000000000,"isFromMe":false,"handle":{"address":"mom@example.test"},"chats":[{"guid":"iMessage;-;mom@example.test","participants":[{}]}]},
            {"guid":"m2","text":"Thanks","dateCreated":1700000001000,"isFromMe":true,"handle":null,"chats":[{"guid":"iMessage;+;group"}]},
            {"guid":"m3","text":"Your verification code is 123456","chats":[{"guid":"sensitive"}]},
            {"guid":"m4","text":null,"chats":[{"guid":"attachment"}]}
        ]}""").jsonObject
        val messages = MessageApis.decodeBlue(result)
        assertEquals(listOf("m1", "m2"), messages.map { it.id })
        assertEquals("mom@example.test", messages[0].sender)
        assertEquals(1700000000000, messages[0].atMs)
        assertTrue(messages[1].outgoing)
        assertTrue(messages[1].group)
    }

    @Test fun `an empty API inbox is valid`() {
        assertTrue(MessageApis.decodeBlue(UmbraJson.parseToJsonElement("""{"data":[]}""").jsonObject).isEmpty())
    }

    @Test fun `X events preserve identity and require approval even with two event participants`() {
        val result = UmbraJson.parseToJsonElement("""{"data":[
            {"id":"m1","text":"Hello","created_at":"2023-11-14T22:13:20Z","sender_id":"123","dm_conversation_id":"123-456","participant_ids":["123","456"]},
            {"id":"m2","text":"Your verification code is 123456","dm_conversation_id":"sensitive"},
            {"id":"m3","text":"No conversation id"}
        ]}""").jsonObject
        val message = MessageApis.decodeX(result).single()
        assertEquals("m1", message.id)
        assertEquals("123-456", message.chat)
        assertEquals("123", message.sender)
        assertEquals(1700000000000, message.atMs)
        assertTrue(message.group)
    }
}
