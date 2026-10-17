package app.umbra.agent.ui.porcelain

import app.umbra.agent.R
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.umbra.agent.container
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
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
    // The named colours and glazes follow the current theme (Theme.current); under Porcelain they are the reference's.
    val base get() = Theme.current.tokens.base
    val base2 get() = Theme.current.tokens.base2
    val ink get() = Theme.current.tokens.ink
    val muted get() = Theme.current.tokens.muted
    val sub get() = Theme.current.tokens.sub
    val accent get() = Theme.current.tokens.accent
    val accent2 get() = Theme.current.tokens.accent2
    val accent3 get() = Theme.current.tokens.accent3
    val line get() = Theme.current.tokens.line
    val line2 get() = Theme.current.tokens.line2
    val danger get() = Theme.current.tokens.danger
    val amber get() = Theme.current.tokens.amber
    val good get() = Theme.current.tokens.good
    val night get() = Theme.current.tokens.night
    val eyebrow get() = Theme.current.tokens.eyebrow
    val highlight get() = Theme.current.tokens.highlight
    val fold get() = Theme.current.tokens.fold
    val code get() = Theme.current.tokens.code
    val codeText get() = Theme.current.tokens.codeText
    val ceramic get() = Theme.current.tokens.ceramic
    val ceramic2 get() = Theme.current.tokens.ceramic2
    val night2 get() = Theme.current.tokens.night2
    val primaryButton get() = Theme.current.tokens.primaryButton
    val page get() = Theme.current.tokens.page

    /** cubic-bezier(.22, 1, .36, 1): controls press before their shape settles. */
    val ease = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /** Interface text: Geist, a variable sans with four weights in use (400, 500, 600, 700); Apple: the system sans. */
    val sans: FontFamily get() = if (Theme.apple) AppleType.text else porcelainSans

    /**
     * Titles and headline tails: Newsreader at a display optical size (the reference's Georgia, with more care);
     * Apple: the system sans drawn semibold, as iOS sets titles.
     */
    val serif: FontFamily get() = if (Theme.apple) AppleType.display else porcelainSerif
    val mono = FontFamily.Monospace

    /** Numbers that change in place (counts, sizes, timers) keep their width. */
    const val tabular = "tnum"

    val radius = 24.dp
}

private val porcelainSans = FontFamily(geist(400), geist(500), geist(600), geist(700))
private val porcelainSerif = FontFamily(newsreader(400), newsreader(500))

/** The Apple theme's type: the platform's own sans (the skill's "system font first"), titles a weight heavier. */
object AppleType {
    val text: FontFamily = FontFamily.Default

