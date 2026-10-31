package app.umbra.agent.agents.proactive

import java.time.Instant
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

/**
 * Finds what is worth asking about in a calendar; it decides nothing. Pairs that overlap (or leave too little time to
 * travel) go to [ConflictJudge]; only what it judges a conflict becomes one. A date that disagrees with its own
 * stated weekday is a fact about one entry, so it is reported as it is.
 */
object ConflictDetector {
    fun candidates(events: List<CalendarItem>, now: Long): List<ConflictCandidate> {
        val upcoming = events.filter { it.endMs > now && it.startMs < now + 7 * 86_400_000L }.sortedBy { it.startMs }
        val result = mutableListOf<ConflictCandidate>()
        for ((i, a) in upcoming.withIndex()) {
            for (b in upcoming.drop(i + 1)) {
                if (b.startMs >= a.endMs + 120 * 60_000L) break
                if (a.startMs < b.endMs && b.startMs < a.endMs) result += ConflictCandidate(a, b, ConflictJudge.OVERLAP)
                else if (a.location.isNotBlank() && b.location.isNotBlank() && a.location != b.location && a.travelMinutes != null && b.startMs - a.endMs < a.travelMinutes * 60_000L)
                    result += ConflictCandidate(a, b, ConflictJudge.TRAVEL)
            }
        }
        return result.distinctBy { it.key }
    }

    fun weekdayMismatches(events: List<CalendarItem>, now: Long, words: ConflictWords = ConflictWords()): List<ConflictAlert> =
        events.filter { it.endMs > now && it.startMs < now + 7 * 86_400_000L }.mapNotNull { a ->
            val declared = a.declaredWeekday ?: return@mapNotNull null
            if (Instant.ofEpochMilli(a.startMs).atZone(words.zone).dayOfWeek.value == declared) return@mapNotNull null
            val explain = words.weekday(a, declared)
            ConflictAlert("${a.source}:${a.id}:weekday", "commitment_mismatch", explain.why, listOf("${a.source}:${a.id}"),
                title = words.weekdayTitle(a), explain = explain)
        }

    /**
     * The alerts from judged pairs: a conflict is one, the same event twice or two that fit together are not. A pair
     * nothing could judge (Laya off, no model to ask) is shown as a possible conflict, saying it may be one event.
     */
    fun alerts(readings: List<ConflictReading>, words: ConflictWords = ConflictWords()): List<ConflictAlert> = readings.mapNotNull { r ->
        val (a, b, kind) = r.candidate
        if (r.verdict != null && r.verdict != ConflictVerdict.CONFLICT) return@mapNotNull null
        val explain = words.pair(a, b, kind, checked = r.verdict != null, judged = r.why)
        val severity = if (r.verdict == null) ConflictJudge.POSSIBLE else kind
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("instruction", kotlinx.serialization.json.JsonPrimitive("The user asked from their brief: prepare a way to resolve the clash between \"${a.title}\" (${words.span(a)}) and " +
                "\"${b.title}\" (${words.span(b)}). Suggest which one to move and draft the message or the calendar change. Ask before sending anything or editing either event."))
        }.toString()
        ConflictAlert("${a.source}:${a.id}:${b.source}:${b.id}:$kind", severity, explain.why,
            listOf("${a.source}:${a.id}", "${b.source}:${b.id}"), listOf(PreparedNextStep(words.fixLabel(), "agent_turn", payload)),
            title = words.pairTitle(a, b, kind, checked = r.verdict != null), explain = explain)
    }.distinctBy { it.id }
}

/**
 * What the brief says about a calendar clash, as a person would (spec §5.2, revised 2026-10-28): the events' names, days
 * and times, their calendars, by how much they collide, and what happens next, in the phone's language (Traditional
 * Chinese or English, like [BriefHeadline]). Never ids.
 */
