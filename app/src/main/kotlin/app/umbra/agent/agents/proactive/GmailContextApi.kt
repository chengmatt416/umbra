package app.umbra.agent.agents.proactive

import app.umbra.core.model.UmbraJson
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Uses a user-supplied OAuth access token kept in SecretStore, never passed to an agent process. */
class GmailContextApi(
    private val base: HttpUrl = "https://gmail.googleapis.com/gmail/v1/users/me/".toHttpUrl(),
    http: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    suspend fun compose(token: String, to: String, subject: String, text: String, send: Boolean): String = withContext(Dispatchers.IO) {
        val raw = message(to, subject, text)
        val payload = buildJsonObject { if (send) put("raw", raw) else put("message", buildJsonObject { put("raw", raw) }) }
        val url = base.newBuilder().addPathSegment(if (send) "messages" else "drafts").apply { if (send) addPathSegment("send") }.build()
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").post(payload.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { reply ->
            val result = response(reply)
            (result["id"] as? JsonPrimitive)?.content?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,200}")) } ?: error("Email response did not identify the result. Inspect Drafts or Sent before retrying.")
        }
    }
    suspend fun search(token: String, query: String, days: Int): List<ContextEvidence> = withContext(Dispatchers.IO) {
        require(days in 1..3650 && query.length <= 500 && token.isNotBlank())
        val url = base.newBuilder().addPathSegment("messages").addQueryParameter("q", "newer_than:${days}d $query").addQueryParameter("maxResults", "20").build()
        val ids = get(url, token)["messages"] as? JsonArray ?: return@withContext emptyList()
        ids.take(20).mapNotNull { entry ->
            val id = (entry as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: return@mapNotNull null
            require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
            val message = get(base.newBuilder().addPathSegment("messages").addPathSegment(id).addQueryParameter("format", "full").build(), token)
            val payload = message["payload"] as? JsonObject
            val headers = (payload?.get("headers") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.associate {
                it["name"]?.jsonPrimitive?.content.orEmpty().lowercase() to it["value"]?.jsonPrimitive?.content.orEmpty()
            }
            val text = plain(payload).ifBlank { message["snippet"]?.jsonPrimitive?.content.orEmpty() }.take(12_000)
            ContextEvidence("email", id, "From: ${headers["from"].orEmpty()}\nSubject: ${headers["subject"].orEmpty()}\n$text",
                message["internalDate"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(), if (Regex("order|receipt|訂單|收據", RegexOption.IGNORE_CASE).containsMatchIn(text)) "order" else "any")
        }
    }
    private fun get(url: HttpUrl, token: String): JsonObject = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use(::response)
    private fun response(reply: okhttp3.Response): JsonObject {
        check(reply.isSuccessful) { if (reply.code == 401 || reply.code == 403) "Email access expired or its required scope is missing. Reconnect in Personal context." else "Email request failed (HTTP ${reply.code}). Inspect Drafts or Sent before retrying an ambiguous write." }
        require(reply.body.contentLength() <= ResearchHttp.MAX_BYTES)
        val source = reply.body.source()
        require(!source.request(ResearchHttp.MAX_BYTES.toLong() + 1)) { "Email response exceeds 512 KB" }
        return UmbraJson.parseToJsonElement(source.readUtf8()) as? JsonObject ?: error("Invalid email response")
    }
    private fun plain(payload: JsonObject?, depth: Int = 0): String {
        if (payload == null || depth > 8) return ""
        if (payload["mimeType"]?.jsonPrimitive?.content == "text/plain") {
            val data = (payload["body"] as? JsonObject)?.get("data")?.jsonPrimitive?.content.orEmpty()
            if (data.length > 32_000) return "[Large email body omitted]"
            return runCatching { String(Base64.getUrlDecoder().decode(data), Charsets.UTF_8) }.getOrDefault("[Email body could not be decoded]")
        }
        return (payload["parts"] as? JsonArray).orEmpty().take(20).mapNotNull { it as? JsonObject }.joinToString("\n") { plain(it, depth + 1) }.take(12_000)
    }
    companion object {
        internal fun message(to: String, subject: String, text: String): String {
            require(to.length <= 254 && to.matches(Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}"))) { "Use one explicit email address" }
            require(to.substringAfter('@').split('.').all { it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() })
            require(subject.length in 1..300 && subject.none(Char::isISOControl) && text.length in 1..100_000)
            val words = mutableListOf<String>()
            var chunk = ""
            subject.codePoints().forEach { cp ->
                val next = String(Character.toChars(cp))
                if ((chunk + next).toByteArray(Charsets.UTF_8).size > 42) { words += chunk; chunk = "" }
                chunk += next
            }
            if (chunk.isNotEmpty()) words += chunk
            val encodedSubject = words.joinToString("\r\n ") { "=?UTF-8?B?${Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))}?=" }
            val body = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(text.toByteArray(Charsets.UTF_8))
            val mime = "To: $to\r\nSubject: $encodedSubject\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n$body"
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mime.toByteArray(Charsets.UTF_8))
        }
    }
}
