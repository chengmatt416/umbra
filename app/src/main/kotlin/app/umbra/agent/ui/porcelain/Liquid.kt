package app.umbra.agent.ui.porcelain

import android.graphics.RuntimeShader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.geometry.isSpecified
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.abs
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/*
 * Liquid: the second theme. Its motion vocabulary is one idea used everywhere — a surface is a drop of liquid:
 *  - the backdrop is a slow field of merging metaballs with a glossy rim (one GPU shader, drawn only in the draw
 *    phase, so nothing recomposes while it flows);
 *  - cards and controls are glass laid over it;
 *  - a press squashes a control like jelly and it wobbles back (x and y spring at different rates);
 *  - a selection moves as a gooey drop: the leading edge leaps, the trailing edge follows, the drop stretches and
 *    thins in between;
 *  - switching theme pours the new one over the old from where you touched, through a wobbling liquid edge.
 */

// ---- backdrop ----

/** AGSL: four metaballs drifting on slow Lissajous paths, merged through a soft threshold, lit from the top left. */
private const val LIQUID_SHADER = """
uniform float2 res;
uniform float t;
uniform float gloss;
uniform float2 light;
layout(color) uniform half4 base;
layout(color) uniform half4 c0;
layout(color) uniform half4 c1;
layout(color) uniform half4 c2;
layout(color) uniform half4 c3;

float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }

half4 main(float2 fc) {
    float2 uv = fc / res.y;
    float ar = res.x / res.y;
    float2 q = uv + 0.03 * float2(sin(uv.y * 5.0 + t * 0.9), sin(uv.x * 6.0 - t * 0.7));

    float2 p0 = float2(ar * (0.22 + 0.22 * sin(t * 0.23)), 0.18 + 0.10 * sin(t * 0.31 + 1.3));
    float2 p1 = float2(ar * (0.80 + 0.16 * sin(t * 0.19 + 2.0)), 0.40 + 0.15 * sin(t * 0.27 + 0.4));
    float2 p2 = float2(ar * (0.35 + 0.28 * sin(t * 0.17 + 4.1)), 0.68 + 0.13 * sin(t * 0.21 + 2.2));
    float2 p3 = float2(ar * (0.75 + 0.20 * sin(t * 0.29 + 5.0)), 0.92 + 0.07 * sin(t * 0.25 + 3.1));

    float2 d0 = q - p0; float2 d1 = q - p1; float2 d2 = q - p2; float2 d3 = q - p3;
    float k0 = 1.0 / (dot(d0, d0) + 0.004);
    float k1 = 1.0 / (dot(d1, d1) + 0.004);
    float k2 = 1.0 / (dot(d2, d2) + 0.004);
    float k3 = 1.0 / (dot(d3, d3) + 0.004);
    float w0 = 0.030 * k0; float w1 = 0.024 * k1; float w2 = 0.034 * k2; float w3 = 0.018 * k3;
    float f = w0 + w1 + w2 + w3;
    // the field's slope, for the surface normal
    float2 g = -2.0 * (w0 * k0 * d0 + w1 * k1 * d1 + w2 * k2 * d2 + w3 * k3 * d3);

    half3 blend = (c0.rgb * w0 + c1.rgb * w1 + c2.rgb * w2 + c3.rgb * w3) / f;
    float e0 = 0.85; float e1 = 1.35;
    float x = clamp((f - e0) / (e1 - e0), 0.0, 1.0);
    float m = x * x * (3.0 - 2.0 * x);
    float dm = 6.0 * x * (1.0 - x) / (e1 - e0);

    half3 halo = mix(base.rgb, blend, clamp(f * 0.30, 0.0, 0.42));
    half3 col = mix(halo, blend, m);

    float3 n = normalize(float3(-g * dm * 0.012, 1.0));
    float3 l = normalize(float3(light, 0.65));
    float3 hv = normalize(l + float3(0.0, 0.0, 1.0));
    float spec = pow(max(dot(n, hv), 0.0), 28.0);
    float rim = m * (1.0 - m) * 4.0;
    col += half3(spec * gloss * m + rim * 0.06 * gloss);
    col += half3((hash(fc) - 0.5) * 2.5 / 255.0);
    return half4(col, 1.0);
}
"""