class ConflictWords(val zh: Boolean = java.util.Locale.getDefault().language == "zh", val zone: ZoneId = ZoneId.systemDefault()) {
    private val day = java.time.format.DateTimeFormatter.ofPattern("EEE", if (zh) java.util.Locale.TRADITIONAL_CHINESE else java.util.Locale.ENGLISH)
    private val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)

    /** "Thu 15:00–16:00" (週四 15:00–16:00); an all-day event is just its day. */
    fun span(e: CalendarItem): String {
        val start = at(e.startMs)
        if (e.allDay) return day.format(start) + if (zh) " 整天" else " (all day)"
        val end = at(e.endMs)
        return "${day.format(start)} ${time.format(start)}–${time.format(end)}"
    }

    private fun place(e: CalendarItem): String = listOf(e.calendar.ifBlank { e.account }, e.location).filter { it.isNotBlank() }.joinToString(if (zh) "，" else ", ")

    private fun described(e: CalendarItem) = if (zh) "「${e.title}」（${span(e)}${place(e).takeIf { it.isNotEmpty() }?.let { "，$it" }.orEmpty()}）"
        else "${e.title} (${span(e)}${place(e).takeIf { it.isNotEmpty() }?.let { ", $it" }.orEmpty()})"

    fun pairTitle(a: CalendarItem, b: CalendarItem, kind: String, checked: Boolean): String = when {
        kind == ConflictJudge.TRAVEL -> if (zh) "「${a.title}」到「${b.title}」來不及趕到" else "Not enough time to get from ${a.title} to ${b.title}"
        !checked -> if (zh) "「${a.title}」和「${b.title}」可能撞期" else "${a.title} and ${b.title} may clash"
        else -> if (zh) "「${a.title}」和「${b.title}」時間重疊" else "${a.title} and ${b.title} overlap"
    }

    fun pair(a: CalendarItem, b: CalendarItem, kind: String, checked: Boolean, judged: String?): CardExplanation {
        val minutes = ((minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)) / 60_000).coerceAtLeast(0)
        val why = when {
            kind == ConflictJudge.TRAVEL -> {
                val gap = ((b.startMs - a.endMs) / 60_000).coerceAtLeast(0)
                if (zh) "${described(a)}結束後，只有 $gap 分鐘就要到${described(b)}，但路程大約需要 ${a.travelMinutes} 分鐘。"
                else "${described(a)} ends only $gap minutes before ${described(b)} starts, and getting there takes about ${a.travelMinutes} minutes."
            }
            zh -> "${described(a)}和${described(b)}重疊 $minutes 分鐘。" + if (!checked) "這兩個也可能是同一場會議出現在兩個行事曆上：Laya 沒開、也沒有模型能確認。" else ""
            else -> "${described(a)} overlaps ${described(b)} by $minutes minutes." + if (!checked) " They may be one meeting on two calendars: Laya is off and no model could check." else ""
        }
        return CardExplanation(
            why = why + judged?.takeIf { checked && it.isNotBlank() }?.let { if (zh) "（判斷依據：$it）" else " ($it)" }.orEmpty(),
            steps = if (zh) listOf("比較兩個行程與受邀的人，建議移動哪一個", "草擬改時間的訊息或行事曆變更", "先給你看，你同意前不會傳送或修改任何行程")
                else listOf("Compare both events and who is invited, and suggest which to move", "Draft the message or the calendar change", "Show it to you first: nothing is sent or changed until you agree"),
            needs = if (zh) "如果你已經知道要保留哪一個，可以在確認時告訴它。" else "If you already know which one to keep, say so when you confirm.",
            ifIgnored = if (zh) "兩個行程都維持原樣。" else "Both stay on your calendar as they are.",
            sources = listOf(a, b).map { e -> (if (zh) "行事曆 · " else "Calendar · ") + e.title + " · " + span(e) },
        )
    }

    fun weekdayTitle(a: CalendarItem) = if (zh) "「${a.title}」的日期和星期對不上" else "The date and weekday of ${a.title} disagree"

    fun weekday(a: CalendarItem, declared: Int): CardExplanation {
        val said = java.time.DayOfWeek.of(declared).getDisplayName(java.time.format.TextStyle.FULL, if (zh) java.util.Locale.TRADITIONAL_CHINESE else java.util.Locale.ENGLISH)
        return CardExplanation(
            why = if (zh) "${described(a)}排在${day.format(at(a.startMs))}，但內容寫的是$said。其中一個可能寫錯了。"
                else "${described(a)} is on ${day.format(at(a.startMs))}, but its text says $said. One of them is probably wrong.",
            steps = if (zh) listOf("查看邀請或原始訊息，找出正確的日子", "告訴你哪個是對的，需要的話準備修正") else listOf("Check the invitation or the message it came from for the right day", "Tell you which is right and prepare the fix if one is needed"),
            ifIgnored = if (zh) "行程維持原樣。" else "The event stays as it is.",
            sources = listOf((if (zh) "行事曆 · " else "Calendar · ") + a.title + " · " + span(a)),
        )
    }

    fun fixLabel() = if (zh) "請它提出解決辦法" else "Ask for a fix"
}

