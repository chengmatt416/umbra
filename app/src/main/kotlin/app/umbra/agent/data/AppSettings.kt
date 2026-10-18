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
    /** Signed in with the provider's account (omp's own login, its credentials kept by omp), not with a key. */
    val oauth: Boolean = false,
    /** Who signed in, as omp reported it ("name@example.com (Org)"), for an OAuth provider. */
    val account: String? = null,
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
    /** The effort the user chose for new tasks, used when [autoEffort] is off (null: omp's default). */
    val thinkingLevel: String? = null,
    /** "Auto" effort: Laya reads each new task and picks how hard the model thinks (omp's own judge without Laya). */
    val autoEffort: Boolean = true,
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
    /** Approval history lists only entries after this time; the ledger itself keeps every line (append-only). */
    val approvalHistoryHiddenBeforeMs: Long = 0,
    /** Look for new versions on the site and install them (update/AppUpdater). */
    val autoUpdate: Boolean = true,
    /** Download updates (several hundred MB) only on an unmetered network; a check the user starts uses any. */
    val updateOnWifiOnly: Boolean = true,
    /** How the app looks: Porcelain (the original glaze) or Liquid (a living backdrop in a colour the user picks). */
    val theme: ThemePrefs = ThemePrefs(),
    /** How much of the app shows (docs/design/redesign-2026-10.md §2). Never changes what runs. */
    val uiMode: UiMode = UiMode.SIMPLE,
    /** First-run setup finished (or skipped through): Home opens straight away. */
    val onboarded: Boolean = false,
    /** The menu button and its menu sit bottom-left instead of bottom-right. */
    val leftHanded: Boolean = false,
    /** "Auto": each new task takes the best model of a connected provider, from the model directory. Always saved, so a file without it is one written before "Auto" existed. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault
    val autoModel: Boolean = true,
    /** The update the user said "later" to at launch: not asked again for that version. */
    val skippedUpdateVersion: Long = 0,
    /** Laya was offered (first-run setup): its answer is final until the user changes it in Settings. */
    val layaOffered: Boolean = false,
    /** Laya should be installed and start with every task. */
    val layaWanted: Boolean = false,
    /**
     * How these settings were written. Defaults are not stored (UmbraJson), so a missing "autoModel" is ambiguous in
     * older files: Auto chosen, or a file from before Auto existed. From [AppSettings.VERSION] on, a missing switch is
     * its default; only older files get the "keep the model (or level) chosen before Auto" reading.
     */
    val prefsVersion: Int = 0,
)

/** Simple shows the essentials, Power adds the working views, Dev every control the app has, resources by hand. */
@Serializable
enum class UiMode { SIMPLE, POWER, DEV }

@Serializable
enum class ThemeStyle { PORCELAIN, LIQUID, APPLE }

/** The Apple theme's tint: the system colours iOS offers as accents. */
@Serializable
enum class AppleAccent { BLUE, INDIGO, PURPLE, PINK, RED, ORANGE, GREEN, TEAL, GRAPHITE }

@Serializable
enum class ThemeAppearance { LIGHT, DARK, SYSTEM }

/**
 * The look the user chose in Appearance. [hue] is Liquid's colour, [appleAccent] and [appleGlass] are the Apple
 * theme's tint and its Liquid Glass switch; [appearance] (light, dark, follow phone) applies to both.
 */
