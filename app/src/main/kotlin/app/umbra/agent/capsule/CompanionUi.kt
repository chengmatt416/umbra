package app.umbra.agent.capsule

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.pcHue
import app.umbra.agent.ui.sessions.stateText
import app.umbra.core.session.Session
import app.umbra.core.session.SessionState
import app.umbra.core.session.SessionTrail
import app.umbra.core.session.StepTrail
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---- the companion (docs/design/capsule-companion.md) ----

/** The clock's value while nothing moves: every companion is drawn in its still pose. */
internal const val REST = -1L

private const val FRAME_MS = 33L
private const val IDLE_BREATH_MS = 60_000L

/** The direction of the mark's cover, from the body's centre (favicon: (5.5, −4) on a body of radius 14). */
private const val AXIS_X = 0.809f
private const val AXIS_Y = -0.588f

/** Where the cover sits, in units of the body's radius, and its size. */
private data class Pose(val dx: Float, val dy: Float, val k: Float)

private fun along(d: Float, k: Float) = Pose(AXIS_X * d, AXIS_Y * d, k)

private fun poseOf(mood: Mood) = when (mood) {
    Mood.IDLE, Mood.PAUSED -> Pose(0.393f, -0.286f, 0.75f) // the mark itself
    Mood.THINKING -> along(0.44f, 0.78f)
    Mood.WORKING -> along(0.42f, 0.78f)
    Mood.COMPACTING -> along(0.12f, 0.86f)
    Mood.WAITING -> Pose(0f, 0f, 0.66f)
    Mood.ERROR -> Pose(0.33f, -0.44f, 0.62f)
    Mood.DONE -> along(1.9f, 0.75f) // off the disc: the full disc shows
}

/** The body's glaze, light end and deep end. Dark by nature, so only the sage follows the theme's hue. */
private fun toneOf(mood: Mood): Pair<Color, Color> = when (mood) {
    Mood.WAITING -> pcHue(0xFFF3D4A0) to pcHue(0xFFC08F4E)
    Mood.ERROR -> pcHue(0xFFEFAE9F) to pcHue(0xFFB0655A)
    Mood.DONE -> pcHue(0xFFD6EAD1) to pcHue(0xFF86AF8C)
    else -> pcHue(0xFFAAC5A7) to pcHue(0xFF5A7F64)
}

/**
 * Frame time for the companions, about 30 times a second, while one of [moods] moves and motion is allowed; [REST]
 * otherwise. At rest alone the breathing stops after a minute and the companion settles on the mark.
 */
@Composable
internal fun rememberCompanionClock(moods: Set<Mood>, moving: Boolean): State<Long> {
    val time = remember { mutableLongStateOf(REST) }
    LaunchedEffect(moods, moving) {
        time.longValue = REST
        if (!moving || moods.none(Crews::moves)) return@LaunchedEffect
        val breathingOnly = moods.all { it == Mood.IDLE }
        var first = REST
        var last = REST
        while (true) {
            val now = withFrameMillis { it }
            if (first == REST) first = now
            if (breathingOnly && now - first > IDLE_BREATH_MS) {
                time.longValue = REST
                break
            }
            if (now - last >= FRAME_MS) {
                last = now
                time.longValue = now
            }
        }
    }
    return time
}

/**
 * One companion: Umbra's eclipse (a sage disc and the disc that covers it) in the pose of [mood]. Poses glide into
 * each other; the continuous motion reads [clock] in the draw phase only, so a frame redraws without recomposing.
 */
@Composable
internal fun CompanionGlyph(mood: Mood, clock: () -> Long, moving: Boolean, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val glide = tween<Float>(if (moving) 420 else 0, easing = P.ease)
    val pose = poseOf(mood)
    val dx by animateFloatAsState(pose.dx, glide, label = "cover-x")
    val dy by animateFloatAsState(pose.dy, glide, label = "cover-y")
    val k by animateFloatAsState(pose.k, glide, label = "cover-size")
    val strength by animateFloatAsState(if (mood == Mood.PAUSED) 0.5f else 1f, glide, label = "strength")
    val (lightTone, deepTone) = toneOf(mood)
    val tint = tween<Color>(if (moving) 420 else 0, easing = P.ease)
    val light by animateColorAsState(lightTone, tint, label = "light")
    val deep by animateColorAsState(deepTone, tint, label = "deep")
    // One-shot moments: the error's short shake, the done glow. 1 = over.
    val once = remember { Animatable(1f) }
    LaunchedEffect(mood, moving) {
        if (moving && (mood == Mood.ERROR || mood == Mood.DONE)) {
            once.snapTo(0f)
            once.animateTo(1f, tween(if (mood == Mood.ERROR) 520 else 900, easing = LinearEasing))
        } else {
            once.snapTo(1f)
        }
    }
    Canvas(modifier.size(size)) {
        drawCompanion(mood, if (moving) clock() else REST, Pose(dx, dy, k), light, deep, strength, once.value)
    }
}

