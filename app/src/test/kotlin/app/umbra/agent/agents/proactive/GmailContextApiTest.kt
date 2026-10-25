package app.umbra.agent.agents.proactive

import java.util.Base64
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class GmailContextApiTest {
    @Test fun draftsAndSendsUseDifferentEndpointsAndNativeMimeEncoding() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = GmailContextApi(server.url("/gmail/v1/users/me/"))
            server.enqueue(MockResponse.Builder().body("""{"id":"draft-1"}""").build())
            server.enqueue(MockResponse.Builder().body("""{"id":"message-1"}""").build())
            assertEquals("draft-1", api.compose("native-test-token", "person@example.com", "會議安排", "A safe test draft.", false))
            assertEquals("message-1", api.compose("native-test-token", "person@example.com", "Meeting", "A safe test message.", true))
            val draft = server.takeRequest()
            assertEquals("/gmail/v1/users/me/drafts", draft.url.encodedPath)
            assertEquals("Bearer native-test-token", draft.headers["Authorization"])
            val raw = Json.parseToJsonElement(requireNotNull(draft.body).utf8()).jsonObject["message"]!!.jsonObject["raw"]!!.jsonPrimitive.content
            val mime = String(Base64.getUrlDecoder().decode(raw))
            assertTrue(mime.startsWith("To: person@example.com\r\nSubject: =?UTF-8?B?"))
            assertFalse(mime.contains("native-test-token"))
            assertEquals("A safe test draft.", String(Base64.getMimeDecoder().decode(mime.substringAfter("\r\n\r\n"))))
            assertEquals("/gmail/v1/users/me/messages/send", server.takeRequest().url.encodedPath)
        }
    }
    @Test fun headersCannotInjectRecipientsAndUnicodeSubjectsFoldAtSafeCodePointBoundaries() {
        assertFailsWith<IllegalArgumentException> { GmailContextApi.message("person@example.com\r\nBcc: other@example.com", "Hello", "Body") }
        assertFailsWith<IllegalArgumentException> { GmailContextApi.message("person@example.com", "Hello\r\nBcc: other@example.com", "Body") }
        assertFailsWith<IllegalArgumentException> { GmailContextApi.message("person@example..com", "Hello", "Body") }
        val subject = "會議安排🙂".repeat(30)
        val mime = String(Base64.getUrlDecoder().decode(GmailContextApi.message("person@example.com", subject, "Body")))
        val words = Regex("=\\?UTF-8\\?B\\?([^?]+)\\?=").findAll(mime).toList()
        assertTrue(words.size > 1 && words.all { it.value.length <= 75 })
        assertEquals(subject, words.joinToString("") { String(Base64.getDecoder().decode(it.groupValues[1]), Charsets.UTF_8) })
    }
    @Test fun redirectsNeverForwardAuthorizationOrRepeatWrites() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { other ->
            first.start(); other.start()
            first.enqueue(MockResponse.Builder().code(307).addHeader("Location", other.url("/stolen").toString()).build())
            assertFailsWith<IllegalStateException> { GmailContextApi(first.url("/")).compose("native-test-token", "person@example.com", "Subject", "Body", true) }
            assertEquals(1, first.requestCount)
            assertEquals(0, other.requestCount)
        } }
    }
    @Test fun searchReadsOnlyPlainTextAndBoundsTheResultCount() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"messages":[{"id":"m1"}]}""").build())
            val body = Base64.getUrlEncoder().withoutPadding().encodeToString("Order receipt 42".toByteArray())
            server.enqueue(MockResponse.Builder().body("""{"id":"m1","internalDate":"100","payload":{"mimeType":"text/plain","headers":[{"name":"Subject","value":"Order"}],"body":{"data":"$body"}}}""").build())
            val item = GmailContextApi(server.url("/")).search("native-test-token", "order", 90).single()
            assertTrue(item.text.contains("Order receipt 42"))
            assertEquals("order", item.entityType)
            assertEquals("newer_than:90d order", server.takeRequest().url.queryParameter("q"))
            assertEquals("full", server.takeRequest().url.queryParameter("format"))
        }
    }
}
