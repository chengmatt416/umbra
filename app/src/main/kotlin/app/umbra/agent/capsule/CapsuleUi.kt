package app.umbra.agent.capsule

import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.porcelain.pcHue
import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.SpeechRecognizer
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.ui.inbox.ApprovalCountdown
import app.umbra.agent.ui.localizedDescription
import app.umbra.core.safety.ApprovalSurface
import app.umbra.core.safety.ApproveGesture
import app.umbra.core.safety.approveGesture
import app.umbra.agent.data.ContextChip
import app.umbra.agent.ui.porcelain.ButtonStyle
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
import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.safety.RiskLevel
import app.umbra.core.session.SessionState
import app.umbra.core.session.Trails
import kotlinx.coroutines.delay

private val Glaze get() = Brush.linearGradient(listOf(pcHue(0xFF34453B), pcHue(0xFF20352D)))
private val Rim get() = pcHue(0xFF617A63)
internal val Label get() = pcHue(0xFFE9F3E6)
internal val Small get() = pcHue(0xFFA4B9A4)
internal val RowText get() = pcHue(0xFFD1E0D2)
internal val Section get() = pcHue(0xFFA5C4A7)
internal val Attention get() = pcHue(0xFFF9E5C3)
private val LIVE = setOf(SessionState.RUNNING, SessionState.WAITING_APPROVAL, SessionState.PAUSED)

