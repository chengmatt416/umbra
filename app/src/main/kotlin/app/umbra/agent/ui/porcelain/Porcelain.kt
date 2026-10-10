package app.umbra.agent.ui.porcelain

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Porcelain in Motion: the product's visual language (grey-green glazed ceramic, soft highlights, restrained lift,
 * tactile press). Tokens and components follow the design reference one to one; screens build only from these.
 */
object P {
    val base = Color(0xFFE9EDE5)
    val base2 = Color(0xFFDDE5D9)
    val ink = Color(0xFF32443A)
    val muted = Color(0xFF7B8C7C)
    val sub = Color(0xFF627866)
    val accent = Color(0xFF66866D)
    val accent2 = Color(0xFF476D54)
    val accent3 = Color(0xFFC5D7C2)
    val line = Color(0x5295AA94)
    val line2 = Color(0x2BA2B6A2)
    val danger = Color(0xFFAD685E)
    val amber = Color(0xFFA17A45)
    val good = Color(0xFF52805D)
    val night = Color(0xFF26372F)
    val eyebrow = Color(0xFF82957D)
    val highlight = Color(0xFFFBFDF7)
    val fold = Color(0xFFDCE5D7)
    val code = Color(0xFF28392F)
    val codeText = Color(0xFFD4E8D3)

    val ceramic = Brush.linearGradient(0f to Color(0xFFFBFDF7), 0.4f to Color(0xFFEFF3E9), 1f to Color(0xFFDCE5D7), start = Offset.Zero, end = Offset(900f, 700f))
    val ceramic2 = Brush.linearGradient(0f to Color(0xFFF4F8EF), 0.7f to Color(0xFFE5ECE0), 1f to Color(0xFFDCE6D7), start = Offset.Zero, end = Offset(800f, 600f))
    val night2 = Brush.linearGradient(listOf(Color(0xFF273C32), Color(0xFF344C3B)), start = Offset.Zero, end = Offset(900f, 700f))
    val primaryButton = Brush.linearGradient(listOf(Color(0xFF758F78), Color(0xFF4E6D55)), start = Offset.Zero, end = Offset(300f, 200f))
    val page = Brush.linearGradient(listOf(Color(0xFFE2E9DD), Color(0xFFD8E1D4)), start = Offset.Zero, end = Offset(1200f, 2400f))

    /** cubic-bezier(.22, 1, .36, 1): controls press before their shape settles. */
    val ease = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    val serif = FontFamily.Serif
    val mono = FontFamily.Monospace

    val radius = 24.dp
}

enum class Tone { Good, Neutral, Warn, Danger }

private val PorcelainColors = lightColorScheme(
    primary = P.accent2,
    onPrimary = Color.White,
    primaryContainer = P.accent3,
    onPrimaryContainer = P.ink,
    secondary = P.accent,
    secondaryContainer = Color(0xFFE5EEE1),
    onSecondaryContainer = P.ink,
    tertiary = P.amber,
    background = P.base,
    onBackground = P.ink,
    surface = P.base,
    onSurface = P.ink,
    surfaceVariant = Color(0xFFE3EBE0),
    onSurfaceVariant = P.sub,
    surfaceContainerLowest = P.highlight,
    surfaceContainerLow = Color(0xFFF2F6EF),
    surfaceContainer = Color(0xFFEDF2E9),
    surfaceContainerHigh = Color(0xFFE5ECE1),
    surfaceContainerHighest = Color(0xFFDCE5D7),
    outline = Color(0xFFA9BAA6),
    outlineVariant = Color(0xFFC9D7C5),
    error = P.danger,
    onError = Color.White,
)

@Composable
fun PorcelainTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val type = base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = P.serif, fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp, color = P.ink),
        headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.2).sp, color = P.ink),
        headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, color = P.ink),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, color = P.ink),
        titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, color = P.ink),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, color = P.ink),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = P.ink),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, color = P.sub),
        labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = P.ink),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = P.sub),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = P.muted),
    )
    MaterialTheme(colorScheme = PorcelainColors, typography = type, content = content)
}