private fun DrawScope.drawCompanion(mood: Mood, t: Long, pose: Pose, light: Color, deep: Color, strength: Float, once: Float) {
    var dx = pose.dx
    var dy = pose.dy
    var k = pose.k
    var scale = 1f
    val live = t != REST
    if (live) {
        when (mood) {
            Mood.IDLE -> {
                val b = sin(2 * PI * (t % 5200) / 5200).toFloat()
                k += 0.03f * b
                scale += 0.03f * b
            }
            Mood.THINKING -> {
                val d = -0.14f * cos(2 * PI * (t % 2600) / 2600).toFloat()
                dx += AXIS_X * d
                dy += AXIS_Y * d
            }
            Mood.WORKING -> {
                val a = 2 * PI * (t % 1800) / 1800
                val c = cos(a).toFloat()
                val s = sin(a).toFloat()
                val x = dx * c - dy * s
                dy = dx * s + dy * c
                dx = x
            }
            Mood.COMPACTING -> k += 0.06f * sin(2 * PI * (t % 1600) / 1600).toFloat()
            else -> Unit
        }
    }
    val r = size.minDimension / 2f * 0.94f * scale
    var c = center
    if (mood == Mood.ERROR && once < 1f) c += Offset(1.5.dp.toPx() * sin(2 * PI * 3 * once).toFloat() * (1f - once), 0f)
    val glaze = Brush.linearGradient(listOf(light, deep), start = c - Offset(r, r) * 0.75f, end = c + Offset(r, r) * 0.75f)
    val ring = Stroke(1.2.dp.toPx())
    if (mood == Mood.WAITING) {
        if (live) {
            val p = (t % 2000) / 2000f
            drawCircle(light, radius = r * (1.15f + 0.5f * p), center = c, alpha = 0.55f * (1f - p) * (1f - p) * strength, style = ring)
        } else {
            drawCircle(light, radius = r * 1.3f, center = c, alpha = 0.28f, style = Stroke(1.dp.toPx()))
        }
    }
    if (mood == Mood.DONE && once < 1f) drawCircle(light, radius = r * (1.08f + 0.55f * once), center = c, alpha = 0.5f * (1f - once), style = ring)
    // The covered part keeps a faint earthshine, so the whole disc reads even as a thin crescent.
    drawCircle(glaze, radius = r, center = c, alpha = 0.16f * strength)
    val cover = Path().apply { addOval(Rect(center = c + Offset(dx * r, dy * r), radius = k * r)) }
    clipPath(cover, ClipOp.Difference) { drawCircle(glaze, radius = r, center = c, alpha = strength) }
}

/** The crew side by side: at most three companions, then "+N". */
@Composable
internal fun CrewRow(crew: Crew, clock: () -> Long, moving: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CREW_GAP)) {
        crew.members.forEach { member -> androidx.compose.runtime.key(member.sessionId) { CompanionGlyph(member.mood, clock, moving) } }
        if (crew.overflow > 0) Text(
            stringResource(R.string.capc_more, crew.overflow),
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Small, style = TextStyle(fontFeatureSettings = P.tabular),
        )
    }
}

internal val CREW_GAP = 4.dp

/** How much wider the crew is than one companion, so the label beside it can give way. */
internal fun crewExtraWidth(crew: Crew): Dp = (18.dp + CREW_GAP) * (crew.members.size - 1) + if (crew.overflow > 0) 24.dp else 0.dp

@Composable
internal fun moodText(mood: Mood): String = stringResource(
    when (mood) {
        Mood.IDLE -> R.string.cap_at_rest
        Mood.THINKING -> R.string.capc_mood_thinking
        Mood.WORKING -> R.string.state_working
        Mood.COMPACTING -> R.string.capc_mood_compacting
        Mood.WAITING -> R.string.state_needs_you
        Mood.PAUSED -> R.string.state_paused
        Mood.ERROR -> R.string.capc_mood_error
        Mood.DONE -> R.string.state_done
    },
)

