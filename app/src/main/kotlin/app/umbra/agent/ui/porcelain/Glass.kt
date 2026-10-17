package app.umbra.agent.ui.porcelain

import android.graphics.RuntimeShader
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/*
 * The Apple theme's Liquid Glass, the whole system rather than a look:
 *  - material: what lies behind is sampled live, made vivid, blurred a little and bent by a lens at the edges; the rim
 *    catches the one light (LiquidLight, which the phone's tilt swings); a soft shadow falls away from the light;
 *  - layers: glass is for the functional layer only (bars, controls, the sidebar), never for content cards, and never
 *    glass on glass; bars float over the content and refract it as it scrolls under them;
 *  - interaction: a touch lights the glass from under the finger, the glass swells and leans toward a drag
 *    (rubber-banded, so it resists more the further it goes) and settles on a critically damped spring;
 *  - selection is a lens: the tab bar's, the segmented control's and the switch's thumbs turn into clear glass while
 *    held, magnify what they pass over, follow the finger one to one, and on release land where the motion was going;
 *  - arrival: glass materializes (scale, blur and opacity together) instead of fading in;
 *  - restraint: off (or with Android's high-contrast text on, the closest thing to "reduce transparency"), the same
 *    controls become iOS's classic opaque materials.
 *
 * DampedDrag, the drag inspector, the interactive highlight and the glass tab bar, switch and lens thumbs follow the
 * catalog of Kyant's AndroidLiquidGlass (github.com/Kyant0/AndroidLiquidGlass, Apache-2.0).
 */

// ---- motion vocabulary ----

/** Apple's spring parameters: damping ratio (1 = no overshoot) and response in seconds (not a duration). */
fun <T> appleSpring(damping: Float = 1f, response: Float = 0.35f, threshold: T? = null): androidx.compose.animation.core.SpringSpec<T> {
    val w = 2f * PI.toFloat() / response
    return spring(dampingRatio = damping, stiffness = w * w, visibilityThreshold = threshold)
}

/** Resistance past an edge: the further past, the less it follows (UIScrollView's rubber band). */
fun rubberBand(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float =
    if (dimension <= 0f) 0f else (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))

/** Where a flick comes to rest (UIScrollView deceleration), from a velocity in px/s. */
fun projectMomentum(velocity: Float, deceleration: Float = 0.998f): Float = (velocity / 1000f) * deceleration / (1f - deceleration)

/** Android's high-contrast text, read as "reduce transparency": glass falls back to the classic materials. */
@Composable
fun rememberReduceTransparency(): Boolean {
    val context = LocalContext.current
    return remember { runCatching { Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1 }.getOrDefault(false) }
}

/** The Apple theme draws Liquid Glass right now (its switch is on and transparency is not reduced). */
@Composable
fun appleGlassActive(): Boolean = Theme.apple && Theme.current.appleGlass && !rememberReduceTransparency()

// ---- touch ----

/** Every drag from its first touch, without consuming it (so a click on the same element still works). */
suspend fun PointerInputScope.inspectDragGestures(
    /** Stop when someone else consumed the drag (a scroll took over); the light under a finger keeps following regardless. */
    stopOnConsumed: Boolean = true,
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)
        onDragStart(down)
        onDrag(initialDown, Offset.Zero)
        var pointer = initialDown.id
        var up: PointerInputChange? = null
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointer } ?: break
            if (change.changedToUpIgnoreConsumed()) {
                val other = event.changes.firstOrNull { it.pressed }
                if (other == null) {
                    up = change
                    break
                }
                pointer = other.id
                continue
            }
            if (stopOnConsumed && change.isConsumed) break
            if (change.previousPosition != change.position) onDrag(change, change.positionChange())
        }
        if (up == null) onDragCancel() else onDragEnd(up)
    }
}

/**
 * A value the finger drags directly (a thumb's position), with the press state, the squash of the thumb and its
 * velocity, all on springs, so it can be grabbed and redirected at any moment.
 */
