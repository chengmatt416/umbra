package app.umbra.agent.ui.agents

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.agents.proactive.BriefItem
import app.umbra.agent.agents.proactive.ConflictAlert
import app.umbra.agent.agents.proactive.BriefTask
import app.umbra.agent.agents.proactive.DailyBrief
import app.umbra.agent.agents.proactive.OpportunityCard
import app.umbra.agent.agents.proactive.PreparedNextStep
import app.umbra.agent.ui.porcelain.*

/*
 * The proactive cards (spec §4.2, §5.2): a suggestion and a daily brief each have their own designed card, used the
 * same way on Home, on the Proactivity page and anywhere else they appear. Ink rules: paper and ink, colour only where
 * it means something (amber for a conflict or what needs you, green for done).
 */

private val SUN = IconSpec(listOf(
    "M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z" to false,
    "M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" to false,
))

internal fun kindIcon(kind: OpportunityCard.Kind) = when (kind) {
    OpportunityCard.Kind.CONFLICT_ALERT -> PIcons.alert
    OpportunityCard.Kind.FOLLOW_UP -> PIcons.message
    OpportunityCard.Kind.TASK_DISCOVERY -> PIcons.checks
    OpportunityCard.Kind.PRICE_OR_MONITOR -> PIcons.eye
    OpportunityCard.Kind.GOAL_SUGGESTION -> PIcons.pin
}

@Composable
internal fun kindLabel(kind: OpportunityCard.Kind) = stringResource(when (kind) {
    OpportunityCard.Kind.TASK_DISCOVERY -> R.string.opp_kind_task; OpportunityCard.Kind.CONFLICT_ALERT -> R.string.opp_kind_conflict
    OpportunityCard.Kind.FOLLOW_UP -> R.string.opp_kind_follow_up; OpportunityCard.Kind.PRICE_OR_MONITOR -> R.string.opp_kind_monitor
    OpportunityCard.Kind.GOAL_SUGGESTION -> R.string.opp_kind_goal
})

@Composable
private fun relative(at: Long): String = DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/**
 * One suggestion in a list (Home, recently handled): its kind and age, the question, one line of why, and its actions.
 * Deciding happens on the full [DecisionCard] (Briefing) or in the [ConfirmSheet] a tap opens.
 */
@Composable
internal fun SuggestionCard(
    card: OpportunityCard, agentName: String? = null, compact: Boolean = false,
    onReview: () -> Unit, onDismiss: () -> Unit, onLess: (() -> Unit)? = null, onOpenResult: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val urgent = card.kind == OpportunityCard.Kind.CONFLICT_ALERT || card.urgency >= .8
    val pending = card.state == OpportunityCard.State.PENDING
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.fillMaxWidth().inkSurface(shape, CardStyle.Ceramic).padding(16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(if (urgent) P.amber.copy(alpha = .14f) else P.highlight), contentAlignment = Alignment.Center) {
                PIcon(kindIcon(card.kind), size = 16.dp, tint = if (urgent) P.amber else P.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(kindLabel(card.kind).uppercase(), color = if (urgent) P.amber else P.sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .8.sp)
                Text(listOfNotNull(agentName, relative(card.createdAtMs)).joinToString(" · "), color = P.muted, fontSize = 12.sp, maxLines = 1)
            }
            if (!pending) StatusChip(stringResource(when (card.state) {
                OpportunityCard.State.ACCEPTED -> R.string.card_state_accepted; OpportunityCard.State.DISMISSED -> R.string.card_state_dismissed
                else -> R.string.card_state_expired
            }), if (card.state == OpportunityCard.State.ACCEPTED) P.good else P.sub)
            else if (urgent) StatusChip(stringResource(R.string.proactive_needs_attention), P.amber)
        }
        Text(card.title, color = P.ink, fontSize = if (compact) 16.sp else 18.sp, lineHeight = if (compact) 22.sp else 24.sp, fontWeight = FontWeight.Medium,
            maxLines = if (compact) 2 else 4, overflow = TextOverflow.Ellipsis)
        // Why it matters, in the agent's words (never the prepared step's data).
        val why = (card.explain?.why?.takeIf { it.isNotBlank() } ?: card.summary).trim()
        if (why.isNotEmpty()) Text(why, color = P.sub, fontSize = 14.sp, lineHeight = 20.sp, maxLines = if (compact) 2 else 6, overflow = TextOverflow.Ellipsis)
        if (!compact) card.error?.let { Text(it, color = P.danger, fontSize = 13.sp) }
        if (pending) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PButton(stringResource(R.string.decision_review), onReview, Modifier.weight(1f, fill = false), tiny = compact, icon = if (compact) null else PIcons.arrowRight)
            PButton(stringResource(R.string.decision_not_now), onDismiss, tiny = compact, style = ButtonStyle.Ghost)
        }
        if (pending && !compact && onLess != null) Text(stringResource(R.string.proactive_less), Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onLess).padding(vertical = 4.dp),
            color = P.sub, fontSize = 13.sp)
        onOpenResult?.let { PButton(stringResource(R.string.proactive_open_result), it, style = ButtonStyle.Alt, tiny = true) }
    }
}