/**
 * The capsule itself: collapsed, one companion per running task (docs/design/capsule-companion.md) and the task, or
 * a companion at rest; expanded, Previous / Current / Next for sessions and steps, the steps as a live activity feed.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun CapsuleView(capsule: Capsule, container: AppContainer) {
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsState(emptyList())
    val approvals by container.approvals.pending.collectAsState()
    val questions by container.questions.pending.collectAsState()
    val focused by container.ui.focusedSession.collectAsState()
    val emergency by container.emergency.state.collectAsState()
    val leases by container.inputLeases.all.collectAsState()
    val expanded by capsule.expanded.collectAsState()
    val draft by container.ui.draft.collectAsState()
    val chips by container.ui.draftContext.collectAsState()
    val notice by capsule.notice.collectAsState()
    val dismissed by capsule.dismissedFailures.collectAsState()
    val hole by capsule.hole.collectAsState()
    val activity by capsule.watch.activity.collectAsState()
    val finished by capsule.watch.finished.collectAsState()
    val density = LocalDensity.current
    val context = LocalContext.current

    val trail = remember(sessions, focused) { Trails.sessions(sessions, focused) }
    val current = trail.current
    val steps = remember(current) { Trails.steps(current) }
    val locked = emergency.level == EmergencyLevel.LOCKDOWN
    val failed = sessions.filter { it.state == SessionState.FAILED && it.id !in dismissed && System.currentTimeMillis() - it.updatedAtMs < 10 * 60_000 }.maxByOrNull { it.updatedAtMs }
    val needsYou = approvals.isNotEmpty() || questions.isNotEmpty()
    val atRest = current == null && !needsYou && !locked && failed == null
    val nowMs = System.currentTimeMillis()
    // Elapsed times tick while the capsule is open.
    val ticking by produceState(nowMs, expanded) { while (expanded) { delay(1_000); value = System.currentTimeMillis() } }
    val clockNow = maxOf(ticking, nowMs)
    val waitingOn = remember(approvals, questions) { approvals.map { it.sessionId }.toSet() + questions.map { it.sessionId } }
    val crew = Crews.of(sessions, activity, waitingOn, finished, dismissed, nowMs)
    val headMood = current?.let { Crews.moodOf(it, activity[it.id], it.id in waitingOn, null, false, nowMs) } ?: Mood.IDLE
    val moving = remember(sessions, approvals, expanded) { motionAllowed(context) } && Theme.current.motion
    val clock = rememberCompanionClock(crew.members.map { it.mood }.toSet() + headMood, moving)
    val tick: () -> Long = remember(clock) { { clock.value } }
    val done = crew.members.firstOrNull { it.mood == Mood.DONE }
    LaunchedEffect(failed?.id) { if (failed != null && !container.ui.appVisible.value) capsule.expanded.value = true }

    val label = when {
        locked -> stringResource(R.string.cap_lockdown)
        approvals.isNotEmpty() -> stringResource(R.string.cap_needs_decision)
        questions.isNotEmpty() -> stringResource(R.string.cap_question)
        failed != null && current == null -> stringResource(R.string.cap_stopped_error)
        done != null && current == null -> stringResource(R.string.capc_done_label, done.title)
        current != null -> (steps.current?.text ?: current.title).let { if (it.length > 30) it.take(29).trimEnd() + "…" else it }
        else -> stringResource(R.string.cap_at_rest)
    }
    val collapseLabel = stringResource(R.string.cap_collapse)
    val composerLabel = stringResource(R.string.cap_open_composer)
    val expandLabel = stringResource(R.string.cap_expand)
    val selectLabel = stringResource(R.string.cap_select_screen)
    val crewSaid = crewDescription(crew)
    val capsuleDescription = stringResource(R.string.cap_description, label).let { if (crewSaid.isEmpty()) it else stringResource(R.string.capc_description_more, it, crewSaid) }
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.then(if (expanded) Modifier.fillMaxWidth() else Modifier)
            .shadow(10.dp, shape, ambientColor = pcHue(0x4020372C), spotColor = pcHue(0x4020372C))
            .clip(shape).background(Glaze).border(1.dp, Rim, shape).animateContentSize(),
    ) {
        val wrap = hole
        // Expanded at the camera, the content starts below the hole; collapsed, the hole sits in a gap at one end.
        if (expanded && wrap != null) Box(Modifier.height(with(density) { wrap.belowPx.toDp() }))
        val around = if (!expanded) wrap else null
        val gap = around?.let { with(density) { it.gapPx.toDp() } }
        Row(
            Modifier.combinedClickable(
                onClickLabel = if (expanded) collapseLabel else if (atRest) composerLabel else expandLabel,
                onLongClickLabel = selectLabel,
                onLongClick = { capsule.startSelection() },
                onClick = { if (expanded) capsule.expanded.value = false else if (atRest) capsule.openComposer(null) else capsule.expanded.value = true },
            ).semantics { contentDescription = capsuleDescription }
                .then(around?.let { Modifier.heightIn(min = with(density) { it.heightPx.toDp() }) } ?: Modifier)
                .padding(vertical = if (atRest && !expanded) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(if (gap != null && around?.contentLeft == false) gap else 13.dp))
            if (expanded) CompanionGlyph(headMood, tick, moving) else CrewRow(crew, tick, moving)
            val labelRoom = (around?.let { a -> with(density) { a.labelMaxPx.toDp() } }?.coerceAtMost(180.dp) ?: 180.dp) - crewExtraWidth(crew)
            if (expanded || labelRoom >= 40.dp) {
                Box(Modifier.width(8.dp))
                Text(
                    if (expanded) (current?.let { "${it.title} · ${moodText(headMood).lowercase()}" } ?: stringResource(R.string.cap_smart_composer)) else label,
                    Modifier.then(if (expanded) Modifier.weight(1f) else Modifier.widthIn(max = labelRoom)),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded && current != null) Text(
                elapsedText(clockNow - (activity[current.id]?.runStartedAtMs ?: current.createdAtMs)),
                Modifier.padding(start = 8.dp), fontSize = 11.sp, color = Small, maxLines = 1, style = TextStyle(fontFeatureSettings = P.tabular),
            )
            if (expanded || !atRest) { Box(Modifier.width(8.dp)); PIcon(if (expanded) PIcons.close else PIcons.chevronDown, size = 13.dp, tint = Label) }
            Box(Modifier.width(if (gap != null && around?.contentLeft == true) gap + 8.dp else 13.dp))
        }
        if (!expanded) return@Column

        Box(Modifier.fillMaxWidth().height(1.dp).background(pcHue(0x24FFFFFF)))
        val maxBody = with(density) { (LocalResources.current.displayMetrics.heightPixels * 0.8f).toDp() }
        Column(Modifier.fillMaxWidth().heightIn(max = maxBody).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp)) {
            if (locked) {
                Text((stringResource(R.string.cap_lockdown_text) + " " + (emergency.reason ?: "")).trim(), Modifier.padding(vertical = 4.dp), fontSize = 12.sp, color = Attention)
                PButton(stringResource(R.string.dest_emergency), { capsule.open("emergency", null) }, style = ButtonStyle.Alt, tiny = true)
                SectionGap()
            }
            approvals.firstOrNull()?.let { request ->
                Text(request.description, Modifier.padding(vertical = 4.dp), fontSize = 12.sp, color = Attention, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(request.assessment.localizedDescription(), fontSize = 11.sp, color = if (request.assessment.level >= RiskLevel.HIGH) pcHue(0xFFE7A493) else Small, maxLines = 3, overflow = TextOverflow.Ellipsis)
                ApprovalCountdown(request, Modifier.padding(top = 6.dp), dark = true)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton(stringResource(R.string.appr_deny), { container.approvals.resolve(request.id, ApprovalDecision.Denied("denied from the capsule")) }, style = ButtonStyle.Alt, tiny = true)
                    when (approveGesture(request.assessment.level, ApprovalSurface.CAPSULE)) {
                        ApproveGesture.ONE_TAP -> PButton(stringResource(R.string.appr_approve_once), { container.approvals.resolve(request.id, ApprovalDecision.Approved()) }, tiny = true)
                        else -> PButton(stringResource(R.string.appr_review_inbox), { capsule.open("inbox", null) }, tiny = true)
                    }
                }
                if (approvals.size > 1) Text(stringResource(R.string.appr_more_waiting, approvals.size - 1), Modifier.padding(top = 4.dp), fontSize = 11.sp, color = Small)
                SectionGap()
            }
            if (questions.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pluralStringResource(R.plurals.cap_questions, questions.size, questions.size), Modifier.weight(1f), fontSize = 12.sp, color = Attention)
                    PButton(stringResource(R.string.cap_answer), { capsule.open("inbox", null) }, style = ButtonStyle.Alt, tiny = true)
                }
                SectionGap()
            }
            failed?.let { f ->
                Text(stringResource(R.string.cap_failed, f.title) + (f.lastError?.let { ": $it" } ?: ""), fontSize = 12.sp, color = Attention, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton(stringResource(R.string.cap_open), { capsule.open(null, f.id) }, style = ButtonStyle.Alt, tiny = true)
                    PButton(stringResource(R.string.cap_dismiss), { capsule.dismissedFailures.value = dismissed + f.id }, style = ButtonStyle.Ghost, tiny = true)
                }
                SectionGap()
            }

            SectionTitle(stringResource(R.string.cap_section_sessions))
            CrewSessions(trail, crew, sessions, activity, headMood, clockNow, tick, moving, onFocus = { container.ui.focus(it) })
            SectionGap()
            SectionTitle(stringResource(R.string.cap_section_steps))
            CrewSteps(current, current?.let { activity[it.id] }, headMood, steps, clockNow, tick, moving)

            Row(Modifier.padding(top = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(pcHue(0x18FFFFFF)).border(1.dp, pcHue(0x2BFFFFFF), RoundedCornerShape(10.dp))
                        .clickable(onClickLabel = stringResource(R.string.cap_write)) { capsule.openComposer(current?.id) }.padding(horizontal = 10.dp, vertical = 9.dp),
                ) {
                    Text(
                        draft.ifBlank { stringResource(if (current != null) R.string.cap_ask_agent else R.string.cap_ask_umbra) } + if (chips.isNotEmpty()) "  · " + pluralStringResource(R.plurals.cap_attached, chips.size, chips.size) else "",
                        fontSize = 12.sp, color = if (draft.isBlank()) pcHue(0xFFC5D8C5) else Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                PButton("", { capsule.startSelection() }, style = ButtonStyle.Alt, tiny = true, icon = PIcons.target, contentDescription = selectLabel)
            }
            if (current != null) {
                val screenLease = leases.values.firstOrNull { it.resourceId == "display:0" }
                val userHolds = screenLease?.owner == Principal.User && screenLease.state == LeaseState.ACTIVE
                FlowRow(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PButton(stringResource(R.string.cap_open), { capsule.open(null, current.id) }, style = ButtonStyle.Alt, tiny = true)
                    if (current.state == SessionState.PAUSED) PButton(stringResource(R.string.cap_resume), { capsule.launch { container.supervisor.resume(current.id) } }, style = ButtonStyle.Alt, tiny = true)
                    else PButton(stringResource(R.string.cap_pause), { capsule.launch { container.supervisor.pause(current.id) } }, style = ButtonStyle.Alt, tiny = true)
                    PButton(stringResource(R.string.cap_stop), { capsule.launch { container.supervisor.stop(current.id) } }, style = ButtonStyle.Danger, tiny = true)
                    if (!userHolds) PButton(stringResource(R.string.cap_take_back), { container.inputLeases.takeBack("display:0") }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.hand)
                    else PButton(stringResource(R.string.cap_return), { container.inputLeases.release("display:0", Principal.User, "returned by the user") }, style = ButtonStyle.Ghost, tiny = true)
                }
            } else {
                Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PButton(stringResource(R.string.cap_open_umbra), { capsule.open(null, null) }, style = ButtonStyle.Alt, tiny = true)
                }
            }
            notice?.let {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), fontSize = 11.sp, color = Attention, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    PButton("", { capsule.notice.value = null }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.close, contentDescription = stringResource(R.string.cap_dismiss))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text.uppercase(), Modifier.padding(top = 4.dp, bottom = 5.dp), fontSize = 9.sp, letterSpacing = 1.sp, color = Section)

@Composable
private fun SectionGap() = Box(Modifier.padding(vertical = 9.dp).fillMaxWidth().height(1.dp).background(pcHue(0x22FFFFFF)))

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
 * Snapshot selection (§3.1): the screenshot under a light glaze, a lasso drawn with a finger (or a tap on one
 * element), and a small toolbar. The screenshot is drawn where the real screen is, so the user circles exactly
 * what they see.
 */
