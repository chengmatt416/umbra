package app.umbra.agent.capsule

import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.porcelain.pcHue
import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.SpeechRecognizer
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.data.UiMode
import app.umbra.agent.ui.inbox.ApprovalCountdown
import app.umbra.agent.ui.localizedDescription
import app.umbra.core.safety.ApprovalSurface
import app.umbra.core.safety.ApproveGesture
import app.umbra.core.safety.approveGesture
import app.umbra.agent.data.ContextChip
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.IconSpec
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Theme
import app.umbra.agent.ui.porcelain.motionAllowed
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.control.EmergencyLevel
import app.umbra.core.control.LeaseState
import app.umbra.core.control.Principal
import app.umbra.core.model.Point
import app.umbra.core.model.observation.Lasso
import app.umbra.core.model.observation.UiSnapshot
import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.safety.RiskLevel
import app.umbra.core.session.SessionState
import app.umbra.core.session.Trails
import kotlinx.coroutines.delay
import kotlin.math.abs

/** The capsule is black where it meets the camera, so the camera reads as part of it. */
private val Ink get() = Color.Black
/** The expanded card: the capsule's dark glaze. */
private val Card get() = Brush.linearGradient(listOf(pcHue(0xFF18211C), pcHue(0xFF101612)))
private val Line get() = pcHue(0xFF2A342E)
internal val Label get() = pcHue(0xFFE9F3E6)
internal val Small get() = pcHue(0xFFA4B9A4)
internal val RowText get() = pcHue(0xFFD1E0D2)
internal val Section get() = pcHue(0xFFA5C4A7)
internal val Attention get() = pcHue(0xFFF9E5C3)
private val Sage get() = pcHue(0xFF9DBBA1)
private val Clay get() = pcHue(0xFFE7A493)
private val LIVE = setOf(SessionState.RUNNING, SessionState.WAITING_APPROVAL, SessionState.PAUSED)

