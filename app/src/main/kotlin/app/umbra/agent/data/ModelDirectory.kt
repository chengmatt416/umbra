package app.umbra.agent.data

import android.content.Context
import android.util.Log
import app.umbra.core.model.UmbraJson
import app.umbra.core.session.ModelRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class DirectoryModel(
    val id: String,
    val name: String = id,
    val images: Boolean = false,
    val reasoning: Boolean = false,
    /** The thinking efforts omp accepts for it (low … max); empty when it does not reason or the list is older. */
    val efforts: List<String> = emptyList(),
    val contextWindow: Long? = null,
    /** The model "Auto" takes for this provider. */
    val recommended: Boolean = false,
)

/** A provider as the directory lists it. [auth] is "oauth" (signed in with an account) or "key". */
@Serializable
data class DirectoryProvider(val id: String, val label: String, val auth: String = "key", val models: List<DirectoryModel> = emptyList())

@Serializable
data class Directory(val version: Int = 1, val updated: String = "", val providers: List<DirectoryProvider> = emptyList())

/**
 * The models Umbra offers, published on the site next to update.json so the list changes without an app update.
 * The copy that came with the app answers until the first fetch; the last fetched copy is kept for offline starts.
 * The ids are omp's own (the site's file is generated from the catalog of the omp the app carries).
 */
class ModelDirectory(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val mutable = MutableStateFlow(load())
    val directory: StateFlow<Directory> = mutable.asStateFlow()

    private val mutableUpdated = MutableStateFlow(prefs.getLong(KEY_AT, 0))
    /** When the list was last fetched from the site; 0 = never (the bundled copy). */
    val updatedAtMs: StateFlow<Long> = mutableUpdated.asStateFlow()

    fun start() {
        scope.launch {
            while (true) {
                refresh()
                delay(EVERY_MS)
            }
        }
    }

    /** Fetches the list now; false when the site could not be reached or sent something unusable. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = http.newCall(Request.Builder().url(url()).header("Cache-Control", "no-cache").build()).execute().use { r ->
                check(r.isSuccessful) { "HTTP ${r.code}" }
                r.body.string()
            }
            val parsed = UmbraJson.decodeFromString(Directory.serializer(), body)
            check(parsed.providers.isNotEmpty()) { "empty directory" }
            prefs.edit().putString(KEY_JSON, body).putLong(KEY_AT, System.currentTimeMillis()).apply()
            mutable.value = parsed
            mutableUpdated.value = System.currentTimeMillis()
        }.onFailure { Log.w(TAG, "refresh", it) }.isSuccess
    }

    /** The directory's providers the user has connected, in the directory's order. */
    fun connected(prefs: AppPrefs): List<DirectoryProvider> = connected(directory.value, prefs)

    /** What "Auto" falls back to when Laya did not pick (AutoModel): the first connected provider's recommended model. */
    fun auto(prefs: AppPrefs): ModelRef? = pick(directory.value, prefs)

    fun model(ref: ModelRef?): DirectoryModel? = ref?.let { r -> directory.value.providers.firstOrNull { it.id == r.provider }?.models?.firstOrNull { it.id == r.modelId } }

    private fun load(): Directory {
        prefs.getString(KEY_JSON, null)?.let { cached -> runCatching { return UmbraJson.decodeFromString(Directory.serializer(), cached) } }
        return runCatching {
            context.assets.open("models.json").bufferedReader().use { UmbraJson.decodeFromString(Directory.serializer(), it.readText()) }
        }.getOrElse { Directory() }
    }

    private fun url(): String = runCatching {
        ProcessBuilder("getprop", "debug.umbra.models_url").start().inputStream.bufferedReader().use { it.readText() }.trim()
    }.getOrNull()?.takeIf { it.startsWith("https://") } ?: DEFAULT_URL

    companion object {
        fun connected(directory: Directory, prefs: AppPrefs): List<DirectoryProvider> {
            val ids = prefs.providers.map { it.id }.toSet()
            return directory.providers.filter { it.id in ids }
        }

        fun pick(directory: Directory, prefs: AppPrefs): ModelRef? {
            val provider = connected(directory, prefs).firstOrNull { it.models.isNotEmpty() } ?: return null
            val model = provider.models.firstOrNull { it.recommended } ?: provider.models.first()
            return ModelRef(provider.id, model.id)
        }

        const val DEFAULT_URL = "https://umbra-agent.pages.dev/models.json"
        private const val TAG = "ModelDirectory"
        private const val KEY_JSON = "directory"
        private const val KEY_AT = "fetched_at"
        private const val EVERY_MS = 6 * 60 * 60_000L
    }
}
