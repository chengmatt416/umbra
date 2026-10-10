package app.umbra.core.decision

/**
 * A decision engine whose real implementation comes and goes, such as the Laya sidecar that the user
 * installs, starts and stops. While nothing is attached it reports itself unavailable, so the router
 * uses its fallback; the moment [target] is set it takes over.
 */
class SwitchableEngine : DecisionEngine {
    @Volatile
    var target: DecisionEngine? = null

    override val name: String get() = target?.name ?: "none"

    override suspend fun isAvailable(): Boolean = target?.isAvailable() ?: false

    override suspend fun decide(request: DecisionRequest): DecisionResponse =
        (target ?: throw DecisionEngineException("no decision engine is running")).decide(request)
}