@Stable
class DampedDrag(
    private val scope: CoroutineScope,
    initialValue: Float,
    val valueRange: ClosedFloatingPointRange<Float>,
    private val initialScale: Float,
    private val pressedScale: Float,
    val onDragStarted: DampedDrag.(position: Offset) -> Unit = {},
    val onDragStopped: DampedDrag.(velocity: Float) -> Unit,
    val onDrag: DampedDrag.(width: Int, dragAmount: Offset) -> Unit,
) {
    private val valueSpec = spring(1f, 1000f, 0.001f)
    private val velocitySpec = spring(0.5f, 300f, 0.01f)
    private val pressSpec = spring(1f, 1000f, 0.001f)
    private val scaleXSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnim = Animatable(initialValue, 0.001f)
    private val velocityAnim = Animatable(0f, 5f)
    private val pressAnim = Animatable(0f, 0.001f)
    private val scaleXAnim = Animatable(initialScale, 0.001f)
    private val scaleYAnim = Animatable(initialScale, 0.001f)
    private val mutex = MutatorMutex()
    private val tracker = VelocityTracker()
    private val fingerTracker = VelocityTracker()

    val value get() = valueAnim.value
    val targetValue get() = valueAnim.targetValue
    val progress get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val pressProgress get() = pressAnim.value
    val scaleX get() = scaleXAnim.value
    val scaleY get() = scaleYAnim.value
    val velocity get() = velocityAnim.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                fingerTracker.resetTracking()
                onDragStarted(down.position)
                press()
            },
            onDragEnd = { up ->
                fingerTracker.addPosition(up.uptimeMillis, up.position)
                onDragStopped(fingerTracker.calculateVelocity().x)
                release()
            },
            onDragCancel = {
                onDragStopped(0f)
                release()
            },
        ) { change, amount ->
            fingerTracker.addPosition(change.uptimeMillis, change.position)
            onDrag(size.width, amount)
            // a thumb owns its drag: the drawer and the page do not move under it
            if (amount != Offset.Zero) change.consume()
        }
    }

    fun press() {
        tracker.resetTracking()
        scope.launch {
            launch { pressAnim.animateTo(1f, pressSpec) }
            launch { scaleXAnim.animateTo(pressedScale, scaleXSpec) }
            launch { scaleYAnim.animateTo(pressedScale, scaleYSpec) }
        }
    }

    fun release() {
        scope.launch {
            awaitFrame()
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnim.value }.filter { abs(it - valueAnim.targetValue) < threshold }.first()
            }
            launch { pressAnim.animateTo(0f, pressSpec) }
            launch { scaleXAnim.animateTo(initialScale, scaleXSpec) }
            launch { scaleYAnim.animateTo(initialScale, scaleYSpec) }
        }
    }

    fun updateValue(v: Float) {
        val t = v.coerceIn(valueRange)
        scope.launch { valueAnim.animateTo(t, valueSpec) { updateVelocity() } }
    }

    fun animateToValue(v: Float) {
        scope.launch {
            mutex.mutate {
                press()
                val t = v.coerceIn(valueRange)
                launch { valueAnim.animateTo(t, valueSpec) }
                if (velocity != 0f) launch { velocityAnim.animateTo(0f, velocitySpec) }
                release()
            }
        }
    }

    fun snapTo(v: Float) {
        scope.launch { valueAnim.snapTo(v.coerceIn(valueRange)) }
    }

    private fun updateVelocity() {
        tracker.addPosition(System.currentTimeMillis(), Offset(value, 0f))
        val v = tracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        scope.launch { velocityAnim.animateTo(v, velocitySpec) }
    }
}

/** Stretches a lens thumb along its motion: faster means longer and thinner. */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.lensStretch(drag: DampedDrag, divisor: Float) {
    scaleX = drag.scaleX
    scaleY = drag.scaleY
    val v = drag.velocity / divisor
    scaleX /= 1f - (v * 0.75f).coerceIn(-0.2f, 0.2f)
    scaleY *= 1f - (v * 0.25f).coerceIn(-0.2f, 0.2f)
}

/**
 * The glass's response to a touch: a pool of light under the finger that blooms on press, the glass swelling a few
 * dp, and leaning toward a drag with rubber-band resistance; everything springs back on release.
 */
@Stable
class InteractiveHighlight(private val scope: CoroutineScope, val position: (Size, Offset) -> Offset = { _, o -> o }) {
    private val pressAnim = Animatable(0f, 0.001f)
    private val positionAnim = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var start = Offset.Zero
    private val shader = RuntimeShader(
        """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;
half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}""",
    )
    private val brush = object : ShaderBrush() {
        override fun createShader(size: Size): android.graphics.Shader = shader
    }

    val pressProgress get() = pressAnim.value
    val offset get() = positionAnim.value - start