/** The backdrop's palette for a theme: the page colour and four drops near the user's hue. */
private class BackdropColors(val base: Color, val c0: Color, val c1: Color, val c2: Color, val c3: Color, val gloss: Float)

private fun backdropColors(spec: ThemeSpec): BackdropColors {
    val h = spec.hue
    return if (spec.dark) BackdropColors(
        base = hsl(h, 0.50f, 0.07f),
        c0 = hsl(h, 0.80f, 0.40f),
        c1 = hsl(h + 42f, 0.75f, 0.36f),
        c2 = hsl(h - 34f, 0.72f, 0.33f),
        c3 = hsl(h + 170f, 0.55f, 0.30f),
        gloss = 0.55f,
    ) else BackdropColors(
        base = hsl(h, 0.45f, 0.95f),
        c0 = hsl(h, 0.85f, 0.76f),
        c1 = hsl(h + 42f, 0.80f, 0.80f),
        c2 = hsl(h - 34f, 0.78f, 0.78f),
        c3 = hsl(h + 170f, 0.55f, 0.86f),
        gloss = 0.75f,
    )
}

/**
 * The living backdrop of the Liquid theme. It flows while [ThemeSpec.motion] is on and the system allows motion
 * (animations not removed, no battery saver, not hot); otherwise it holds one still frame. [seed] offsets time so
 * two backdrops on one screen do not move in step.
 */
@Composable
fun LiquidBackdrop(modifier: Modifier = Modifier, spec: ThemeSpec = Theme.current, seed: Float = 0f) {
    val context = LocalContext.current
    val shader = remember { RuntimeShader(LIQUID_SHADER) }
    val brush = remember(shader) { object : ShaderBrush() { override fun createShader(size: Size): Shader = shader } }
    val time = remember { mutableFloatStateOf(17f + seed) }
    val moving = spec.motion && remember(spec) { motionAllowed(context) }
    if (moving) LaunchedEffect(Unit) {
        val from = time.floatValue
        var start = -1L
        while (true) withFrameNanos { now ->
            if (start < 0) start = now
            time.floatValue = from + (now - start) / 1_000_000_000f
        }
    }
    val colors = remember(spec) { backdropColors(spec) }
    Box(
        modifier.drawBehind {
            shader.setFloatUniform("res", size.width, size.height)
            shader.setFloatUniform("t", time.floatValue)
            shader.setFloatUniform("gloss", colors.gloss)
            val (lx, ly) = LiquidLight.direction()
            shader.setFloatUniform("light", lx, ly)
            shader.setColorUniform("base", colors.base.toArgb())
            shader.setColorUniform("c0", colors.c0.toArgb())
            shader.setColorUniform("c1", colors.c1.toArgb())
            shader.setColorUniform("c2", colors.c2.toArgb())
            shader.setColorUniform("c3", colors.c3.toArgb())
            drawRect(brush)
        },
    )
}

/** What draws behind every page: the static Porcelain page glaze, or the Liquid backdrop. */
@Composable
fun PageBackdrop(modifier: Modifier = Modifier) {
    val spec = Theme.current
    if (spec.liquid) LiquidBackdrop(modifier.fillMaxSize(), spec) else Box(modifier.fillMaxSize().drawBehind { drawRect(spec.tokens.page) })
}

// ---- light ----

/**
 * The one light of the Liquid theme. Every glass rim, glare and shadow, and the gloss on the backdrop's drops, is lit
 * from [angle]; tilting the phone swings it (the gravity sensor, smoothed), so the glass catches the light as it moves.
 * 0° lights from the right, 90° from below, as the glass rim shader measures it; rest is 225° (top left).
 */
object LiquidLight {
    const val REST = 225f
    var angle by mutableFloatStateOf(REST)
        internal set

    /** Where the light comes from, as a unit vector in screen space (y down). */
    fun direction(): Pair<Float, Float> {
        val a = Math.toRadians(angle.toDouble())
        return cos(a).toFloat() to sin(a).toFloat()
    }
}