/** What a screen reader hears after the label: each companion's task and state. */
@Composable
internal fun crewDescription(crew: Crew): String {
    val parts = crew.members.filter { it.sessionId != null }.map { stringResource(R.string.capc_member, it.title, moodText(it.mood)) }
    val more = if (crew.overflow > 0) listOf(pluralStringResource(R.plurals.capc_more_running, crew.overflow, crew.overflow)) else emptyList()
    return (parts + more).joinToString(stringResource(R.string.capc_list_sep))
}

// ---- the expanded capsule: sessions and their activity ----

private val LABEL_WIDTH = 62.dp
private val tabular = TextStyle(fontFeatureSettings = P.tabular)

@Composable
private fun RowLabel(text: String?) = Text(text.orEmpty(), Modifier.width(LABEL_WIDTH), fontSize = 11.sp, lineHeight = 15.sp, color = Small, maxLines = 1, overflow = TextOverflow.Ellipsis)

/** Elapsed time and context use of a session, as one short line. */
@Composable
private fun sessionMeta(member: CrewMember?, activity: SessionActivity?, nowMs: Long): String =
    listOfNotNull(
        member?.sinceMs?.let { elapsedText(nowMs - it) },
        activity?.contextPercent?.let { stringResource(R.string.cur_context, it) },
    ).joinToString(" · ")

/**
 * Session · Previous / Current / Next (architecture spec §3.1), each live session with its companion, how long it
 * has run and how full its context is. Tapping another session makes it the current one.
 */
@Composable
internal fun CrewSessions(
    trail: SessionTrail,
    crew: Crew,
    sessions: List<Session>,
    activity: Map<String, SessionActivity>,
    currentMood: Mood,
    nowMs: Long,
    clock: () -> Long,
    moving: Boolean,
    onFocus: (String) -> Unit,
) {
    val current = trail.current
    val live = sessions.filter(Crews::isLive)
    val others = crew.members.mapNotNull { m -> live.firstOrNull { it.id == m.sessionId } }.filter { it.id != current?.id }
    val more = live.count { it.id != current?.id } - others.size
    Column(Modifier.fillMaxWidth()) {
        Line(stringResource(R.string.cap_previous), null, trail.previous?.let { "${it.title} · ${stateText(it).lowercase()}" }, muted = true)
        if (current != null) {
            val member = crew.members.firstOrNull { it.sessionId == current.id } ?: CrewMember(current.id, current.title, currentMood, activity[current.id]?.runStartedAtMs ?: current.createdAtMs)
            Line(stringResource(R.string.cap_current), { CompanionGlyph(currentMood, clock, moving, size = 14.dp) }, current.title, strong = true, meta = sessionMeta(member, activity[current.id], nowMs))
        } else {
            Line(stringResource(R.string.cap_current), null, null, strong = true)
        }
        if (others.isEmpty()) {
            Line(stringResource(R.string.cap_next), null, trail.next?.title ?: stringResource(R.string.cap_nothing_queued))
        } else {
            val showLabel = stringResource(R.string.capc_show_session)
            others.forEachIndexed { i, s ->
                val member = crew.members.first { it.sessionId == s.id }
                Line(
                    if (i == 0) stringResource(R.string.cap_next) else null,
                    { CompanionGlyph(member.mood, clock, moving, size = 14.dp) },
                    s.title,
                    meta = sessionMeta(member, activity[s.id], nowMs),
                    modifier = Modifier.clickable(onClickLabel = showLabel) { onFocus(s.id) },
                )
            }
        }
        if (more > 0) Text(
            pluralStringResource(R.plurals.capc_more_running, more, more),
            Modifier.padding(start = LABEL_WIDTH, top = 2.dp, bottom = 2.dp), fontSize = 11.sp, color = Small,
        )
    }
}

@Composable
private fun Line(
    label: String?,
    glyph: (@Composable () -> Unit)?,
    value: String?,
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    muted: Boolean = false,
    meta: String = "",
) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        RowLabel(label)
        if (glyph != null) {
            glyph()
            Box(Modifier.width(8.dp))
        }
        Text(
            value ?: "—", Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = when {
                strong -> pcHue(0xFFFFFFFF)
                muted -> Small
                else -> RowText
            },
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (meta.isNotEmpty()) Text(meta, Modifier.padding(start = 8.dp), fontSize = 11.sp, color = Small, maxLines = 1, style = tabular)
    }
}

/**
 * Step · Previous / Current / Next as a live activity feed of the current session: its last finished steps, what runs
 * now, and the next step of its plan. All of it from the session's own timeline.
 */