    val modifier: Modifier = Modifier.drawWithContent {
        val p = pressAnim.value
        if (p > 0f) {
            drawRect(Color.White.copy(alpha = 0.08f * p), blendMode = BlendMode.Plus)
            val at = position(size, positionAnim.value)
            shader.setFloatUniform("size", size.width, size.height)
            shader.setColorUniform("color", android.graphics.Color.argb((0.15f * p * 255).roundToInt(), 255, 255, 255))
            shader.setFloatUniform("radius", size.minDimension * 1.5f)
            shader.setFloatUniform("position", at.x.coerceIn(0f, size.width), at.y.coerceIn(0f, size.height))
            drawRect(brush, blendMode = BlendMode.Plus)
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(scope) {
        val spec = spring(0.5f, 300f, 0.001f)
        val posSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)
        inspectDragGestures(
            stopOnConsumed = false,
            onDragStart = { down ->
                start = down.position
                scope.launch {
                    launch { pressAnim.animateTo(1f, spec) }
                    launch { positionAnim.snapTo(start) }
                }
            },
            onDragEnd = {
                scope.launch {
                    launch { pressAnim.animateTo(0f, spec) }
                    launch { positionAnim.animateTo(start, posSpec) }
                }
            },
            onDragCancel = {
                scope.launch {
                    launch { pressAnim.animateTo(0f, spec) }
                    launch { positionAnim.animateTo(start, posSpec) }
                }
            },
        ) { change, _ -> scope.launch { positionAnim.snapTo(change.position) } }
    }

    /** Swell on press and lean toward the drag, with resistance (Kyant's LiquidButton). */
    fun applyTo(layer: androidx.compose.ui.graphics.GraphicsLayerScope, swell: Float) = with(layer) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@with
        val p = pressProgress
        val scale = 1f + (swell / h) * p
        val maxOffset = size.minDimension
        val o = offset
        translationX = maxOffset * tanh(0.05f * o.x / maxOffset)
        translationY = maxOffset * tanh(0.05f * o.y / maxOffset)
        val maxDrag = swell / h
        val angle = atan2(o.y, o.x)
        scaleX = scale + maxDrag * abs(cos(angle) * o.x / size.maxDimension) * (w / h).coerceAtMost(1f)
        scaleY = scale + maxDrag * abs(sin(angle) * o.y / size.maxDimension) * (h / w).coerceAtMost(1f)
    }
}

@Composable
fun rememberInteractiveHighlight(position: (Size, Offset) -> Offset = { _, o -> o }): InteractiveHighlight {
    val scope = rememberCoroutineScope()
    return remember(scope) { InteractiveHighlight(scope, position) }
}

// ---- material ----

/** The surface a glass control sits on (the page, a card), which it refracts when no live backdrop is behind it. */
val LocalGlassSurface = androidx.compose.runtime.staticCompositionLocalOf { Color.Unspecified }

/** The rim light of Apple glass: brightest on the edges facing [LiquidLight] and the opposite ones. */
fun appleRim(alpha: Float = 1f, width: Dp = 0.75.dp) =
    Highlight(width = width, blurRadius = width / 2f, alpha = alpha, style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.7f), angle = LiquidLight.angle, falloff = 1.4f))

/** The shadow under Apple glass, falling away from the light. */
fun appleShadow(radius: Dp, alpha: Float): Shadow {
    val (lx, ly) = LiquidLight.direction()
    return Shadow(radius = radius, offset = androidx.compose.ui.unit.DpOffset(radius * -lx * 0.2f, radius * (0.18f - ly * 0.12f)), color = Color.Black.copy(alpha = alpha))
}

/** The backdrop glass inside a page refracts: the surface under it, drawn flat (content glass would sample itself). */
@Composable
fun rememberSurfaceBackdrop(): Backdrop {
    val surface = LocalGlassSurface.current.takeIf { it.isSpecified() } ?: Theme.current.tokens.base
    val draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = remember(surface) { { drawRect(surface) } }
    return rememberCanvasBackdrop(draw)
}

private fun Color.isSpecified() = this != Color.Unspecified

enum class GlassTint { Clear, Regular, Prominent }

/**
 * Apple Liquid Glass on a control: refraction of [backdrop], vibrancy and a little blur, the rim light, a soft shadow,
 * a tint ([GlassTint.Prominent] fills with [tint], as `.glassProminent` does), and with [highlight] the interactive
 * response (light under the finger, swell, lean).
 */
fun Modifier.appleGlass(
    backdrop: Backdrop,
    shape: Shape,
    kind: GlassTint = GlassTint.Regular,
    tint: Color = Color.Unspecified,
    highlight: InteractiveHighlight? = null,
    swell: Dp = 4.dp,
    shadow: Boolean = true,
    dark: Boolean = Theme.dark,
): Modifier = this
    .drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(2.dp.toPx())
            lens(12.dp.toPx(), 24.dp.toPx())
        },
        highlight = { appleRim(if (kind == GlassTint.Prominent) 0.8f else 1f) },
        shadow = if (shadow) ({ appleShadow(14.dp, if (dark) 0.35f else 0.10f) }) else null,
        layerBlock = if (highlight != null) ({ highlight.applyTo(this, swell.toPx()) }) else null,
        onDrawSurface = {
            when (kind) {
                GlassTint.Prominent -> if (tint.isSpecified()) {
                    drawRect(tint, blendMode = BlendMode.Hue)
                    drawRect(tint.copy(alpha = 0.78f))
                }
                GlassTint.Regular -> drawRect(if (dark) Color.Black.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.42f))
                GlassTint.Clear -> drawRect(if (dark) Color.White.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.10f))
            }
        },
    )
    .then(highlight?.modifier ?: Modifier)
    .then(highlight?.gestureModifier ?: Modifier)

