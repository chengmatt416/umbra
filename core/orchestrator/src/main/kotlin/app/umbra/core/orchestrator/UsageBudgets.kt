package app.umbra.core.orchestrator

import app.umbra.core.model.Clock
import app.umbra.core.model.UmbraJson
import app.umbra.core.session.AiUsageLimit
import app.umbra.core.session.AiUsageUnit
import app.umbra.core.session.Session
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

data class QuotaReading(val id: String, val window: AiUsageUnit, val usedPercent: Double, val resetsAtMs: Long)

@Serializable
private data class UsageMeter(val spent: Double = 0.0, val quotaHighWater: Map<String, Double> = emptyMap())

@Serializable
private data class UsageBudgetState(val costs: Map<String, Double> = emptyMap(), val meters: Map<String, UsageMeter> = emptyMap())

/** Accounts actual reported usage and admits each provider request, including follow-ups and background wakes. */
class UsageBudgets(
    private val file: Path,
    private val agentLimit: (String) -> AiUsageLimit?,
    private val quota: suspend (provider: String) -> List<QuotaReading>,
    private val clock: Clock = Clock.System,
    private val sessionLookup: suspend (String) -> Session? = { null },
) {
    private val mutex = Mutex()
    private var state: UsageBudgetState? = null

    private suspend fun limits(s: Session): List<Pair<String, AiUsageLimit>> = buildList {
        var task: Session? = s
        val visited = mutableSetOf<String>()
        val agents = mutableSetOf<String>()
        while (task != null && visited.size < 32 && visited.add(task.id)) {
            val current = task
            current.usageLimit?.let { add("task:${current.id}" to it) }
            current.agentId?.takeIf { agents.add(it) }?.let { id -> agentLimit(id)?.let { add("agent:$id" to it) } }
            task = current.spawnedBy?.let { requireNotNull(sessionLookup(it)) { "The parent task is missing; inherited usage limits cannot be verified." } }
        }
        check(task == null) { "The task ancestry is cyclic or too deep to verify its usage limits." }
    }

    suspend fun configured(session: Session): Boolean = limits(session).isNotEmpty()

    private fun load(): UsageBudgetState = state ?: if (Files.exists(file)) {
        UmbraJson.decodeFromString(UsageBudgetState.serializer(), Files.readString(file))
    } else UsageBudgetState()

    private fun key(scope: String, limit: AiUsageLimit, now: Long) = "$scope:${limit.startedAtMs}:${limit.unit}:${limit.reset}:${limit.bucket(now)}"

    suspend fun reason(session: Session): String? = mutex.withLock {
        val applicable = limits(session)
        if (applicable.isEmpty()) return@withLock null
        val data = load().also { state = it }
        applicable.firstOrNull { (scope, limit) -> (data.meters[key(scope, limit, clock.nowMs())]?.spent ?: 0.0) >= limit.maximum }
            ?.let { "AI usage limit reached for ${it.first}. Change the limit or wait for its reset." }
    }

    suspend fun spent(session: Session): Map<String, Double> = mutex.withLock {
        val applicable = limits(session)
        if (applicable.isEmpty()) return@withLock emptyMap()
        val data = load().also { state = it }
        applicable.associate { (scope, limit) -> scope to (data.meters[key(scope, limit, clock.nowMs())]?.spent ?: 0.0) }
    }

    suspend fun admit(session: Session, report: JsonObject): String? = mutex.withLock {
        val applicable = limits(session)
        if (applicable.isEmpty()) return@withLock null
        val now = clock.nowMs()
        val data = load()
        val total = (report["totalCost"] as? JsonPrimitive)?.doubleOrNull
        val transcript = report.str("transcript")
        if (total == null || !total.isFinite() || total < 0 || transcript.isNullOrBlank() || transcript.length > 4096) {
            return@withLock "AI usage could not be verified. The request was stopped."
        }
        if (applicable.any { it.second.unit == AiUsageUnit.API_USD } && (report["priced"] != JsonPrimitive(true) || report["costKnown"] != JsonPrimitive(true))) {
            return@withLock "This model has no API-equivalent pricing. Choose a priced model or a provider-quota limit."
        }
        val percentages = applicable.map { it.second.unit }.filter { it != AiUsageUnit.API_USD }.toSet()
        val readings = if (percentages.isEmpty()) emptyList() else {
            val provider = report.str("provider")?.takeIf { it.isNotBlank() } ?: return@withLock "Provider quota is unavailable; request stopped."
            quota(provider)
        }
        if (percentages.any { unit -> readings.none { it.window == unit } } || readings.any { !it.usedPercent.isFinite() || it.usedPercent !in 0.0..100.0 || it.resetsAtMs <= now }) {
            return@withLock "Fresh provider quota for the selected window is unavailable; request stopped."
        }
        val cursor = "${session.id}:$transcript"
        val previous = data.costs[cursor] ?: total
        val delta = (total - previous).coerceAtLeast(0.0)
        val meters = data.meters.toMutableMap()
        for ((scope, limit) in applicable) {
            val id = key(scope, limit, now)
            val old = meters[id] ?: UsageMeter()
            var increment = if (limit.unit == AiUsageUnit.API_USD) delta else 0.0
            val highWater = old.quotaHighWater.toMutableMap()
            readings.filter { it.window == limit.unit }.forEach { reading ->
                val quotaId = "${reading.id}:${reading.resetsAtMs}"
                val before = highWater[quotaId] ?: if (highWater.keys.any { it.startsWith("${reading.id}:") }) 0.0 else reading.usedPercent
                increment += (reading.usedPercent - before).coerceAtLeast(0.0)
                highWater[quotaId] = maxOf(before, reading.usedPercent)
            }
            meters[id] = UsageMeter(old.spent + increment, highWater)
        }
        val next = data.copy(costs = data.costs + (cursor to maxOf(previous, total)), meters = meters)
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, "usage-", ".tmp")
        try {
            Files.writeString(temp, UmbraJson.encodeToString(UsageBudgetState.serializer(), next))
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
        state = next
        applicable.firstOrNull { (scope, limit) -> next.meters.getValue(key(scope, limit, now)).spent >= limit.maximum }
            ?.let { "AI usage limit reached for ${it.first}. No further model request was allowed." }
    }
}
