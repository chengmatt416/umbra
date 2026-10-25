package app.umbra.agent.data

import android.content.Context
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class UsageCountState(val installId: String = "", val sentMonth: String? = null, val retryAfterMs: Long = 0)

/** Opted-in foreground use only; background service wakes never count as user activity. */
internal class UsageCounts(
    private val enabled: () -> Boolean,
    private val load: () -> UsageCountState,
    private val save: (UsageCountState) -> Unit,
    private val send: suspend (String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Mutex()
    suspend fun recordOpen() = lock.withLock {
        if (!enabled()) return@withLock
        try {
            val at = now()
            val month = YearMonth.from(Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC)).toString()
            val old = load()
            if (old.sentMonth == month || old.retryAfterMs > at) return@withLock
            val id = old.installId.takeIf(::validId) ?: newId().also { require(validId(it)) }
            val state = old.copy(installId = id, retryAfterMs = at + 6 * 3_600_000L)
            save(state)
            if (enabled() && send(state.installId)) save(state.copy(sentMonth = month, retryAfterMs = 0))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Metrics must never prevent the app from opening. */ }
    }

    companion object {
        private val INSTALL_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        fun validId(value: String): Boolean = INSTALL_ID.matches(value)
        fun native(context: Context, enabled: () -> Boolean): UsageCounts {
            val prefs = context.getSharedPreferences("usage-counts", Context.MODE_PRIVATE)
            val http = UsageCountHttp()
            return UsageCounts(enabled, load = {
                UsageCountState(prefs.getString("install", "").orEmpty(), prefs.getString("month", null), prefs.getLong("retry_after", 0))
            }, save = { state ->
                check(prefs.edit().putString("install", state.installId).putString("month", state.sentMonth).putLong("retry_after", state.retryAfterMs).commit())
            }, send = http::send)
        }
    }
}

internal class UsageCountHttp(
    private val endpoint: HttpUrl = "https://umbra-agent.pages.dev/api/usage-counts".toHttpUrl(),
    http: OkHttpClient = OkHttpClient(),
) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(10, TimeUnit.SECONDS).build()
    suspend fun send(installId: String): Boolean = withContext(Dispatchers.IO) {
        require(UsageCounts.validId(installId))
        val body = buildJsonObject { put("install_id", installId) }.toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(endpoint).header("User-Agent", "Umbra-Usage-Counts/1").post(body).build())
            .execute().use { it.code == 204 }
    }
}
