package app.umbra.agent.ui.agents

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.agents.proactive.CardExplanation
import app.umbra.agent.agents.proactive.ConflictAlert
import app.umbra.agent.agents.proactive.ConflictJudge
import app.umbra.agent.agents.proactive.OpportunityCard
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.*
import app.umbra.core.model.UmbraJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Decisions (spec §5.2, revised 2026-10-28): whatever an agent brings the user to confirm is shown as a decision a person
 * can make at a glance: why it comes up now, what happens if they confirm, what it needs from them, what happens if they
 * do nothing, and what it rests on, in plain sentences. Raw data never reaches the screen (no ids, JSON, field names or
 * scores). The agent writes the explanation with the suggestion ([CardExplanation]); for older cards and whatever it
 * left out, the words are made here from the prepared step.
 */

/** A suggestion's account, complete: the agent's words where it gave them, otherwise made from the card. */
internal data class Explained(
    val why: String,
    val steps: List<String>,
    val needs: String,
    val ifIgnored: String,
    val sources: List<String>,
    /** The one-line promise under the buttons: what confirming does not do yet. */
    val reassurance: String,
    /** For a message draft: who it goes to and the text, shown as the message itself. */
    val draftTo: String? = null,
    val draftText: String? = null,
)

@Composable
internal fun explained(card: OpportunityCard): Explained {
    val step = card.preparedAction
    val payload = remember(step.payloadJson) { runCatching { UmbraJson.parseToJsonElement(step.payloadJson) as? JsonObject }.getOrNull() }
    fun field(vararg names: String) = names.firstNotNullOfOrNull { (payload?.get(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
    val given = card.explain ?: CardExplanation()
    val draftTo = field("to", "recipient", "chat")
    val made: List<String> = when (step.actionType) {
        "send_message_draft" -> listOf(
            if (draftTo != null) stringResource(R.string.decision_step_draft_to, draftTo) else stringResource(R.string.decision_step_draft),
            stringResource(R.string.decision_step_send_after),
        )
        "start_coding" -> listOf(
            stringResource(R.string.decision_step_coding, field("task", "instruction", "text") ?: card.title),
            stringResource(R.string.decision_step_report),
        )
        "create_goal" -> listOfNotNull(
            stringResource(R.string.decision_step_goal, field("title") ?: card.title),
            (payload?.get("milestones") as? JsonArray)?.size?.takeIf { it > 0 }?.let { stringResource(R.string.decision_step_goal_steps, it) },
        )
        "run_skill" -> {
            val name = field("skill_name", "name") ?: field("skill_id")?.let { id -> LocalContext.current.container.skills.get(id)?.name } ?: card.title
            listOf(stringResource(R.string.decision_step_skill, name), stringResource(R.string.decision_step_report))
        }
        else -> listOf(field("instruction", "task", "text") ?: step.label)
    }
    val reassurance = stringResource(when (step.actionType) {
        "send_message_draft" -> R.string.decision_safe_message
        "start_coding" -> R.string.decision_safe_coding
        "create_goal" -> R.string.decision_safe_goal
        else -> R.string.decision_safe_default
    })
    val sources = given.sources.ifEmpty { card.evidenceRefs.map { sourceLabel(it) }.distinct().take(4) }
    return Explained(
        why = given.why.ifBlank { card.summary },
        steps = given.steps.ifEmpty { made },
        needs = given.needs,
        ifIgnored = given.ifIgnored,
        sources = sources,
        reassurance = reassurance,
        draftTo = draftTo.takeIf { step.actionType == "send_message_draft" },
        draftText = field("text").takeIf { step.actionType == "send_message_draft" },
    )
}

/**
 * Where a piece of evidence came from, as a person says it ("Calendar event", "WhatsApp chat", "Watching “ryokan
 * prices”"), from a reference like "calendar:evt_81f", "whatsapp:chat_42" or "watcher:ryokan". The id is never shown.
 */
@Composable
internal fun sourceLabel(ref: String): String {
    val kind = ref.substringBefore(':').trim().lowercase()
    val rest = ref.substringAfter(':', "").substringBefore('#').trim()
    return when (kind) {
        "calendar" -> stringResource(R.string.source_calendar)
        "email", "gmail" -> stringResource(R.string.source_email)
        "watcher" -> rest.takeIf { it.isNotEmpty() && !looksLikeId(it) }?.let { stringResource(R.string.source_watcher_named, it.replace('_', ' ')) }
            ?: stringResource(R.string.source_watcher)
        "notif", "notification", "notifications" -> stringResource(R.string.source_notification)
        "web", "http", "https" -> runCatching { java.net.URI(if (kind == "web") rest else ref).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.source_web)
        "file", "files", "drive_files", "documents", "workspace" -> rest.substringAfterLast('/').takeIf { it.isNotEmpty() && !looksLikeId(it) }
            ?: stringResource(R.string.source_file)
        "git", "github" -> stringResource(R.string.source_git)
        "memory" -> stringResource(R.string.source_memory)
        "goal" -> stringResource(R.string.source_goal)
        "tasks", "task" -> stringResource(R.string.source_tasks)
        "contacts" -> stringResource(R.string.source_contacts)
        "sms" -> stringResource(R.string.source_sms)
        "", "messages" -> stringResource(R.string.source_messages)
        // A message app ("whatsapp", "line", "telegram", "messages:whatsapp"): its name and "chat".
        else -> stringResource(R.string.source_chat, appName(if (kind == "messages") rest.substringBefore(':') else kind))
    }
}

private fun looksLikeId(text: String) = text.length > 14 || text.count { it.isDigit() } >= 4 || Regex("^[a-z]+_[0-9a-f]+$", RegexOption.IGNORE_CASE).matches(text)

private fun appName(key: String) = when (key.lowercase()) {
    "whatsapp" -> "WhatsApp"; "line" -> "LINE"; "telegram" -> "Telegram"; "messenger" -> "Messenger"; "signal" -> "Signal"
    "instagram" -> "Instagram"; "wechat" -> "WeChat"; "slack" -> "Slack"; "discord" -> "Discord"; "teams" -> "Teams"
    else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** "Decide by 18:00" (today) or "Decide by Thu" for a card that runs out; null when it does not. */
@Composable
private fun dueText(expiresAtMs: Long?): String? {
    val at = expiresAtMs?.takeIf { it > System.currentTimeMillis() } ?: return null
    val context = LocalContext.current
    val sameDay = DateUtils.isToday(at)
    val text = DateUtils.formatDateTime(context, at, if (sameDay) DateUtils.FORMAT_SHOW_TIME else DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
    return stringResource(R.string.decision_due, text)
}

@Composable
private fun ago(at: Long): String = DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/**
 * One suggestion waiting for the user, laid out as a decision: what kind and when, the question, why now, the message
 * itself for a draft, what confirming does (numbered), what it needs from them, what it rests on, then the choice. Calm
 * ink; amber only where something needs the user's own word or clashes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DecisionCard(
    card: OpportunityCard,
    agentName: String,
    onConfirm: () -> Unit,
    onNotNow: () -> Unit,
    onLess: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val e = explained(card)
    val urgent = card.kind == OpportunityCard.Kind.CONFLICT_ALERT || card.urgency >= .8
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier.fillMaxWidth().inkSurface(shape, CardStyle.Ceramic)
            .then(if (urgent) Modifier.border(1.dp, P.amber.copy(alpha = .45f), shape) else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DecisionHead(kindIcon(card.kind), kindLabel(card.kind), "$agentName · ${ago(card.updatedAtMs ?: card.createdAtMs)}", urgent, dueText(card.expiresAtMs))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(card.title, Modifier.semantics { heading() }, color = P.ink, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            if (e.why.isNotBlank()) Text(e.why, color = P.sub, fontSize = 15.sp, lineHeight = 22.sp)
        }
        e.draftText?.let { MessagePreview(e.draftTo, it) }
        if (e.steps.isNotEmpty()) StepsBlock(stringResource(R.string.decision_if_confirm, agentName), e.steps)
        if (e.needs.isNotBlank()) NeedsLine(e.needs)
        if (e.ifIgnored.isNotBlank() || e.sources.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e.ifIgnored.isNotBlank()) QuietLine(PIcons.info, stringResource(R.string.decision_if_ignored_line, e.ifIgnored))
            if (e.sources.isNotEmpty()) Sources(e.sources)
        }
        card.error?.let { Text(it, color = P.danger, fontSize = 13.sp, lineHeight = 18.sp) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The agent writes the button's words; a long label gets the whole width instead of being cut off.
            if (wide(card.preparedAction.label)) {
                PButton(card.preparedAction.label, onConfirm, Modifier.fillMaxWidth(), icon = PIcons.check)
                PButton(stringResource(R.string.decision_not_now), onNotNow, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PButton(card.preparedAction.label, onConfirm, Modifier.weight(1f), icon = PIcons.check)
                PButton(stringResource(R.string.decision_not_now), onNotNow, style = ButtonStyle.Ghost)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuietLine(PIcons.lock, e.reassurance, Modifier.weight(1f))
                onLess?.let {
                    Text(stringResource(R.string.decision_fewer), Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = it).padding(horizontal = 6.dp, vertical = 4.dp),
                        color = P.sub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/**
 * A calendar clash from the brief, as a decision: what collides and by how much, what the agent does if asked (it only
 * prepares; nothing is sent or moved without the user), and the button that asks it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConflictDecision(conflict: ConflictAlert, agentName: String, onAsk: (() -> Unit)?, modifier: Modifier = Modifier) {
    val possible = conflict.severity == ConflictJudge.POSSIBLE
    val tone = if (possible) P.sub else P.amber
    val e = conflict.explain
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier.fillMaxWidth().inkSurface(shape, CardStyle.Ceramic).border(1.dp, tone.copy(alpha = .4f), shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DecisionHead(PIcons.alert, stringResource(if (possible) R.string.decision_kind_maybe_clash else R.string.opp_kind_conflict), agentName, !possible, null)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(conflict.title.ifBlank { conflict.description }, Modifier.semantics { heading() }, color = P.ink, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            if (conflict.title.isNotBlank()) Text(e?.why?.ifBlank { null } ?: conflict.description, color = P.sub, fontSize = 15.sp, lineHeight = 22.sp)
        }
        e?.steps?.takeIf { it.isNotEmpty() && onAsk != null }?.let { StepsBlock(stringResource(R.string.decision_if_ask, agentName), it) }
        e?.needs?.takeIf { it.isNotBlank() && onAsk != null }?.let { NeedsLine(it) }
        if (e != null && (e.ifIgnored.isNotBlank() || e.sources.isNotEmpty())) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e.ifIgnored.isNotBlank()) QuietLine(PIcons.info, stringResource(R.string.decision_if_ignored_line, e.ifIgnored))
            if (e.sources.isNotEmpty()) Sources(e.sources)
        }
        if (onAsk != null) conflict.resolutionOptions.firstOrNull()?.let { option ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PButton(option.label, onAsk, Modifier.fillMaxWidth(), style = if (possible) ButtonStyle.Alt else ButtonStyle.Primary, icon = PIcons.message)
                QuietLine(PIcons.lock, stringResource(R.string.decision_safe_conflict))
            }
        }
    }
}

/** Whether a button label needs a row of its own: about 20 Latin letters, a CJK character counting as two. */
private fun wide(label: String) = label.sumOf { if (it.code >= 0x2E80) 2 else 1 } > 20

/** The kind's mark, its name and who/when, and the deadline when it has one. */
@Composable
private fun DecisionHead(icon: IconSpec, kind: String, byline: String, attention: Boolean, due: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(if (attention) P.amber.copy(alpha = .14f) else P.highlight), contentAlignment = Alignment.Center) {
            PIcon(icon, size = 16.dp, tint = if (attention) P.amber else P.ink)
        }
        Column(Modifier.weight(1f)) {
            Text(kind.uppercase(), color = if (attention) P.amber else P.sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .8.sp, maxLines = 1)
            Text(byline, color = P.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        due?.let { StatusChip(it, if (attention) P.amber else P.sub) }
    }
}

/** "If you confirm, Mira will": the steps in order, each with its number in a ring, joined by a thin rail. */
@Composable
internal fun StepsBlock(title: String, steps: List<String>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.highlight).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = P.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        steps.forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(22.dp).clip(CircleShape).border(1.25.dp, P.ink.copy(alpha = .55f), CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = P.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = P.tabular))
                }
                Text(step, Modifier.weight(1f).padding(top = 1.dp), color = P.ink, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

/** What only the user can give: their own word, in amber, with a hand. */
@Composable
private fun NeedsLine(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.amber.copy(alpha = .1f)).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        PIcon(PIcons.hand, size = 16.dp, tint = P.amber)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.decision_needs), color = P.amber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp)
            Text(text, color = P.ink, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun QuietLine(icon: IconSpec, text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        PIcon(icon, Modifier.padding(top = 2.dp), size = 13.dp, tint = P.muted)
        Text(text, color = P.sub, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sources(sources: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.decision_based_on), color = P.muted, fontSize = 12.sp, modifier = Modifier.padding(end = 2.dp))
        sources.forEach { source ->
            Text(source, Modifier.clip(CircleShape).border(1.dp, P.line2, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                color = P.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A message draft as it would be sent: who it goes to, then the words in the user's own ink bubble. */
@Composable
internal fun MessagePreview(to: String?, text: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        to?.let { Text(stringResource(R.string.card_to, it), color = P.sub, fontSize = 12.sp) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(text, Modifier.padding(start = 28.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(P.ink).padding(horizontal = 14.dp, vertical = 10.dp),
                color = inkOnPrimary, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

/**
 * Confirming a suggestion: the same account as the card, then one field. A message draft is the message itself (edit it
 * and the edited words are what get prepared); anything else gets an optional field for what the agent should know.
 * The button says what happens; the line under it says what does not happen yet.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ConfirmSheet(card: OpportunityCard, agentName: String, onDismiss: () -> Unit, onConfirm: suspend (payload: String?, note: String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val e = explained(card)
    val draft = remember(card.id) { OpportunityInput.from(card.preparedAction)?.takeIf { it.message } }
    var text by remember(card.id) { mutableStateOf(draft?.text.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = P.base) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.decision_confirm_eyebrow, agentName).uppercase(), color = P.sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .8.sp)
                Text(card.title, Modifier.semantics { heading() }, color = P.ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                if (e.why.isNotBlank()) Text(e.why, color = P.sub, fontSize = 14.sp, lineHeight = 20.sp)
            }
            if (draft != null) {
                draft.context.firstOrNull { (field, _) -> field in setOf("to", "recipient", "chat") }?.let { (_, to) -> Text(stringResource(R.string.card_to, to), color = P.sub, fontSize = 13.sp) }
                PField(text, { text = it; error = null }, Modifier.fillMaxWidth().heightIn(min = 112.dp), label = stringResource(R.string.decision_message_label))
                Text(stringResource(R.string.decision_message_hint), color = P.sub, fontSize = 12.sp, lineHeight = 17.sp)
            } else {
                if (e.steps.isNotEmpty()) StepsBlock(stringResource(R.string.decision_if_confirm, agentName), e.steps)
                if (e.needs.isNotBlank()) NeedsLine(e.needs)
                PField(text, { text = it; error = null }, Modifier.fillMaxWidth().heightIn(min = 88.dp),
                    label = stringResource(R.string.decision_note_label), placeholder = stringResource(R.string.decision_note_hint))
            }
            error?.let { Callout(it, Tone.Danger) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val confirm: () -> Unit = {
                    busy = true
                    scope.launch {
                        try {
                            if (draft != null) onConfirm(draft.payload(text), null) else onConfirm(null, text.takeIf { it.isNotBlank() })
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "That did not work" }
                        finally { busy = false }
                    }
                }
                val ready = !busy && (draft == null || text.isNotBlank()) && text.length <= 4_000
                if (wide(card.preparedAction.label)) {
                    PButton(card.preparedAction.label, confirm, Modifier.fillMaxWidth(), enabled = ready, icon = PIcons.check)
                    PButton(stringResource(R.string.action_cancel), onDismiss, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PButton(card.preparedAction.label, confirm, Modifier.weight(1f), enabled = ready, icon = PIcons.check)
                    PButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Ghost)
                }
                QuietLine(PIcons.lock, e.reassurance)
            }
        }
    }
}