/**
 * Glass arriving: it grows from 85 %, sharpens from a blur and becomes opaque together, on a critically damped
 * spring, so it reads as a material taking shape rather than a fade.
 */
fun Modifier.materialize(): Modifier = this then MaterializeElement

private data object MaterializeElement : ModifierNodeElement<MaterializeNode>() {
    override fun create() = MaterializeNode()
    override fun update(node: MaterializeNode) = Unit
    override fun InspectorInfo.inspectableProperties() { name = "materialize" }
}

private class MaterializeNode : Modifier.Node(), LayoutModifierNode {
    private val p = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch { p.animateTo(1f, appleSpring(1f, 0.45f)) }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val v = p.value
                alpha = v.coerceIn(0f, 1f)
                scaleX = 0.85f + 0.15f * v
                scaleY = 0.85f + 0.15f * v
                val b = (1f - v) * 16.dp.toPx()
                renderEffect = if (b > 0.5f) BlurEffect(b, b, TileMode.Decal) else null
            }
        }
    }
}

// ---- press without ripple ----

/**
 * iOS's touch feedback for rows and plain controls: a grey highlight the instant a finger lands (no ripple), fading
 * out when it lifts.
 */
object AppleHighlightIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = AppleHighlightNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0x41504c
}

private class AppleHighlightNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val level = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> launch { level.snapTo(1f); invalidateDraw() }
                    is PressInteraction.Release, is PressInteraction.Cancel -> launch { level.animateTo(0f, tween(220)) { invalidateDraw() } }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val l = level.value
        if (l > 0f) drawRect(if (Theme.dark) Color.White.copy(alpha = 0.10f * l) else Color.Black.copy(alpha = 0.07f * l))
    }
}

// ---- switch ----

/**
 * The Apple switch. With glass, the thumb turns into a clear lens while held or moving (Kyant's LiquidToggle): it
 * swells, refracts the track under it and stretches along its travel. Without glass it is UISwitch: green track,
 * white knob that widens while held. [onChange] null makes it display-only (the row around it toggles).
 */
@Composable
fun AppleSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val glass = appleGlassActive()
    val dark = Theme.dark
    val on = AppleColors.green(dark)
    val track = if (dark) Color(0xFF787880).copy(0.36f) else Color(0xFF787878).copy(0.2f)
    val density = LocalDensity.current
    val travel = with(density) { 20.dp.toPx() }
    val scope = rememberCoroutineScope()
    val change by rememberUpdatedState(onChange)
    var dragged by remember { mutableStateOf(false) }
    var fraction by remember { mutableStateOf(if (checked) 1f else 0f) }
    val drag = remember(scope) {
        DampedDrag(
            scope, fraction, 0f..1f, initialScale = 1f, pressedScale = if (glass) 1.5f else 1f,
            onDragStopped = {
                val c = change
                if (c != null) {
                    fraction = if (dragged) (if (targetValue >= 0.5f) 1f else 0f) else (if (checked) 0f else 1f)
                    dragged = false
                    c(fraction == 1f)
                }
            },
            onDrag = { _, amount ->
                if (change == null) return@DampedDrag
                if (!dragged) dragged = amount.x != 0f
                fraction = (fraction + amount.x / travel).coerceIn(0f, 1f)
            },
        )
    }
    LaunchedEffect(drag) { snapshotFlow { fraction }.collectLatest { drag.updateValue(it) } }
    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (target != fraction) {
            fraction = target
            drag.animateToValue(target)
        }
    }
    val trackLayer = rememberLayerBackdrop()
    val surface = rememberSurfaceBackdrop()
    val capsule = RoundedCornerShape(50)
    Box(
        modifier
            .size(64.dp, 28.dp)
            .then(if (!enabled) Modifier.alpha(0.4f) else Modifier)
            .semantics {
                role = Role.Switch
                toggleableState = androidx.compose.ui.state.ToggleableState(checked)
                if (onChange != null && enabled) onClick { onChange(!checked); true }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .layerBackdrop(trackLayer)
                .clip(capsule)
                .drawBehind { drawRect(lerp(track, on, drag.value)) }
                .size(64.dp, 28.dp),
        )
        val thumb = Modifier
            .graphicsLayer {
                val pad = 2.dp.toPx()
                translationX = pad + travel * drag.value
            }
            .then(if (onChange != null && enabled) drag.modifier else Modifier)
            .clearAndSetSemantics { }
        if (glass) {
            Box(
                thumb
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(
                            surface,
                            rememberBackdrop(trackLayer) { draw ->
                                val p = drag.pressProgress
                                scale(androidx.compose.ui.util.lerp(2f / 3f, 0.75f, p), androidx.compose.ui.util.lerp(0f, 0.75f, p)) { draw() }
                            },
                        ),
                        shape = { capsule },
                        effects = {
                            val p = drag.pressProgress
                            blur(8.dp.toPx() * (1f - p))
                            lens(5.dp.toPx() * p, 10.dp.toPx() * p, chromaticAberration = true)
                        },
                        highlight = { Highlight.Ambient.copy(width = Highlight.Ambient.width / 1.5f, blurRadius = Highlight.Ambient.blurRadius / 1.5f, alpha = drag.pressProgress) },
                        shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f)) },
                        innerShadow = { InnerShadow(radius = 4.dp * drag.pressProgress, alpha = drag.pressProgress) },
                        layerBlock = { lensStretch(drag, 50f) },
                        onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - drag.pressProgress)) },
                    )
                    .size(40.dp, 24.dp),
            )
        } else {
            // UISwitch's knob: white, a soft shadow, and 7 dp wider while held (it leans toward where it will go)
            Box(
                thumb
                    .graphicsLayer {
                        val grow = drag.pressProgress * 7.dp.toPx()
                        translationX -= grow * drag.value
                    }
                    .drawBehind {
                        val grow = drag.pressProgress * 7.dp.toPx()
                        val w = 24.dp.toPx() + grow
                        val h = 24.dp.toPx()
                        drawRoundRect(Color.Black.copy(alpha = 0.12f), topLeft = Offset(0f, 2.dp.toPx()), size = Size(w, h), cornerRadius = CornerRadius(h / 2))
                        drawRoundRect(Color.White, size = Size(w, h), cornerRadius = CornerRadius(h / 2))
                    }
                    .size(24.dp, 24.dp),
            )
        }
    }
}

