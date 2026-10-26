package app.umbra.agent.ui.settings

import android.graphics.RectF
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.CapsuleAnchor
import app.umbra.agent.data.CapsulePlacement
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PIconButton
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.shell.PorcelainPage
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Settings → Capsule → Size and position: the camera's place on the screen, set by hand in pixels (X and Y of its top
 * left corner, width, height). While the page is open a red circle marks the rectangle on the real screen and the
 * Capsule wraps it; lined up, the circle hides behind the camera. Saving switches the Capsule to this placement, so
 * phones whose cutout Android reports wrongly (or not at all) still get a Capsule centred on the camera.
 */
@Composable
fun CapsulePositionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val screen = remember { context.getSystemService(WindowManager::class.java).maximumWindowMetrics }
    val screenW = screen.bounds.width()
    val screenH = screen.bounds.height()
    val reported = remember { reportedCamera(screen, (24 * context.resources.displayMetrics.density).toInt()) }
    val start = remember {
        container.settings.value.capsuleAnchor?.on(screenW, screenH)?.let { CapsuleAnchor(it.left, it.top, it.width(), it.height(), screenW, screenH) } ?: reported
    }
    var x by remember { mutableIntStateOf(start.x) }
    var y by remember { mutableIntStateOf(start.y) }
    var w by remember { mutableIntStateOf(start.width) }
    var h by remember { mutableIntStateOf(start.height) }
    val maxSize = minOf(screenW / 2, 600)
    val maxY = maxOf(screen.windowInsets.getInsets(WindowInsets.Type.statusBars()).top * 3, screenH / 10)
    val draft = CapsuleAnchor(x.coerceIn(0, screenW - w), y.coerceIn(0, maxY), w, h, screenW, screenH)
    // The real screen follows every change while the page is in front; leaving it (Back, or Home with the page still
    // open) ends the preview and the saved placement comes back.
    androidx.lifecycle.compose.LifecycleStartEffect(draft) {
        container.capsule.previewAnchor(draft)
        onStopOrDispose { container.capsule.previewAnchor(null) }
    }
    val accessibility by app.umbra.platform.android.UmbraAccessibilityService.connected.collectAsState()
    val canPreview = Settings.canDrawOverlays(context) || accessibility

    PorcelainPage(stringResource(R.string.cap_pos_title), onBack, actions = {
        PIconButton(PIcons.refresh, stringResource(R.string.cap_pos_reset), {
            x = reported.x; y = reported.y; w = reported.width; h = reported.height
        }, size = 36.dp)
    }) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 110.dp),
            ) {
                Eyebrow(stringResource(R.string.cap_pos_example), Modifier.padding(top = 8.dp))
                Example(Modifier.padding(top = 10.dp))
                Text(
                    stringResource(R.string.cap_pos_help), Modifier.fillMaxWidth().padding(top = 12.dp, start = 8.dp, end = 8.dp),
                    fontSize = 13.sp, lineHeight = 19.sp, color = P.sub, textAlign = TextAlign.Center,
                )
                if (!canPreview) Callout(stringResource(R.string.cap_pos_no_overlay), Tone.Warn, Modifier.padding(top = 12.dp))
                else if (!accessibility) {
                    // Only an accessibility window may sit above the status bar and still be tapped.
                    Callout(stringResource(R.string.cap_pos_needs_service), Tone.Warn, Modifier.padding(top = 12.dp))
                    TextAction(stringResource(R.string.cap_pos_turn_on), Modifier.padding(top = 4.dp)) {
                        runCatching { context.startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
                AnchorRow(stringResource(R.string.cap_pos_x), x, 0..(screenW - w), Glyph.LEFT to stringResource(R.string.cap_pos_left),
                    Glyph.RIGHT to stringResource(R.string.cap_pos_right), action = stringResource(R.string.cap_pos_centre) to { x = (screenW - w) / 2 }) { x = it }
                AnchorRow(stringResource(R.string.cap_pos_y), y, 0..maxY, Glyph.UP to stringResource(R.string.cap_pos_up),
                    Glyph.DOWN to stringResource(R.string.cap_pos_down)) { y = it }
                AnchorRow(stringResource(R.string.cap_pos_width), w, MIN_SIZE..maxSize, Glyph.NARROWER to stringResource(R.string.cap_pos_narrower),
                    Glyph.WIDER to stringResource(R.string.cap_pos_wider)) { v ->
                    // Narrowing or widening keeps the circle's centre where it is.
                    val centre = x + w / 2
                    w = v
                    x = (centre - v / 2).coerceIn(0, screenW - v)
                }
                AnchorRow(stringResource(R.string.cap_pos_height), h, MIN_SIZE..maxSize, Glyph.SHORTER to stringResource(R.string.cap_pos_shorter),
                    Glyph.TALLER to stringResource(R.string.cap_pos_taller)) { v ->
                    val centre = y + h / 2
                    h = v
                    y = (centre - v / 2).coerceIn(0, maxY)
                }
                TextAction(stringResource(R.string.cap_pos_auto), Modifier.padding(top = 22.dp).align(Alignment.CenterHorizontally)) {
                    container.settings.update { it.copy(capsulePlacement = CapsulePlacement.AUTO) }
                    onBack()
                }
            }
            PButton(
                stringResource(R.string.cap_pos_save), {
                    container.settings.update { it.copy(capsulePlacement = CapsulePlacement.CUSTOM, capsuleAnchor = draft) }
                    onBack()
                },
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp),
                style = ButtonStyle.Primary,
            )
        }
    }
}