    /** The system sans, where a "regular" title asks for 600 and a "medium" one for 700. */
    val display: FontFamily = FontFamily(
        Font(androidx.compose.ui.text.font.DeviceFontFamilyName("sans-serif"), FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(androidx.compose.ui.text.font.DeviceFontFamilyName("sans-serif"), FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
        Font(androidx.compose.ui.text.font.DeviceFontFamilyName("sans-serif"), FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
        Font(androidx.compose.ui.text.font.DeviceFontFamilyName("sans-serif"), FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    )

    /**
     * iOS's text styles, tracked by size: large type tighter, small type a touch looser, leading tightening as
     * the size grows (Large Title 34, Title 28/22/20, Headline 17, Body 15 here, Footnote 13, Caption 12/11).
     */
    fun typography(base: androidx.compose.material3.Typography, ink: Color, secondary: Color, tertiary: Color): androidx.compose.material3.Typography {
        fun t(size: Int, line: Int, weight: FontWeight, track: Double, color: Color, family: FontFamily = text) =
            TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = track.sp, color = color)
        return base.copy(
            displayLarge = t(57, 62, FontWeight.Bold, -1.6, ink, display),
            displayMedium = t(45, 50, FontWeight.Bold, -1.2, ink, display),
            displaySmall = t(34, 40, FontWeight.Bold, -0.8, ink, display),
            headlineLarge = t(32, 38, FontWeight.Bold, -0.7, ink, display),
            headlineMedium = t(28, 33, FontWeight.Bold, -0.6, ink, display),
            headlineSmall = t(22, 27, FontWeight.Bold, -0.4, ink, display),
            titleLarge = t(20, 25, FontWeight.SemiBold, -0.35, ink),
            titleMedium = t(17, 22, FontWeight.SemiBold, -0.3, ink),
            titleSmall = t(15, 20, FontWeight.SemiBold, -0.15, ink),
            bodyLarge = t(15, 21, FontWeight.Normal, -0.15, ink),
            bodyMedium = t(14, 20, FontWeight.Normal, -0.1, ink),
            bodySmall = t(12, 16, FontWeight.Normal, 0.0, secondary),
            labelLarge = t(13, 18, FontWeight.SemiBold, -0.05, ink),
            labelMedium = t(12, 16, FontWeight.Medium, 0.0, secondary),
            labelSmall = t(11, 13, FontWeight.Medium, 0.07, tertiary),
        )
    }
}

private fun geist(weight: Int) =
    Font(R.font.geist, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private fun newsreader(weight: Int) =
    Font(R.font.newsreader, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", 36f)))

enum class Tone { Good, Neutral, Warn, Danger }

private fun colorsFor(spec: ThemeSpec): androidx.compose.material3.ColorScheme {
    val t = spec.tokens
    val scheme = if (spec.dark) androidx.compose.material3.darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = t.accent2,
        onPrimary = if (spec.dark && spec.liquid) t.base else Color.White,
        primaryContainer = t.accent3,
        onPrimaryContainer = t.ink,
        secondary = t.accent,
        secondaryContainer = spec.adapt(0xFFE5EEE1),
        onSecondaryContainer = t.ink,
        tertiary = t.amber,
        background = t.base,
        onBackground = t.ink,
        surface = t.base,
        onSurface = t.ink,
        surfaceVariant = spec.adapt(0xFFE3EBE0),
        onSurfaceVariant = t.sub,
        surfaceContainerLowest = t.highlight,
        surfaceContainerLow = spec.adapt(0xFFF2F6EF),
        surfaceContainer = spec.adapt(0xFFEDF2E9),
        surfaceContainerHigh = spec.adapt(0xFFE5ECE1),
        surfaceContainerHighest = spec.adapt(0xFFDCE5D7),
        outline = spec.adapt(0xFFA9BAA6),
        outlineVariant = spec.adapt(0xFFC9D7C5),
        error = t.danger,
        onError = Color.White,
    )
}

/**
 * The app's theme root. The theme itself (Porcelain or Liquid, hue, light or dark) lives in [Theme.current]; it is
 * set from the saved preference when the app starts and follows it here when the preference or the system's dark
 * mode changes. Everything below reads it, so a change reaches every screen at once.
 */
@Composable
fun PorcelainTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs by context.container.settings.flow.collectAsState()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    androidx.compose.runtime.LaunchedEffect(prefs.theme, systemDark) {
        val next = ThemeSpec.of(prefs.theme, systemDark)
        if (next != Theme.current) Theme.current = next
    }
    val spec = Theme.current
    val base = MaterialTheme.typography
    val type = remember(spec) { if (spec.apple) AppleType.typography(base, spec.tokens.ink, spec.tokens.sub, spec.tokens.muted) else typography(base) }
    val colors = remember(spec) { colorsFor(spec) }
    MaterialTheme(colorScheme = colors, typography = type) {
        // Porcelain leaves the content colour and the ripple as Material sets them; Liquid and Apple bring their own ink,
        // Apple its grey touch highlight, and glass inside a page refracts the page colour.
        // (one provider either way: switching theme must not change the tree, or every screen's state would reset)
        CompositionLocalProvider(
            LocalContentColor provides if (spec.porcelain) LocalContentColor.current else spec.tokens.ink,
            androidx.compose.foundation.LocalIndication provides if (spec.apple) AppleHighlightIndication else androidx.compose.foundation.LocalIndication.current,
            LocalGlassSurface provides spec.tokens.base,
            content = content,
        )
    }
}

private fun typography(base: androidx.compose.material3.Typography): androidx.compose.material3.Typography {
    // Every style carries Geist; display styles use the serif. Text without a style inherits bodyLarge.
    val sans = TextStyle(fontFamily = P.sans)
    return base.copy(
        displayLarge = base.displayLarge.merge(sans),
        displayMedium = base.displayMedium.merge(sans),
        displaySmall = base.displaySmall.copy(fontFamily = P.serif, fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp, color = P.ink),
        headlineLarge = base.headlineLarge.merge(sans),
        headlineMedium = TextStyle(fontFamily = P.sans, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.2).sp, color = P.ink),
        headlineSmall = TextStyle(fontFamily = P.sans, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, color = P.ink),
        titleLarge = base.titleLarge.merge(sans).copy(color = P.ink, letterSpacing = (-0.4).sp),
        titleMedium = TextStyle(fontFamily = P.sans, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, color = P.ink),
        titleSmall = TextStyle(fontFamily = P.sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, color = P.ink),
        bodyLarge = TextStyle(fontFamily = P.sans, fontSize = 15.sp, lineHeight = 23.sp, color = P.ink),
        bodyMedium = TextStyle(fontFamily = P.sans, fontSize = 14.sp, lineHeight = 21.sp, color = P.ink),
        bodySmall = TextStyle(fontFamily = P.sans, fontSize = 12.sp, lineHeight = 18.sp, color = P.sub),
        labelLarge = TextStyle(fontFamily = P.sans, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = P.ink),
        labelMedium = TextStyle(fontFamily = P.sans, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = P.sub),
        labelSmall = TextStyle(fontFamily = P.sans, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = P.muted),
    )
}

// ---- text ----

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = P.eyebrow) =
    Text(text.uppercase(), modifier, fontSize = 10.sp, letterSpacing = if (text.hasCjk()) 0.8.sp else 2.4.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)

/**
 * Chinese, Japanese and Korean set solid: the wide tracking of the small Latin capitals pulls ideographs apart, so
 * labels in those scripts use a fraction of it, and a heading's two parts join without a space.
 */
fun String.hasCjk(): Boolean = any { Character.UnicodeScript.of(it.code).let { s -> s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL } }

@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = P.accent) =
    Text(text.uppercase(), modifier, fontSize = 10.sp, letterSpacing = if (text.hasCjk()) 0.6.sp else 1.6.sp, fontWeight = FontWeight.Bold, color = color)