/** Swings [LiquidLight] with the phone's tilt while Liquid flows; otherwise it rests at the top left. */
@Composable
fun LiquidLightSource(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(active) {
        val sensors = context.getSystemService(android.hardware.SensorManager::class.java)
        val gravity = sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        if (!active || sensors == null || gravity == null) {
            LiquidLight.angle = LiquidLight.REST
            return@DisposableEffect onDispose { }
        }
        var smooth = LiquidLight.REST
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                // left-right tilt swings the light up to 40° either way; forward-back tilt half as much
                val x = (e.values[0] / 9.81f).coerceIn(-1f, 1f)
                val y = ((e.values[1] - 6.9f) / 9.81f).coerceIn(-1f, 1f)
                val target = LiquidLight.REST + x * 40f - y * 20f
                smooth += (target - smooth) * 0.12f
                if (abs(smooth - LiquidLight.angle) > 0.15f) LiquidLight.angle = smooth
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, gravity, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            sensors.unregisterListener(listener)
            LiquidLight.angle = LiquidLight.REST
        }
    }
}

// ---- glass ----

/** The live backdrop that glass in this window refracts, and the window it belongs to (dialogs fall back to paint). */
class GlassHost(val backdrop: LayerBackdrop, val view: android.view.View)

val LocalGlassHost = androidx.compose.runtime.staticCompositionLocalOf<GlassHost?> { null }

enum class GlassKind { Card, Soft, Control, Bar }

/**
 * A sheet of liquid glass over the backdrop. Where the window has a live backdrop it is real glass (the approach of
 * Kyant's AndroidLiquidGlass): what lies behind is blurred, made more vivid and bent by a lens at the rounded edges;
 * the rim catches [LiquidLight] on the side facing it and the opposite corner, a soft glare sits where the light
 * enters, and the shadow falls away from the light. Elsewhere (dialogs, the Capsule) the same look is painted.
 */
@Composable
fun Modifier.liquidGlass(shape: Shape, kind: GlassKind = GlassKind.Card, elevation: Dp = 0.dp, tone: Float = -1f): Modifier {
    val spec = Theme.current
    val host = LocalGlassHost.current?.takeIf { it.view === androidx.compose.ui.platform.LocalView.current }
    val h = spec.hue
    // [tone] (0 light … 1 dark, read from what is behind) overrides the theme's darkness for the tint
    val dark = if (tone >= 0f) tone > 0.5f else spec.dark
    val fill: Brush = when (kind) {
        GlassKind.Card, GlassKind.Control -> spec.tokens.ceramic
        GlassKind.Soft -> spec.tokens.ceramic2
        GlassKind.Bar -> {
            val t = if (tone >= 0f) tone else if (spec.dark) 1f else 0f
            Brush.verticalGradient(
                listOf(
                    androidx.compose.ui.graphics.lerp(Color.White.copy(alpha = 0.66f), hsl(h, 0.40f, 0.20f, 0.62f), t),
                    androidx.compose.ui.graphics.lerp(hsl(h, 0.55f, 0.96f, 0.52f), hsl(h, 0.45f, 0.12f, 0.72f), t),
                ),
            )
        }
    }
    val glare = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.40f)
    val spot = if (dark) Color.Black.copy(alpha = 0.50f) else hsl(h, 0.65f, 0.30f, 0.26f)
    if (host == null) return paintedGlass(shape, kind, elevation, fill, glare, spot, dark, h)
    val shapeOf = remember(shape) { { shape } }
    val (blurDp, lensH, lensA) = when (kind) {
        GlassKind.Card -> Triple(6.dp, 14.dp, 22.dp)
        GlassKind.Soft -> Triple(4.dp, 9.dp, 14.dp)
        GlassKind.Control -> Triple(3.dp, 12.dp, 24.dp)
        GlassKind.Bar -> Triple(10.dp, 18.dp, 30.dp)
    }
    val rimAlpha = when (kind) {
        GlassKind.Soft -> if (dark) 0.35f else 0.55f
        else -> if (dark) 0.55f else 0.85f
    }
    return this.drawBackdrop(
        backdrop = host.backdrop,
        shape = shapeOf,
        effects = {
            vibrancy()
            blur(blurDp.toPx())
            lens(lensH.toPx(), lensA.toPx())
        },
        highlight = {
            Highlight(
                width = if (kind == GlassKind.Soft) 0.75.dp else 1.dp,
                alpha = rimAlpha,
                style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.6f), angle = LiquidLight.angle, falloff = 1.6f),
            )
        },
        shadow = if (elevation > 0.dp) {
            {
                val (lx, ly) = LiquidLight.direction()
                val r = elevation * 1.6f
                Shadow(radius = r, offset = DpOffset(r * -lx * 0.3f, r * (0.12f - ly * 0.2f)), color = spot)
            }
        } else null,
        onDrawSurface = {
            drawRect(fill)
            if (kind != GlassKind.Soft) {
                val (lx, ly) = LiquidLight.direction()
                val c = Offset(size.width * (0.5f + lx * 0.42f), size.height * (0.5f + ly * 0.9f))
                drawRect(Brush.radialGradient(listOf(glare, Color.Transparent), center = c, radius = max(size.maxDimension * 0.75f, 1f)))
            }
        },
    )
}