// ---- text ----

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = P.eyebrow) =
    Text(text.uppercase(), modifier, fontSize = 10.sp, letterSpacing = 2.4.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = P.accent) =
    Text(text.uppercase(), modifier, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold, color = color)

/** A heading with an optional serif tail, as the reference writes them ("Workspaces, *without boundaries.*"). */
@Composable
fun Headline(sans: String, serif: String? = null, modifier: Modifier = Modifier, size: Int = 28) {
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            append(sans)
            if (serif != null) {
                append(" ")
                pushStyle(androidx.compose.ui.text.SpanStyle(fontFamily = P.serif, fontWeight = FontWeight.Normal, letterSpacing = (-0.4).sp))
                append(serif)
                pop()
            }
        },
        modifier,
        fontSize = size.sp,
        lineHeight = (size * 1.14).sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-1).sp,
        color = P.ink,
    )
}

@Composable
fun SerifTitle(text: String, modifier: Modifier = Modifier, size: Int = 28) =
    Text(text, modifier, fontFamily = P.serif, fontSize = size.sp, lineHeight = (size * 1.12).sp, color = P.ink, letterSpacing = (-0.4).sp)

@Composable
fun Subtle(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

// ---- surfaces ----

enum class CardStyle { Ceramic, Soft, Dark }

private fun Modifier.ceramicSurface(shape: Shape, style: CardStyle, elevation: Dp): Modifier = when (style) {
    CardStyle.Ceramic -> this
        .shadow(elevation, shape, ambientColor = Color(0x22455F49), spotColor = Color(0x33455F49))
        .clip(shape)
        .background(P.ceramic)
        .border(1.dp, Color(0xCCFFFFFF), shape)
    CardStyle.Soft -> this
        .clip(shape)
        .background(Color(0xADF5F8F1))
        .border(1.dp, Color(0x36A5B9A5), shape)
    CardStyle.Dark -> this
        .shadow(elevation, shape, ambientColor = Color(0x2222352D), spotColor = Color(0x4422352D))
        .clip(shape)
        .background(P.night2)
        .border(1.dp, Color(0x85617862), shape)
}

/** The glazed card. [fold] gives it the folded bottom-right corner. */
@Composable
fun PCard(
    modifier: Modifier = Modifier,
    style: CardStyle = CardStyle.Ceramic,
    fold: Boolean = false,
    padding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = if (fold) RoundedCornerShape(topStart = P.radius, topEnd = P.radius, bottomStart = P.radius, bottomEnd = 45.dp) else RoundedCornerShape(P.radius)
    val color = if (style == CardStyle.Dark) Color(0xFFEAF1E8) else P.ink
    Box(modifier.fillMaxWidth().ceramicSurface(shape, style, 10.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }) {
        CompositionLocalProvider(LocalContentColor provides color) {
            Column(Modifier.padding(padding).animateContentSize(), content = content)
        }
        if (fold) {
            Canvas(Modifier.align(Alignment.BottomEnd).size(40.dp)) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width, 0f)
                    cubicTo(size.width * 0.2f, size.height * 0.1f, size.width * 0.1f, size.height * 0.2f, 0f, size.height)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(path, Brush.linearGradient(listOf(Color(0xFFC7D8C0), Color(0xFFF0F5E9)), start = Offset.Zero, end = Offset(size.width, size.height)))
            }
        }
    }
}

/** A row-shaped object (a project, session, node, file) the reference calls `.object`. */
@Composable
fun PObject(
    title: String,
    subtitle: String? = null,
    icon: IconSpec? = null,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Color(0xFFE6EFE1) else Color(0xCCF2F6EF))
            .border(1.dp, if (selected) Color(0xFF82A185) else Color(0x6EBFD1BB), shape)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (icon != null) PIcon(icon, size = 19.dp, tint = Color(0xFF6A866B))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke(this)
    }
}