private val TIME = Regex("""^\d{1,2}:\d{2}$""")

@Composable
private fun briefTaskCounts(tasks: List<BriefTask>): List<Pair<String, String>> = buildList {
    for ((state, label) in listOf("completed" to R.string.proactive_tasks_done, "failed" to R.string.proactive_tasks_failed,
        "running" to R.string.proactive_tasks_running, "waiting" to R.string.proactive_tasks_waiting,
        "paused" to R.string.proactive_tasks_paused, "stopped" to R.string.proactive_tasks_stopped)) {
        val count = tasks.count { it.state == state }
        if (count > 0) add(state to stringResource(label, count))
    }
}

/** The brief's dark head: kind, date, the sentence, and what is inside as counts. Home shows this part alone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BriefHead(brief: DailyBrief, agentName: String?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val morning = brief.kind == DailyBrief.Kind.MORNING
    val shape = RoundedCornerShape(22.dp)
    val onDark = P.codeText
    Column(modifier.fillMaxWidth().inkSurface(shape, CardStyle.Dark).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PIcon(if (morning) SUN else PIcons.moon, size = 16.dp, tint = onDark)
            Text((listOfNotNull(agentName) + stringResource(if (morning) R.string.proactive_morning else R.string.proactive_evening)).joinToString(" · ").uppercase(),
                Modifier.weight(1f), color = onDark.copy(alpha = .72f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, maxLines = 1)
            Text(runCatching { java.time.LocalDate.parse(brief.date).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")) }.getOrDefault(brief.date),
                color = onDark.copy(alpha = .72f), fontSize = 12.sp)
            trailing?.invoke()
        }
        Text(brief.headline, color = onDark, fontFamily = P.serif, fontSize = 22.sp, lineHeight = 28.sp)
        val taskCounts = briefTaskCounts(brief.tasks)
        val stats = buildList {
            if (brief.scheduleHighlights.isNotEmpty()) add(Triple(PIcons.clock, stringResource(R.string.home_brief_events, brief.scheduleHighlights.size), false))
            if (brief.conflictsDetected.isNotEmpty()) add(Triple(PIcons.alert, stringResource(R.string.home_brief_conflicts, brief.conflictsDetected.size), true))
            if (brief.attentionNeeded.isNotEmpty()) add(Triple(PIcons.spark, stringResource(R.string.card_brief_decisions, brief.attentionNeeded.size), false))
            taskCounts.forEach { (state, label) ->
                add(Triple(when (state) { "completed" -> PIcons.check; "failed" -> PIcons.alert; else -> PIcons.clock }, label, state == "failed" || state == "waiting"))
            }
        }
        if (stats.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            stats.forEach { (icon, label, warn) ->
                Row(Modifier.clip(CircleShape).background(if (warn) P.amber.copy(alpha = .22f) else onDark.copy(alpha = .1f)).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    PIcon(icon, size = 12.dp, tint = if (warn) P.amber else onDark)
                    Text(label, color = onDark, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

/** The whole brief keeps session states visible alongside the day's decisions. */
@Composable
internal fun BriefCard(brief: DailyBrief, agentName: String? = null, onSuggestion: (OpportunityCard) -> Unit, onOpenSession: (String) -> Unit, modifier: Modifier = Modifier,
    onConflict: ((ConflictAlert) -> Unit)? = null) {
    val shape = RoundedCornerShape(24.dp)
    Column(modifier.fillMaxWidth().inkSurface(shape, CardStyle.Ceramic)) {
        BriefHead(brief, agentName)
        BriefBody(brief, agentName, onSuggestion, onOpenSession, onConflict, conflictsAbove = false, unroll = false)
    }
}