/** Glass without a live backdrop behind it: the fill, glare and a light-facing rim are painted. */
private fun Modifier.paintedGlass(shape: Shape, kind: GlassKind, elevation: Dp, fill: Brush, glare: Color, spot: Color, dark: Boolean, h: Float): Modifier {
    val rimTop = if (dark) Color.White.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.95f)
    val rimEnd = if (dark) Color.White.copy(alpha = 0.04f) else hsl(h, 0.45f, 0.78f, 0.35f)
    val ambient = if (dark) Color.Black.copy(alpha = 0.35f) else hsl(h, 0.55f, 0.35f, 0.16f)
    return this
        .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, ambientColor = ambient, spotColor = spot) else Modifier)
        .clip(shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val glareBrush = Brush.radialGradient(listOf(glare, Color.Transparent), center = Offset(size.width * 0.16f, 0f), radius = max(size.maxDimension * 0.75f, 1f))
            val rim = Brush.linearGradient(listOf(rimTop, rimTop.copy(alpha = rimTop.alpha * 0.5f), rimEnd), start = Offset.Zero, end = Offset(size.width, size.height))
            val stroke = Stroke(1.5.dp.toPx())
            onDrawBehind {
                drawRect(fill)
                if (kind != GlassKind.Soft) drawRect(glareBrush)
                drawOutline(outline, rim, style = stroke)
            }
        }
}

// ---- jelly press ----

/**
 * A press squashes the element like jelly; on release it springs back past its size and wobbles out, x and y at
 * different rates. Even a quick tap gets the impulse, so every touch answers. Draw-phase only: nothing recomposes.
 */
fun Modifier.jelly(interaction: InteractionSource, strength: Float = 1f, glow: Shape? = null): Modifier = this then JellyElement(interaction, strength, glow)

private data class JellyElement(val interaction: InteractionSource, val strength: Float, val glow: Shape?) : ModifierNodeElement<JellyNode>() {
    override fun create() = JellyNode(interaction, strength, glow)
    override fun update(node: JellyNode) = node.update(interaction, strength, glow)
    override fun InspectorInfo.inspectableProperties() { name = "jelly" }
}

/**
 * With a [glow] shape, the press also lights the element from under the finger (a soft additive pool of light that
 * blooms on press and fades on release), as Kyant's interactive highlight does.
 */
private class JellyNode(var interaction: InteractionSource, var strength: Float, var glow: Shape?) : Modifier.Node(), LayoutModifierNode, androidx.compose.ui.node.DrawModifierNode {
    private val sx = Animatable(1f)
    private val sy = Animatable(1f)
    private val light = Animatable(0f)
    private var at = Offset.Unspecified
    private var job: Job? = null

    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        drawContent()
        val g = light.value
        val shape = glow ?: return
        if (g <= 0.002f) return
        val outline = shape.createOutline(size, layoutDirection, this)
        val c = if (at.isSpecified) at else Offset(size.width / 2f, size.height / 2f)
        drawOutline(outline, Color.White.copy(alpha = 0.07f * g), blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
        drawOutline(
            outline,
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.26f * g), Color.Transparent), center = c, radius = max(size.minDimension * 1.4f, 1f)),
            blendMode = androidx.compose.ui.graphics.BlendMode.Plus,
        )
    }

    override fun onAttach() = listen()

    fun update(source: InteractionSource, s: Float, g: Shape?) {
        strength = s
        glow = g
        if (source != interaction) {
            interaction = source
            listen()
        }
    }

    private fun listen() {
        job?.cancel()
        job = coroutineScope.launch {
            interaction.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> {
                        at = i.pressPosition
                        if (glow != null) launch { light.animateTo(1f, spring(0.5f, 300f)) }
                        launch { sx.animateTo(1f - 0.045f * strength, spring(0.7f, 1400f)) }
                        launch { sy.animateTo(1f - 0.075f * strength, spring(0.7f, 1400f)) }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        if (glow != null) launch { light.animateTo(0f, spring(0.6f, 220f)) }
                        launch { sx.animateTo(1f, spring(dampingRatio = 0.30f, stiffness = 420f), initialVelocity = 0.9f * strength) }
                        launch { sy.animateTo(1f, spring(dampingRatio = 0.26f, stiffness = 300f), initialVelocity = 1.3f * strength) }
                    }
                }
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        return layout(p.width, p.height) {
            p.placeWithLayer(0, 0) {
                scaleX = sx.value
                scaleY = sy.value
            }
        }
    }
}

