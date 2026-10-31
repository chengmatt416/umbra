@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.umbra.agent.ui.questions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.*
import app.umbra.core.orchestrator.QuestionBroker
import app.umbra.runtime.omp.AskAnswer
import app.umbra.runtime.omp.UiRequest
import app.umbra.runtime.omp.UiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/*
 * Questions an agent or a session asks while it works (omp's ask, select, confirm and text dialogs), 2026-10-31. One
 * card, wherever the question shows: popped up over what the user is doing, in the agent's chat, in the Inbox. Options
 * are full-width rows a thumb can hit; a single choice answers with one tap, several choices or several questions with
 * Send; a text question is a field and Send. Nothing here decides for the user: Skip tells the agent there is no answer.
 */

/** Who is asking, in the user's words: the agent by name, a session by its title (with its agent when it has one). */
suspend fun askerOf(container: AppContainer, sessionId: String): String? = withContext(Dispatchers.IO) {
    container.agents.store.bySession(sessionId)?.name ?: container.sessionStore.get(sessionId)?.let { s ->
        val agent = s.agentId?.let { container.agents.store.get(it)?.name }
        listOfNotNull(agent, s.title.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null }
    }
}

/** The agent a question belongs to (its conversation, or a session it started), for the chat that should show it. */
suspend fun agentOf(container: AppContainer, sessionId: String): String? = withContext(Dispatchers.IO) {
    container.agents.store.bySession(sessionId)?.id ?: container.sessionStore.get(sessionId)?.agentId
}

@Composable
fun QuestionCard(pending: QuestionBroker.Pending, asker: String?, onAnswer: (UiResponse) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().inkSurface(shape, CardStyle.Ceramic).border(1.5.dp, P.accent2.copy(alpha = .55f), shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(P.highlight).border(1.dp, P.line2, CircleShape), contentAlignment = Alignment.Center) {
                PIcon(PIcons.message, size = 15.dp, tint = P.ink)
            }
            Text(
                (asker?.let { stringResource(R.string.qa_asks, it) } ?: stringResource(R.string.qa_asks_anon)).uppercase(),
                Modifier.weight(1f), color = P.sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .8.sp, maxLines = 1,
            )
        }
        when (val r = pending.request) {
            is UiRequest.Select -> {
                Title(r.title)
                r.options.forEachIndexed { i, option ->
                    OptionRow(option, r.optionDescriptions.getOrNull(i), selected = false, multi = false, recommended = false) { onAnswer(UiResponse.Value(option)) }
                }
            }
            is UiRequest.Confirm -> {
                Title(r.title)
                if (r.message.isNotBlank()) Text(r.message, fontSize = 14.sp, lineHeight = 20.sp, color = P.sub)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton(stringResource(R.string.q_no), { onAnswer(UiResponse.Confirmed(false)) }, Modifier.weight(1f), style = ButtonStyle.Alt)
                    PButton(stringResource(R.string.q_yes), { onAnswer(UiResponse.Confirmed(true)) }, Modifier.weight(1f))
                }
            }
            is UiRequest.Input -> TextQuestion(r.id, r.title, r.placeholder, null, onAnswer)
            is UiRequest.Editor -> TextQuestion(r.id, r.title, null, r.prefill, onAnswer)
            is UiRequest.Ask -> AskQuestions(r, onAnswer)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PButton(stringResource(R.string.q_skip), { onAnswer(UiResponse.Cancelled()) }, style = ButtonStyle.Ghost, tiny = true)
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, Modifier.semantics { heading() }, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium, color = P.ink)

/** One answer to pick: a full-width row, the mark on the left (a ring, or a box for several), Suggested on the right. */
@Composable
private fun OptionRow(label: String, description: String?, selected: Boolean, multi: Boolean, recommended: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val state = stringResource(if (selected) R.string.pchoice_selected else R.string.pchoice_not_selected)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
            .background(if (selected) P.highlight else androidx.compose.ui.graphics.Color.Transparent)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) P.ink else P.line2, shape)
            .clickable(role = if (multi) Role.Checkbox else Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label; stateDescription = state }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val markShape = if (multi) RoundedCornerShape(5.dp) else CircleShape
        Box(Modifier.size(20.dp).clip(markShape).border(1.5.dp, if (selected) P.ink else P.muted, markShape).background(if (selected && multi) P.ink else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center) {
            if (selected && multi) PIcon(PIcons.check, size = 13.dp, tint = inkOnPrimary)
            else if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(P.ink))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 15.sp, lineHeight = 20.sp, color = P.ink)
            description?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.5.sp, lineHeight = 17.sp, color = P.sub) }
        }
        if (recommended) Text(stringResource(R.string.q_suggested_badge).uppercase(), Modifier.clip(RoundedCornerShape(6.dp)).background(P.ink).padding(horizontal = 6.dp, vertical = 2.dp),
            color = inkOnPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .5.sp)
    }
}

@Composable
private fun AnswerField(value: String, onChange: (String) -> Unit, hint: String, minLines: Int = 1) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.base2).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, fontSize = 15.sp, color = P.muted)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = (minLines * 20).dp).semantics { contentDescription = hint },
            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = P.ink), cursorBrush = SolidColor(P.accent2), maxLines = 8)
    }
}

@Composable
private fun TextQuestion(id: String, title: String, placeholder: String?, prefill: String?, onAnswer: (UiResponse) -> Unit) {
    var text by remember(id) { mutableStateOf(prefill.orEmpty()) }
    Title(title)
    AnswerField(text, { text = it }, placeholder ?: stringResource(R.string.qa_your_answer), if (prefill != null) 3 else 1)
    PButton(stringResource(R.string.q_send), { onAnswer(UiResponse.Value(text.trim())) }, Modifier.fillMaxWidth(), enabled = text.isNotBlank(), icon = PIcons.send)
}