/**
 * A brief that unrolls (Home, Briefing): the dark head is always there; a tap, or dragging it down, unrolls the day
 * below it, each section sliding down into place one after the other; a tap or dragging up rolls it back. The head
 * follows the finger a little while it is dragged and springs back.
 */
@Composable
internal fun ExpandableBrief(
    brief: DailyBrief, agentName: String?, expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    onSuggestion: (OpportunityCard) -> Unit, onOpenSession: (String) -> Unit, modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    /** Asks the agent to resolve a conflict; null shows conflicts without the action. */
    onConflict: ((ConflictAlert) -> Unit)? = null,
    /** The page already shows this brief's conflicts as decisions above it: here they are only counted. */
    conflictsAbove: Boolean = false,
) {
    val shape = RoundedCornerShape(24.dp)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val threshold = with(density) { 48.dp.toPx() }
    val pull = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val chevron by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f, Motion.settle(), label = "brief-chevron")
    Column(modifier.fillMaxWidth().clip(shape).then(if (expanded) Modifier.inkSurface(shape, CardStyle.Ceramic) else Modifier)) {
        BriefHead(brief, agentName,
            Modifier.graphicsLayer { translationY = pull.value }.pointerInput(expanded) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { change, dy ->
                        change.consume(); total += dy
                        // Rubber band: the head gives a little toward the drag, less the further it goes.
                        scope.launch { pull.snapTo(rubber(total, threshold)) }
                    },
                    onDragEnd = {
                        if (!expanded && total > threshold) onExpandedChange(true)
                        else if (expanded && total < -threshold) onExpandedChange(false)
                        scope.launch { pull.animateTo(0f, Motion.release()) }
                    },
                    onDragCancel = { scope.launch { pull.animateTo(0f, Motion.release()) } },
                )
            },
            onClick = { onExpandedChange(!expanded) },
            trailing = { PIcon(PIcons.chevronDown, Modifier.graphicsLayer { rotationZ = chevron }, size = 16.dp, tint = P.codeText.copy(alpha = .8f)) })
        androidx.compose.animation.AnimatedVisibility(expanded,
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = .86f, stiffness = 340f), expandFrom = Alignment.Top) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
            exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 520f), shrinkTowards = Alignment.Top) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) {
            Column {
                BriefBody(brief, agentName, onSuggestion, onOpenSession, onConflict, conflictsAbove, unroll = true)
                footer?.let { Box(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) { it() } }
            }
        }
    }
}

private fun rubber(distance: Float, limit: Float): Float {
    val sign = if (distance < 0) -1f else 1f
    val d = kotlin.math.abs(distance)
    return sign * limit * 0.6f * (1f - 1f / (d / (limit * 1.6f) + 1f))
}

/** Slides down into place [index] beats after the first section when [on]; otherwise just there. */
private fun Modifier.unrollIn(index: Int, on: Boolean): Modifier = if (!on) this else composed {
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60L * index.coerceIn(0, 8))
        progress.animateTo(1f, spring(dampingRatio = .78f, stiffness = 300f))
    }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * -26.dp.toPx()
    }
}