/** An interaction source and, under Liquid, the jelly that listens to it. Pass the source to the clickable. */
@Composable
fun rememberPress(): MutableInteractionSource = remember { MutableInteractionSource() }

/** [jelly] only under Liquid; Porcelain controls keep their own press. */
fun Modifier.liquidPress(interaction: InteractionSource, strength: Float = 1f): Modifier = if (Theme.liquid) jelly(interaction, strength) else this

// ---- gooey drop ----

/**
 * A selection marker that moves like a drop of liquid between [left] and [right] edges: the edge in the direction of
 * travel leaps ahead, the other follows, so the drop stretches on the way and settles round.
 */
@Stable
class GooeyDrop {
    val left = Animatable(0f)
    val right = Animatable(0f)
    private var placed = false

    /** How far the drop is stretched beyond its resting width (0 at rest), for thinning it while it travels. */
    fun stretch(rest: Float): Float = if (rest <= 0f) 0f else ((right.value - left.value) / rest - 1f).coerceIn(0f, 1.5f)

    suspend fun moveTo(l: Float, r: Float, animate: Boolean = true) {
        if (!placed || !animate) {
            placed = true
            left.snapTo(l); right.snapTo(r)
            return
        }
        val forward = l > left.value
        val lead = spring<Float>(dampingRatio = 0.62f, stiffness = 760f)
        val trail = spring<Float>(dampingRatio = 0.78f, stiffness = 210f)
        coroutineScope {
            launch { left.animateTo(l, if (forward) trail else lead) }
            launch { right.animateTo(r, if (forward) lead else trail) }
        }
    }
}

// ---- liquid switch ----

