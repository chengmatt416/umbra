package app.umbra.runtime.omp

import kotlinx.coroutines.CoroutineScope

/**
 * Starts an omp process and its [OmpClient]. The config files are written first, so each session
 * sees exactly the providers and policies the host chose, whatever the user's global config says.
 */
object OmpProcess {
    class Started(val client: OmpClient, val transport: OmpTransport, val files: AgentFiles)

    /**
     * @param files omp's config directory (`PI_CODING_AGENT_DIR`): `models.yml` and the settings overlay are written there.
     * @param environment extra variables (`HOME`, `PATH`, ...).
     * @param secrets variables that carry credentials (API keys by their `apiKeyEnv` names); the spawner
     *   keeps their values out of command lines.
     */
    suspend fun start(
        launch: OmpLaunch,
        settings: OmpSettings,
        providers: List<OmpProvider>,
        files: AgentFiles,
        scope: CoroutineScope,
        environment: Map<String, String> = emptyMap(),
        secrets: Map<String, String> = emptyMap(),
        clientConfig: OmpClientConfig = OmpClientConfig(),
        stderrLines: Int = 200,
        spawner: OmpSpawner = LocalSpawner,
    ): Started {
        if (providers.isNotEmpty()) files.write(MODELS_FILE, OmpModelsFile.toYaml(providers))
        files.write(OVERLAY_FILE, settings.toYaml())

        val effective = launch.copy(settingsOverlay = launch.settingsOverlay ?: "${files.ompPath.trimEnd('/')}/$OVERLAY_FILE")
        val env = buildMap {
            put("PI_CODING_AGENT_DIR", files.ompPath)
            // RPC mode already disables titles, but a stray model call would still leak the first prompt.
            put("PI_NO_TITLE", "1")
            put("NO_COLOR", "1")
            putAll(environment)
        }
        val transport = spawner.spawn(
            SpawnSpec(
                command = effective.command(),
                environment = env,
                secrets = secrets,
                workingDirectory = effective.workingDirectory,
                stderrLines = stderrLines,
            ),
        )
        return Started(OmpClient(transport, scope, clientConfig), transport, files)
    }

    const val OVERLAY_FILE = "umbra-session.yml"
    const val MODELS_FILE = "models.yml"
}
