package app.umbra.core.orchestrator

import app.umbra.core.session.ModelRef
import app.umbra.runtime.omp.OmpProvider

/**
 * The model access one session runs with: which providers omp may use (written to its `models.yml`),
 * the credentials for them, and the model to start on.
 */
data class ProviderSelection(
    /** Name of the omp state directory this selection uses; different accounts get different profiles. */
    val profile: String,
    val providers: List<OmpProvider>,
    /** Environment variable name to value, for the providers' `apiKeyEnv`. Never written to disk or a command line. */
    val secrets: Map<String, String> = emptyMap(),
    val model: ModelRef? = null,
    /** omp's thinking level (`off`, `low`, `medium`, `high`...), or null for its default. */
    val thinkingLevel: String? = null,
) {
    override fun toString() = "ProviderSelection(profile=$profile, providers=${providers.map { it.id }}, model=$model, secrets=${secrets.keys})"
}

/** Resolves the providers for a session. The app implements it on top of its settings and secure storage. */
fun interface ProviderResolver {
    /** @param sessionModel the model the session was created with, or null for the user's default. */
    suspend fun resolve(sessionModel: ModelRef?): ProviderSelection
}

/** What the agent is told about the device it lives on (see [AgentPrompts]). */
data class PromptContext(
    val deviceModel: String = "an Android phone",
    val androidVersion: String = "Android 16",
    val screen: String? = null,
    /** One line per available capability, e.g. "Root shell: yes". */
    val capabilities: List<String> = emptyList(),
    val extraInstructions: String? = null,
)