/** A heading with an optional serif tail, as the reference writes them ("Workspaces, *without boundaries.*"). */
@Composable
fun Headline(sans: String, serif: String? = null, modifier: Modifier = Modifier, size: Int = 28) {
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            append(sans)
            if (serif != null) {
                if (!(sans.hasCjk() || serif.hasCjk())) append(" ")
                pushStyle(androidx.compose.ui.text.SpanStyle(fontFamily = P.serif, fontWeight = FontWeight.Normal, letterSpacing = if (Theme.apple) (-0.03 * size).sp else (-0.4).sp))
                append(serif)
                pop()
            }
        },
        modifier,
        fontSize = size.sp,
        lineHeight = (size * 1.14).sp,
        fontWeight = if (Theme.apple) FontWeight.Bold else FontWeight.Medium,
        letterSpacing = if (Theme.apple) (-0.03 * size).sp else (-1).sp,
        color = P.ink,
        style = TextStyle(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading),
    )
}

@Composable
fun SerifTitle(text: String, modifier: Modifier = Modifier, size: Int = 28) =
    Text(text, modifier, fontFamily = P.serif, fontSize = size.sp, lineHeight = (size * 1.12).sp, color = P.ink, letterSpacing = (-0.4).sp, style = TextStyle(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading))