// ---- segmented control ----

/**
 * UISegmentedControl. The thumb can be tapped to or dragged (it follows the finger one to one and lands on the segment
 * the motion was heading for); with glass, while held it becomes a clear lens that magnifies the labels under it.
 */
@Composable
fun <T> AppleSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier, fill: Boolean) {
    val glass = appleGlassActive()
    val dark = Theme.dark
    val count = options.size.coerceAtLeast(1)
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val select by rememberUpdatedState(onSelect)
    val opts by rememberUpdatedState(options)
    val scope = rememberCoroutineScope()
    var segment by remember { mutableStateOf(1f) }
    val drag = remember(scope, count) {
        DampedDrag(
            scope, index.toFloat(), 0f..(count - 1).toFloat(), initialScale = 1f, pressedScale = if (glass) 1.25f else 0.96f,
            onDragStopped = { v ->
                val landing = (targetValue + projectMomentum(v) / segment.coerceAtLeast(1f)).roundToInt().coerceIn(0, count - 1)
                animateToValue(landing.toFloat())
                opts.getOrNull(landing)?.let { select(it.first) }
            },
            onDrag = { _, amount -> updateValue(targetValue + amount.x / segment.coerceAtLeast(1f)) },
        )
    }
    LaunchedEffect(index) { if (abs(drag.targetValue - index) > 0.01f) drag.animateToValue(index.toFloat()) }
    val trackLayer = rememberLayerBackdrop()
    val surface = rememberSurfaceBackdrop()
    val track = AppleColors.fill(dark)
    val thumbColor = if (dark) Color(0xFF636366) else Color.White
    val outer = RoundedCornerShape(10.dp)
    val inner = RoundedCornerShape(8.dp)
    val ink = Theme.current.tokens.ink
    BoxWithConstraints(modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).height(36.dp)) {
        val widthPx = constraints.maxWidth.toFloat()
        val pad = with(LocalDensity.current) { 2.dp.toPx() }
        segment = (widthPx - 2 * pad) / count
        Row(
            Modifier
                .layerBackdrop(trackLayer)
                .fillMaxWidth()
                .height(36.dp)
                .clip(outer)
                .drawBehind { drawRect(track) }
                .padding(2.dp),
        ) {
            options.forEachIndexed { i, (value, label) ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = null, indication = null, role = Role.Tab) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) { Text(label, fontSize = 13.sp, fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Medium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        val thumbMod = Modifier
            .padding(2.dp)
            .graphicsLayer { translationX = drag.value * segment }
            .then(drag.modifier)
            .height(32.dp)
            .fillMaxWidth(1f / count)
        if (glass) {
            Box(
                thumbMod.drawBackdrop(
                    backdrop = rememberCombinedBackdrop(surface, trackLayer),
                    shape = { inner },
                    effects = {
                        val p = drag.pressProgress
                        lens(8.dp.toPx() * p, 12.dp.toPx() * p, chromaticAberration = true)
                    },
                    highlight = { appleRim(0.4f + 0.6f * drag.pressProgress) },
                    shadow = { Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.10f * (1f - drag.pressProgress) + 0.06f)) },
                    innerShadow = { InnerShadow(radius = 6.dp * drag.pressProgress, alpha = drag.pressProgress) },
                    layerBlock = { lensStretch(drag, 10f) },
                    onDrawSurface = { drawRect(thumbColor.copy(alpha = (1f - drag.pressProgress) * thumbColor.alpha)) },
                ),
            ) {
                // the label rides on the thumb at rest; while held it fades and the lens shows the track's, magnified
                Text(
                    options.getOrNull(drag.value.roundToInt().coerceIn(0, count - 1))?.second.orEmpty(),
                    Modifier.align(Alignment.Center).graphicsLayer { alpha = 1f - drag.pressProgress },
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Box(
                thumbMod
                    .graphicsLayer {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                    }
                    .drawBehind {
                        val r = CornerRadius(8.dp.toPx())
                        drawRoundRect(Color.Black.copy(alpha = 0.10f), topLeft = Offset(0f, 2.dp.toPx()), size = size, cornerRadius = r)
                        drawRoundRect(thumbColor, cornerRadius = r)
                    },
            ) {
                Text(
                    options.getOrNull(drag.value.roundToInt().coerceIn(0, count - 1))?.second.orEmpty(),
                    Modifier.align(Alignment.Center), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---- tab bar ----

/** One tab of the Apple tab bar: an icon over a short label. */
class AppleTab(val icon: IconSpec, val label: String, val badge: Int = 0)

/**
 * The Apple tab bar over [content] (the live layer of the pages, so the bar refracts what scrolls under it).
 *
 * Glass: a floating capsule (Kyant's LiquidBottomTabs). Pressing anywhere on it swells the bar and lifts the
 * selection into a clear lens that follows the finger across the tabs, magnifying the tinted icons under it; release
 * lands on the tab the motion was heading for. Classic: iOS's full-width translucent bar with a hairline.
 */
@Composable
fun AppleTabBar(tabs: List<AppleTab>, selected: Int, onSelect: (Int) -> Unit, content: com.kyant.backdrop.backdrops.LayerBackdrop, contentOrigin: () -> Offset) {
    if (appleGlassActive()) AppleGlassTabBar(tabs, selected, onSelect, content, contentOrigin) else AppleClassicTabBar(tabs, selected, onSelect, content)
}

@Composable
private fun AppleClassicTabBar(tabs: List<AppleTab>, selected: Int, onSelect: (Int) -> Unit, content: Backdrop) {
    val dark = Theme.dark
    val accent = Theme.current.accentColor
    val inactive = Color(if (dark) 0xFF8E8E93 else 0xFF999999)
    val material = if (dark) Color(0xFF161616).copy(alpha = 0.78f) else Color(0xFFF9F9F9).copy(alpha = 0.80f)
    val hairline = AppleColors.separator(dark)
    Box(
        Modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = content,
                shape = { androidx.compose.ui.graphics.RectangleShape },
                effects = {
                    vibrancy()
                    blur(24.dp.toPx())
                },
                highlight = null,
                shadow = null,
                onDrawSurface = {
                    drawRect(material)
                    drawRect(hairline, size = Size(size.width, 0.5.dp.toPx().coerceAtLeast(1f)))
                },
            )
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(50.dp)) {
            tabs.forEachIndexed { i, tab ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = null, indication = null, role = Role.Tab, onClickLabel = tab.label) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    TabGlyph(tab, if (on) accent else inactive)
                    Text(tab.label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = if (on) accent else inactive, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TabGlyph(tab: AppleTab, tint: Color) {
    Box {
        PIcon(tab.icon, size = 22.dp, tint = tint)
        if (tab.badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(start = 16.dp).size(16.dp).clip(RoundedCornerShape(50)).drawBehind { drawRect(AppleColors.red(Theme.dark)) },
                contentAlignment = Alignment.Center,
            ) { Text("${tab.badge.coerceAtMost(9)}", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun AppleGlassTabBar(tabs: List<AppleTab>, selected: Int, onSelect: (Int) -> Unit, content: com.kyant.backdrop.backdrops.LayerBackdrop, contentOrigin: () -> Offset) {
    // the glass takes its tint from what scrolls under it, not from the theme: dark content, dark glass and light ink
    val tone = rememberBackdropTone(content.graphicsLayer, contentOrigin, Theme.dark, Theme.current.tokens.base)
    val t = tone.value
    val dark = t > 0.5f
    val accent = Theme.current.accentColor
    val container = lerp(Color(0xFFFAFAFA).copy(0.40f), Color(0xFF121212).copy(0.40f), t)
    val ink = lerp(Color.Black, Color.White, t)
    val count = tabs.size
    val tabsLayer = rememberLayerBackdrop()
    val scope = rememberCoroutineScope()
    val select by rememberUpdatedState(onSelect)
    val capsule = RoundedCornerShape(50)
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 10.dp, top = 6.dp).materialize().then(tone.modifier)) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            val density = LocalDensity.current
            val tabWidth = with(density) { (constraints.maxWidth.toFloat() - 8.dp.toPx()) / count }
            val panel = remember { Animatable(0f) }
            var current by remember { mutableIntStateOf(selected) }
            val drag = remember(scope, count) {
                DampedDrag(
                    scope, selected.toFloat(), 0f..(count - 1).toFloat(), initialScale = 1f, pressedScale = 78f / 56f,
                    onDragStopped = { v ->
                        val landing = (targetValue + projectMomentum(v) / tabWidth).roundToInt().coerceIn(0, count - 1)
                        current = landing
                        animateToValue(landing.toFloat())
                        scope.launch { panel.animateTo(0f, spring(1f, 300f, 0.5f)) }
                    },
                    onDrag = { _, amount ->
                        updateValue((targetValue + amount.x / tabWidth).coerceIn(0f, (count - 1).toFloat()))
                        // past the ends the whole bar follows, with resistance
                        scope.launch { panel.snapTo(panel.value + amount.x) }
                    },
                )
            }
            val latest by rememberUpdatedState(selected)
            LaunchedEffect(selected) { current = selected }
            LaunchedEffect(drag) {
                snapshotFlow { current }.drop(1).collectLatest { i ->
                    drag.animateToValue(i.toFloat())
                    if (i != latest) select(i)
                }
            }
            val panelOffset = {
                with(density) { rubberBand(panel.value, constraints.maxWidth.toFloat(), 0.08f).coerceIn(-6.dp.toPx(), 6.dp.toPx()) }
            }
            val highlight = rememberInteractiveHighlight { size, _ -> Offset((drag.value + 0.5f) * tabWidth + panelOffset(), size.height / 2f) }

            // the bar
            Row(
                Modifier
                    .graphicsLayer { translationX = panelOffset() }
                    .drawBackdrop(
                        backdrop = content,
                        shape = { capsule },
                        effects = {
                            vibrancy()
                            blur(8.dp.toPx())
                            lens(24.dp.toPx(), 24.dp.toPx())
                        },
                        highlight = { appleRim() },
                        shadow = { appleShadow(20.dp, androidx.compose.ui.util.lerp(0.12f, 0.40f, tone.value)) },
                        layerBlock = {
                            val s = androidx.compose.ui.util.lerp(1f, 1f + 16.dp.toPx() / size.width, drag.pressProgress)
                            scaleX = s
                            scaleY = s
                        },
                        onDrawSurface = { drawRect(container) },
                    )
                    .then(highlight.modifier)
                    .height(64.dp)
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { tabs.forEachIndexed { i, tab -> GlassTab(tab, ink, { 1f }) { current = i } } }

            // the same tabs in the accent, invisible: recorded so the lens can show them magnified and tinted
            Row(
                Modifier
                    .clearAndSetSemantics { }
                    .alpha(0f)
                    .layerBackdrop(tabsLayer)
                    .graphicsLayer { translationX = panelOffset() }
                    .drawBackdrop(
                        backdrop = content,
                        shape = { capsule },
                        effects = {
                            val p = drag.pressProgress
                            vibrancy()
                            blur(8.dp.toPx())
                            lens(24.dp.toPx() * p, 24.dp.toPx() * p)
                        },
                        highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                        shadow = null,
                        onDrawSurface = { drawRect(container) },
                    )
                    .height(56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accent)),
                verticalAlignment = Alignment.CenterVertically,
            ) { tabs.forEach { tab -> GlassTab(tab, ink, { androidx.compose.ui.util.lerp(1f, 1.2f, drag.pressProgress) }) { } } }

            // the selection: a tinted pill at rest, a clear magnifying lens while held
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .graphicsLayer { translationX = drag.value * tabWidth + panelOffset() }
                    .then(highlight.gestureModifier)
                    .then(drag.modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(content, tabsLayer),
                        shape = { capsule },
                        effects = {
                            val p = drag.pressProgress
                            lens(10.dp.toPx() * p, 14.dp.toPx() * p, chromaticAberration = true)
                        },
                        highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                        shadow = { Shadow(alpha = drag.pressProgress) },
                        innerShadow = { InnerShadow(radius = 8.dp * drag.pressProgress, alpha = drag.pressProgress) },
                        layerBlock = { lensStretch(drag, 10f) },
                        onDrawSurface = {
                            val p = drag.pressProgress
                            drawRect(lerp(Color.Black.copy(0.1f), Color.White.copy(0.1f), tone.value), alpha = 1f - p)
                            drawRect(Color.Black.copy(alpha = 0.03f * p))
                        },
                    )
                    .height(56.dp)
                    .fillMaxWidth(1f / count),
            )
        }
    }
}

@Composable
private fun RowScope.GlassTab(tab: AppleTab, ink: Color, scale: () -> Float, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(interactionSource = null, indication = null, role = Role.Tab, onClickLabel = tab.label, onClick = onClick)
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TabGlyph(tab, ink)
        Text(tab.label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = ink, maxLines = 1)
    }
}

// ---- adaptive tint ----

/**
 * How dark what lies behind a piece of glass is, read from the live layer it refracts: [value] eases between 0
 * (light content: light glass, dark ink) and 1 (dark content: dark glass, light ink), with hysteresis so it does not
 * flicker at the threshold, as iOS's glass flips over photos and dark pages. Attach [modifier] to the glass.
 */
@Stable
class BackdropTone internal constructor(initialDark: Boolean) {
    internal val anim = Animatable(if (initialDark) 1f else 0f)
    internal var bounds: androidx.compose.ui.geometry.Rect? = null
    val value: Float get() = anim.value
    val modifier: Modifier = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }
}

/**
 * Reads [layer] (the live pages, or the backdrop) under the glass about three times a second while frames are being
 * drawn: the region is drawn into a 12 × 4 px layer and its pixels averaged, so the cost is a few dozen pixels.
 */
@Composable
fun rememberBackdropTone(layer: androidx.compose.ui.graphics.layer.GraphicsLayer?, layerOrigin: () -> Offset, initialDark: Boolean, base: Color): BackdropTone {
    val tone = remember { BackdropTone(initialDark) }
    val probe = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val density = LocalDensity.current
    val origin by rememberUpdatedState(layerOrigin)
    val under by rememberUpdatedState(base)
    LaunchedEffect(layer) {
        if (layer == null) return@LaunchedEffect
        var target = tone.anim.targetValue
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            kotlinx.coroutines.delay(320)
            val b = tone.bounds ?: continue
            if (b.width < 1f || b.height < 1f) continue
            val o = origin()
            val w = 12
            val h = 4
            probe.record(density, androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.unit.IntSize(w, h)) {
                scale(w / b.width, h / b.height, pivot = Offset.Zero) {
                    translate(-(b.left - o.x), -(b.top - o.y)) {
                        drawLayer(layer)
                    }
                }
            }
            val shot = runCatching { probe.toImageBitmap() }.getOrNull() ?: continue
            val raw = shot.asAndroidBitmapCompat()
            val bmp = if (raw.config == android.graphics.Bitmap.Config.HARDWARE) raw.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else raw
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            if (bmp !== raw) bmp.recycle()
            val baseLum = luminanceOf(under)
            var sum = 0.0
            for (p in px) {
                val a = (p ushr 24 and 0xFF) / 255.0
                val l = 0.2126 * srgb(p shr 16 and 0xFF) + 0.7152 * srgb(p shr 8 and 0xFF) + 0.0722 * srgb(p and 0xFF)
                sum += a * l + (1 - a) * baseLum
            }
            val lum = sum / px.size
            target = when {
                lum < 0.18 -> 1f
                lum > 0.30 -> 0f
                else -> target
            }
            if (target != tone.anim.targetValue) launch { tone.anim.animateTo(target, tween(400)) }
        }
    }
    return tone
}

private fun androidx.compose.ui.graphics.ImageBitmap.asAndroidBitmapCompat() = asAndroidBitmap()

private fun srgb(c: Int): Double {
    val v = c / 255.0
    return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
}

private fun luminanceOf(c: Color): Double = 0.2126 * srgb((c.red * 255).roundToInt()) + 0.7152 * srgb((c.green * 255).roundToInt()) + 0.0722 * srgb((c.blue * 255).roundToInt())

/** Whether the surface a control sits on is dark (glass on a dark card takes dark tint and light ink). */
@Composable
fun surfaceIsDark(): Boolean {
    val s = LocalGlassSurface.current.takeIf { it != Color.Unspecified } ?: Theme.current.tokens.base
    return luminanceOf(s) < 0.18
}
