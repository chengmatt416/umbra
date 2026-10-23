package app.umbra.core.orchestrator

import app.umbra.core.model.UmbraJson
import app.umbra.core.session.AiUsageUnit
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Reads the normalized schema of the product's pinned `omp usage --json`. */
object ProviderQuota {
    fun parse(text: String, nowMs: Long): List<QuotaReading> {
        val root = UmbraJson.parseToJsonElement(text) as? JsonObject ?: error("Invalid quota response")
        val reports = root["reports"] as? JsonArray ?: error("No provider quota reports")
        return reports.flatMap { raw ->
            val report = raw as? JsonObject ?: return@flatMap emptyList()
            val fetched = report.number("fetchedAt")?.toLong() ?: return@flatMap emptyList()
            if (fetched > nowMs + 5_000 || nowMs - fetched > 120_000) return@flatMap emptyList()
            val metadata = report["metadata"] as? JsonObject
            val identity = listOf("accountId", "email", "projectId", "orgId").mapNotNull { metadata?.str(it) }.joinToString("|")
            (report["limits"] as? JsonArray).orEmpty().mapNotNull { item ->
                val limit = item as? JsonObject ?: return@mapNotNull null
                val window = limit["window"] as? JsonObject ?: return@mapNotNull null
                val duration = (window["durationMs"] as? JsonPrimitive)?.longOrNull
                val unit = when {
                    duration == 18_000_000L || window.str("id") in setOf("5h", "five_hour") -> AiUsageUnit.FIVE_HOUR_PERCENT
                    duration == 604_800_000L || window.str("id") in setOf("7d", "weekly", "seven_day") -> AiUsageUnit.WEEKLY_PERCENT
                    else -> return@mapNotNull null
                }
                val reset = (window["resetsAt"] as? JsonPrimitive)?.longOrNull?.takeIf { it > nowMs } ?: return@mapNotNull null
                val amount = limit["amount"] as? JsonObject ?: return@mapNotNull null
                val fraction = amount.number("usedFraction") ?: amount.number("remainingFraction")?.let { 1 - it }
                    ?: (if (amount.str("unit") == "percent") amount.number("used")?.div(100) else null)
                    ?: amount.number("limit")?.takeIf { it > 0 }?.let { cap -> amount.number("used")?.div(cap) }
                    ?: return@mapNotNull null
                if (!fraction.isFinite() || fraction !in 0.0..1.0) return@mapNotNull null
                val scope = limit["scope"] as? JsonObject
                val account = scope?.str("accountId") ?: identity.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val key = report.str("provider") + account + limit.str("id") + limit["scope"].toString()
                val id = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
                QuotaReading(id, unit, fraction * 100, reset)
            }
        }.distinctBy { it.id }
    }

    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
}
