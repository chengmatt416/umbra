package app.umbra.agent.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Checks an API key with the provider itself before it is saved: one request to the provider's model list, which
 * every valid key may read. Null when the key works; otherwise what the provider said, in a line.
 */
object KeyCheck {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    suspend fun problem(provider: String, key: String): String? = withContext(Dispatchers.IO) {
        val request = when (provider) {
            "openai" -> Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $key")
            "anthropic" -> Request.Builder().url("https://api.anthropic.com/v1/models").header("x-api-key", key).header("anthropic-version", "2023-06-01")
            else -> return@withContext null
        }.build()
        try {
            http.newCall(request).execute().use { r ->
                if (r.isSuccessful) null
                else "HTTP ${r.code}: " + r.body.string().take(200).replace(Regex("\\s+"), " ")
            }
        } catch (e: Exception) {
            e.message ?: e::class.simpleName
        }
    }
}