/**
 * The capsule: collapsed, the companions and a short label in the zone left of the camera and the time (or a count)
 * right of it; tucked, only the companion; expanded, the card under the camera (docs/design/redesign-2026-10.md §5).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun CapsuleView(capsule: Capsule, container: AppContainer) {
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsState(emptyList())
    val approvals by container.approvals.pending.collectAsState()
    val questions by container.questions.pending.collectAsState()
    val focused by container.ui.focusedSession.collectAsState()
    val emergency by container.emergency.state.collectAsState()
    val expanded by capsule.expanded.collectAsState()
    val tucked by capsule.tucked.collectAsState()
    val dismissed by capsule.dismissedFailures.collectAsState()
    val hole by capsule.hole.collectAsState()
    val activity by capsule.watch.activity.collectAsState()
    val finished by capsule.watch.finished.collectAsState()
    val context = LocalContext.current

    val trail = remember(sessions, focused) { Trails.sessions(sessions, focused) }
    val current = trail.current
    val steps = remember(current) { Trails.steps(current) }
    val locked = emergency.level == EmergencyLevel.LOCKDOWN
    val failed = sessions.filter { it.state == SessionState.FAILED && it.id !in dismissed && System.currentTimeMillis() - it.updatedAtMs < 10 * 60_000 }.maxByOrNull { it.updatedAtMs }
    val needsYou = approvals.isNotEmpty() || questions.isNotEmpty()
    val atRest = current == null && !needsYou && !locked && failed == null
    val nowMs = System.currentTimeMillis()
    val ticking by produceState(nowMs, expanded, current?.id) { while (expanded || current != null) { delay(1_000); value = System.currentTimeMillis() } }
    val clockNow = maxOf(ticking, nowMs)
    val waitingOn = remember(approvals, questions) { approvals.map { it.sessionId }.toSet() + questions.map { it.sessionId } }
    val crew = Crews.of(sessions, activity, waitingOn, finished, dismissed, nowMs)
    val headMood = current?.let { Crews.moodOf(it, activity[it.id], it.id in waitingOn, null, false, nowMs) } ?: Mood.IDLE
    val moving = remember(sessions, approvals, expanded) { motionAllowed(context) } && Theme.current.motion
    val clock = rememberCompanionClock(crew.members.map { it.mood }.toSet() + headMood, moving)
    val tick: () -> Long = remember(clock) { { clock.value } }
    val done = crew.members.firstOrNull { it.mood == Mood.DONE }
    LaunchedEffect(failed?.id) { if (failed != null && !container.ui.appVisible.value) { capsule.tucked.value = false; capsule.expanded.value = true } }

    val label = when {
        locked -> stringResource(R.string.cap_lockdown)
        approvals.isNotEmpty() -> stringResource(R.string.cap_needs_decision)
        questions.isNotEmpty() -> stringResource(R.string.cap_question)
        failed != null && current == null -> stringResource(R.string.cap_stopped_error)
        done != null && current == null -> stringResource(R.string.capc_done_label, done.title)
        current != null -> steps.current?.text ?: current.title
        else -> stringResource(R.string.cap_at_rest)
    }
    val trailing = when {
        needsYou -> "${approvals.size + questions.size}"
        current != null -> elapsedText(clockNow - (activity[current.id]?.runStartedAtMs ?: current.createdAtMs))
        crew.overflow > 0 -> "+${crew.overflow}"
        else -> null
    }
    val crewSaid = crewDescription(crew)
    val capsuleDescription = stringResource(R.string.cap_description, label).let { if (crewSaid.isEmpty()) it else stringResource(R.string.capc_description_more, it, crewSaid) }

    if (expanded) {
        CapsuleCard(capsule, container, current, headMood, steps, activity, trail, crew, sessions, clockNow, tick, moving, locked, failed, approvals, questions)
        return
    }
    CollapsedPill(capsule, hole, tucked, atRest, label, trailing, needsYou, crew, headMood, tick, moving, capsuleDescription)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollapsedPill(
    capsule: Capsule,
    hole: Capsule.Hole?,
    tucked: Boolean,
    atRest: Boolean,
    label: String,
    trailing: String?,
    needsYou: Boolean,
    crew: Crew,
    headMood: Mood,
    tick: () -> Long,
    moving: Boolean,
    description: String,
) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(50)
    val selectLabel = stringResource(R.string.cap_select_screen)
    val expandLabel = stringResource(R.string.cap_expand)
    val height = hole?.let { with(density) { it.heightPx.toDp() } } ?: 34.dp
    val small = tucked || atRest
    Row(
        Modifier
            .clip(shape).background(Ink).animateContentSize(spring(dampingRatio = 0.82f, stiffness = 520f))
            .pointerInput(hole, tucked) {
                // Toward the camera tucks it (the task goes on); outward brings it back; down expands it.
                var total = Offset.Zero
                var startX = 0f
                detectDragGestures(
                    onDragStart = { at -> total = Offset.Zero; startX = at.x },
                    onDragEnd = {
                        val threshold = 22.dp.toPx()
                        if (total.y > threshold && abs(total.y) > abs(total.x)) {
                            capsule.tucked.value = false
                            capsule.expanded.value = true
                        } else if (abs(total.x) > threshold) {
                            val fromLeft = startX < size.width / 2f
                            val towardCentre = (fromLeft && total.x > 0) || (!fromLeft && total.x < 0)
                            capsule.tucked.value = towardCentre
                            capsule.haptic(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
                        }
                    },
                ) { change, drag -> change.consume(); total += drag }
            }
            .combinedClickable(onClickLabel = expandLabel, onLongClickLabel = selectLabel, onLongClick = { capsule.startSelection() }, onClick = { capsule.tap() })
            .semantics { contentDescription = description }
            .heightIn(min = height),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val wrap = hole
        if (wrap != null && wrap.centered) {
            val zone = with(density) { wrap.zonePx.toDp() }
            val gap = with(density) { wrap.gapPx.toDp() }
            val side = if (small) 26.dp else zone
            Row(Modifier.width(side).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tucked || atRest) CompanionGlyph(if (atRest) Mood.IDLE else headMood, tick, moving, size = 18.dp)
                else CrewRow(crew, tick, moving)
                val labelRoom = zone - 10.dp - 6.dp - 18.dp - crewExtraWidth(crew)
                if (!small && labelRoom >= 44.dp) Text(label, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (needsYou) Attention else Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.width(gap))
            Row(Modifier.width(side).padding(end = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                when {
                    tucked && !atRest -> Box(Modifier.size(6.dp).clip(CircleShape).background(Sage))
                    !small && trailing != null -> Text(trailing, fontSize = 12.sp, color = if (needsYou) Attention else Small, maxLines = 1, style = TextStyle(fontFeatureSettings = P.tabular))
                }
            }
        } else {
            // A corner camera: the camera sits in a gap at one end, the content on the side with more room.
            val gap = wrap?.let { with(density) { it.gapPx.toDp() } }
            Box(Modifier.width(if (gap != null && wrap.contentLeft == false) gap else 12.dp))
            if (tucked || atRest) CompanionGlyph(if (atRest) Mood.IDLE else headMood, tick, moving) else CrewRow(crew, tick, moving)
            val room = (wrap?.let { with(density) { it.labelMaxPx.toDp() } }?.coerceAtMost(180.dp) ?: 180.dp) - crewExtraWidth(crew)
            if (!small && room >= 40.dp) {
                Box(Modifier.width(8.dp))
                Text(label, Modifier.widthIn(max = room), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (needsYou) Attention else Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!small && trailing != null) Text(trailing, Modifier.padding(start = 8.dp), fontSize = 12.sp, color = Small, maxLines = 1)
            Box(Modifier.width(if (gap != null && wrap.contentLeft) gap + 6.dp else 12.dp))
        }
    }
}

/**
 * The card the capsule expands into, under the camera: the task (Previous / Current / Next), what it waits for, a
 * field to add instructions and its controls at the bottom, nearest the thumb. At rest it is the Smart Composer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapsuleCard(
    capsule: Capsule,
    container: AppContainer,
    current: app.umbra.core.session.Session?,
    headMood: Mood,
    steps: app.umbra.core.session.StepTrail,
    activity: Map<String, SessionActivity>,
    trail: app.umbra.core.session.SessionTrail,
    crew: Crew,
    sessions: List<app.umbra.core.session.Session>,
    clockNow: Long,
    tick: () -> Long,
    moving: Boolean,
    locked: Boolean,
    failed: app.umbra.core.session.Session?,
    approvals: List<app.umbra.core.safety.ApprovalRequest>,
    questions: List<app.umbra.core.orchestrator.QuestionBroker.Pending>,
) {
    val density = LocalDensity.current
    val emergency by container.emergency.state.collectAsState()
    val leases by container.inputLeases.all.collectAsState()
    val prefs by container.settings.flow.collectAsState()
    val notice by capsule.notice.collectAsState()
    val dismissed by capsule.dismissedFailures.collectAsState()
    val power = prefs.uiMode != UiMode.SIMPLE
    val shape = RoundedCornerShape(32.dp)
    val maxBody = with(density) { (LocalResources.current.displayMetrics.heightPixels * 0.72f).toDp() }
    Column(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 18.dp)
            .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.4f))
            .clip(shape).background(Card).animateContentSize(spring(dampingRatio = 0.86f, stiffness = 480f))
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp),
    ) {
        // The camera's row: the camera sits in the middle of it, between the companion and the collapse button.
        Row(Modifier.fillMaxWidth().height(Capsule.CARD_ROW_DP.dp), verticalAlignment = Alignment.CenterVertically) {
            CompanionGlyph(if (current == null) Mood.IDLE else headMood, tick, moving, size = 22.dp)
            if (current != null) Text(
                elapsedText(clockNow - (activity[current.id]?.runStartedAtMs ?: current.createdAtMs)), Modifier.padding(start = 8.dp),
                fontSize = 13.sp, color = Small, style = TextStyle(fontFeatureSettings = P.tabular),
            )
            Box(Modifier.weight(1f))
            RoundButton(PIcons.chevronDown, stringResource(R.string.cap_collapse), rotate = true) { capsule.expanded.value = false }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = maxBody).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (current != null) {
                Column {
                    Text(current.title, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val at = current.plan.indexOfFirst { it.status == "active" }
                    if (at >= 0) Text(stringResource(R.string.r_home_step, at + 1, current.plan.size), fontSize = 12.5.sp, color = Small)
                }
                if (power && sessions.count(Crews::isLive) > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sessions.filter(Crews::isLive).forEach { s ->
                            val on = s.id == current.id
                            Row(
                                Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Sage else Line).clickable { container.ui.focus(s.id) }.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                CompanionGlyph(crew.moodOf(s.id) ?: Mood.IDLE, tick, moving, size = 16.dp)
                                Text(s.title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = if (on) pcHue(0xFF26372F) else Label, maxLines = 1, modifier = Modifier.widthIn(max = 120.dp), overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Line.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    CrewSteps(current, activity[current.id], headMood, steps, clockNow, tick, moving)
                }
            }
            if (locked) {
                Text((stringResource(R.string.cap_lockdown_text) + " " + (emergency.reason ?: "")).trim(), fontSize = 13.sp, color = Attention)
                Row { DarkButton(stringResource(R.string.dest_emergency), null) { capsule.open("emergency", null) } }
            }
            approvals.firstOrNull()?.let { request ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Line.copy(alpha = 0.6f)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(request.description, fontSize = 14.sp, color = Attention, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    Text(request.assessment.localizedDescription(), fontSize = 12.sp, color = if (request.assessment.level >= RiskLevel.HIGH) Clay else Small, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    ApprovalCountdown(request, dark = true)
                    if (approvals.size > 1) Text(stringResource(R.string.appr_more_waiting, approvals.size - 1), fontSize = 11.sp, color = Small)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DarkButton(stringResource(R.string.appr_deny), null) { container.approvals.resolve(request.id, ApprovalDecision.Denied("denied from the capsule")) }
                    when (approveGesture(request.assessment.level, ApprovalSurface.CAPSULE)) {
                        ApproveGesture.ONE_TAP -> DarkButton(stringResource(R.string.appr_approve_once), PIcons.check, primary = true) { container.approvals.resolve(request.id, ApprovalDecision.Approved()) }
                        else -> DarkButton(stringResource(R.string.appr_review_inbox), null, primary = true) { capsule.open(null, request.sessionId) }
                    }
                }
            }
            if (questions.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.cap_questions, questions.size, questions.size), Modifier.weight(1f), fontSize = 13.sp, color = Attention)
                Box(Modifier.width(120.dp)) { Row { DarkButton(stringResource(R.string.cap_answer), null) { capsule.open(null, questions.first().sessionId) } } }
            }
            failed?.let { f ->
                Text(stringResource(R.string.cap_failed, f.title) + (f.lastError?.let { ": $it" } ?: ""), fontSize = 13.sp, color = Attention, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DarkButton(stringResource(R.string.cap_open), PIcons.arrowRight) { capsule.open(null, f.id) }
                    DarkButton(stringResource(R.string.cap_dismiss), null) { capsule.dismissedFailures.value = dismissed + f.id }
                }
            }
            if (current == null) RecentTasks(capsule, sessions)
        }
        Box(Modifier.height(12.dp))
        CardField(capsule, container, current?.id)
        if (current != null) {
            val screenLease = leases.values.firstOrNull { it.resourceId == "display:0" }
            val userHolds = screenLease?.owner == Principal.User && screenLease.state == LeaseState.ACTIVE
            // Four buttons share the row in Power: words only, so none is cut short.
            val icons = !power
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkButton(stringResource(R.string.cap_open), if (icons) PIcons.arrowRight else null) { capsule.open(null, current.id) }
                if (current.state == SessionState.PAUSED) DarkButton(stringResource(R.string.cap_resume), if (icons) PIcons.play else null) { capsule.launch { container.supervisor.resume(current.id) } }
                else DarkButton(stringResource(R.string.cap_pause), if (icons) PIcons.pause else null) { capsule.launch { container.supervisor.pause(current.id) } }
                DarkButton(stringResource(R.string.r_stop), if (icons) PIcons.stop else null, tone = Clay) { capsule.launch { container.supervisor.stop(current.id) } }
                if (power) {
                    if (!userHolds) DarkButton(stringResource(R.string.r_task_take_over), null) { container.inputLeases.takeBack("display:0") }
                    else DarkButton(stringResource(R.string.r_task_give_back), null) { container.inputLeases.release("display:0", Principal.User, "returned by the user") }
                }
            }
        }
        notice?.let {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), fontSize = 12.sp, color = Attention, maxLines = 3, overflow = TextOverflow.Ellipsis)
                RoundButton(PIcons.close, stringResource(R.string.cap_dismiss)) { capsule.notice.value = null }
            }
        }
    }
}

/** The last two finished tasks, at rest: one tap opens them in the app. */
@Composable
private fun RecentTasks(capsule: Capsule, sessions: List<app.umbra.core.session.Session>) {
    val recent = sessions.filter { it.state.isTerminal }.sortedByDescending { it.updatedAtMs }.take(2)
    if (recent.isEmpty()) return
    Column {
        recent.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { capsule.open(null, s.id) }.padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PIcon(PIcons.clock, size = 18.dp, tint = Small)
                Text(s.title, Modifier.weight(1f), fontSize = 14.sp, color = Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The card's field: the shared draft (Home's composer shows the same). It takes the keyboard only when tapped; the
 * button beside it selects on the screen, then becomes Send once there is something to send.
 */
@Composable
private fun CardField(capsule: Capsule, container: AppContainer, target: String?) {
    val context = LocalContext.current
    val draft by container.ui.draft.collectAsState()
    val chips by container.ui.draftContext.collectAsState()
    val focused by capsule.cardFocused.collectAsState()
    val busy by capsule.busy.collectAsState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val canListen = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    LaunchedEffect(focused) {
        if (focused) {
            delay(60)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    chips.forEach { chip -> ChipRow(chip) { container.ui.removeContext(chip.id) } }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(Line).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (draft.isEmpty()) Text(stringResource(if (target != null) R.string.r_cap_more else R.string.r_composer_hint), fontSize = 14.5.sp, color = Small, maxLines = 1)
            val messageLabel = stringResource(R.string.cap_message)
            BasicTextField(
                draft, { container.ui.saveDraft(it) },
                Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = messageLabel },
                textStyle = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp, color = Label), cursorBrush = SolidColor(Sage), maxLines = 4,
            )
            if (!focused) Box(Modifier.matchParentSize().clickable(onClickLabel = stringResource(R.string.cap_type)) { capsule.focusCard(true) })
        }
        if (draft.isBlank()) {
            RoundButton(PIcons.target, stringResource(R.string.cap_select_screen), size = 40.dp) { capsule.startSelection() }
            if (canListen) RoundButton(PIcons.mic, stringResource(R.string.cap_speak), size = 40.dp) {
                context.startActivity(Intent(context, VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Sage).clickable(enabled = busy == null, role = Role.Button, onClickLabel = stringResource(R.string.cap_send)) {
                    capsule.send(draft.trim(), container.ui.homeProject.value, target = target)
                },
                contentAlignment = Alignment.Center,
            ) { PIcon(PIcons.send, size = 20.dp, tint = pcHue(0xFF1C2A22)) }
        }
    }
}

/** A button of the card: dark, 48 dp tall, sharing the row with the others. [primary] is the sage one. */
@Composable
private fun RowScope.DarkButton(text: String, icon: IconSpec?, tone: Color = Label, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (primary) Sage else Line).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        val ink = if (primary) pcHue(0xFF1C2A22) else tone
        if (icon != null) PIcon(icon, size = 16.dp, tint = ink)
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RoundButton(icon: IconSpec, description: String, size: Dp = 32.dp, rotate: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (size <= 32.dp) Line else Color.Transparent).clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { PIcon(icon, Modifier.then(if (rotate) Modifier.rotate(180f) else Modifier), size = if (size <= 32.dp) 16.dp else 20.dp, tint = Label) }
}


