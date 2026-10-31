package app.umbra.agent.telegram

import app.umbra.core.model.UmbraJson
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class TelegramApiException(val status: Int) : IllegalStateException("Telegram request was rejected (HTTP $status)")

/** Telegram owns file paths; they never select a host, query, or a path outside the bot's download URL. */
internal class TelegramApi(
    client: OkHttpClient,
    private val token: () -> String?,
    private val base: HttpUrl = "https://api.telegram.org/".toHttpUrl(),
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun call(method: String, body: JsonObject, key: String? = token()): JsonElement = withContext(Dispatchers.IO) {
        require(method.matches(Regex("[A-Za-z][A-Za-z0-9]{1,50}")))
        val secret = requireNotNull(key) { "No bot token" }
        val url = base.newBuilder().addPathSegment("bot$secret").addPathSegment(method).build()
        val request = Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()
        http.newCall(request).execute().use { response ->
            val bytes = response.body.byteStream().use { readBounded(it, response.body.contentLength(), MAX_JSON_BYTES) }
            val parsed = runCatching { UmbraJson.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()
            if (!response.isSuccessful || parsed?.get("ok")?.jsonPrimitive?.booleanOrNull != true) throw TelegramApiException(response.code)
            parsed?.get("result") ?: JsonObject(emptyMap())
        }
    }

    /** A malformed entity must not hide an otherwise useful answer; transport failures must not duplicate it. */
    suspend fun callHtml(method: String, body: JsonObject): JsonElement = try {
        call(method, body)
    } catch (e: TelegramApiException) {
        if (e.status != 400) throw e
        val html = body["text"]?.jsonPrimitive?.contentOrNull ?: throw e
        call(method, JsonObject(body - "parse_mode" + ("text" to JsonPrimitive(telegramPlain(html)))))
    }

    suspend fun download(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        val key = requireNotNull(token()) { "No bot token" }
        val file = call("getFile", buildJsonObject { put("file_id", fileId) }, key).jsonObject
        file["file_size"]?.jsonPrimitive?.longOrNull?.let { require(it in 0..MAX_FILE_BYTES.toLong()) { "Telegram attachments must be at most 20 MB" } }
        val path = requireNotNull(file["file_path"]?.jsonPrimitive?.contentOrNull) { "Telegram did not provide a download" }
        val parts = path.split('/')
        require(parts.isNotEmpty() && parts.all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it != "." && it != ".." }) { "Invalid Telegram download path" }
        val url = base.newBuilder().addPathSegment("file").addPathSegment("bot$key").apply { parts.forEach(::addPathSegment) }.build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Telegram attachment download failed (HTTP ${response.code})" }
            response.body.byteStream().use { readBounded(it, response.body.contentLength(), MAX_FILE_BYTES) }
        }
    }

    /** Uploads [file] to the chat as a document (Telegram bots may send at most 50 MB). */
    suspend fun sendDocument(chatId: Long, file: java.io.File, name: String, mime: String, caption: String?): JsonElement = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() in 1..MAX_UPLOAD_BYTES) { "Telegram bots can send files of at most 50 MB" }
        val secret = requireNotNull(token()) { "No bot token" }
        val url = base.newBuilder().addPathSegment("bot$secret").addPathSegment("sendDocument").build()
        val type = mime.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .apply { if (!caption.isNullOrBlank()) addFormDataPart("caption", caption.take(1024)) }
            .addFormDataPart("document", cleanName(name), file.asRequestBody(type))
            .build()
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            val bytes = response.body.byteStream().use { readBounded(it, response.body.contentLength(), MAX_JSON_BYTES) }
            val parsed = runCatching { UmbraJson.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()
            check(response.isSuccessful && parsed?.get("ok")?.jsonPrimitive?.booleanOrNull == true) { "Telegram could not send the file (HTTP ${response.code})" }
            parsed["result"] ?: JsonObject(emptyMap())
        }
    }

    suspend fun react(chatId: Long, messageId: Long, emoji: String) {
        call("setMessageReaction", buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId)
            putJsonArray("reaction") { add(buildJsonObject { put("type", "emoji"); put("emoji", emoji) }) }
        })
    }

    companion object {
        const val MAX_FILE_BYTES = 20_000_000
        const val MAX_UPLOAD_BYTES = 50_000_000L
        private const val MAX_JSON_BYTES = 2_000_000
        private val JSON = "application/json; charset=utf-8".toMediaType()

        internal fun readBounded(input: InputStream, length: Long, limit: Int): ByteArray {
            require(length in -1..limit.toLong()) { "Telegram response is too large" }
            val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
                if (count < 0) return output.toByteArray()
                check(count > 0) { "Telegram download stopped making progress" }
                require(count <= limit - output.size()) { "Telegram response is too large" }
                output.write(buffer, 0, count)
            }
        }

        internal fun cleanName(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').take(80).ifEmpty { "file" }

        internal fun attachmentName(name: String): String = "${java.util.UUID.randomUUID()}-${cleanName(name)}"
    }
}