@Composable
fun Subtle(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

// ---- surfaces ----

enum class CardStyle { Ceramic, Soft, Dark }

@Composable
private fun Modifier.ceramicSurface(shape: Shape, style: CardStyle, elevation: Dp): Modifier = if (Theme.apple) when (style) {
    // content stays solid under Apple, glass or not: glass is for the functional layer (HIG), never glass on glass
    CardStyle.Ceramic -> this.clip(shape).background(AppleColors.card(Theme.dark))
    CardStyle.Soft -> this.clip(shape).background(AppleColors.fill(Theme.dark))
    CardStyle.Dark -> this.clip(shape).background(P.night2)
} else if (Theme.liquid) when (style) {
    CardStyle.Ceramic -> liquidGlass(shape, GlassKind.Card, elevation + 4.dp)
    CardStyle.Soft -> liquidGlass(shape, GlassKind.Soft)
    CardStyle.Dark -> this
        .shadow(elevation + 4.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = hsl(Theme.current.hue, 0.7f, 0.25f, 0.5f))
        .clip(shape)
        .background(P.night2)
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.04f))), shape)
} else when (style) {
    CardStyle.Ceramic -> this
        .shadow(elevation, shape, ambientColor = pcHue(0x22455F49), spotColor = pcHue(0x33455F49))
        .clip(shape)
        .background(P.ceramic)
        .border(1.dp, pc(0xCCFFFFFF), shape)
    CardStyle.Soft -> this
        .clip(shape)
        .background(pc(0xADF5F8F1))
        .border(1.dp, pc(0x36A5B9A5), shape)
    CardStyle.Dark -> this
        .shadow(elevation, shape, ambientColor = pcHue(0x2222352D), spotColor = pcHue(0x4422352D))
        .clip(shape)
        .background(P.night2)
        .border(1.dp, pc(0x85617862), shape)
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
    val color = if (style == CardStyle.Dark) pcHue(0xFFEAF1E8) else P.ink
    val press = rememberPress()
    val surface = when {
        !Theme.apple -> Theme.current.tokens.base
        style == CardStyle.Ceramic -> AppleColors.card(Theme.dark)
        style == CardStyle.Dark -> Color(0xFF2C2C2E)
        else -> AppleColors.fill(Theme.dark).compositeOverCompat(Theme.current.tokens.base)
    }
    Box(
        modifier.fillMaxWidth()
            .let { if (onClick != null && Theme.liquid) it.jelly(press, 0.35f, glow = shape) else it }
            .ceramicSurface(shape, style, 10.dp)
            .let { if (onClick != null) it.clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick) else it },
    ) {
        CompositionLocalProvider(LocalContentColor provides color, LocalGlassSurface provides surface) {
            Column(Modifier.padding(padding).animateContentSize(), content = content)
        }
        if (fold && !Theme.apple) {
            Canvas(Modifier.align(Alignment.BottomEnd).size(40.dp)) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width, 0f)
                    cubicTo(size.width * 0.2f, size.height * 0.1f, size.width * 0.1f, size.height * 0.2f, 0f, size.height)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(path, Brush.linearGradient(listOf(pc(0xFFC7D8C0), pc(0xFFF0F5E9)), start = Offset.Zero, end = Offset(size.width, size.height)))
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
    val press = rememberPress()
    Row(
        modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.liquidPress(press, 0.5f) else it }
            .clip(shape)
            .then(
                if (Theme.apple) Modifier.background(if (selected) P.accent3 else AppleColors.card(Theme.dark))
                else Modifier.background(if (selected) pc(0xFFE6EFE1) else pc(0xCCF2F6EF)).border(1.dp, if (selected) pc(0xFF82A185) else pc(0x6EBFD1BB), shape),
            )
            .let { if (onClick != null) it.clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick) else it }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (icon != null) PIcon(icon, size = 19.dp, tint = pc(0xFF6A866B))
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
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(pc(0x3391AA90)))
    }
}

@Composable
fun PLine(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().padding(vertical = 13.dp).height(1.dp).background(P.line2))

