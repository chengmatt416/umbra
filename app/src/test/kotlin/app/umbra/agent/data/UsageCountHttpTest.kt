package app.umbra.agent.data

import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class UsageCountHttpTest {
    @Test fun onlyTheRandomIdentifierIsSentAndHtmlOrRedirectsNeverCountAsSuccess(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = UsageCountHttp(server.url("/api/usage-counts"))
            val id = "1a25995e-76ee-45cb-ab59-b232650c8438"
            assertFailsWith<IllegalArgumentException> { client.send("private task data") }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse.Builder().code(204).build())
            assertTrue(client.send(id))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/usage-counts", request.url.encodedPath)
            assertEquals("Umbra-Usage-Counts/1", request.headers["User-Agent"])
            assertNull(request.headers["Authorization"])
            assertEquals(buildJsonObject { put("install_id", id) }, Json.parseToJsonElement(requireNotNull(request.body).utf8()))
            server.enqueue(MockResponse.Builder().code(200).body("<html>Site fallback</html>").build())
            assertFalse(client.send(id))
            server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "/other").build())
            assertFalse(client.send(id))
            assertEquals(3, server.requestCount)
        }
    }
}