/** label + description on the left, a value or control on the right (`.setting`). */
@Composable
fun PSetting(label: String, description: String? = null, modifier: Modifier = Modifier, divider: Boolean = true, trailing: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                if (!description.isNullOrBlank()) Text(description, Modifier.padding(top = 3.dp), fontSize = 11.sp, lineHeight = 16.sp, color = P.muted)
            }
            trailing()
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x3391AA90)))
    }
}

@Composable
fun PLine(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().padding(vertical = 13.dp).height(1.dp).background(P.line2))

@Composable
fun Tag(text: String, tone: Tone = Tone.Good, modifier: Modifier = Modifier, icon: IconSpec? = null) {
    val (bg, fg, border) = when (tone) {
        Tone.Good -> Triple(Color(0xFFE5EEE1), Color(0xFF5F8060), Color(0xFFBDCFB8))
        Tone.Neutral -> Triple(Color(0xFFE8EEE5), Color(0xFF6C7B6A), Color(0xFFCFDACB))
        Tone.Warn -> Triple(Color(0xFFF1E9DE), Color(0xFF956F44), Color(0xFFE1CBAC))
        Tone.Danger -> Triple(Color(0xFFF3DFDC), Color(0xFFA3625B), Color(0xFFE4C3BF))
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).border(1.dp, border, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) PIcon(icon, size = 12.dp, tint = fg)
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Callout(text: String, tone: Tone = Tone.Good, modifier: Modifier = Modifier) {
    val (bg, border, fg) = when (tone) {
        Tone.Good, Tone.Neutral -> Triple(Color(0xFFE3EDE0), Color(0xFFC9DAC7), Color(0xFF557155))
        Tone.Warn -> Triple(Color(0xFFF1E9DD), Color(0xFFE0CEAE), Color(0xFF856B49))
        Tone.Danger -> Triple(Color(0xFFF4E1DD), Color(0xFFE4C7C2), Color(0xFF97564F))
    }
    Text(
        text,
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).border(1.dp, border, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        fontSize = 12.sp, lineHeight = 19.sp, color = fg,
    )
}

/** A small labelled fact (`.decision`). */
@Composable
fun Fact(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(11.dp)).background(Color(0xFFE8F0E3)).border(1.dp, Color(0x4DC9DAC5), RoundedCornerShape(11.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3F5F46))
        Text(body, Modifier.padding(top = 3.dp), fontSize = 11.sp, lineHeight = 16.sp, color = Color(0xFF5A765E))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FactGrid(facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
        facts.forEach { (t, b) -> Fact(t, b, Modifier.weight(1f).fillMaxHeight()) }
    }
}

// ---- controls ----

enum class ButtonStyle { Primary, Alt, Ghost, Danger, Warn }

/** The tactile button: presses (scale .97) before the shape settles. */
@Composable
fun PButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    tiny: Boolean = false,
    enabled: Boolean = true,
    icon: IconSpec? = null,
    /** What a screen reader says for an icon-only button. */
    contentDescription: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(180, easing = P.ease), label = "press")
    val shape = RoundedCornerShape(if (tiny) 10.dp else 13.dp)
    val fg = when (style) {
        ButtonStyle.Primary, ButtonStyle.Danger, ButtonStyle.Warn -> Color.White
        ButtonStyle.Alt -> Color(0xFF506D57)
        ButtonStyle.Ghost -> Color(0xFF517458)
    }
    val surface: Modifier = when (style) {
        ButtonStyle.Primary -> Modifier.shadow(4.dp, shape, spotColor = Color(0x553A5945)).clip(shape).background(P.primaryButton)
        ButtonStyle.Alt -> Modifier.shadow(5.dp, shape, ambientColor = Color(0x22455F49), spotColor = Color(0x33455F49)).clip(shape).background(P.ceramic).border(1.dp, Color.White, shape)
        ButtonStyle.Ghost -> Modifier.clip(shape).background(Color(0xFFDFEBDE))
        ButtonStyle.Danger -> Modifier.clip(shape).background(Color(0xFFB17669))
        ButtonStyle.Warn -> Modifier.clip(shape).background(Color(0xFFAA8756))
    }
    Row(
        modifier
            .scale(scale)
            .alpha(if (enabled) 1f else 0.43f)
            .then(surface)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .defaultMinSize(minHeight = if (tiny) 34.dp else 44.dp)
            .padding(horizontal = if (tiny) 11.dp else 16.dp, vertical = if (tiny) 6.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) PIcon(icon, size = if (tiny) 14.dp else 16.dp, tint = fg)
        if (text.isNotEmpty()) Text(text, fontSize = if (tiny) 12.sp else 13.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round-cornered ceramic icon button (`.icbtn`). */
@Composable
fun PIconButton(icon: IconSpec, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Color = P.accent2) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .size(size)
            .shadow(4.dp, shape, ambientColor = Color(0x22455F49), spotColor = Color(0x33455F49))
            .clip(shape)
            .background(P.ceramic)
            .border(1.dp, Color(0xBBFFFFFF), shape)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PIcon(icon, size = 18.dp, tint = tint, contentDescription = contentDescription) }
}