/** The brief's sections below the head. */
@Composable
private fun BriefBody(brief: DailyBrief, agentName: String?, onSuggestion: (OpportunityCard) -> Unit, onOpenSession: (String) -> Unit,
    onConflict: ((ConflictAlert) -> Unit)?, conflictsAbove: Boolean, unroll: Boolean) {
    var section = 0
    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (brief.scheduleHighlights.isNotEmpty()) Column(Modifier.unrollIn(section++, unroll), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            SectionLabel(stringResource(R.string.proactive_brief_schedule))
            Spacer(Modifier.height(8.dp))
            brief.scheduleHighlights.forEachIndexed { i, item -> TimelineRow(item, first = i == 0, last = i == brief.scheduleHighlights.lastIndex) }
        }
        if (brief.conflictsDetected.isNotEmpty()) Column(Modifier.unrollIn(section++, unroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.proactive_brief_conflicts), P.amber)
            if (conflictsAbove) Text(stringResource(R.string.briefing_conflicts_above, brief.conflictsDetected.size), color = P.sub, fontSize = 13.sp, lineHeight = 19.sp)
            else brief.conflictsDetected.forEach { conflict ->
                ConflictDecision(conflict, agentName ?: stringResource(R.string.ag_default_name), onConflict?.let { ask -> { ask(conflict) } })
            }
        }
        if (brief.attentionNeeded.isNotEmpty()) Column(Modifier.unrollIn(section++, unroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.card_brief_needs_you))
            brief.attentionNeeded.forEach { card ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.highlight).clickable(role = Role.Button) { onSuggestion(card) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PIcon(kindIcon(card.kind), size = 16.dp, tint = if (card.kind == OpportunityCard.Kind.CONFLICT_ALERT) P.amber else P.ink)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(card.title, color = P.ink, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        (card.explain?.why?.takeIf { it.isNotBlank() } ?: card.summary).takeIf { it.isNotBlank() }?.let {
                            Text(it, color = P.sub, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    PIcon(PIcons.arrowRight, size = 14.dp, tint = P.sub)
                }
            }
        }
        if (brief.tasks.isNotEmpty()) Column(Modifier.unrollIn(section++, unroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(stringResource(R.string.proactive_brief_tasks))
            Text(briefTaskCounts(brief.tasks).joinToString(" · ") { it.second }, Modifier.horizontalScroll(rememberScrollState()), color = P.sub, fontSize = 12.sp, maxLines = 1)
            brief.tasks.forEach { task ->
                val sessionId = task.linkedSessionId
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(P.highlight)
                    .then(if (sessionId != null) Modifier.clickable(role = Role.Button) { onOpenSession(sessionId) } else Modifier).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusChip(stringResource(when (task.state) {
                        "completed" -> R.string.state_done; "failed" -> R.string.state_failed
                        "running" -> R.string.state_working; "waiting" -> R.string.proactive_task_waiting
                        "paused" -> R.string.state_paused; else -> R.string.state_stopped
                    }), when (task.state) { "completed" -> P.good; "failed" -> P.danger; "waiting" -> P.amber; else -> P.sub })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(task.title, color = P.ink, fontSize = 14.sp, lineHeight = 20.sp)
                        if (task.detail.isNotBlank()) Text(task.detail, color = P.sub, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    if (sessionId != null) PIcon(PIcons.arrowRight, size = 14.dp, tint = P.sub)
                }
            }
        }
        if (brief.lowPriorityDigest.isNotBlank()) Column(Modifier.unrollIn(section++, unroll).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.highlight).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(stringResource(R.string.proactive_brief_digest))
            Text(brief.lowPriorityDigest, color = P.sub, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
        Text(stringResource(R.string.card_brief_generated, DateUtils.formatDateTime(LocalContext.current, brief.generatedAtMs, DateUtils.FORMAT_SHOW_TIME)), color = P.muted, fontSize = 11.sp)
    }
}

/** One event on the day's line: the time in its own column, a dot on a thin rail, then what it is. */
@Composable
private fun TimelineRow(item: BriefItem, first: Boolean, last: Boolean) {
    val time = item.title.takeIf { TIME.matches(it.trim()) }
    val title = if (time != null) item.detail.substringBefore(" · ") else item.title
    val detail = if (time != null) item.detail.substringAfter(" · ", "") else item.detail
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(time ?: "", Modifier.width(46.dp).padding(top = 10.dp), color = P.ink, fontFamily = P.mono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.width(12.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            val rail = P.sub.copy(alpha = .35f)
            Box(Modifier.width(1.5.dp).height(12.dp).background(if (first) Color.Transparent else rail))
            Box(Modifier.size(9.dp).clip(CircleShape).background(P.ink))
            Box(Modifier.width(1.5.dp).weight(1f).background(if (last) Color.Transparent else rail))
        }
        Column(Modifier.weight(1f).padding(top = 8.dp, bottom = 10.dp)) {
            Text(title, color = P.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (detail.isNotBlank()) Text(detail, color = P.sub, fontSize = 13.sp)
        }
    }
}
