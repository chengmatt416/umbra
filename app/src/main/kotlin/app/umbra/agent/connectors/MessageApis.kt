package app.umbra.agent.connectors

import app.umbra.agent.AppContainer
import app.umbra.agent.notifications.NotificationPrivacy
import app.umbra.core.model.UmbraJson
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Official transports. They use only credentials explicitly saved for this connector. */
class MessageApis(private val c: AppContainer) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    fun configured(app: String): Boolean = when (app) {
        "imessage" -> c.connectors.settings.of(app).api["url"]?.startsWith("https://") == true && c.secrets.has(secret(app))
        "x" -> c.secrets.has(secret(app))
        else -> false
    }

    suspend fun check(app: String, url: String?, credential: String): String = withContext(Dispatchers.IO) {
        require(credential.isNotBlank()) { "A credential is required" }
        when (app) {
            "imessage" -> {
                val base = url.orEmpty().toHttpUrl()
                require(base.isHttps && base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "Use an HTTPS server URL without credentials or query parameters" }
                request(base.newBuilder().addPathSegments("api/v1/server/info").addQueryParameter("password", credential).build().toString(), null)
            }
            "x" -> request("https://api.x.com/2/users/me", credential)
            else -> error("No official API transport for this app yet")
        }
        "Connected"
    }

    suspend fun read(app: String, chat: String?, since: Long): List<ChatMessage> = withContext(Dispatchers.IO) {
        when (app) {
            "imessage" -> {
                val result = request(blueUrl("message/query"), null, buildJsonObject {
                    put("limit", 100); put("offset", 0); put("sort", "DESC"); put("after", since)
                    put("with", JsonArray(listOf(JsonPrimitive("chats.participants"), JsonPrimitive("handle"))))
                    if (chat != null) put("chatGuid", chat)
                })
                decodeBlue(result)
            }
            "x" -> {
                val url = "https://api.x.com/2/dm_events".toHttpUrl().newBuilder()
                    .addQueryParameter("max_results", "100").addQueryParameter("event_types", "MessageCreate")
                    .addQueryParameter("dm_event.fields", "created_at,dm_conversation_id,text")
                    .addQueryParameter("expansions", "sender_id,participant_ids").build()
                decodeX(request(url.toString(), c.secrets.get(secret(app))))
                    .filter { it.atMs > since && (chat == null || it.chat == chat) }
            }
            else -> emptyList()
        }
    }

    suspend fun send(app: String, chat: String, text: String): String = withContext(Dispatchers.IO) {
        when (app) {
            "imessage" -> request(blueUrl("message/send-text"), null, buildJsonObject {
                put("chatGuid", chat); put("message", text); put("method", "apple-script"); put("tempGuid", UUID.randomUUID().toString())
            })
            "x" -> {
                require(chat.matches(Regex("[0-9-]{1,100}"))) { "Use the conversation id from messages_list" }
                request("https://api.x.com/2/dm_conversations/$chat/messages", c.secrets.get(secret(app)), buildJsonObject { put("text", text) })
            }
            else -> error("No official API transport")
        }
        "The API accepted the message. Delivery is not yet confirmed."
    }

    private fun blueUrl(path: String): String {
        val url = c.connectors.settings.of("imessage").api["url"] ?: error("Set the BlueBubbles server URL")
        val password = c.secrets.get(secret("imessage")) ?: error("Set the BlueBubbles password")
        return url.toHttpUrl().newBuilder().addPathSegments("api/v1/$path").addQueryParameter("password", password).build().toString()
    }

    private fun request(url: String, token: String?, body: JsonObject? = null): JsonObject {
        val request = Request.Builder().url(url).apply {
            if (token != null) header("Authorization", "Bearer $token")
            if (body != null) post(body.toString().toRequestBody("application/json".toMediaType()))
        }.build()
        // Do not propagate a network exception containing a URL: BlueBubbles authenticates in its query string.
        val response = try { http.newCall(request).execute() } catch (_: java.io.IOException) { error("The message API could not be reached. Check the connection before retrying a send.") }
        return response.use {
            check(it.isSuccessful) { "The message API returned HTTP ${it.code}. Check the account permissions; do not retry a send without checking the chat." }
            val source = it.body.source()
            check(!source.request(4L * 1024 * 1024 + 1)) { "API response exceeds 4 MB" }
            try { UmbraJson.parseToJsonElement(source.readUtf8()).jsonObject }
            catch (_: IllegalArgumentException) { error("The message API returned an invalid JSON response") }
        }
    }

    companion object {
        fun secret(app: String) = "connector_${app}_api"

        fun decodeX(result: JsonObject): List<ChatMessage> = result["data"]?.jsonArray.orEmpty().mapNotNull { v ->
            val o = v.jsonObject
            val text = o.str("text") ?: return@mapNotNull null
            if (NotificationPrivacy.containsCode(text)) return@mapNotNull null
            ChatMessage(o.str("id") ?: return@mapNotNull null, "x", o.str("dm_conversation_id") ?: return@mapNotNull null,
                o.str("sender_id").orEmpty(), NotificationPrivacy.redact(text), o.str("created_at")?.let { Instant.parse(it).toEpochMilli() } ?: 0,
                // Event participants do not establish the conversation's current membership. Always ask for X.
                group = true, method = Method.API.id)
        }

        fun decodeBlue(result: JsonObject): List<ChatMessage> = result["data"]?.jsonArray.orEmpty().mapNotNull { v ->
            val o = v.jsonObject
            val text = o.str("text") ?: return@mapNotNull null
            if (NotificationPrivacy.containsCode(text)) return@mapNotNull null
            val chat = o["chats"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
            ChatMessage(o.str("guid") ?: return@mapNotNull null, "imessage", chat.str("guid") ?: return@mapNotNull null,
                (o["handle"] as? JsonObject)?.str("address").orEmpty(), NotificationPrivacy.redact(text), o["dateCreated"]?.jsonPrimitive?.longOrNull ?: 0,
                o["isFromMe"]?.jsonPrimitive?.booleanOrNull ?: false,
                group = chat["participants"]?.jsonArray?.size?.let { it > 1 } ?: true, method = Method.API.id)
        }

        private fun JsonObject.str(name: String) = this[name]?.jsonPrimitive?.contentOrNull
    }
}
