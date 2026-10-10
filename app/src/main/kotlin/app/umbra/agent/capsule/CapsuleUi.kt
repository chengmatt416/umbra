package app.umbra.agent.capsule

import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.SpeechRecognizer
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.AppContainer
import app.umbra.agent.data.ContextChip
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.motionAllowed
import app.umbra.agent.ui.sessions.stateText
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

private val Glaze = Brush.linearGradient(listOf(Color(0xFF34453B), Color(0xFF20352D)))
private val Rim = Color(0xFF617A63)
private val Label = Color(0xFFE9F3E6)
private val Small = Color(0xFFA4B9A4)
private val RowText = Color(0xFFD1E0D2)
private val Section = Color(0xFFA5C4A7)
private val Attention = Color(0xFFF9E5C3)
private val LIVE = setOf(SessionState.RUNNING, SessionState.WAITING_APPROVAL, SessionState.PAUSED)

/** The ceramic orb of the capsule (`.cap-dot`); it breathes while an agent works, unless motion is reduced. */
@Composable
private fun CapDot(working: Boolean, attention: Boolean) {
    val context = LocalContext.current
    val moving = working && remember { motionAllowed(context) }
    val breath = if (moving) rememberInfiniteTransition(label = "orb").animateFloat(0.9f, 1.08f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breath").value else 1f
    Box(
        Modifier.size(18.dp).scale(breath).clip(CircleShape).background(
            Brush.radialGradient(
                0f to Color(0xFFEDF7E7), 0.32f to (if (attention) Color(0xFFD8B27A) else Color(0xFF87B28E)),
                0.65f to (if (attention) Color(0xFF8A6235) else Color(0xFF49634D)), 1f to Color(0xFF273E2F),
                center = Offset(14f, 12f), radius = 46f,
            ),
        ),
    )
}

/** The capsule itself: collapsed orb + task (or a mini island at rest), expanded Previous / Current / Next. */
@OptIn(ExperimentalFoundationApi::class)
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
    val density = LocalDensity.current

    val trail = remember(sessions, focused) { Trails.sessions(sessions, focused) }
    val current = trail.current
    val steps = remember(current) { Trails.steps(current) }
    val locked = emergency.level == EmergencyLevel.LOCKDOWN
    val failed = sessions.filter { it.state == SessionState.FAILED && it.id !in dismissed && System.currentTimeMillis() - it.updatedAtMs < 10 * 60_000 }.maxByOrNull { it.updatedAtMs }
    val needsYou = approvals.isNotEmpty() || questions.isNotEmpty()
    val atRest = current == null && !needsYou && !locked && failed == null
    LaunchedEffect(failed?.id) { if (failed != null) capsule.expanded.value = true }

    val label = when {
        locked -> "Lockdown · input held"
        approvals.isNotEmpty() -> "Needs your decision"
        questions.isNotEmpty() -> "A question for you"
        failed != null && current == null -> "Stopped with an error"
        current != null -> (steps.current?.text ?: current.title).let { if (it.length > 30) it.take(29).trimEnd() + "…" else it }
        else -> "At rest"
    }
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.then(if (expanded) Modifier.fillMaxWidth() else Modifier)
            .shadow(10.dp, shape, ambientColor = Color(0x4020372C), spotColor = Color(0x4020372C))
            .clip(shape).background(Glaze).border(1.dp, Rim, shape).animateContentSize(),
    ) {
        val wrap = hole
        // Expanded at the camera, the content starts below the hole; collapsed, the hole sits in a gap at one end.
        if (expanded && wrap != null) Box(Modifier.height(with(density) { wrap.belowPx.toDp() }))
        val around = if (!expanded) wrap else null
        val gap = around?.let { with(density) { it.gapPx.toDp() } }
        Row(
            Modifier.combinedClickable(
                onClickLabel = if (expanded) "Collapse" else if (atRest) "Open the composer" else "Expand",
                onLongClickLabel = "Select from the screen",
                onLongClick = { capsule.startSelection() },
                onClick = { if (expanded) capsule.expanded.value = false else if (atRest) capsule.openComposer(null) else capsule.expanded.value = true },
            ).semantics { contentDescription = "Umbra capsule: $label" }
                .then(around?.let { Modifier.heightIn(min = with(density) { it.heightPx.toDp() }) } ?: Modifier)
                .padding(vertical = if (atRest && !expanded) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(if (gap != null && around?.contentLeft == false) gap else 13.dp))
            CapDot(working = current?.state == SessionState.RUNNING, attention = needsYou || locked || failed != null)
            Box(Modifier.width(8.dp))
            Text(
                if (expanded) (current?.let { "${it.title} · ${stateText(it.state).lowercase()}" } ?: "Smart Composer") else label,
                Modifier.then(if (expanded) Modifier.weight(1f) else Modifier.widthIn(max = around?.let { a -> with(density) { a.labelMaxPx.toDp() } }?.coerceAtMost(180.dp) ?: 180.dp)),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (expanded || !atRest) { Box(Modifier.width(8.dp)); PIcon(if (expanded) PIcons.close else PIcons.chevronDown, size = 13.dp, tint = Label) }
            Box(Modifier.width(if (gap != null && around?.contentLeft == true) gap + 8.dp else 13.dp))
        }
        if (!expanded) return@Column

        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x24FFFFFF)))
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp)) {
            if (locked) {
                Text("Emergency lockdown: every agent's input is held. ${emergency.reason ?: ""}".trim(), Modifier.padding(vertical = 4.dp), fontSize = 12.sp, color = Attention)
                PButton("Emergency Control", { capsule.open("emergency", null) }, style = ButtonStyle.Alt, tiny = true)
                SectionGap()
            }
            approvals.firstOrNull()?.let { request ->
                Text(request.description, Modifier.padding(vertical = 4.dp), fontSize = 12.sp, color = Attention, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(request.assessment.describe(), fontSize = 11.sp, color = if (request.assessment.level >= RiskLevel.HIGH) Color(0xFFE7A493) else Small, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton("Deny", { container.approvals.resolve(request.id, ApprovalDecision.Denied("denied from the capsule")) }, style = ButtonStyle.Alt, tiny = true)
                    if (request.assessment.level < RiskLevel.CRITICAL) PButton("Approve once", { container.approvals.resolve(request.id, ApprovalDecision.Approved()) }, tiny = true)
                    else PButton("Review in Inbox", { capsule.open("inbox", null) }, tiny = true)
                }
                if (approvals.size > 1) Text("${approvals.size - 1} more waiting in Inbox", Modifier.padding(top = 4.dp), fontSize = 11.sp, color = Small)
                SectionGap()
            }
            if (questions.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${questions.size} question${if (questions.size > 1) "s" else ""} from the agent", Modifier.weight(1f), fontSize = 12.sp, color = Attention)
                    PButton("Answer", { capsule.open("inbox", null) }, style = ButtonStyle.Alt, tiny = true)
                }
                SectionGap()
            }
            failed?.let { f ->
                Text("“${f.title}” stopped with an error" + (f.lastError?.let { ": $it" } ?: ""), fontSize = 12.sp, color = Attention, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton("Open", { capsule.open(null, f.id) }, style = ButtonStyle.Alt, tiny = true)
                    PButton("Dismiss", { capsule.dismissedFailures.value = dismissed + f.id }, style = ButtonStyle.Ghost, tiny = true)
                }
                SectionGap()
            }

            SectionTitle("Session · previous / current / next")
            TrailRow("Previous", trail.previous?.let { "${it.title} · ${stateText(it.state).lowercase()}" }, false)
            TrailRow("Current", current?.title, true)
            TrailRow("Next", trail.next?.title ?: "Nothing queued", false)
            SectionGap()
            SectionTitle("Step · previous / current / next")
            TrailRow("Previous", steps.previous?.text, false)
            TrailRow("Current", steps.current?.text ?: if (current != null && current.plan.isEmpty()) "No plan steps reported yet" else null, true)
            TrailRow("Next", steps.next?.text, false)

            Row(Modifier.padding(top = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x18FFFFFF)).border(1.dp, Color(0x2BFFFFFF), RoundedCornerShape(10.dp))
                        .clickable(onClickLabel = "Write a message") { capsule.openComposer(current?.id) }.padding(horizontal = 10.dp, vertical = 9.dp),
                ) {
                    Text(
                        draft.ifBlank { if (current != null) "Ask the agent…" else "Ask Umbra to do anything…" } + if (chips.isNotEmpty()) "  · ${chips.size} attached" else "",
                        fontSize = 12.sp, color = if (draft.isBlank()) Color(0xFFC5D8C5) else Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                PButton("", { capsule.startSelection() }, style = ButtonStyle.Alt, tiny = true, icon = PIcons.target, contentDescription = "Select from the screen")
            }
            if (current != null) {
                val screenLease = leases.values.firstOrNull { it.resourceId == "display:0" }
                val userHolds = screenLease?.owner == Principal.User && screenLease.state == LeaseState.ACTIVE
                Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PButton("Open", { capsule.open(null, current.id) }, style = ButtonStyle.Alt, tiny = true)
                    if (current.state == SessionState.PAUSED) PButton("Resume", { capsule.launch { container.supervisor.resume(current.id) } }, style = ButtonStyle.Alt, tiny = true)
                    else PButton("Pause", { capsule.launch { container.supervisor.pause(current.id) } }, style = ButtonStyle.Alt, tiny = true)
                    PButton("Stop", { capsule.launch { container.supervisor.stop(current.id) } }, style = ButtonStyle.Danger, tiny = true)
                    if (!userHolds) PButton("Take Back", { container.inputLeases.takeBack("display:0") }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.hand)
                    else PButton("Return", { container.inputLeases.release("display:0", Principal.User, "returned by the user") }, style = ButtonStyle.Ghost, tiny = true)
                }
            } else {
                Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PButton("Open Umbra", { capsule.open(null, null) }, style = ButtonStyle.Alt, tiny = true)
                }
            }
            notice?.let {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), fontSize = 11.sp, color = Attention, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    PButton("", { capsule.notice.value = null }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.close, contentDescription = "Dismiss")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text.uppercase(), Modifier.padding(top = 4.dp, bottom = 5.dp), fontSize = 9.sp, letterSpacing = 1.sp, color = Section)

