package app.umbra.agent.telegram

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class TelegramApiTest {
    @Test
    fun downloadAndReactionUseTheBotProtocol(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"ok":true,"result":{"file_path":"documents/file_4.txt","file_size":5}}""").build())
            server.enqueue(MockResponse.Builder().body("hello").build())
            server.enqueue(MockResponse.Builder().body("""{"ok":true,"result":true}""").build())
            val api = TelegramApi(OkHttpClient(), { "fixture-token" }, server.url("/"))
            assertContentEquals("hello".toByteArray(), api.download("telegram-file-id"))
            api.react(42, 7, "👍")
            val meta = server.takeRequest()
            assertEquals("/botfixture-token/getFile", meta.url.encodedPath)
            assertEquals("telegram-file-id", Json.parseToJsonElement(requireNotNull(meta.body).utf8()).jsonObject["file_id"]?.jsonPrimitive?.content)
            assertEquals("/file/botfixture-token/documents/file_4.txt", server.takeRequest().url.encodedPath)
            val reaction = server.takeRequest()
            assertEquals("/botfixture-token/setMessageReaction", reaction.url.encodedPath)
            val body = Json.parseToJsonElement(requireNotNull(reaction.body).utf8()).jsonObject
            assertEquals(42, body["chat_id"]?.jsonPrimitive?.int)
            assertEquals(7, body["message_id"]?.jsonPrimitive?.int)
            assertEquals("emoji", body["reaction"]?.jsonArray?.single()?.jsonObject?.get("type")?.jsonPrimitive?.content)
            assertEquals("👍", body["reaction"]?.jsonArray?.single()?.jsonObject?.get("emoji")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun oversizedAndEscapingFileMetadataNeverStartsADownload(): Unit = runBlocking {
        for (metadata in listOf(
            """{"file_path":"documents/file.txt","file_size":20000001}""",
            """{"file_path":"documents/file.txt","file_size":-5}""",
            """{"file_path":"../getUpdates"}""",
            """{"file_path":"https://example.com/file"}""",
            """{"file_path":"photos/%2e%2e/file"}""",
        )) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body("""{"ok":true,"result":$metadata}""").build())
                val api = TelegramApi(OkHttpClient(), { "fixture-token" }, server.url("/"))
                assertFailsWith<IllegalArgumentException> { api.download("fixture") }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun chunkedResponsesAreBoundedDuringTheReadAndExactLimitIsAllowed() {
        assertContentEquals(byteArrayOf(1, 2, 3), TelegramApi.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), -1, 3))
        val input = ByteArrayInputStream(ByteArray(100))
        assertFailsWith<IllegalArgumentException> { TelegramApi.readBounded(input, -1, 3) }
        assertEquals(96, input.available(), "Only the limit and one extra byte should be read")
        val known = ByteArrayInputStream(ByteArray(100))
        assertFailsWith<IllegalArgumentException> { TelegramApi.readBounded(known, 100, 3) }
        assertEquals(100, known.available(), "An oversized declared length is rejected before allocation or reading")
    }

    @Test
    fun attachmentNamesCannotTraverseOrOverwriteAPreviousUpload() {
        val first = TelegramApi.attachmentName("../../report.txt")
        val second = TelegramApi.attachmentName("../../report.txt")
        assertNotEquals(first, second)
        assertTrue(first.endsWith("-report.txt"))
        for (name in listOf("..", ".", "", "/", "\\", "../../../../sensitive", "name\u0000.txt")) {
            val safe = TelegramApi.attachmentName(name)
            assertFalse('/' in safe || '\\' in safe || '\u0000' in safe)
            assertTrue(safe.length in 38..117)
        }
    }

    @Test
    fun redirectAndApiErrorsNeverExposeTheBotToken(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://example.com/steal").body("").build())
            val api = TelegramApi(OkHttpClient(), { "private-fixture-token" }, server.url("/"))
            val error = assertFailsWith<IllegalStateException> { api.call("getMe", JsonObject(emptyMap())) }
            assertFalse(error.message.orEmpty().contains("private-fixture-token"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun documentsUploadAsMultipartWithASanitizedNameAndBoundedCaption(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"ok":true,"result":{"message_id":9}}""").build())
            val api = TelegramApi(OkHttpClient(), { "fixture-token" }, server.url("/"))
            val file = java.io.File.createTempFile("report", ".md").apply { writeText("# Result"); deleteOnExit() }
            api.sendDocument(42, file, "../../report final.md", "text/markdown", "x".repeat(2000))
            val request = server.takeRequest()
            assertEquals("/botfixture-token/sendDocument", request.url.encodedPath)
            val body = requireNotNull(request.body).utf8()
            assertTrue("name=\"chat_id\"" in body && "\r\n\r\n42\r\n" in body)
            assertTrue("filename=\"report_final.md\"" in body, body.take(600))
            assertTrue("# Result" in body)
            assertTrue("x".repeat(1024) in body && "x".repeat(1025) !in body)
        }
    }

    @Test
    fun emptyOrOversizedUploadsNeverReachTelegram(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = TelegramApi(OkHttpClient(), { "fixture-token" }, server.url("/"))
            val empty = java.io.File.createTempFile("empty", ".bin").apply { deleteOnExit() }
            assertFailsWith<IllegalArgumentException> { api.sendDocument(42, empty, "empty.bin", "application/octet-stream", null) }
            assertEquals(0, server.requestCount)
        }
    }
    @Test
    fun rejectedHtmlFallsBackToPlainTextWithTheSameButtons(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(400).body("""{"ok":false}""").build())
            server.enqueue(MockResponse.Builder().body("""{"ok":true,"result":{"message_id":8}}""").build())
            val api = TelegramApi(OkHttpClient(), { "fixture-token" }, server.url("/"))
            val markup = telegramKeyboard(listOf(listOf(telegramButton("Choose", "opaque"))))
            val result = api.callHtml("sendMessage", buildJsonObject {
                put("chat_id", 42); put("text", "<b>A &amp; &lt;B&gt;</b>"); put("parse_mode", "HTML"); put("reply_markup", markup)
            })
            assertEquals(8, result.jsonObject["message_id"]?.jsonPrimitive?.int)
            val first = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject
            val fallback = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject
            assertEquals("HTML", first["parse_mode"]?.jsonPrimitive?.content)
            assertFalse("parse_mode" in fallback)
            assertEquals("A & <B>", fallback["text"]?.jsonPrimitive?.content)
            assertEquals(markup, fallback["reply_markup"])
        }
    }

    @Test
    fun htmlTransportErrorsDoNotRetryTheMessage(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(500).body("""{"ok":false}""").build())
            val api = TelegramApi(OkHttpClient(), { "fixture-token" }, server.url("/"))
            assertFailsWith<TelegramApiException> {
                api.callHtml("sendMessage", buildJsonObject { put("chat_id", 42); put("text", "<b>Text</b>"); put("parse_mode", "HTML") })
            }
            assertEquals(1, server.requestCount)
        }
    }

}