/** The Liquid switch: a glass track that fills with the accent, and a thumb that stretches like a drop as it crosses. */
@Composable
fun LiquidSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val spec = Theme.current
    val drop = remember { GooeyDrop() }
    val fill = remember { Animatable(if (checked) 1f else 0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val w = with(density) { 52.dp.toPx() }
    val pad = with(density) { 4.dp.toPx() }
    val th = with(density) { 22.dp.toPx() }
    LaunchedEffect(checked) {
        val l = if (checked) w - pad - th else pad
        launch { fill.animateTo(if (checked) 1f else 0f, tween(260, easing = P.ease)) }
        drop.moveTo(l, l + th)
    }
    val press = rememberPress()
    val off = if (spec.dark) hsl(spec.hue, 0.25f, 0.30f, 0.55f) else hsl(spec.hue, 0.25f, 0.70f, 0.45f)
    val on = P.accent
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
    Box(
        modifier
            .jelly(press, 0.8f)
            .size(52.dp, 30.dp)
            .clip(shape)
            .then(
                if (onChange != null) Modifier.toggleable(checked, press, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onChange) else Modifier,
            )
            .drawBehind {
                val f = fill.value
                drawRoundRect(androidx.compose.ui.graphics.lerp(off, on, f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                // the accent rises inside the track like a liquid level
                drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                val l = drop.left.value
                val r = drop.right.value
                val stretch = drop.stretch(th)
                val hgt = th * (1f - 0.22f * stretch)
                val top = (size.height - hgt) / 2f
                drawRoundRect(
                    Color.Black.copy(alpha = 0.16f), topLeft = Offset(l, top + 1.5f), size = Size(r - l, hgt),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(hgt / 2),
                )
                drawRoundRect(Color.White, topLeft = Offset(l, top), size = Size(r - l, hgt), cornerRadius = androidx.compose.ui.geometry.CornerRadius(hgt / 2))
            },
    )
}

// ---- theme reveal ----

/** Pours a new theme over the old one from a point on the screen. The root of the app hosts it. */
object ThemeReveal {
    internal var host: ((Offset?, () -> Unit) -> Unit)? = null

    /** Applies [change] (which sets the theme) behind a liquid reveal from [origin] (root coordinates), or at once. */
    fun switch(origin: Offset?, change: () -> Unit) {
        val h = host
        if (h == null) change() else h(origin, change)
    }
}

/**
 * Hosts [ThemeReveal]: keeps the last frame of the app in a graphics layer; on a switch it freezes that frame, applies
 * the new theme underneath, and opens a wobbling liquid hole in the frozen frame from the touch point until the new
 * theme fills the screen.
 */
@Composable
fun ThemeRevealHost(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val layer = rememberGraphicsLayer()
    var frozen by remember { mutableStateOf<ImageBitmap?>(null) }
    var origin by remember { mutableStateOf<Offset?>(null) }
    var dissolve by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val mine: (Offset?, () -> Unit) -> Unit = { at, change ->
            scope.launch {
                if (frozen != null || !motionAllowed(context)) {
                    change()
                    return@launch
                }
                val shot = runCatching { layer.toImageBitmap() }.getOrNull()
                if (shot == null) {
                    change()
                    return@launch
                }
                val wasApple = Theme.apple
                frozen = shot
                origin = at
                progress.snapTo(0f)
                change()
                // Apple changes its appearance by cross-dissolving (no sudden brightness jump, nothing sweeping across
                // the screen); the other themes pour the new look in from the touch.
                dissolve = wasApple || Theme.apple
                if (dissolve) progress.animateTo(1f, tween(420, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)))
                else progress.animateTo(1f, tween(900, easing = CubicBezierEasing(0.45f, 0f, 0.15f, 1f)))
                frozen = null
            }
        }
        ThemeReveal.host = mine
        onDispose { if (ThemeReveal.host === mine) ThemeReveal.host = null }
    }
    Box(
        modifier
            .fillMaxSize()
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
                val shot = frozen ?: return@drawWithContent
                val p = progress.value
                if (dissolve) {
                    drawImage(shot, alpha = (1f - p).coerceIn(0f, 1f))
                    return@drawWithContent
                }
                val o = origin ?: Offset(size.width / 2f, size.height)
                val reach = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height)).maxOf { hypot(it.x - o.x, it.y - o.y) }
                val r = reach * 1.12f * p
                val path = wobble(o, r, p)
                clipPath(path, ClipOp.Difference) { drawImage(shot) }
                // the liquid's rim: a bright meniscus that thins out as the pour completes
                val fade = 1f - p
                drawPath(path, Color.White.copy(alpha = 0.55f * fade), style = Stroke((10.dp.toPx()) * fade + 1f))
                drawPath(path, P.accent.copy(alpha = 0.35f * fade), style = Stroke((3.dp.toPx()) * fade + 0.5f))
            },
        content = content,
    )
}