@Serializable
data class ThemePrefs(
    val style: ThemeStyle = ThemeStyle.PORCELAIN,
    val hue: Float = 204f,
    val appearance: ThemeAppearance = ThemeAppearance.LIGHT,
    /** Liquid's backdrop flows; off, it holds still (the system's "remove animations" also stills it). */
    val motion: Boolean = true,
    val appleAccent: AppleAccent = AppleAccent.BLUE,
    /** Apple: controls, bars and sheets are Liquid Glass (refraction, rim light, interactive); off, the classic materials. */
    val appleGlass: Boolean = true,
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
        val next = change(state.value).copy(prefsVersion = VERSION)
        prefs.edit().putString(KEY, UmbraJson.encodeToString(AppPrefs.serializer(), next)).apply()
        state.value = next
    }

    private fun load(): AppPrefs = prefs.getString(KEY, null)?.let {
        try {
            var loaded = UmbraJson.decodeFromString(AppPrefs.serializer(), it)
            if (loaded.prefsVersion < VERSION) {
                // Saved before "Auto" existed: a model the person had chosen stays chosen.
                if ("\"autoModel\"" !in it && loaded.defaultModel != null) loaded = loaded.copy(autoModel = false)
                // The same for effort: a thinking level the person had set stays set.
                if ("\"autoEffort\"" !in it && loaded.thinkingLevel != null) loaded = loaded.copy(autoEffort = false)
                loaded = loaded.copy(prefsVersion = VERSION)
            }
            loaded.defaultModel?.let { m -> ModelRenames.current(m).takeIf { it != m }?.let { loaded = loaded.copy(defaultModel = it) } }
            loaded
        } catch (_: Exception) {
            null
        }
    } ?: AppPrefs()

    companion object {
        private const val KEY = "prefs.v1"
        /** 1: every save carries its version, so a missing switch means its default (see [AppPrefs.prefsVersion]). */
        const val VERSION = 1
    }
}

/** Turns the user's provider settings and stored keys into what an agent session starts with. */
class AppProviderResolver(
    private val settings: AppSettings,
    private val secrets: SecretStore,
    /** Whether a key may reach a session whose model belongs to the given provider (the vault's scopes, §12.1). */
    private val admits: (keyName: String, sessionProvider: String?) -> Boolean = { _, _ -> true },
    /** What "Auto" takes for a task Laya did not pick a model for (the model directory's recommendation). */
    private val auto: (AppPrefs) -> ModelRef? = { null },
) : ProviderResolver {
    override suspend fun resolve(sessionModel: ModelRef?): ProviderSelection {
        val prefs = settings.value
        if (prefs.providers.isEmpty()) throw NoProviderException("No model is connected yet. Connect an account or a key in Accounts and models.")
        val chosen = (sessionModel ?: (if (prefs.autoModel) auto(prefs) else null) ?: prefs.defaultModel)?.let(ModelRenames::current)
        val sessionProvider = chosen?.provider
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
        val model = chosen ?: throw NoProviderException("No model is chosen yet. Pick a model from the model button beside the message field.")
        val own = prefs.providers.firstOrNull { it.id == model.provider }
            ?: throw NoProviderException("The provider '${model.provider}' of the chosen model is not set up.")
        own.apiKeyEnv?.let { env ->
            if (!admits(env, model.provider)) throw NoProviderException("The key of ${own.label} is held back from agents in the Credential Vault. Change its scope there to use this model.")
        }
        return ProviderSelection(profile = "default", providers = custom, secrets = keys, model = model, thinkingLevel = prefs.thinkingLevel)
    }
}

class NoProviderException(message: String) : IllegalStateException(message)

/**
 * Model ids the pinned omp no longer has, and what takes their place: a saved choice, an older session, or a cached
 * model list from before an omp update would otherwise start omp on a model it refuses.
 */
object ModelRenames {
    private val renamed = mapOf(
        // omp 18.12.1's catalog has no gpt-5.5 for ChatGPT accounts.
        ("openai-codex" to "gpt-5.5") to "gpt-6.1-sol",
        // Antigravity's Claude ids lost their "-medium" suffix.
        ("google-antigravity" to "claude-sonnet-5-5-medium") to "claude-sonnet-5-5",
        ("google-antigravity" to "claude-opus-5-5-medium") to "claude-opus-5-5",
    )

    fun current(ref: ModelRef): ModelRef = renamed[ref.provider to ref.modelId]?.let { ref.copy(modelId = it) } ?: ref
}