/** The glaze segmented control (`.seg`). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier.clip(shape).background(Color(0xFFDCE7D9)).border(1.dp, Color(0xA1FFFFFF), shape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            val inner = RoundedCornerShape(11.dp)
            Box(
                Modifier
                    .let { if (on) it.shadow(3.dp, inner, spotColor = Color(0x33455F49)).clip(inner).background(P.ceramic) else it.clip(inner) }
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .defaultMinSize(minHeight = 34.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color(0xFF355740) else Color(0xFF6C806D), maxLines = 1) }
        }
    }
}

@Composable
fun PBar(fraction: Float, modifier: Modifier = Modifier, tone: Tone = Tone.Good) {
    val fill = when (tone) {
        Tone.Good, Tone.Neutral -> Brush.horizontalGradient(listOf(Color(0xFFB6CDB0), Color(0xFF5D8064)))
        Tone.Warn -> Brush.horizontalGradient(listOf(Color(0xFFE0CEAE), P.amber))
        Tone.Danger -> Brush.horizontalGradient(listOf(Color(0xFFE4C3BF), P.danger))
    }
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFFCDDBC8))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(20.dp)).background(fill))
    }
}

// ---- steps and graphs ----

enum class StepState { Done, Wait, Mute, Active, Failed }

data class StepItem(val title: String, val detail: String? = null, val state: StepState = StepState.Done)

/** A vertical list of steps joined by a thin line (`.steps`). */
@Composable
fun Steps(items: List<StepItem>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        items.forEachIndexed { i, step ->
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.width(25.dp)) {
                    if (i < items.lastIndex) Box(Modifier.padding(start = 12.dp, top = 25.dp).width(1.dp).height(30.dp).background(Color(0xFFAFC0AC)))
                    val (bg, fg, border) = when (step.state) {
                        StepState.Done -> Triple(Color(0xFFE5F0DF), Color(0xFF57835A), Color(0xFFB6CCB0))
                        StepState.Active -> Triple(Color(0xFFDCEBD6), P.accent2, Color(0xFF82A185))
                        StepState.Wait -> Triple(Color(0xFFF1E9DD), Color(0xFFA08256), Color(0xFFE4C9A6))
                        StepState.Mute -> Triple(Color(0xFFE6EAE2), Color(0xFF87988B), Color(0xFFCFD8CC))
                        StepState.Failed -> Triple(Color(0xFFF3DFDC), P.danger, Color(0xFFE4C3BF))
                    }
                    Box(Modifier.size(25.dp).clip(CircleShape).background(bg).border(1.dp, border, CircleShape), contentAlignment = Alignment.Center) {
                        PIcon(
                            when (step.state) {
                                StepState.Done -> PIcons.check
                                StepState.Wait -> PIcons.clock
                                StepState.Active -> PIcons.activity
                                StepState.Failed -> PIcons.close
                                StepState.Mute -> PIcons.more
                            },
                            size = 13.dp, tint = fg,
                        )
                    }
                }
                Column(Modifier.padding(start = 11.dp, bottom = 16.dp).weight(1f)) {
                    Text(step.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (step.state == StepState.Mute) P.muted else P.ink)
                    if (!step.detail.isNullOrBlank()) Text(step.detail, Modifier.padding(top = 2.dp), fontSize = 12.sp, lineHeight = 17.sp, color = P.sub)
                }
            }
        }
    }
}