/** A circle of radius [r] whose edge ripples like a liquid front; the ripple calms as [p] goes to 1. */
private fun wobble(c: Offset, r: Float, p: Float): Path {
    val path = Path()
    if (r <= 0f) return path
    val n = 120
    val amp = 0.07f * (1f - p) * (1f - p)
    val phase = p * 7f
    for (i in 0..n) {
        val a = (i.toFloat() / n) * 2f * PI.toFloat()
        val k = 1f + amp * (sin(3f * a + phase) + 0.6f * sin(5f * a - 1.7f * phase) + 0.35f * sin(9f * a + 2.3f * phase))
        val x = c.x + cos(a) * r * k
        val y = c.y + sin(a) * r * k
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** A small morphing drop of colour (the swatches in Appearance): its outline breathes while [lively]. */
@Composable
fun LiquidSwatch(color: Color, selected: Boolean, modifier: Modifier = Modifier, seed: Float = 0f) {
    val context = LocalContext.current
    val t = remember { mutableFloatStateOf(seed) }
    val moving = remember { motionAllowed(context) }
    val grow = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) { grow.animateTo(if (selected) 1f else 0f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow)) }
    if (moving) LaunchedEffect(Unit) {
        val from = t.floatValue
        var start = -1L
        while (true) withFrameNanos { now ->
            if (start < 0) start = now
            t.floatValue = from + (now - start) / 1_000_000_000f
        }
    }
    Box(
        modifier.drawBehind {
            val g = grow.value
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f * (0.68f + 0.14f * g)
            val time = t.floatValue
            val path = Path()
            val n = 48
            val amp = 0.035f + 0.05f * g
            for (i in 0..n) {
                val a = (i.toFloat() / n) * 2f * PI.toFloat()
                val k = 1f + amp * (sin(3f * a + time * 1.6f) + 0.5f * sin(2f * a - time * 1.1f))
                val x = c.x + cos(a) * r * k
                val y = c.y + sin(a) * r * k
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            if (g > 0.01f) drawCircle(color.copy(alpha = 0.30f * g), radius = size.minDimension / 2f * (0.78f + 0.2f * g), center = c, style = Stroke(2.dp.toPx()))
            drawPath(path, Brush.radialGradient(listOf(androidx.compose.ui.graphics.lerp(color, Color.White, 0.45f), color), center = Offset(c.x - r * 0.35f, c.y - r * 0.4f), radius = r * 1.6f))
            // a highlight on the drop
            drawCircle(Color.White.copy(alpha = 0.55f), radius = r * 0.18f, center = Offset(c.x - r * 0.34f, c.y - r * 0.36f))
        },
    )
}

/** The light catching the top half of a filled control. */
fun Modifier.liquidSheen(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.30f), 0.55f to Color.White.copy(alpha = 0.04f), 1f to Color.Transparent))
}

/** [Segmented] under Liquid: a glass track and a gooey drop that flows to the chosen segment. */
@Composable
internal fun <T> LiquidSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier, fill: Boolean) {
    val spec = Theme.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val inner = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    val drop = remember { GooeyDrop() }
    val bounds = remember(options.size) { androidx.compose.runtime.mutableStateListOf<Pair<Float, Float>>().apply { repeat(options.size) { add(0f to 0f) } } }
    val index = options.indexOfFirst { it.first == selected }
    val target = bounds.getOrNull(index)?.takeIf { it.second > it.first }
    LaunchedEffect(index, target) { if (target != null) drop.moveTo(target.first, target.second) }
    val dropFill = if (spec.dark) hsl(spec.hue, 0.55f, 0.72f, 0.28f) else Color.White.copy(alpha = 0.94f)
    val dropShadow = if (spec.dark) Color.Black.copy(alpha = 0.30f) else hsl(spec.hue, 0.6f, 0.35f, 0.16f)
    val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }
    androidx.compose.foundation.layout.Row(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .liquidGlass(shape, GlassKind.Soft)
            .padding(4.dp)
            .drawBehind {
                if (target == null) return@drawBehind
                val l = drop.left.value
                val r = drop.right.value
                val stretch = drop.stretch(target.second - target.first)
                val hgt = size.height * (1f - 0.16f * stretch)
                val top = (size.height - hgt) / 2f
                val cr = androidx.compose.ui.geometry.CornerRadius(radius * (1f - 0.2f * stretch) + hgt * 0.1f * stretch)
                drawRoundRect(dropShadow, topLeft = Offset(l, top + 2.dp.toPx()), size = Size(r - l, hgt), cornerRadius = cr)
                drawRoundRect(dropFill, topLeft = Offset(l, top), size = Size(r - l, hgt), cornerRadius = cr)
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = if (spec.dark) 0.18f else 0.6f), Color.Transparent), startY = top, endY = top + hgt * 0.6f),
                    topLeft = Offset(l, top), size = Size(r - l, hgt), cornerRadius = cr,
                )
            },
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, (value, label) ->
            val on = i == index
            val press = rememberPress()
            val color by androidx.compose.animation.animateColorAsState(if (on) P.accent2 else P.sub, tween(220), label = "seg")
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .onPlaced { c -> val x = c.positionInParent().x; bounds[i] = x to x + c.size.width }
                    .jelly(press, 0.7f, glow = inner)
                    .clip(inner)
                    .clickable(interactionSource = press, indication = null, role = Role.Tab) { onSelect(value) }
                    .defaultMinSize(minHeight = 34.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.Text(
                    label, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, color = color,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}
