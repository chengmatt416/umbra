package app.umbra.agent.telegram

import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.agents.AgentProfile
import app.umbra.agent.agents.proactive.OpportunityCard
import app.umbra.agent.agents.proactive.GoalState
import app.umbra.agent.agents.proactive.RoutineTrigger
import app.umbra.agent.agents.proactive.CronText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Buttons bind a reviewed proposal version to one agent and expire; restart invalidates outstanding callbacks. */
internal class ProactiveTelegram(
    private val container: AppContainer,
    private val send: suspend (Long, String, JsonObject?) -> Unit,
) {
    private val reviews = OpportunityReviews()
    private val store get() = container.agents.proactive.store
    suspend fun command(chatId: Long, raw: String?, p: AgentProfile): Boolean {
        val output = when (raw) {
            "/goals" -> store.goals(p.id).joinToString("\n\n") { g -> container.context.getString(R.string.tgf_goal, g.title,
                text(when (g.state) {
                    GoalState.IN_PROGRESS -> R.string.goal_state_in_progress; GoalState.SCHEDULED -> R.string.goal_state_scheduled
                    GoalState.WAITING_USER -> R.string.goal_state_waiting; GoalState.BLOCKED -> R.string.goal_state_blocked
                    GoalState.PAUSED -> R.string.goal_state_paused; GoalState.COMPLETED -> R.string.goal_state_completed
                    GoalState.ARCHIVED -> R.string.goal_state_archived
                }), g.milestones.count { it.status == "done" }, g.milestones.size, g.description.take(600)) }.ifBlank { text(R.string.tgf_no_goals) }
            "/routines" -> store.routines(p.id).joinToString("\n\n") { r ->
                "${r.title} · ${text(if (r.enabled) R.string.tgf_enabled else R.string.tgf_disabled)}\n${trigger(r.trigger)}\n" +
                    r.recentRuns.lastOrNull()?.let { "${it.status.replace('_', ' ')}: ${it.summary.ifBlank { text(R.string.tgf_run_details) }}" }.orEmpty()
            }.ifBlank { text(R.string.tgf_no_routines) }
            "/brief" -> store.briefs(p.id).maxByOrNull { it.generatedAtMs }?.let(app.umbra.agent.agents.proactive.BriefText::format) ?: text(R.string.tgf_no_brief)
            "/suggestions" -> { suggestions(chatId, p); return true }
            else -> return false
        }
        send(chatId, protected(telegramEscape("${p.name}\n$output")), null)
        return true
    }
    private suspend fun suggestions(chatId: Long, p: AgentProfile) {
        val cards = store.opportunities(p.id).filter { it.state == OpportunityCard.State.PENDING && it.expiresAtMs?.let { end -> end <= System.currentTimeMillis() } != true }.sortedByDescending { it.urgency * it.confidence }.take(5)
        if (cards.isEmpty()) { send(chatId, telegramEscape(text(R.string.tgf_no_suggestions)), null); return }
        for (card in cards) sendCard(chatId, p, card)
    }
    suspend fun offer(chatId: Long, p: AgentProfile, cardId: String): Boolean {
        val card = store.opportunities(p.id).find { it.id == cardId } ?: return false
        if (card.state != OpportunityCard.State.PENDING || card.expiresAtMs?.let { it <= System.currentTimeMillis() } == true) return false
        sendCard(chatId, p, card)
        return true
    }
    private suspend fun sendCard(chatId: Long, p: AgentProfile, card: OpportunityCard) {
        val token = reviews.create(card, chatId)
        send(chatId, protected(telegramSuggestionText(card, p.name, ::text)), keyboard(text(R.string.tgf_review), "op:r:$token"))
    }
    fun clearReviews() { reviews.clear() }
    suspend fun callback(chatId: Long, data: String): String? {
        if (!data.startsWith("op:")) return null
        val parts = data.split(':')
        val token = parts.getOrNull(2) ?: return text(R.string.tgf_suggestion_invalid)
        val review = reviews.get(token, chatId) ?: return text(R.string.tgf_suggestion_expired)
        val card = store.opportunities(review.agentId).find { it.id == review.cardId } ?: return text(R.string.tgf_suggestion_removed)
        if (reviews.validate(token, card, chatId) == null) {
            reviews.remove(token); return text(R.string.tgf_suggestion_changed)
        }
        return try {
            when (parts.getOrNull(1)) {
                "r" -> {
                    check(reviews.markReviewed(token, card, chatId)) { text(R.string.tgf_suggestion_changed) }
                    val name = container.agents.store.get(review.agentId)?.name.orEmpty()
                    send(chatId, protected(telegramSuggestionText(card, name, ::text)), telegramKeyboard(listOf(listOf(
                        telegramButton(text(R.string.tgf_confirm), "op:y:$token"), telegramButton(text(R.string.tgf_not_now), "op:n:$token")))))
                    text(R.string.tgf_review_above)
                }
                "y" -> {
                    check(review.reviewed) { text(R.string.tgf_review_first) }
                    check(container.telegram.state.value.messages) { text(R.string.tgf_messages_off) }
                    container.agents.proactive.adopt(review.agentId, review.cardId)
                    reviews.remove(token)
                    send(chatId, telegramEscape(text(R.string.tgf_started).format(card.title)), null)
                    text(R.string.tgf_done)
                }
                "n" -> { reviews.remove(token); text(R.string.tgf_not_now) }
                else -> text(R.string.tgf_suggestion_invalid)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { text(R.string.tgf_suggestion_failed) }
    }
    private fun trigger(trigger: RoutineTrigger): String = when (trigger) {
        is RoutineTrigger.CronSchedule -> CronText.describe(trigger.cron, container.context.resources.configuration.locales[0].language == "zh") ?: text(R.string.tgf_custom_schedule)
        is RoutineTrigger.EventMatch -> text(R.string.tgf_on_event)
        is RoutineTrigger.WatcherMatch -> text(R.string.tgf_on_change)
        RoutineTrigger.Manual -> text(R.string.tgf_manual)
    }
    private fun text(id: Int) = container.context.getString(id)
    private fun protected(text: String) = app.umbra.core.safety.OutboundDlpGuard(4) { container.protectedValues() }.redact(text)
    private fun keyboard(label: String, data: String) = buildJsonObject { putJsonArray("inline_keyboard") { add(buildJsonArray { add(buildJsonObject { put("text", label); put("callback_data", data) }) }) } }
}

/** The explanation is the review: execution metadata is deliberately never part of a user's decision text. */
internal fun telegramSuggestionText(card: OpportunityCard, agent: String, text: (Int) -> String): String = buildList {
    add("<b>${telegramEscape(card.title)}</b>")
    add(telegramEscape(card.summary))
    card.explain?.takeUnless { it.isEmpty() }?.let { e ->
        if (e.why.isNotBlank()) add(telegramEscape(text(R.string.tgf_why_now).format(e.why)))
        if (e.steps.isNotEmpty()) add(telegramEscape(text(R.string.tgf_confirm_steps).format(agent)) + "\n" + e.steps.mapIndexed { i, step -> "${i + 1}. ${telegramEscape(step)}" }.joinToString("\n"))
        if (e.needs.isNotBlank()) add(telegramEscape(text(R.string.tgf_needs).format(e.needs)))
        if (e.ifIgnored.isNotBlank()) add(telegramEscape(text(R.string.tgf_if_ignored).format(e.ifIgnored)))
        if (e.sources.isNotEmpty()) add(telegramEscape(text(R.string.tgf_based_on).format(e.sources.joinToString(" · "))))
    }
}.joinToString("\n\n")