@Composable
fun Tag(text: String, tone: Tone = Tone.Good, modifier: Modifier = Modifier, icon: IconSpec? = null) {
    val (bg, fg, border) = when (tone) {
        Tone.Good -> Triple(pc(0xFFE5EEE1), pc(0xFF5F8060), pc(0xFFBDCFB8))
        Tone.Neutral -> Triple(pc(0xFFE8EEE5), pc(0xFF6C7B6A), pc(0xFFCFDACB))
        Tone.Warn -> Triple(pc(0xFFF1E9DE), pc(0xFF956F44), pc(0xFFE1CBAC))
        Tone.Danger -> Triple(pc(0xFFF3DFDC), pc(0xFFA3625B), pc(0xFFE4C3BF))
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
        Tone.Good, Tone.Neutral -> Triple(pc(0xFFE3EDE0), pc(0xFFC9DAC7), pc(0xFF557155))
        Tone.Warn -> Triple(pc(0xFFF1E9DD), pc(0xFFE0CEAE), pc(0xFF856B49))
        Tone.Danger -> Triple(pc(0xFFF4E1DD), pc(0xFFE4C7C2), pc(0xFF97564F))
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
    Column(modifier.clip(RoundedCornerShape(11.dp)).background(pc(0xFFE8F0E3)).border(1.dp, pc(0x4DC9DAC5), RoundedCornerShape(11.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pc(0xFF3F5F46))
        Text(body, Modifier.padding(top = 3.dp), fontSize = 11.sp, lineHeight = 16.sp, color = pc(0xFF5A765E))
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
    val liquid = Theme.liquid
    val apple = Theme.apple
    if (apple) return AppleButton(text, onClick, modifier, style, tiny, enabled, icon, contentDescription)
    val shape = RoundedCornerShape(if (tiny) 10.dp else 13.dp)
    val fg = when (style) {
        ButtonStyle.Primary, ButtonStyle.Danger, ButtonStyle.Warn -> if (liquid && style != ButtonStyle.Primary && Theme.dark) Theme.current.tokens.base else Color.White
        ButtonStyle.Alt -> if (liquid) P.accent2 else pc(0xFF506D57)
        ButtonStyle.Ghost -> pc(0xFF517458)
    }
    val surface: Modifier = if (liquid) when (style) {
        ButtonStyle.Primary -> Modifier.shadow(10.dp, shape, ambientColor = hsl(Theme.current.hue, 0.7f, 0.35f, 0.25f), spotColor = hsl(Theme.current.hue, 0.8f, 0.4f, 0.6f)).clip(shape).background(P.primaryButton).liquidSheen()
        ButtonStyle.Alt -> Modifier.liquidGlass(shape, GlassKind.Control, 6.dp)
        ButtonStyle.Ghost -> Modifier.clip(shape).background(pc(0xFFDFEBDE)).border(1.dp, pc(0x6EBFD1BB), shape)
        ButtonStyle.Danger -> Modifier.shadow(8.dp, shape, spotColor = P.danger.copy(alpha = 0.5f)).clip(shape).background(Brush.linearGradient(listOf(P.danger.copy(alpha = 0.92f), P.danger))).liquidSheen()
        ButtonStyle.Warn -> Modifier.shadow(8.dp, shape, spotColor = P.amber.copy(alpha = 0.5f)).clip(shape).background(Brush.linearGradient(listOf(P.amber.copy(alpha = 0.92f), P.amber))).liquidSheen()
    } else when (style) {
        ButtonStyle.Primary -> Modifier.shadow(4.dp, shape, spotColor = pcHue(0x553A5945)).clip(shape).background(P.primaryButton)
        ButtonStyle.Alt -> Modifier.shadow(5.dp, shape, ambientColor = pcHue(0x22455F49), spotColor = pcHue(0x33455F49)).clip(shape).background(P.ceramic).border(1.dp, Color.White, shape)
        ButtonStyle.Ghost -> Modifier.clip(shape).background(pc(0xFFDFEBDE))
        ButtonStyle.Danger -> Modifier.clip(shape).background(pc(0xFFB17669))
        ButtonStyle.Warn -> Modifier.clip(shape).background(pc(0xFFAA8756))
    }
    Row(
        modifier
            .then(if (liquid) Modifier.jelly(interaction, glow = shape) else Modifier.scale(scale))
            .alpha(if (enabled) 1f else 0.43f)
            .then(surface)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
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
    if (Theme.apple) return AppleIconButton(icon, contentDescription, onClick, modifier, size, tint)
    val shape = RoundedCornerShape(12.dp)
    val press = rememberPress()
    Box(
        modifier
            .size(size)
            .then(
                if (Theme.liquid) Modifier.jelly(press, glow = RoundedCornerShape(size / 2)).liquidGlass(RoundedCornerShape(size / 2), GlassKind.Control, 6.dp)
                else Modifier.shadow(4.dp, shape, ambientColor = pcHue(0x22455F49), spotColor = pcHue(0x33455F49)).clip(shape).background(P.ceramic).border(1.dp, pc(0xBBFFFFFF), shape),
            )
            .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PIcon(icon, size = 18.dp, tint = tint, contentDescription = contentDescription) }
}

/**
 * The glaze segmented control (`.seg`). With [fill] it spans the width it is given and every segment gets an equal
 * share, so it lines up with the content under it.
 */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, fill: Boolean = false) {
    if (Theme.liquid) return LiquidSegmented(options, selected, onSelect, modifier, fill)
    if (Theme.apple) return AppleSegmented(options, selected, onSelect, modifier, fill)
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).clip(shape).background(pc(0xFFDCE7D9)).border(1.dp, pc(0xA1FFFFFF), shape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            val inner = RoundedCornerShape(11.dp)
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .let { if (on) it.shadow(3.dp, inner, spotColor = pcHue(0x33455F49)).clip(inner).background(P.ceramic) else it.clip(inner) }
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .defaultMinSize(minHeight = 34.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) pc(0xFF355740) else pc(0xFF6C806D), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
fun PBar(fraction: Float, modifier: Modifier = Modifier, tone: Tone = Tone.Good) {
    val fill = when (tone) {
        Tone.Good, Tone.Neutral -> Brush.horizontalGradient(listOf(pc(0xFFB6CDB0), pc(0xFF5D8064)))
        Tone.Warn -> Brush.horizontalGradient(listOf(pc(0xFFE0CEAE), P.amber))
        Tone.Danger -> Brush.horizontalGradient(listOf(pc(0xFFE4C3BF), P.danger))
    }
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(20.dp)).background(pc(0xFFCDDBC8))) {
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
                    if (i < items.lastIndex) Box(Modifier.padding(start = 12.dp, top = 25.dp).width(1.dp).height(30.dp).background(pc(0xFFAFC0AC)))
                    val (bg, fg, border) = when (step.state) {
                        StepState.Done -> Triple(pc(0xFFE5F0DF), pc(0xFF57835A), pc(0xFFB6CCB0))
                        StepState.Active -> Triple(pc(0xFFDCEBD6), P.accent2, pc(0xFF82A185))
                        StepState.Wait -> Triple(pc(0xFFF1E9DD), pc(0xFFA08256), pc(0xFFE4C9A6))
                        StepState.Mute -> Triple(pc(0xFFE6EAE2), pc(0xFF87988B), pc(0xFFCFD8CC))
                        StepState.Failed -> Triple(pc(0xFFF3DFDC), P.danger, pc(0xFFE4C3BF))
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
            if (i > 0) Text(edge, Modifier.align(Alignment.CenterVertically), color = pc(0xFF87A085))
            val border = when (n.tone) {
                Tone.Good -> pc(0xFF9DBB9A)
                Tone.Neutral -> pc(0xFFC5D4C2)
                Tone.Warn -> pc(0xFFE0CEAE)
                Tone.Danger -> pc(0xFFE4C3BF)
            }
            Column(
                Modifier.defaultMinSize(minWidth = 96.dp).clip(RoundedCornerShape(17.dp)).background(pc(0xCFF2F6EE)).border(1.dp, border, RoundedCornerShape(17.dp)).padding(11.dp),
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
        Text(value, Modifier.padding(top = 8.dp), fontFamily = P.serif, fontSize = 28.sp, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFeatureSettings = P.tabular))
        if (detail != null) Text(detail, Modifier.padding(top = 2.dp), fontSize = 11.sp, color = P.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A dark console pane with a title bar, for terminal output and logs. */
@Composable
fun ConsolePane(title: String, status: String? = null, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(pcHue(0xFF293A2F))) {
        Row(Modifier.fillMaxWidth().background(pcHue(0xFF223229)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(pcHue(0xFF9BD3A2)))
            Text(title, Modifier.padding(start = 9.dp).weight(1f), fontSize = 11.sp, color = pcHue(0xFFB9CDBA), fontFamily = P.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status != null) Text(status, fontSize = 11.sp, color = pcHue(0xFF9DB2A0))
        }
        Box(Modifier.fillMaxWidth(), content = content)
    }
}

/** A square ceramic badge holding an icon (`.badgeicon`). */
@Composable
fun BadgeIcon(icon: IconSpec, modifier: Modifier = Modifier, size: Dp = 39.dp) {
    val shape = RoundedCornerShape(size * 0.36f)
    // Apple: a Settings-style tile, the glyph white on the accent
    if (Theme.apple) return Box(modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(Theme.current.accentColor), contentAlignment = Alignment.Center) {
        PIcon(icon, size = size * 0.56f, tint = Color.White)
    }
    val surface = if (Theme.liquid) Modifier.liquidGlass(shape, GlassKind.Control, 5.dp) else Modifier.shadow(4.dp, shape, spotColor = pcHue(0x33455F49)).clip(shape).background(P.ceramic)
    Box(modifier.size(size).then(surface), contentAlignment = Alignment.Center) {
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
                if (fill) drawPath(path, pc(0xFFEAF0E6), style = Fill)
                drawPath(path, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
fun Gap(height: Dp = 12.dp) = Spacer(Modifier.height(height))

// ---- Apple controls ----

/**
 * Apple buttons. Glass: capsules of Liquid Glass ([ButtonStyle.Primary], Danger, Warn tinted like `.glassProminent`,
 * Alt clear), lit from the finger, swelling and leaning toward a drag. Classic: iOS's filled, tinted and plain
 * buttons, dimming the instant they are touched.
 */
@Composable
private fun AppleButton(text: String, onClick: () -> Unit, modifier: Modifier, style: ButtonStyle, tiny: Boolean, enabled: Boolean, icon: IconSpec?, contentDescription: String?) {
    val glass = appleGlassActive()
    val dark = Theme.dark
    // glass on a dark surface takes dark tint and light ink, whatever the theme
    val onDark = surfaceIsDark()
    val accent = Theme.current.accentColor
    val tint = when (style) {
        ButtonStyle.Primary -> accent
        ButtonStyle.Danger -> AppleColors.red(dark)
        ButtonStyle.Warn -> AppleColors.orange(dark)
        else -> Color.Unspecified
    }
    val filled = tint != Color.Unspecified
    val fg = when {
        filled -> Color.White
        style == ButtonStyle.Ghost -> P.accent2
        glass -> AppleColors.label(onDark)
        else -> P.accent2
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // classic iOS: the press shows on touch-down, at once, and fades back
    val dim by animateFloatAsState(if (pressed && !glass) 0.55f else 1f, if (pressed) tween(0) else tween(200), label = "dim")
    val capsule = RoundedCornerShape(50)
    val classicShape = RoundedCornerShape(if (tiny) 9.dp else 12.dp)
    val highlight = rememberInteractiveHighlight()
    val backdrop = rememberSurfaceBackdrop()
    val surface: Modifier = when {
        style == ButtonStyle.Ghost -> Modifier
        glass -> Modifier.appleGlass(backdrop, capsule, if (filled) GlassTint.Prominent else GlassTint.Regular, tint, highlight, dark = onDark)
        filled -> Modifier.clip(classicShape).background(tint)
        else -> Modifier.clip(classicShape).background(accent.copy(alpha = if (dark) 0.22f else 0.14f))
    }
    Row(
        modifier
            .alpha((if (enabled) 1f else 0.4f) * dim)
            .then(surface)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .defaultMinSize(minHeight = if (tiny) 34.dp else 44.dp)
            .padding(horizontal = if (tiny) 13.dp else 18.dp, vertical = if (tiny) 6.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) PIcon(icon, size = if (tiny) 14.dp else 16.dp, tint = fg)
        if (text.isNotEmpty()) Text(text, fontSize = if (tiny) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Apple bar buttons: a glass circle (iOS 26) or, classic, the bare symbol in the accent that dims on touch. */
@Composable
private fun AppleIconButton(icon: IconSpec, contentDescription: String, onClick: () -> Unit, modifier: Modifier, size: Dp, tint: Color) {
    val glass = appleGlassActive()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dim by animateFloatAsState(if (pressed && !glass) 0.4f else 1f, if (pressed) tween(0) else tween(200), label = "dim")
    val highlight = rememberInteractiveHighlight()
    val backdrop = rememberSurfaceBackdrop()
    val onDark = surfaceIsDark()
    val iconTint = if (tint == P.danger) tint else if (glass) AppleColors.label(onDark) else Theme.current.accentColor
    Box(
        modifier
            .size(size)
            .alpha(dim)
            .then(if (glass) Modifier.appleGlass(backdrop, RoundedCornerShape(50), GlassTint.Regular, highlight = highlight, swell = 3.dp, dark = onDark) else Modifier)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PIcon(icon, size = if (glass) 18.dp else 22.dp, tint = iconTint, contentDescription = contentDescription) }
}
