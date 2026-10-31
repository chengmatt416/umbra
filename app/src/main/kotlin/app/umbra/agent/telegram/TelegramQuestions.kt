package app.umbra.agent.telegram

import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.runtime.omp.AskAnswer
import app.umbra.runtime.omp.UiRequest
import app.umbra.runtime.omp.UiResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** An Ask is resolved once, with answers in the original question order even when taps arrive out of order. */
internal class TelegramAskAnswers(private val request: UiRequest.Ask) {
    private val answers = mutableMapOf<String, AskAnswer>()
    val selected = mutableMapOf<Int, MutableSet<Int>>()
    fun choose(index: Int, option: Int): Boolean {
        val q = request.questions.getOrNull(index) ?: return false
        val value = q.options.getOrNull(option) ?: return false
        if (q.multi) {
            val picked = selected.getOrPut(index) { mutableSetOf() }
            if (!picked.add(option)) picked.remove(option)
            answers.remove(q.id)
        } else {
            selected[index] = mutableSetOf(option)
            answers[q.id] = AskAnswer(q.id, listOf(value.label))
        }
        return true
    }
    fun done(index: Int): Boolean {
        val q = request.questions.getOrNull(index)?.takeIf { it.multi } ?: return false
        val picked = selected[index].orEmpty()
        if (picked.isEmpty()) return false
        answers[q.id] = AskAnswer(q.id, q.options.filterIndexed { i, _ -> i in picked }.map { it.label })
        return true
    }
    fun custom(index: Int, text: String): Boolean {
        val q = request.questions.getOrNull(index) ?: return false
        selected.remove(index)
        answers[q.id] = AskAnswer(q.id, emptyList(), text)
        return true
    }
    fun response(): UiResponse.Answers? = request.questions.map { answers[it.id] ?: return null }.let { UiResponse.Answers(it) }
}

internal fun telegramQuestionNeedsReply(request: UiRequest, index: Int = 0): Boolean =
    request is UiRequest.Input || request is UiRequest.Editor || (request as? UiRequest.Ask)?.questions?.getOrNull(index)?.options?.isEmpty() == true

internal fun telegramForceReply() = buildJsonObject { put("force_reply", true); put("selective", true) }

internal fun telegramQuestionKeyboard(request: UiRequest, index: Int, prefix: String, text: (Int) -> String, selected: Set<Int> = emptySet()): JsonObject {
    fun button(label: String, op: String) = telegramButton(label, "$prefix:$index:$op")
    val rows = when (request) {
        is UiRequest.Select -> request.options.mapIndexed { i, label -> listOf(button(label, "p$i")) }
        is UiRequest.Confirm -> listOf(listOf(button(text(R.string.tgf_yes), "y"), button(text(R.string.tgf_no), "n")))
        is UiRequest.Input, is UiRequest.Editor -> emptyList()
        is UiRequest.Ask -> request.questions.getOrNull(index)?.let { q -> buildList {
            q.options.forEachIndexed { i, option -> add(listOf(button((if (i in selected) "✓ " else "") + option.label, "p$i"))) }
            if (q.multi && q.options.isNotEmpty()) add(listOf(button(text(R.string.tgf_done), "d")))
            add(listOf(button(text(R.string.tgf_type_answer), "t")))
        } }.orEmpty()
    }
    return telegramKeyboard(rows + listOf(listOf(button(text(R.string.tgf_skip), "s"))))
}