/**
 * The bottom Quick Composer: the same draft and context as Home and the capsule, the target (new work or a running
 * session), the context chips, Live Context (off unless the user turns it on), the microphone and Send.
 */
@Composable
internal fun QuickComposerView(capsule: Capsule, container: AppContainer, screenHeightPx: Int, navigationInsetPx: Int) {
    val context = LocalContext.current
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsState(emptyList())
    val draft by container.ui.draft.collectAsState()
    val chips by container.ui.draftContext.collectAsState()
    val target by container.ui.composerTarget.collectAsState()
    val settings by container.settings.flow.collectAsState()
    val focused by capsule.composerFocused.collectAsState()
    val busy by capsule.busy.collectAsState()
    val notice by capsule.notice.collectAsState()
    val homeProject by container.ui.homeProject.collectAsState()
    val projects by container.projects.all.collectAsState()
    val live = sessions.filter { it.state in LIVE }
    val targetSession = sessions.firstOrNull { it.id == target }
    val project = homeProject?.let { projects[it] }
    var menu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val canListen = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    LaunchedEffect(focused) {
        if (focused) {
            delay(60)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }

    val shape = RoundedCornerShape(21.dp)
    // An accessibility overlay is not fitted above the navigation bar: while it reaches the bottom of the screen (no
    // keyboard), it keeps clear of the bar itself.
    var atBottom by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Column(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { c -> atBottom = c.positionOnScreen().y + c.size.height >= screenHeightPx - 2 }
            .padding(bottom = if (atBottom) with(density) { navigationInsetPx.toDp() } else 0.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .shadow(14.dp, shape, ambientColor = pcHue(0x33455F49), spotColor = pcHue(0x44455F49)).clip(shape)
            .background(pc(0xFFF7FAF4)).border(1.dp, pc(0xB5FFFFFF), shape).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                PButton(
                    targetSession?.let { stringResource(R.string.cap_to, it.title) } ?: (project?.let { stringResource(R.string.cap_new_task_in, it.name) } ?: stringResource(R.string.cap_new_task)),
                    { menu = true }, style = ButtonStyle.Ghost, tiny = true, icon = if (targetSession != null) PIcons.bot else PIcons.spark,
                )
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(project?.let { stringResource(R.string.cap_new_task_menu_in, it.name) } ?: stringResource(R.string.cap_new_task)) }, onClick = { menu = false; container.ui.composerTarget.value = null })
                    live.forEach { s -> DropdownMenuItem(text = { Text(stringResource(R.string.cap_to, s.title), maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { menu = false; container.ui.composerTarget.value = s.id }) }
                }
            }
            PButton(
                stringResource(if (settings.liveContext) R.string.cap_live_on else R.string.cap_live_off),
                { container.settings.update { it.copy(liveContext = !it.liveContext) } },
                style = if (settings.liveContext) ButtonStyle.Warn else ButtonStyle.Ghost, tiny = true,
            )
            PButton("", { capsule.closeComposer() }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.close, contentDescription = stringResource(R.string.action_close))
        }
        chips.forEach { chip -> ChipRow(chip) { container.ui.removeContext(chip.id) } }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) {
            if (draft.isEmpty()) Text(stringResource(if (targetSession != null) R.string.cap_message_agent else R.string.cap_ask_umbra), fontSize = 15.sp, color = pc(0xFF9AAA98))
            val messageLabel = stringResource(R.string.cap_message)
            BasicTextField(
                draft, { container.ui.saveDraft(it) },
                Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = messageLabel },
                textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = P.ink), cursorBrush = SolidColor(P.accent2), maxLines = 6,
            )
            // The keyboard opens only when the field is tapped (§3.1): until then the window cannot take focus at all.
            if (!focused) Box(Modifier.matchParentSize().clickable(onClickLabel = stringResource(R.string.cap_type)) { capsule.focusComposer(true) })
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PButton("", { capsule.startSelection() }, style = ButtonStyle.Alt, tiny = true, icon = PIcons.target, contentDescription = stringResource(R.string.cap_select_screen))
            if (canListen) PButton("", {
                context.startActivity(Intent(context, VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, style = ButtonStyle.Alt, tiny = true, icon = PIcons.mic, contentDescription = stringResource(R.string.cap_speak))
            Box(Modifier.weight(1f)) { busy?.let { Text(it, fontSize = 12.sp, color = P.sub) } }
            PButton("", { capsule.send(draft.trim(), project?.id) }, tiny = true, icon = PIcons.send, enabled = draft.isNotBlank() && busy == null, contentDescription = stringResource(R.string.cap_send))
        }
        notice?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = P.danger, maxLines = 4, overflow = TextOverflow.Ellipsis) }
    }
}