@Composable
private fun SectionGap() = Box(Modifier.padding(vertical = 9.dp).fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))

@Composable
private fun TrailRow(label: String, value: String?, current: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.width(62.dp), fontSize = 11.sp, color = Small)
        Text(value ?: "—", Modifier.weight(1f), fontSize = 12.sp, color = if (current) Color.White else RowText, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
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
            .shadow(14.dp, shape, ambientColor = Color(0x33455F49), spotColor = Color(0x44455F49)).clip(shape)
            .background(Color(0xFFF7FAF4)).border(1.dp, Color(0xB5FFFFFF), shape).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                PButton(
                    targetSession?.let { "To: ${it.title}" } ?: ("New task" + (project?.let { " · ${it.name}" } ?: "")),
                    { menu = true }, style = ButtonStyle.Ghost, tiny = true, icon = if (targetSession != null) PIcons.bot else PIcons.spark,
                )
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("New task" + (project?.let { " in ${it.name}" } ?: "")) }, onClick = { menu = false; container.ui.composerTarget.value = null })
                    live.forEach { s -> DropdownMenuItem(text = { Text("To: ${s.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { menu = false; container.ui.composerTarget.value = s.id }) }
                }
            }
            PButton(
                if (settings.liveContext) "Live context on" else "Live context off",
                { container.settings.update { it.copy(liveContext = !it.liveContext) } },
                style = if (settings.liveContext) ButtonStyle.Warn else ButtonStyle.Ghost, tiny = true,
            )
            PButton("", { capsule.closeComposer() }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.close, contentDescription = "Close")
        }
        chips.forEach { chip -> ChipRow(chip) { container.ui.removeContext(chip.id) } }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) {
            if (draft.isEmpty()) Text(if (targetSession != null) "Message the agent…" else "Ask Umbra to do anything…", fontSize = 15.sp, color = Color(0xFF9AAA98))
            BasicTextField(
                draft, { container.ui.saveDraft(it) },
                Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Message" },
                textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = P.ink), cursorBrush = SolidColor(P.accent2), maxLines = 6,
            )
            // The keyboard opens only when the field is tapped (§3.1): until then the window cannot take focus at all.
            if (!focused) Box(Modifier.matchParentSize().clickable(onClickLabel = "Type a message") { capsule.focusComposer(true) })
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PButton("", { capsule.startSelection() }, style = ButtonStyle.Alt, tiny = true, icon = PIcons.target, contentDescription = "Select from the screen")
            if (canListen) PButton("", {
                context.startActivity(Intent(context, VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, style = ButtonStyle.Alt, tiny = true, icon = PIcons.mic, contentDescription = "Speak")
            Box(Modifier.weight(1f)) { busy?.let { Text(it, fontSize = 12.sp, color = P.sub) } }
            PButton("", { capsule.send(draft.trim(), project?.id) }, tiny = true, icon = PIcons.send, enabled = draft.isNotBlank() && busy == null, contentDescription = "Send")
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
        Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFE4EDE1)).border(1.dp, Color(0xFFC9D8C6), RoundedCornerShape(12.dp)).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (thumb != null) Image(thumb, null, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        else PIcon(PIcons.target, size = 16.dp, tint = P.accent2)
        Column(Modifier.weight(1f, fill = false)) {
            Text(chip.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Snapshot · not live", fontSize = 10.sp, color = P.sub)
        }
        PButton("", onRemove, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.close, contentDescription = "Remove ${chip.label}")
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
    val dim = Color(0x550C2414)
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionOnScreen() }) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { at -> points.clear(); points.add(at) }, onDrag = { change, _ -> points.add(change.position) })
                }
                .pointerInput(Unit) { detectTapGestures { at -> points.clear(); points.add(at); points.add(at + Offset(1f, 1f)) } }
                .semantics { contentDescription = "Screenshot. Draw around what you mean, or tap it." },
        ) {
            drawImage(image, topLeft = Offset(-origin.x, -origin.y))
            if (points.size >= 3) {
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                    close()
                }
                clipPath(path, ClipOp.Difference) { drawRect(dim) }
                drawPath(path, Color(0x19AEE7B0))
                drawPath(path, Color(0xFFE0FAE6), style = Stroke(width = 2.dp.toPx() * 1.5f))
            } else {
                drawRect(dim)
                points.firstOrNull()?.let { drawCircle(Color(0xFFE0FAE6), radius = 18.dp.toPx(), center = it, style = Stroke(width = 3.dp.toPx())) }
            }
        }
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = with(density) { topInsetPx.toDp() } + 10.dp)
                .clip(RoundedCornerShape(50)).background(Glaze).border(1.dp, Rim, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PIcon(PIcons.target, size = 14.dp, tint = Label)
            Text("Screen selection · snapshot, not live", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Label)
        }
        if (points.isEmpty()) Text(
            "Draw around what you mean, or tap it",
            Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color(0xAA17251D)).padding(horizontal = 14.dp, vertical = 9.dp),
            fontSize = 14.sp, color = Color.White,
        )
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = with(density) { bottomInsetPx.toDp() } + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PButton("Cancel", onCancel, Modifier.weight(1f), style = ButtonStyle.Alt)
            if (points.isNotEmpty()) PButton("Clear", { points.clear() }, Modifier.weight(1f), style = ButtonStyle.Alt)
            PButton("Done · Attach", {
                onDone(points.map { Point((it.x + origin.x).toInt(), (it.y + origin.y).toInt()) })
            }, Modifier.weight(1.4f), enabled = points.isNotEmpty())
        }
    }
}
