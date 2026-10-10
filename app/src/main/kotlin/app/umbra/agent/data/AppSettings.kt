package app.umbra.agent.data

import android.content.Context
import app.umbra.core.model.UmbraJson
import app.umbra.core.orchestrator.ProviderResolver
import app.umbra.core.orchestrator.ProviderSelection
import app.umbra.core.safety.ApprovalMode
import app.umbra.core.session.ModelRef
import app.umbra.runtime.omp.OmpModel
import app.umbra.runtime.omp.OmpProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * A model provider the user set up. Well-known providers (Anthropic, OpenAI, Google, OpenRouter...)
 * need only a key: omp knows their endpoints and model catalogs. A custom provider is any
 * OpenAI-compatible (or other supported) endpoint, such as a server on the user's network.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val label: String,
    /** Name of the secret (and of the environment variable omp reads) that holds the API key. */
    val apiKeyEnv: String? = null,
    val custom: Boolean = false,
    val baseUrl: String? = null,
    val api: String = "openai-completions",
    val models: List<String> = emptyList(),
    val keyless: Boolean = false,
    /** A custom provider's models take pictures (a screen selection, a photo). Umbra cannot tell, so the user says. */
    val images: Boolean = false,
)

@Serializable
data class ApprovalPrefs(val mode: ApprovalMode = ApprovalMode.BALANCED)

@Serializable
data class AppPrefs(
    val providers: List<ProviderConfig> = emptyList(),
    val defaultModel: ModelRef? = null,
    val thinkingLevel: String? = null,
    val approvalMode: ApprovalMode = ApprovalMode.BALANCED,
    /** Appended to the agent's instructions in every session. */
    val standingInstructions: String = "",
    val notifyOnApproval: Boolean = true,
    /** The Capsule (§3.1): drawn over other apps at the camera, idle or busy. */
    val overlayEnabled: Boolean = false,
    /**
     * Live Context (§3.1), explicitly opted into: each message sent from the Capsule carries what the app in front
     * shows at that moment, read from the accessibility tree. Off, only a snapshot the user selected is attached.
     */
    val liveContext: Boolean = false,
    /** Where the Linux environment archive is downloaded from (an https URL). */
    val rootfsUrl: String = "",
    val setupDismissed: Boolean = false,
)

object ProviderPresets {
    class Preset(val id: String, val label: String, val keyEnv: String, val hint: String, val suggestedModel: String? = null)

    val all = listOf(
        Preset("anthropic", "Anthropic (Claude)", "ANTHROPIC_API_KEY", "sk-ant-…", "claude-sonnet-5-5"),
        Preset("openai", "OpenAI", "OPENAI_API_KEY", "sk-…"),
        Preset("google", "Google (Gemini)", "GEMINI_API_KEY", "AIza…"),
        Preset("openrouter", "OpenRouter", "OPENROUTER_API_KEY", "sk-or-…"),
        Preset("xai", "xAI (Grok)", "XAI_API_KEY", "xai-…"),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}

/** The app's preferences, persisted as one JSON document in private storage. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val flow: StateFlow<AppPrefs> = state.asStateFlow()
    val value: AppPrefs get() = state.value

    @Synchronized
    fun update(change: (AppPrefs) -> AppPrefs) {
        val next = change(state.value)
        prefs.edit().putString(KEY, UmbraJson.encodeToString(AppPrefs.serializer(), next)).apply()
        state.value = next
    }

    private fun load(): AppPrefs = prefs.getString(KEY, null)?.let {
        try {
            UmbraJson.decodeFromString(AppPrefs.serializer(), it)
        } catch (_: Exception) {
            null
        }
    } ?: AppPrefs()

    private companion object {
        const val KEY = "prefs.v1"
    }
}

/** Turns the user's provider settings and stored keys into what an agent session starts with. */
class AppProviderResolver(
    private val settings: AppSettings,
    private val secrets: SecretStore,
    /** Whether a key may reach a session whose model belongs to the given provider (the vault's scopes, §12.1). */
    private val admits: (keyName: String, sessionProvider: String?) -> Boolean = { _, _ -> true },
) : ProviderResolver {
    override suspend fun resolve(sessionModel: ModelRef?): ProviderSelection {
        val prefs = settings.value
        if (prefs.providers.isEmpty()) throw NoProviderException("No model provider is set up yet. Add one in Settings → Models.")
        val sessionProvider = (sessionModel ?: prefs.defaultModel)?.provider
        val keys = LinkedHashMap<String, String>()
        val custom = ArrayList<OmpProvider>()
        for (p in prefs.providers) {
            val env = p.apiKeyEnv
            val admitted = env == null || admits(env, sessionProvider)
            if (env != null && admitted) secrets.get(env)?.let { keys[env] = it }
            // A custom provider whose key this session may not have is left out (omp would refuse it without one).
            if (p.custom && !admitted) continue
            if (p.custom) {
                custom += OmpProvider(
                    id = p.id,
                    baseUrl = p.baseUrl ?: continue,
                    api = p.api,
                    apiKeyEnv = env?.takeIf { it in keys },
                    auth = if (p.keyless || env == null) "none" else null,
                    models = p.models.map { OmpModel(it, inputImages = p.images) },
                )
            }
        }
        val model = sessionModel ?: prefs.defaultModel
        if (model == null) throw NoProviderException("No model is chosen yet. Pick a default model in Settings → Models.")
        val own = prefs.providers.firstOrNull { it.id == model.provider }
            ?: throw NoProviderException("The provider '${model.provider}' of the chosen model is not set up.")
        own.apiKeyEnv?.let { env ->
            if (!admits(env, model.provider)) throw NoProviderException("The key of ${own.label} is held back from agents in the Credential Vault. Change its scope there to use this model.")
        }
        return ProviderSelection(profile = "default", providers = custom, secrets = keys, model = model, thinkingLevel = prefs.thinkingLevel)
    }
}

class NoProviderException(message: String) : IllegalStateException(message)