private const val MIN_SIZE = 8

@Composable
private fun TextAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = P.accent2,
    )
}

/** The camera as the screen reports it (the cutout's path within its top rectangle), or a circle at the top centre. */
private fun reportedCamera(metrics: android.view.WindowMetrics, fallback: Int): CapsuleAnchor {
    val w = metrics.bounds.width()
    val h = metrics.bounds.height()
    val cutout = metrics.windowInsets.displayCutout
    val top = cutout?.boundingRectTop?.takeIf { !it.isEmpty }
    val rect = top?.let { t ->
        cutout.cutoutPath?.let { path ->
            val f = RectF().also { path.computeBounds(it, true) }
            android.graphics.Rect(floor(f.left).toInt(), floor(f.top).toInt(), ceil(f.right).toInt(), ceil(f.bottom).toInt()).takeIf { it.intersect(t) }
        } ?: t
    }
    if (rect != null && rect.width() in MIN_SIZE..w / 2) return CapsuleAnchor(rect.left, rect.top, rect.width(), rect.height().coerceAtLeast(MIN_SIZE), w, h)
    val statusTop = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
    return CapsuleAnchor((w - fallback) / 2, ((statusTop - fallback) / 2).coerceAtLeast(0), fallback, fallback, w, h)
}

private enum class Glyph { LEFT, RIGHT, UP, DOWN, NARROWER, WIDER, SHORTER, TALLER }

/** One value: its name and pixels, a step button at each end (hold to repeat) and a slider between them. */
@Composable
private fun AnchorRow(
    label: String,
    value: Int,
    range: IntRange,
    less: Pair<Glyph, String>,
    more: Pair<Glyph, String>,
    action: Pair<String, () -> Unit>? = null,
    onChange: (Int) -> Unit,
) {
    val safe = if (range.last > range.first) range else range.first..(range.first + 1)
    val current by rememberUpdatedState(value)
    Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.ink)
        Text(
            "$value", Modifier.padding(start = 10.dp).weight(1f), fontSize = 15.sp, color = P.sub,
            style = TextStyle(fontFeatureSettings = P.tabular),
        )
        action?.let { (text, run) -> TextAction(text, onClick = run) }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StepButton(less.first, less.second) { onChange((current - 1).coerceIn(safe)) }
        Slider(
            value.toFloat().coerceIn(safe.first.toFloat(), safe.last.toFloat()),
            { onChange(it.toInt().coerceIn(safe)) },
            Modifier.weight(1f),
            valueRange = safe.first.toFloat()..safe.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = P.ink, activeTrackColor = P.ink, inactiveTrackColor = P.line, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        )
        StepButton(more.first, more.second) { onChange((current + 1).coerceIn(safe)) }
    }
}

