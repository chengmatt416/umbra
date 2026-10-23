package app.umbra.core.session

import kotlinx.serialization.Serializable

@Serializable
enum class AiUsageUnit { API_USD, FIVE_HOUR_PERCENT, WEEKLY_PERCENT }

@Serializable
enum class UsageReset(val periodMs: Long?) {
    NEVER(null), TEN_MINUTES(600_000), HOURLY(3_600_000), DAILY(86_400_000),
}

@Serializable
data class AiUsageLimit(
    val unit: AiUsageUnit,
    val maximum: Double,
    val reset: UsageReset = UsageReset.NEVER,
    val startedAtMs: Long,
) {
    init {
        require(maximum.isFinite() && maximum > 0 && (unit == AiUsageUnit.API_USD || maximum <= 100))
        require(startedAtMs >= 0)
    }

    fun bucket(nowMs: Long): Long = reset.periodMs?.let { (nowMs - startedAtMs).coerceAtLeast(0) / it } ?: 0
}
