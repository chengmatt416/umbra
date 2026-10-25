package app.umbra.agent.ui.porcelain

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The app's motion vocabulary. Everything that moves in response to a touch uses a spring, so a gesture interrupted
 * half way continues from where it is instead of restarting; only fades use time. Values follow the platform's
 * spring presets: [press] is quick and slightly lively, [settle] is critically damped for layout, [enter] is soft.
 */
object Motion {
    /** A finger going down: the control gives at once. */
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.7f, stiffness = 900f)
    /** Letting go: a small, lively return. */
    fun <T> release(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.45f, stiffness = 520f)
    /** Things changing place or size (a selection pill, a row moving): no overshoot. */
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.86f, stiffness = 520f)
    /** Things arriving: a soft spring that barely overshoots. */
    fun <T> enter(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    /** Page pushes (shared X axis). */
    val page = spring<IntOffset>(dampingRatio = 0.9f, stiffness = 420f, visibilityThreshold = IntOffset(1, 1))

    /** Stagger between siblings appearing one after another. */
    const val STAGGER_MS = 35L
}

/** Shrinks a little while pressed and springs back when released (the tactile feel of every Ink control). */
fun Modifier.inkPress(interaction: InteractionSource, pressedScale: Float = 0.96f): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, if (pressed) Motion.press() else Motion.release(), label = "ink-press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Arrives once: rises a few dp and fades in, [index] beats after its first sibling. */
fun Modifier.inkEnter(index: Int = 0, rise: Dp = 10.dp): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(Motion.STAGGER_MS * index.coerceIn(0, 12))
        progress.animateTo(1f, Motion.enter())
    }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * rise.toPx()
    }
}

// ---- surfaces ----

/** Ink cards: flat, opaque, a hairline border; only the dark card carries weight. */
internal fun Modifier.inkSurface(shape: Shape, style: CardStyle): Modifier = when (style) {
    CardStyle.Ceramic -> this
        .shadow(if (Theme.dark) 0.dp else 1.dp, shape, ambientColor = Color(0x0F000000), spotColor = Color(0x14000000))
        .clip(shape).background(P.ceramic).border(1.dp, P.line2, shape)
    CardStyle.Soft -> this.clip(shape).background(P.ceramic2)
    CardStyle.Dark -> this.clip(shape).background(P.night2).border(1.dp, Color(0x1FFFFFFF), shape)
}

/** The colour that reads on [P.primaryButton]: white on black, black on white. */
val inkOnPrimary: Color get() = if (Theme.dark) Color.Black else Color.White

@Composable
internal fun InkButton(
    text: String, onClick: () -> Unit, modifier: Modifier, style: ButtonStyle, tiny: Boolean, enabled: Boolean, icon: IconSpec?, contentDescription: String?,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    val (bg, fg) = when (style) {
        ButtonStyle.Primary -> P.accent2 to inkOnPrimary
        ButtonStyle.Alt -> P.highlight to P.ink
        ButtonStyle.Ghost -> P.accent3 to P.ink
        ButtonStyle.Danger -> P.danger to Color.White
        ButtonStyle.Warn -> P.amber to Color.White
    }
    Row(
        modifier
            .inkPress(interaction)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .clip(shape)
            .background(bg)
            .then(if (style == ButtonStyle.Alt) Modifier.border(1.dp, P.line, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .defaultMinSize(minHeight = if (tiny) 34.dp else 46.dp)
            .padding(horizontal = if (tiny) 14.dp else 20.dp, vertical = if (tiny) 6.dp else 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) PIcon(icon, size = if (tiny) 14.dp else 17.dp, tint = fg)
        if (text.isNotEmpty()) Text(text, fontSize = if (tiny) 12.5.sp else 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun InkIconButton(icon: IconSpec, contentDescription: String, onClick: () -> Unit, modifier: Modifier, size: Dp, tint: Color) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .inkPress(interaction, 0.9f)
            .clip(CircleShape)
            .background(P.highlight)
            .border(1.dp, P.line2, CircleShape)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PIcon(icon, size = 18.dp, tint = if (tint == P.danger) tint else P.ink, contentDescription = contentDescription) }
}

/**
 * The Ink segmented control: a track with an ink pill that springs to the chosen option, its label turning white
 * (black in the dark) as the pill arrives. The pill follows the measured position of each option, so options of
 * different widths work, and a quick second tap redirects the pill mid-flight.
 */
@Composable
internal fun <T> InkSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier, fill: Boolean) {
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val x = remember { Animatable(0f) }
    val w = remember { Animatable(0f) }
    val target = bounds[index]
    LaunchedEffect(target) {
        val (tx, tw) = target ?: return@LaunchedEffect
        if (w.value == 0f) { x.snapTo(tx); w.snapTo(tw) } else {
            kotlinx.coroutines.coroutineScope {
                launch { x.animateTo(tx, Motion.settle()) }
                w.animateTo(tw, Motion.settle())
            }
        }
    }
    val track = RoundedCornerShape(50)
    val pill = P.accent2
    Row(
        modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .clip(track).background(P.accent3).padding(4.dp)
            .drawBehind {
                if (w.value > 0f) drawRoundRect(pill, Offset(x.value, 0f), Size(w.value, size.height - 0f), CornerRadius(size.height / 2f))
            },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, (value, label) ->
            val on = i == index
            val color by animateColorAsState(if (on) inkOnPrimary else P.sub, Motion.settle(), label = "seg-label")
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .onPlaced { c -> bounds[i] = c.positionInParent().x to c.size.width.toFloat() }
                    .inkPress(interaction, 0.97f)
                    .clip(track)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(value) }
                    .defaultMinSize(minHeight = 36.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** An icon on a soft tile. */
@Composable
internal fun InkBadgeIcon(icon: IconSpec, modifier: Modifier, size: Dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(P.accent3), contentAlignment = Alignment.Center) {
        PIcon(icon, size = size * 0.5f, tint = P.ink)
    }
}

/**
 * A list row that animates when it is added, removed or moved: it fades in, springs to its new place and fades out
 * quickly. Chat timelines pass [placement] false: text streaming into the last message must not make rows slide.
 * The row needs a stable key.
 */
fun androidx.compose.foundation.lazy.LazyItemScope.inkItem(placement: Boolean = true): Modifier = Modifier.animateItem(
    fadeInSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
    placementSpec = if (placement) spring(dampingRatio = 0.86f, stiffness = 420f, visibilityThreshold = IntOffset(1, 1)) else null,
    fadeOutSpec = androidx.compose.animation.core.tween(140),
)