/** Reply targets remember the question and chat, so a typed answer cannot accidentally become a new agent message. */
internal class TelegramQuestions(
    private val container: AppContainer,
    private val send: suspend (Long, String, JsonObject?) -> Long?,
    private val edit: suspend (Long, Long, String, JsonObject?) -> Unit,
) {
    private class Pending(val request: UiRequest, val chatId: Long, val agent: String, val identity: Triple<Long, Long, Long>) {
        val answers = (request as? UiRequest.Ask)?.let(::TelegramAskAnswers)
        val messages = mutableMapOf<Long, Pair<Int, String>>()
        val replies = mutableMapOf<Long, Int>()
        var answered = false
        var token = ""
    }
    private val mutex = Mutex()
    private val tokens = TelegramTokens<Pending>()
    private data class ReplyTarget(val pending: Pending, val index: Int, val untilMs: Long)
    private val replyTargets = linkedMapOf<Pair<Long, Long>, ReplyTarget>()
    private val shown = linkedMapOf<String, Pending>()
    private fun text(id: Int) = container.context.getString(id)
    private fun identity(s: TelegramBot.State): Triple<Long, Long, Long>? = if (s.messages && s.userId != null && s.chatId != null)
        Triple(s.botId, s.userId, s.chatId) else null

    suspend fun watch() {
        combine(container.questions.pending, container.telegram.state) { pending, state -> pending to state }.collect { (pending, state) -> mutex.withLock {
            val active = pending.map { it.request.id }.toSet()
            val owner = identity(state)
            for ((id, p) in shown.toMap()) {
                if (id !in active || p.identity != owner) {
                    for ((messageId, content) in p.messages.toMap()) runCatching {
                        edit(p.chatId, messageId, content.second + "\n\n" + telegramEscape(text(if (p.answered) R.string.tgf_answered else R.string.tgf_no_longer_needed)), telegramKeyboard(emptyList()))
                    }
                    tokens.remove(p.token)
                    shown.remove(id)
                }
            }
            if (owner == null) return@withLock
            for (entry in pending) {
                if (shown.containsKey(entry.request.id)) continue
                val profile = container.agents.store.bySession(entry.sessionId) ?: container.sessionStore.get(entry.sessionId)?.agentId?.let { container.agents.store.get(it) } ?: continue
                val p = Pending(entry.request, owner.third, profile.name, owner)
                p.token = tokens.put(p)
                shown[entry.request.id] = p
                try {
                    val count = (p.request as? UiRequest.Ask)?.questions?.size ?: 1
                    if (count == 0) resolve(p, UiResponse.Answers(emptyList()))
                    for (index in 0 until count) {
                        val html = content(p, index)
                        val id = send(p.chatId, html, keyboard(p, index)) ?: continue
                        p.messages[id] = index to html
                        if (telegramQuestionNeedsReply(p.request, index)) prompt(p, index)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { shown.remove(entry.request.id); tokens.remove(p.token) }
            }
        } }
    }

    private fun keyboard(p: Pending, index: Int) = telegramQuestionKeyboard(p.request, index, "tq:${p.token}", ::text, p.answers?.selected?.get(index).orEmpty())
    private fun content(p: Pending, index: Int): String {
        val question = when (val r = p.request) {
            is UiRequest.Select -> telegramEscape(r.title) + "\n" + r.options.mapIndexed { i, label -> "• ${telegramEscape(label)}" + r.optionDescriptions.getOrNull(i)?.let { " — ${telegramEscape(it)}" }.orEmpty() }.joinToString("\n")
            is UiRequest.Confirm -> "<b>${telegramEscape(r.title)}</b>\n${telegramEscape(r.message)}"
            is UiRequest.Input -> "<b>${telegramEscape(r.title)}</b>" + r.placeholder?.let { "\n${telegramEscape(it)}" }.orEmpty()
            is UiRequest.Editor -> "<b>${telegramEscape(r.title)}</b>" + r.prefill?.let { "\n${telegramEscape(it)}" }.orEmpty()
            is UiRequest.Ask -> r.questions[index].let { q -> q.header?.let { "<b>${telegramEscape(it)}</b>\n" }.orEmpty() + telegramEscape(q.question) + "\n" + q.options.mapIndexed { i, o ->
                "• ${telegramEscape(o.label)}${if (i == q.recommended) " ★" else ""}" + o.description?.let { " — ${telegramEscape(it)}" }.orEmpty()
            }.joinToString("\n") }
        }
        return telegramEscape(text(R.string.tgf_agent_asks).format(p.agent)) + "\n\n" + question
    }
    private suspend fun prompt(p: Pending, index: Int) {
        val markup = telegramForceReply()
        val html = content(p, index) + "\n\n" + telegramEscape(text(R.string.tgf_reply_answer))
        send(p.chatId, html, markup)?.let {
            p.replies[it] = index
            p.messages[it] = index to html
            replyTargets.entries.removeAll { entry -> entry.value.untilMs <= System.currentTimeMillis() }
            while (replyTargets.size >= 500) replyTargets.remove(replyTargets.keys.first())
            replyTargets[p.chatId to it] = ReplyTarget(p, index, System.currentTimeMillis() + 86_400_000)
        }
    }
    /** Telegram calls made while answering a tap: one that fails must not fail the tap, nor hold up later updates. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    private fun valid(p: Pending) = !p.answered && p.identity == identity(container.telegram.state.value) && container.questions.pending.value.any { it.request.id == p.request.id }
    private fun resolve(p: Pending, response: UiResponse): Boolean = container.questions.answer(p.request.id, response).also { if (it) p.answered = true }

    suspend fun reply(chatId: Long, message: JsonObject, value: String?): Boolean = mutex.withLock {
        val target = message["reply_to_message"]?.jsonObject?.get("message_id")?.jsonPrimitive?.longOrNull ?: return@withLock false
        val reply = replyTargets[chatId to target] ?: shown.values.firstOrNull { it.chatId == chatId && target in it.replies }?.let {
            ReplyTarget(it, it.replies.getValue(target), Long.MAX_VALUE)
        } ?: return@withLock false
        val p = reply.pending
        if (value == null) return@withLock true
        // A late reply still belongs to the question, not to the chat: say so instead of passing it to the agent.
        if (!valid(p)) {
            send(chatId, telegramEscape(text(if (p.answered) R.string.tgf_answered else R.string.tgf_no_longer_needed)), null)
            return@withLock true
        }
        val index = reply.index
        if (p.request is UiRequest.Ask) {
            p.answers?.custom(index, value)
            p.answers?.response()?.let { resolve(p, it) }
        } else resolve(p, UiResponse.Value(value))
        return@withLock true
    }

    suspend fun callback(chatId: Long, data: String): String = mutex.withLock {
        val parts = data.split(':')
        val p = parts.getOrNull(1)?.let(tokens::get)?.takeIf { it.chatId == chatId && valid(it) } ?: return@withLock text(R.string.tgf_button_expired)
        val index = parts.getOrNull(2)?.toIntOrNull() ?: return@withLock text(R.string.tgf_button_expired)
        val op = parts.getOrNull(3) ?: return@withLock text(R.string.tgf_button_expired)
        if (op == "s") { resolve(p, UiResponse.Cancelled()); return@withLock text(R.string.tgf_skipped) }
        when (val r = p.request) {
            is UiRequest.Select -> op.removePrefix("p").toIntOrNull()?.let { r.options.getOrNull(it) }?.let { resolve(p, UiResponse.Value(it)) }
            is UiRequest.Confirm -> if (op in listOf("y", "n")) resolve(p, UiResponse.Confirmed(op == "y"))
            is UiRequest.Input, is UiRequest.Editor -> Unit
            is UiRequest.Ask -> {
                if (index !in r.questions.indices) return@withLock text(R.string.tgf_button_expired)
                when {
                    op == "t" -> quietly { prompt(p, index) }
                    op == "d" -> p.answers?.done(index)
                    op.startsWith('p') -> op.drop(1).toIntOrNull()?.let { p.answers?.choose(index, it) }
                }
                p.answers?.response()?.let { resolve(p, it) }
                // Telegram refuses an edit that changes nothing (the same option tapped again); the tap still counts.
                if (!p.answered && op.startsWith('p')) for ((id, content) in p.messages.toMap()) if (content.first == index && id !in p.replies) quietly { edit(p.chatId, id, content.second, keyboard(p, index)) }
            }
        }
        return@withLock text(if (p.answered) R.string.tgf_answered else R.string.tgf_answer_saved)
    }
}