@Composable
internal fun SelectionView(capture: ScreenContext.Capture, topInsetPx: Int, bottomInsetPx: Int, onDone: (List<Point>) -> Unit, onCancel: () -> Unit) {
    val image = remember(capture) { capture.image.asImageBitmap() }
    val points = remember { mutableStateListOf<Offset>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val dim = pcHue(0x550C2414)
    val selectionDescription = stringResource(R.string.sel_description)
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionOnScreen() }) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { at -> points.clear(); points.add(at) }, onDrag = { change, _ -> points.add(change.position) })
                }
                .pointerInput(Unit) { detectTapGestures { at -> points.clear(); points.add(at); points.add(at + Offset(1f, 1f)) } }
                .semantics { contentDescription = selectionDescription },
        ) {
            drawImage(image, topLeft = Offset(-origin.x, -origin.y))
            if (points.size >= 3) {
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                    close()
                }
                clipPath(path, ClipOp.Difference) { drawRect(dim) }
                drawPath(path, pcHue(0x19AEE7B0))
                drawPath(path, pcHue(0xFFE0FAE6), style = Stroke(width = 2.dp.toPx() * 1.5f))
            } else {
                drawRect(dim)
                points.firstOrNull()?.let { drawCircle(pcHue(0xFFE0FAE6), radius = 18.dp.toPx(), center = it, style = Stroke(width = 3.dp.toPx())) }
            }
        }
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = with(density) { topInsetPx.toDp() } + 10.dp)
                .clip(RoundedCornerShape(50)).background(Glaze).border(1.dp, Rim, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PIcon(PIcons.target, size = 14.dp, tint = Label)
            Text(stringResource(R.string.sel_banner), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Label)
        }
        if (points.isEmpty()) Text(
            stringResource(R.string.sel_hint),
            Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(pcHue(0xAA17251D)).padding(horizontal = 14.dp, vertical = 9.dp),
            fontSize = 14.sp, color = Color.White,
        )
        // A solid glaze base under the three buttons: nothing of the screenshot shows between them, and Clear is always
        // there (unavailable until something is selected), so no button moves on the first touch.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = with(density) { bottomInsetPx.toDp() } + 12.dp)
                .shadow(12.dp, RoundedCornerShape(22.dp), ambientColor = pcHue(0x4020372C), spotColor = pcHue(0x4020372C))
                .clip(RoundedCornerShape(22.dp)).background(Glaze).border(1.dp, Rim, RoundedCornerShape(22.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PButton(stringResource(R.string.sel_cancel), onCancel, Modifier.weight(1f), style = ButtonStyle.Ghost)
            PButton(stringResource(R.string.sel_clear), { points.clear() }, Modifier.weight(1f), style = ButtonStyle.Alt, enabled = points.isNotEmpty())
            PButton(stringResource(R.string.sel_done), {
                onDone(points.map { Point((it.x + origin.x).toInt(), (it.y + origin.y).toInt()) })
            }, Modifier.weight(1.3f), enabled = points.isNotEmpty(), icon = PIcons.check)
        }
    }
}