data class GraphNode(val title: String, val detail: String, val tone: Tone = Tone.Neutral)

/** Nodes joined by arrows (`.graph`): a delegation tree, a phase DAG path, a commit protocol. Wraps on a phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GraphFlow(nodes: List<GraphNode>, modifier: Modifier = Modifier, edge: String = "→") {
    FlowRow(modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        nodes.forEachIndexed { i, n ->
            if (i > 0) Text(edge, Modifier.align(Alignment.CenterVertically), color = Color(0xFF87A085))
            val border = when (n.tone) {
                Tone.Good -> Color(0xFF9DBB9A)
                Tone.Neutral -> Color(0xFFC5D4C2)
                Tone.Warn -> Color(0xFFE0CEAE)
                Tone.Danger -> Color(0xFFE4C3BF)
            }
            Column(
                Modifier.defaultMinSize(minWidth = 96.dp).clip(RoundedCornerShape(17.dp)).background(Color(0xCFF2F6EE)).border(1.dp, border, RoundedCornerShape(17.dp)).padding(11.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(n.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(n.detail, Modifier.padding(top = 4.dp), fontSize = 11.sp, color = P.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A metric tile: label, big serif value, detail. */
@Composable
fun Metric(label: String, value: String, detail: String? = null, icon: IconSpec? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    PCard(modifier, padding = PaddingValues(15.dp), onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Kicker(label, Modifier.weight(1f), color = P.muted)
            if (icon != null) PIcon(icon, size = 16.dp, tint = P.accent)
        }
        Text(value, Modifier.padding(top = 8.dp), fontFamily = P.serif, fontSize = 28.sp, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) Text(detail, Modifier.padding(top = 2.dp), fontSize = 11.sp, color = P.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A dark console pane with a title bar, for terminal output and logs. */
@Composable
fun ConsolePane(title: String, status: String? = null, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF293A2F))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF223229)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF9BD3A2)))
            Text(title, Modifier.padding(start = 9.dp).weight(1f), fontSize = 11.sp, color = Color(0xFFB9CDBA), fontFamily = P.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status != null) Text(status, fontSize = 11.sp, color = Color(0xFF9DB2A0))
        }
        Box(Modifier.fillMaxWidth(), content = content)
    }
}

/** A square ceramic badge holding an icon (`.badgeicon`). */
@Composable
fun BadgeIcon(icon: IconSpec, modifier: Modifier = Modifier, size: Dp = 39.dp) {
    val shape = RoundedCornerShape(size * 0.36f)
    Box(modifier.size(size).shadow(4.dp, shape, spotColor = Color(0x33455F49)).clip(shape).background(P.ceramic), contentAlignment = Alignment.Center) {
        PIcon(icon, size = size * 0.5f, tint = P.accent)
    }
}

// ---- icons ----

private val parsed = java.util.concurrent.ConcurrentHashMap<IconSpec, List<Pair<androidx.compose.ui.graphics.Path, Boolean>>>()

private fun paths(spec: IconSpec) = parsed.getOrPut(spec) { spec.parts.map { (d, fill) -> PathParser().parsePathString(d).toPath() to fill } }

/** Draws a Porcelain line icon in [tint]. */
@Composable
fun PIcon(icon: IconSpec, modifier: Modifier = Modifier, size: Dp = 18.dp, tint: Color = LocalContentColor.current, contentDescription: String? = null, strokeWidth: Float = 1.75f) {
    val list = remember(icon) { paths(icon) }
    Canvas(modifier.size(size).then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)) {
        val k = this.size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) {
            list.forEach { (path, fill) ->
                if (fill) drawPath(path, Color(0xFFEAF0E6), style = Fill)
                drawPath(path, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
fun Gap(height: Dp = 12.dp) = Spacer(Modifier.height(height))
