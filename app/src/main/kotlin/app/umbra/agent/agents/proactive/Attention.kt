package app.umbra.agent.agents.proactive

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class InterruptionContext(val inCallOrMeeting: Boolean = false, val immersiveOrTyping: Boolean = false,
    val quietOrDnd: Boolean = false) {
    val cost: Double get() = ((if (inCallOrMeeting) .60 else .0) + (if (immersiveOrTyping) .35 else .0) + (if (quietOrDnd) .70 else .0)).coerceAtMost(1.0)
}

object AttentionRanker {
    fun tier(urgency: Double, importance: Double, confidence: Double, context: InterruptionContext, sourceWeight: Double = 1.0): DeliveryTier {
        require(listOf(urgency, importance, confidence).all { it.isFinite() && it in .0..1.0 })
        require(sourceWeight.isFinite() && sourceWeight in .0..1.0)
        val raw = (.40 * urgency + .35 * importance + .25 * confidence) * sourceWeight
        // Urgent breakthrough is deliberately narrow; ordinary high scores never bypass quiet hours.
        if (urgency >= .85 && confidence >= .85 && raw >= .90 && sourceWeight >= .9) return DeliveryTier.URGENT_BREAKTHROUGH
        val score = raw - context.cost
        return when {
            score < .20 -> DeliveryTier.SILENT_LOG
            confidence < .65 || score < .50 -> DeliveryTier.BRIEF_QUEUE
            score < .70 -> DeliveryTier.CAPSULE_AMBIENT
            else -> DeliveryTier.STANDARD_NOTIFY
        }
    }
}

object ConflictDetector {
    fun detect(events: List<CalendarItem>, now: Long): List<ConflictAlert> {
        val upcoming = events.filter { it.endMs > now && it.startMs < now + 7 * 86_400_000L }.sortedBy { it.startMs }
        val result = mutableListOf<ConflictAlert>()
        fun conflict(a: CalendarItem, b: CalendarItem, kind: String, text: String) {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("instruction", kotlinx.serialization.json.JsonPrimitive("Prepare a rescheduling draft for ${a.title} or ${b.title}. Ask before sending or editing either event."))
            }.toString()
            result += ConflictAlert("${a.source}:${a.id}:${b.source}:${b.id}:$kind", kind, text,
                listOf("${a.source}:${a.id}", "${b.source}:${b.id}"), listOf(PreparedNextStep("Prepare rescheduling draft", "agent_turn", payload)))
        }
        for ((i, a) in upcoming.withIndex()) {
            for (b in upcoming.drop(i + 1)) {
                if (b.startMs >= a.endMs + 120 * 60_000L) break
                if (a.startMs < b.endMs && b.startMs < a.endMs) conflict(a, b, "hard_overlap", "${a.title} overlaps ${b.title}.")
                else if (a.location.isNotBlank() && b.location.isNotBlank() && a.location != b.location && a.travelMinutes != null && b.startMs - a.endMs < a.travelMinutes * 60_000L)
                    conflict(a, b, "travel_buffer_tight", "Only ${(b.startMs - a.endMs) / 60_000} minutes between ${a.title} and ${b.title}; travel needs ${a.travelMinutes} minutes.")
            }
            if (a.declaredWeekday != null && Instant.ofEpochMilli(a.startMs).atZone(ZoneId.systemDefault()).dayOfWeek.value != a.declaredWeekday)
                result += ConflictAlert("${a.source}:${a.id}:weekday", "commitment_mismatch", "The date and stated weekday for ${a.title} disagree.", listOf("${a.source}:${a.id}"))
        }
        return result.distinctBy { it.id }
    }
}

/** One sentence a person would say about the day, in the phone's language (Traditional Chinese or English). */
object BriefHeadline {
    fun of(kind: DailyBrief.Kind, events: Int, decisions: Int, conflicts: Int, done: Int, zh: Boolean = java.util.Locale.getDefault().language == "zh"): String {
        val morning = kind == DailyBrief.Kind.MORNING
        if (events + decisions + conflicts + done == 0) return when {
            zh && morning -> "早安，今天目前沒有需要你處理的事。"
            zh -> "今晚一切平靜，沒有需要你處理的事。"
            morning -> "A clear morning: nothing needs you yet."
            else -> "All quiet tonight: nothing needs you."
        }
        fun n(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
        val parts = if (zh) buildList {
            if (conflicts > 0) add("$conflicts 個衝突要處理")
            if (decisions > 0) add("$decisions 件事等你決定")
            if (events > 0) add("行程 $events 項")
            if (done > 0) add("已完成 $done 項")
        } else buildList {
            if (conflicts > 0) add(n(conflicts, "conflict to settle", "conflicts to settle"))
            if (decisions > 0) add(n(decisions, "decision waiting", "decisions waiting"))
            if (events > 0) add(n(events, "event", "events") + if (morning) " today" else " tomorrow")
            if (done > 0) add(n(done, "thing done", "things done"))
        }
        return if (zh) parts.joinToString("，") + "。" else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }
}

class DailyBriefEngine(private val store: ProactiveStore, private val zone: ZoneId = ZoneId.systemDefault()) {
    fun generate(agentId: String, kind: DailyBrief.Kind, now: Long, schedule: List<CalendarItem> = emptyList(), completed: List<BriefItem> = emptyList()): DailyBrief {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val id = "brief_${date}_${kind.name.lowercase()}"
        store.briefs(agentId).find { it.id == id }?.let { return it }
        val prior = store.briefs(agentId).maxOfOrNull { it.generatedAtMs } ?: date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val staged = store.attention(agentId).filter { it.createdAtMs in (prior + 1)..now }
        val actionable = store.opportunities(agentId).filter { it.state == OpportunityCard.State.PENDING && (it.expiresAtMs == null || it.expiresAtMs > now) }
            .sortedByDescending { .6 * it.urgency + .4 * it.confidence }.take(5)
        val conflicts = ConflictDetector.detect(schedule, now)
        val today = schedule.filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == date }.map { BriefItem(it.title, it.location) }
        val digest = staged.filter { it.tier != DeliveryTier.SILENT_LOG }.joinToString("\n") { "${it.title}: ${it.detail.take(500)}" }.take(12_000)
        return DailyBrief(id, date.toString(), kind, BriefHeadline.of(kind, today.size, actionable.size, conflicts.size, completed.size),
            today, conflicts, completed.take(20), actionable, digest, now).also { store.saveBrief(agentId, it) }
    }
}

object EvidenceRanker {
    fun tokens(text: String): Set<String> {
        val words = Regex("[\\p{L}\\p{N}]{2,}").findAll(text.lowercase()).map { it.value }.toSet()
        val cjk = Regex("[\\p{IsHan}]+").findAll(text).flatMap { it.value.windowed(2).asSequence() }.toSet()
        return words + cjk
    }
    fun rank(query: String, evidence: List<ContextEvidence>, now: Long, days: Int = 90, limit: Int = 30): List<ContextEvidence> {
        require(days in 1..3650 && limit in 1..100)
        val terms = tokens(query)
        if (terms.isEmpty()) return emptyList()
        return evidence.filter { it.atMs <= now && it.atMs >= now - days * 86_400_000L }.mapNotNull { e ->
            val overlap = terms.count { term -> term in e.text.lowercase() }
            if (overlap == 0) null else e.copy(score = overlap.toDouble() / terms.size + .1 * (1.0 - (now - e.atMs).toDouble() / (days * 86_400_000L)))
        }.sortedByDescending { it.score }.distinctBy { it.source to it.id }.take(limit)
    }
}