/**
 * omp's structured ask: one or more questions, each with options (one or several) and room for the user's own words.
 * One question with one choice to make answers on the tap; anything more is chosen, then sent together.
 */
@Composable
private fun AskQuestions(request: UiRequest.Ask, onAnswer: (UiResponse) -> Unit) {
    var picked by remember(request.id) { mutableStateOf(request.questions.associate { it.id to emptySet<String>() }) }
    var own by remember(request.id) { mutableStateOf(request.questions.associate { it.id to "" }) }
    val quick = request.questions.size == 1 && !request.questions[0].multi && request.questions[0].options.isNotEmpty()
    fun send() = onAnswer(UiResponse.Answers(request.questions.map { q -> AskAnswer(q.id, picked[q.id].orEmpty().toList(), own[q.id]?.trim()?.takeIf { it.isNotEmpty() }) }))
    request.questions.forEachIndexed { index, q ->
        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(P.line2))
        q.header?.takeIf { it.isNotBlank() }?.let { Text(it.uppercase(), color = P.sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .8.sp) }
        Title(q.question)
        q.options.forEachIndexed { i, option ->
            val on = option.label in picked[q.id].orEmpty()
            OptionRow(option.label, option.description, on, q.multi, recommended = q.recommended == i) {
                val current = picked[q.id].orEmpty()
                picked = picked + (q.id to if (q.multi) (if (on) current - option.label else current + option.label) else setOf(option.label))
                if (quick && own[q.id].isNullOrBlank()) send()
            }
        }
        AnswerField(own[q.id].orEmpty(), { own = own + (q.id to it) },
            stringResource(if (q.options.isEmpty()) R.string.qa_your_answer else R.string.qa_own_answer))
    }
    val ready = request.questions.all { q -> picked[q.id].orEmpty().isNotEmpty() || !own[q.id].isNullOrBlank() }
    if (!quick || request.questions.any { !own[it.id].isNullOrBlank() }) {
        PButton(stringResource(if (request.questions.size > 1) R.string.q_send_answers else R.string.q_send), ::send, Modifier.fillMaxWidth(), enabled = ready, icon = PIcons.send)
    }
}

/**
 * Pages that already show questions: a session's own page shows its questions, the Inbox shows them all ([ALL_QUESTIONS]).
 * A list rather than a set, so two pages showing the same session during a transition both count.
 */
private val pagesShowingQuestions = MutableStateFlow(emptyList<String>())
private const val ALL_QUESTIONS = "*"

/** Marks the composing page as showing [sessionId]'s questions (every question when null), so none pops up over it. */
@Composable
fun ShowsQuestions(sessionId: String?) {
    val key = sessionId ?: ALL_QUESTIONS
    DisposableEffect(key) {
        pagesShowingQuestions.update { it + key }
        onDispose { pagesShowingQuestions.update { it - key } }
    }
}

/**
 * Questions pop up over whatever the user is doing, one at a time, oldest first, each once: Later puts it away (it
 * stays in the agent's chat and the Inbox). A question the page in front of the user already shows (the asking agent's
 * chat, the session's own page, the Inbox) does not pop up, then or after the user leaves that page.
 */
@Composable
fun QuestionPopupHost() {
    val container = LocalContext.current.container
    val pending by container.questions.pending.collectAsStateWithLifecycle()
    val onScreen by container.agents.onScreen.collectAsStateWithLifecycle()
    val pages by pagesShowingQuestions.collectAsStateWithLifecycle()
    var seen by remember { mutableStateOf(emptySet<String>()) }
    // Who asks, looked up once per session: the agent it belongs to and the name to show. A question waits for its
    // lookup, so it never flashes up over the chat that already shows it.
    val owners by produceState(emptyMap<String, Pair<String?, String?>>(), pending) {
        val known = value
        val missing = pending.map { it.sessionId }.distinct().filter { it !in known }
        if (missing.isNotEmpty()) value = known + missing.associateWith { agentOf(container, it) to askerOf(container, it) }
    }
    fun inView(q: QuestionBroker.Pending) = owners[q.sessionId]?.let { (agent, _) ->
        ALL_QUESTIONS in pages || q.sessionId in pages || agent != null && agent == onScreen
    } == true
    val inViewNow = pending.filter { inView(it) && it.request.id !in seen }.map { it.request.id }
    LaunchedEffect(inViewNow) { if (inViewNow.isNotEmpty()) seen = seen + inViewNow }
    val next = pending.firstOrNull { it.request.id !in seen && it.sessionId in owners && !inView(it) } ?: return
    val asker = owners[next.sessionId]?.second
    // One sheet per question: a sheet swiped away stays hidden, so the next question gets a fresh one. Fully open, so
    // the answer, Skip and Later all show without dragging the sheet up first.
    key(next.request.id) {
        ModalBottomSheet(onDismissRequest = { seen = seen + next.request.id }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = pc(0xFFFBFDF7)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).imePadding().padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuestionCard(next, asker, onAnswer = { container.questions.answer(next.request.id, it); seen = seen + next.request.id })
                PButton(stringResource(R.string.qa_later), { seen = seen + next.request.id }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
                Text(stringResource(R.string.qa_later_hint), Modifier.fillMaxWidth(), fontSize = 12.sp, color = P.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