@Composable
internal fun CrewSteps(session: Session?, activity: SessionActivity?, mood: Mood, steps: StepTrail, nowMs: Long, clock: () -> Long, moving: Boolean) {
    val active = session?.state == SessionState.RUNNING || session?.state == SessionState.WAITING_APPROVAL
    val feed = activity?.feed.orEmpty()
    val running = if (active) feed.lastOrNull { it.status == FeedEntry.Status.RUNNING } else null
    val previous = feed.filter { it != running && it.status != FeedEntry.Status.RUNNING }.takeLast(2)
    Column(Modifier.fillMaxWidth()) {
        if (previous.isEmpty()) Line(stringResource(R.string.cap_previous), null, steps.previous?.text, muted = true)
        previous.forEachIndexed { i, entry -> FeedLine(if (i == 0) stringResource(R.string.cap_previous) else null, entry, current = false, mood, nowMs, clock, moving) }
        when {
            running != null -> FeedLine(stringResource(R.string.cap_current), running, current = true, mood, nowMs, clock, moving)
            session != null && session.state != SessionState.COMPLETED && session.state != SessionState.STOPPED -> Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                RowLabel(stringResource(R.string.cap_current))
                StatusDot(if (mood == Mood.WAITING) FeedEntry.Status.WARN else FeedEntry.Status.RUNNING, live = active, clock, moving)
                Text(
                    steps.current?.text?.let { "${moodText(mood)} · $it" } ?: moodText(mood),
                    Modifier.padding(start = 8.dp).weight(1f), fontSize = 12.sp, lineHeight = 16.sp, color = pcHue(0xFFFFFFFF), fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            else -> Line(stringResource(R.string.cap_current), null, steps.current?.text, strong = true)
        }
        // With no plan there is no next step to name; say why.
        Line(stringResource(R.string.cap_next), null, steps.next?.text ?: if (session != null && session.plan.isEmpty()) stringResource(R.string.cap_no_steps) else null, muted = true)
    }
}

@Composable
private fun FeedLine(label: String?, entry: FeedEntry, current: Boolean, mood: Mood, nowMs: Long, clock: () -> Long, moving: Boolean) {
    val kind = when (entry.kind) {
        FeedEntry.Kind.TOOL -> null
        FeedEntry.Kind.SAID -> stringResource(R.string.capc_feed_said)
        FeedEntry.Kind.YOU -> stringResource(R.string.capc_feed_you)
        FeedEntry.Kind.APPROVAL -> stringResource(R.string.capc_feed_approval)
        FeedEntry.Kind.PROBLEM -> stringResource(R.string.capc_feed_problem)
    }
    val meta = when {
        current -> elapsedText(nowMs - entry.atMs)
        entry.durationMs > 0 -> shortSeconds(entry.durationMs)?.let { stringResource(R.string.capc_seconds, it) } ?: elapsedText(entry.durationMs)
        else -> ""
    }
    val status = if (current && mood == Mood.WAITING) FeedEntry.Status.WARN else entry.status
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        RowLabel(label)
        StatusDot(status, live = current, clock, moving)
        Text(
            entry.tool ?: kind.orEmpty(), Modifier.padding(start = 8.dp).widthIn(max = 104.dp), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontFamily = if (entry.tool != null) P.mono else null, color = Section,
        )
        Text(
            entry.text, Modifier.padding(start = 6.dp).weight(1f), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (current) pcHue(0xFFFFFFFF) else RowText, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (meta.isNotEmpty()) Text(meta, Modifier.padding(start = 8.dp), fontSize = 11.sp, color = Small, maxLines = 1, style = tabular)
    }
}

/** A small status dot; the current step's breathes while the session works (and motion is allowed). */
@Composable
private fun StatusDot(status: FeedEntry.Status, live: Boolean, clock: () -> Long, moving: Boolean) {
    val color = when (status) {
        FeedEntry.Status.RUNNING, FeedEntry.Status.OK -> pcHue(0xFFA5CBA8)
        FeedEntry.Status.WARN -> pcHue(0xFFE6C186)
        FeedEntry.Status.FAILED -> pcHue(0xFFE59C8D)
        FeedEntry.Status.NEUTRAL -> pcHue(0x80D1E0D2)
    }
    val shown = if (status == FeedEntry.Status.RUNNING && !live) pcHue(0x80D1E0D2) else color
    Canvas(Modifier.size(7.dp)) {
        val t = if (moving && live) clock() else REST
        val pulse = if (t != REST) 0.5f + 0.5f * sin(2 * PI * (t % 1400) / 1400).toFloat() else 1f
        if (t != REST) drawCircle(shown, radius = size.minDimension * (0.5f + 0.35f * pulse), alpha = 0.25f * (1f - pulse))
        drawCircle(shown, radius = size.minDimension / 2f)
    }
}