/** An attached piece of context: its picture, its name, and a way to take it off. */
@Composable
internal fun ChipRow(chip: ContextChip, onRemove: () -> Unit) {
    val thumb = remember(chip.imagePath) {
        chip.imagePath?.let { path -> runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }.getOrNull() }
    }
    Row(
        Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(pc(0xFFE4EDE1)).border(1.dp, pc(0xFFC9D8C6), RoundedCornerShape(12.dp)).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (thumb != null) Image(thumb, null, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        else PIcon(PIcons.target, size = 16.dp, tint = P.accent2)
        Column(Modifier.weight(1f, fill = false)) {
            Text(chip.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.cap_snapshot_not_live), fontSize = 10.sp, color = P.sub)
        }
        PButton("", onRemove, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.close, contentDescription = stringResource(R.string.cap_remove, chip.label))
    }
}


/**
 * Selecting on the screen (after Circle to Search): the screenshot, frozen, a light dim and a glow along the bottom
 * edge; a circle, a tap (the element under it) or a line over text selects, the stroke settles into a rounded box
 * with corner handles and the rest dims. Every control is in the card at the top, by the camera: close, Live,
 * the selection's thumbnail and quick actions, and the field. Sending starts the task and the screen comes back.
 */
@Composable
internal fun SelectionView(capsule: Capsule, container: AppContainer, capture: ScreenContext.Capture, topInsetPx: Int, cameraCenterYPx: Int?, onCancel: () -> Unit) {
    val image = remember(capture) { capture.image.asImageBitmap() }
    val points = remember { mutableStateListOf<Offset>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var region by remember { mutableStateOf<app.umbra.core.model.Rect?>(null) }
    var text by remember { mutableStateOf("") }
    val density = LocalDensity.current
    val prefs by container.settings.flow.collectAsState()
    val focused by capsule.selectionFocused.collectAsState()
    val busy by capsule.busy.collectAsState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val selectionDescription = stringResource(R.string.sel_description)
    LaunchedEffect(focused) {
        if (focused) {
            delay(60)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    val strokeOnScreen = { points.map { Point((it.x + origin.x).toInt(), (it.y + origin.y).toInt()) } }
    // Nothing selected: the question is about the whole screen.
    val wholeScreen = listOf(Point(0, 0), Point(capture.screen.width - 1, 0), Point(capture.screen.width - 1, capture.screen.height - 1), Point(0, capture.screen.height - 1))
    val settle = {
        val stroke = strokeOnScreen()
        region = if (stroke.isEmpty()) null else runCatching { Lasso.select(capture.tree ?: UiSnapshot(), stroke, capture.screen).region }.getOrNull()
    }
    val send = { message: String ->
        if (message.isNotBlank()) capsule.sendSelection(capture, strokeOnScreen().takeIf { it.isNotEmpty() } ?: wholeScreen, message.trim())
    }
    val sage = Sage
    val dim = pcHue(0x330C1A12)
    val dimHard = pcHue(0x730C1A12)
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionOnScreen() }) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { at -> points.clear(); region = null; points.add(at); capsule.focusSelection(false) },
                        onDragEnd = { settle() },
                    ) { change, _ -> points.add(change.position) }
                }
                .pointerInput(Unit) { detectTapGestures { at -> points.clear(); points.add(at); points.add(at + Offset(1f, 1f)); capsule.focusSelection(false); settle() } }
                .semantics { contentDescription = selectionDescription },
        ) {
            drawImage(image, topLeft = Offset(-origin.x, -origin.y))
            val r = region
            if (r == null) {
                drawRect(dim)
                // the glow rests on the bottom edge; the middle stays clear for the circle
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, pcHue(0x59E3B470), pcHue(0xD99DBBA1)), startY = size.height - 220.dp.toPx(), endY = size.height), topLeft = Offset(0f, size.height - 220.dp.toPx()))
                if (points.size >= 2) {
                    val path = Path().apply { moveTo(points[0].x, points[0].y); for (i in 1 until points.size) lineTo(points[i].x, points[i].y) }
                    drawPath(path, sage.copy(alpha = 0.55f), style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, Color.White, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            } else {
                val left = r.left - origin.x
                val top = r.top - origin.y
                val w = r.width.toFloat()
                val h = r.height.toFloat()
                val corner = 24.dp.toPx()
                val box = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(left, top, left + w, top + h, CornerRadius(corner))) }
                clipPath(box, ClipOp.Difference) { drawRect(dimHard) }
                for ((width, alpha) in listOf(14.dp.toPx() to 0.18f, 8.dp.toPx() to 0.3f)) drawPath(box, sage.copy(alpha = alpha), style = Stroke(width))
                drawPath(box, Color.White.copy(alpha = 0.55f), style = Stroke(1.5.dp.toPx()))
                // the corner handles: an L at each corner, following the rounding
                val arm = 26.dp.toPx().coerceAtMost(minOf(w, h) / 2)
                val handle = Path().apply {
                    moveTo(left, top + arm); lineTo(left, top + corner); quadraticTo(left, top, left + corner, top); lineTo(left + arm, top)
                    moveTo(left + w - arm, top); lineTo(left + w - corner, top); quadraticTo(left + w, top, left + w, top + corner); lineTo(left + w, top + arm)
                    moveTo(left + w, top + h - arm); lineTo(left + w, top + h - corner); quadraticTo(left + w, top + h, left + w - corner, top + h); lineTo(left + w - arm, top + h)
                    moveTo(left + arm, top + h); lineTo(left + corner, top + h); quadraticTo(left, top + h, left, top + h - corner); lineTo(left, top + h - arm)
                }
                drawPath(handle, Color.White, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }

        // The selection's card: at the top, its first row on the camera's row.
        val cardTop = with(density) { (((cameraCenterYPx ?: (topInsetPx / 2)) - Capsule.CARD_ROW_DP.dp.roundToPx() / 2 - 6.dp.roundToPx()).coerceAtLeast(0)).toDp() }
        val shape = RoundedCornerShape(32.dp)
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = cardTop, start = 12.dp, end = 12.dp).fillMaxWidth()
                .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.4f))
                .clip(shape).background(Card).animateContentSize(spring(dampingRatio = 0.86f, stiffness = 480f))
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(Capsule.CARD_ROW_DP.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundButton(PIcons.close, stringResource(R.string.sel_cancel), onClick = onCancel)
                Text(stringResource(R.string.r_cap_select_title), fontSize = 13.sp, color = Small)
                Box(Modifier.weight(1f))
                val live = prefs.liveContext
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (live) pcHue(0xFF8A6A3C) else Line).clickable(role = Role.Switch) { container.settings.update { it.copy(liveContext = !it.liveContext) } }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PIcon(PIcons.eye, size = 15.dp, tint = Label)
                    Text(stringResource(if (live) R.string.r_cap_live_on else R.string.r_cap_live), fontSize = 12.5.sp, color = Label)
                }
            }
            region?.let { r ->
                val thumb = remember(r) {
                    runCatching {
                        val crop = android.graphics.Bitmap.createBitmap(capture.image, r.left.coerceAtLeast(0), r.top.coerceAtLeast(0), r.width.coerceAtMost(capture.image.width - r.left.coerceAtLeast(0)), r.height.coerceAtMost(capture.image.height - r.top.coerceAtLeast(0)))
                        val scale = 120f / maxOf(crop.width, crop.height)
                        android.graphics.Bitmap.createScaledBitmap(crop, (crop.width * scale).toInt().coerceAtLeast(1), (crop.height * scale).toInt().coerceAtLeast(1), true).asImageBitmap()
                    }.getOrNull()
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    thumb?.let { Image(it, null, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
                    for ((labelRes, promptRes, icon) in listOf(Triple(R.string.r_cap_what, R.string.r_cap_what_prompt, PIcons.search), Triple(R.string.r_cap_translate, R.string.r_cap_translate_prompt, PIcons.book), Triple(R.string.r_cap_help, R.string.r_cap_help_prompt, PIcons.spark))) {
                        val prompt = stringResource(promptRes)
                        Row(
                            Modifier.clip(RoundedCornerShape(18.dp)).background(Line).clickable(enabled = busy == null) { send(prompt) }.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PIcon(icon, size = 15.dp, tint = Label)
                            Text(stringResource(labelRes), fontSize = 13.sp, color = Label, maxLines = 1)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(Line).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (text.isEmpty()) Text(stringResource(if (region != null) R.string.r_cap_select_ask else R.string.r_cap_select_hint), fontSize = 14.5.sp, color = Small, maxLines = 1)
                    BasicTextField(
                        text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus),
                        textStyle = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp, color = Label), cursorBrush = SolidColor(Sage), maxLines = 4,
                    )
                    if (!focused) Box(Modifier.matchParentSize().clickable(onClickLabel = stringResource(R.string.cap_type)) { capsule.focusSelection(true) })
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(if (text.isNotBlank()) Sage else Color.Transparent)
                        .clickable(enabled = text.isNotBlank() && busy == null, role = Role.Button, onClickLabel = stringResource(R.string.cap_send)) { send(text) },
                    contentAlignment = Alignment.Center,
                ) { PIcon(PIcons.send, size = 20.dp, tint = if (text.isNotBlank()) pcHue(0xFF1C2A22) else Small) }
            }
        }
    }
}