/** A pixel at a tap; held, it repeats faster and faster, so a long move needs no slider. */
@Composable
private fun StepButton(glyph: Glyph, description: String, step: () -> Unit) {
    val onStep by rememberUpdatedState(step)
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.size(52.dp).clip(shape).background(P.base2)
            .semantics { contentDescription = description; role = Role.Button; onClick { onStep(); true } }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    coroutineScope {
                        val repeat = launch {
                            onStep()
                            delay(380)
                            var wait = 90L
                            while (true) { onStep(); delay(wait); wait = (wait * 0.86).toLong().coerceAtLeast(16) }
                        }
                        tryAwaitRelease()
                        repeat.cancel()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) { drawGlyph(glyph, P.ink) }
    }
}

/** Chevrons drawn in the button's own stroke: one for a move, a pair facing in or out for a size. */
private fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun chevron(cx: Float, cy: Float, dx: Float, dy: Float, k: Float) {
        // A "<" pointing along (dx, dy), its tip at (cx, cy).
        val back = Offset(cx - dx * k, cy - dy * k)
        val side = Offset(-dy * k, dx * k)
        drawPath(Path().apply { moveTo(back.x + side.x, back.y + side.y); lineTo(cx, cy); lineTo(back.x - side.x, back.y - side.y) }, color, style = stroke)
    }
    val c = s / 2
    when (glyph) {
        Glyph.LEFT -> chevron(c - s * 0.16f, c, -1f, 0f, s * 0.3f)
        Glyph.RIGHT -> chevron(c + s * 0.16f, c, 1f, 0f, s * 0.3f)
        Glyph.UP -> chevron(c, c - s * 0.16f, 0f, -1f, s * 0.3f)
        Glyph.DOWN -> chevron(c, c + s * 0.16f, 0f, 1f, s * 0.3f)
        // "><" and "<>": the two chevrons a clear gap apart, so a pair never reads as an "×".
        Glyph.NARROWER -> { chevron(c - s * 0.1f, c, 1f, 0f, s * 0.2f); chevron(c + s * 0.1f, c, -1f, 0f, s * 0.2f) }
        Glyph.WIDER -> { chevron(s * 0.1f, c, -1f, 0f, s * 0.2f); chevron(s * 0.9f, c, 1f, 0f, s * 0.2f) }
        Glyph.SHORTER -> { chevron(c, c - s * 0.1f, 0f, 1f, s * 0.2f); chevron(c, c + s * 0.1f, 0f, -1f, s * 0.2f) }
        Glyph.TALLER -> { chevron(c, s * 0.1f, 0f, -1f, s * 0.2f); chevron(c, s * 0.9f, 0f, 1f, s * 0.2f) }
    }
}

/**
 * The example: on the left the circle beside the camera (wrong), on the right hidden behind it (right), each inside a
 * grey ring like the Capsule around the camera.
 */
@Composable
private fun Example(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(36.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(P.base2).padding(vertical = 26.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        val ring = Color(0xFF77777F)
        val red = Color(0xFFFF5F57)
        val camera = Color(0xFF0B0B0B)
        Canvas(Modifier.size(84.dp)) {
            val r = size.minDimension / 2
            drawCircle(ring, r)
            drawCircle(Color.White, r * 0.52f)
            drawCircle(red, r * 0.44f)
            drawCircle(camera, r * 0.42f, Offset(center.x - r * 0.52f, center.y - r * 0.52f))
        }
        Canvas(Modifier.size(28.dp)) {
            val s = size.minDimension
            val stroke = Stroke(width = s * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawLine(P.sub, Offset(s * 0.1f, s / 2), Offset(s * 0.85f, s / 2), s * 0.09f, StrokeCap.Round)
            drawPath(Path().apply { moveTo(s * 0.55f, s * 0.22f); lineTo(s * 0.88f, s / 2); lineTo(s * 0.55f, s * 0.78f) }, P.sub, style = stroke)
        }
        Canvas(Modifier.size(84.dp)) {
            val r = size.minDimension / 2
            drawCircle(ring, r)
            drawCircle(Color.White, r * 0.52f)
            drawCircle(camera, r * 0.44f)
        }
    }
    Spacer(Modifier.height(0.dp))
}