/** One sentence a person would say about the day, in the phone's language (Traditional Chinese or English). */
object BriefHeadline {
    fun of(kind: DailyBrief.Kind, events: Int, decisions: Int, conflicts: Int, done: Int, zh: Boolean = java.util.Locale.getDefault().language == "zh",
        failed: Int = 0, running: Int = 0, waiting: Int = 0, paused: Int = 0, stopped: Int = 0): String {
        val morning = kind == DailyBrief.Kind.MORNING
        if (events + decisions + conflicts + done + failed + running + waiting + paused + stopped == 0) return when {
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
            if (failed > 0) add("$failed 項任務失敗")
            if (running > 0) add("$running 項任務執行中")
            if (waiting > 0) add("$waiting 項任務等待核准")
            if (paused > 0) add("$paused 項任務已暫停")
            if (stopped > 0) add("$stopped 項任務已停止")
        } else buildList {
            if (conflicts > 0) add(n(conflicts, "conflict to settle", "conflicts to settle"))
            if (decisions > 0) add(n(decisions, "decision waiting", "decisions waiting"))
            if (events > 0) add(n(events, "event", "events") + if (morning) " today" else " tomorrow")
            if (done > 0) add(n(done, "thing done", "things done"))
            if (failed > 0) add(n(failed, "task failed", "tasks failed"))
            if (running > 0) add(n(running, "task running", "tasks running"))
            if (waiting > 0) add(n(waiting, "task awaiting approval", "tasks awaiting approval"))
            if (paused > 0) add(n(paused, "task paused", "tasks paused"))
            if (stopped > 0) add(n(stopped, "task stopped", "tasks stopped"))
        }
        return if (zh) parts.joinToString("，") + "。" else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }
}

class DailyBriefEngine(private val store: ProactiveStore, private val zone: ZoneId = ZoneId.systemDefault()) {
    /** [conflicts]: the overlapping pairs of [schedule] as [ConflictJudge] judged them. */
    fun generate(agentId: String, kind: DailyBrief.Kind, now: Long, schedule: List<CalendarItem> = emptyList(), tasks: List<BriefTask> = emptyList(),
        conflicts: List<ConflictReading> = emptyList()): DailyBrief {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val id = "brief_${date}_${kind.name.lowercase()}"
        store.briefs(agentId).find { it.id == id }?.let { return it }
        val prior = store.briefs(agentId).maxOfOrNull { it.generatedAtMs } ?: date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val staged = store.attention(agentId).filter { it.createdAtMs in (prior + 1)..now }
        val actionable = store.opportunities(agentId).filter { it.state == OpportunityCard.State.PENDING && (it.expiresAtMs == null || it.expiresAtMs > now) }
            .sortedByDescending { .6 * it.urgency + .4 * it.confidence }.take(5)
        val words = ConflictWords(zone = zone)
        val alerts = ConflictDetector.alerts(conflicts, words) + ConflictDetector.weekdayMismatches(schedule, now, words)
        // One meeting on two calendars is listed once.
        val copies = conflicts.filter { it.verdict == ConflictVerdict.SAME_EVENT }.map { "${it.candidate.b.source}:${it.candidate.b.id}" }.toSet()
        val today = schedule.filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == date && "${it.source}:${it.id}" !in copies }.map { BriefItem(it.title, it.location) }
        val digest = staged.filter { it.tier != DeliveryTier.SILENT_LOG }.joinToString("\n") { "${it.title}: ${it.detail.take(500)}" }.take(12_000)
        val recentTasks = tasks.take(30)
        fun count(state: String) = recentTasks.count { it.state == state }
        return DailyBrief(id, date.toString(), kind, BriefHeadline.of(kind, today.size, actionable.size, alerts.count { it.severity != ConflictJudge.POSSIBLE }, count("completed"),
            failed = count("failed"), running = count("running"), waiting = count("waiting"), paused = count("paused"), stopped = count("stopped")),
            scheduleHighlights = today, conflictsDetected = alerts, attentionNeeded = actionable, lowPriorityDigest = digest,
            generatedAtMs = now, tasks = recentTasks).also { store.saveBrief(agentId, it) }
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
