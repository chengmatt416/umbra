package app.umbra.agent.ui.porcelain

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.umbra.agent.data.AppleAccent
import app.umbra.agent.data.ThemeAppearance
import app.umbra.agent.data.ThemePrefs
import app.umbra.agent.data.ThemeStyle
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The look the app is drawn in right now. Porcelain is the original grey-green glaze, every colour exactly as
 * designed. Liquid re-tints the same design into the user's [hue], light or [dark], over a living backdrop. Apple
 * re-draws it in iOS's system palette and type ([accent] tint), with Liquid Glass controls and bars when [glass] is on.
 *
 * Every colour in the UI passes through [adapt] (via [pc]), so a theme reaches every screen without each one knowing
 * about themes: Porcelain returns the colour as written, Liquid moves its hue to the user's, gives it more colour,
 * and in the dark turns its lightness over; Apple maps neutrals to iOS greys, the glaze's greens to the accent, and
 * clay and amber to system red and orange.
 */
@Immutable
data class ThemeSpec(
    val style: ThemeStyle,
    val hue: Float,
    val dark: Boolean,
    val motion: Boolean,
    val accent: AppleAccent = AppleAccent.BLUE,
    /** Apple only: Liquid Glass on (Liquid's surfaces are always glass). */
    val appleGlass: Boolean = true,
) {
    private val cache = ConcurrentHashMap<Long, Color>()
    private val fixedCache = ConcurrentHashMap<Long, Color>()

    val liquid: Boolean get() = style == ThemeStyle.LIQUID
    val apple: Boolean get() = style == ThemeStyle.APPLE
    val porcelain: Boolean get() = style == ThemeStyle.PORCELAIN

    /** Surfaces are glass: always under Liquid, under Apple when its Liquid Glass is on. */
    val glass: Boolean get() = liquid || (apple && appleGlass)

    /** The Apple accent as a colour (light or dark variant). */
    val accentColor: Color get() = AppleColors.accent(accent, dark)

    val tokens: Tokens by lazy {
        when (style) {
            ThemeStyle.PORCELAIN -> Tokens.Porcelain
            ThemeStyle.LIQUID -> Tokens.liquid(hue, dark)
            ThemeStyle.APPLE -> Tokens.apple(accentColor, dark)
        }
    }

    /** A colour of the design, in this theme. */
    fun adapt(argb: Long): Color = when (style) {
        ThemeStyle.PORCELAIN -> Color(argb)
        ThemeStyle.LIQUID -> cache.getOrPut(argb) { Retint.adapt(argb, hue, dark) }
        ThemeStyle.APPLE -> cache.getOrPut(argb) { Retint.apple(argb, accentColor, dark) }
    }

    /** A colour that is dark by nature (consoles, the capsule's glaze, the orb): it takes the hue but keeps its lightness. */
    fun hueOnly(argb: Long): Color = when (style) {
        ThemeStyle.PORCELAIN -> Color(argb)
        ThemeStyle.LIQUID -> fixedCache.getOrPut(argb) { Retint.hueOnly(argb, hue) }
        ThemeStyle.APPLE -> fixedCache.getOrPut(argb) { Retint.appleFixed(argb) }
    }

    companion object {
        val Porcelain = ThemeSpec(style = ThemeStyle.PORCELAIN, hue = 0f, dark = false, motion = true)

        fun of(prefs: ThemePrefs, systemDark: Boolean): ThemeSpec {
            if (prefs.style == ThemeStyle.PORCELAIN) return Porcelain
            val dark = when (prefs.appearance) {
                ThemeAppearance.LIGHT -> false
                ThemeAppearance.DARK -> true
                ThemeAppearance.SYSTEM -> systemDark
            }
            val hue = if (prefs.style == ThemeStyle.APPLE) Retint.toHsl(AppleColors.accent(prefs.appleAccent, dark))[0] else ((prefs.hue % 360f) + 360f) % 360f
            return ThemeSpec(prefs.style, hue, dark, prefs.motion, prefs.appleAccent, prefs.appleGlass)
        }
    }
}

/** Where the current theme lives. Reading [current] in a composable recomposes it when the theme changes. */
object Theme {
    var current: ThemeSpec by mutableStateOf(ThemeSpec.Porcelain)

    val liquid: Boolean get() = current.liquid
    val apple: Boolean get() = current.apple
    val glass: Boolean get() = current.glass
    val dark: Boolean get() = current.dark
}

/** iOS's system colours (light, dark), as UIKit defines them. */
object AppleColors {
    fun accent(a: AppleAccent, dark: Boolean): Color = Color(
        when (a) {
            AppleAccent.BLUE -> if (dark) 0xFF0A84FF else 0xFF007AFF
            AppleAccent.INDIGO -> if (dark) 0xFF5E5CE6 else 0xFF5856D6
            AppleAccent.PURPLE -> if (dark) 0xFFBF5AF2 else 0xFFAF52DE
            AppleAccent.PINK -> if (dark) 0xFFFF375F else 0xFFFF2D55
            AppleAccent.RED -> if (dark) 0xFFFF453A else 0xFFFF3B30
            AppleAccent.ORANGE -> if (dark) 0xFFFF9F0A else 0xFFFF9500
            AppleAccent.GREEN -> if (dark) 0xFF30D158 else 0xFF34C759
            AppleAccent.TEAL -> if (dark) 0xFF40C8E0 else 0xFF30B0C7
            AppleAccent.GRAPHITE -> if (dark) 0xFF98989D else 0xFF8E8E93
        },
    )

    fun red(dark: Boolean) = Color(if (dark) 0xFFFF453A else 0xFFFF3B30)
    fun orange(dark: Boolean) = Color(if (dark) 0xFFFF9F0A else 0xFFFF9500)
    fun green(dark: Boolean) = Color(if (dark) 0xFF30D158 else 0xFF34C759)

    /** systemGroupedBackground and secondarySystemGroupedBackground: the page and the cards on it. */
    fun groupedBackground(dark: Boolean) = Color(if (dark) 0xFF000000 else 0xFFF2F2F7)
    fun card(dark: Boolean) = Color(if (dark) 0xFF1C1C1E else 0xFFFFFFFF)
    fun elevated(dark: Boolean) = Color(if (dark) 0xFF2C2C2E else 0xFFFFFFFF)

    /** tertiarySystemFill: the grey of a segmented track, a search field, a plain button. */
    fun fill(dark: Boolean) = if (dark) Color(0x3D767680) else Color(0x1F767680)
    fun separator(dark: Boolean) = if (dark) Color(0x99545458) else Color(0x4A3C3C43)
    fun label(dark: Boolean) = if (dark) Color.White else Color.Black
    fun secondaryLabel(dark: Boolean) = if (dark) Color(0x99EBEBF5) else Color(0x993C3C43)
}

/** A colour of the design ("Color(0xFF...)" as the reference writes it), in the current theme. */
fun pc(argb: Long): Color = Theme.current.adapt(argb)

/** A colour that stays dark in every theme (consoles, the capsule, the orb): only its hue follows the theme. */
fun pcHue(argb: Long): Color = Theme.current.hueOnly(argb)

/** The named colours and glazes of a theme. [Porcelain] holds the design reference's values one to one. */
@Immutable
class Tokens(
    val base: Color, val base2: Color, val ink: Color, val muted: Color, val sub: Color,
    val accent: Color, val accent2: Color, val accent3: Color, val line: Color, val line2: Color,
    val danger: Color, val amber: Color, val good: Color, val night: Color, val eyebrow: Color,
    val highlight: Color, val fold: Color, val code: Color, val codeText: Color,
    val ceramic: Brush, val ceramic2: Brush, val night2: Brush, val primaryButton: Brush, val page: Brush,
) {
    companion object {
        val Porcelain = Tokens(
            base = Color(0xFFE9EDE5), base2 = Color(0xFFDDE5D9), ink = Color(0xFF32443A), muted = Color(0xFF7B8C7C), sub = Color(0xFF627866),
            accent = Color(0xFF66866D), accent2 = Color(0xFF476D54), accent3 = Color(0xFFC5D7C2), line = Color(0x5295AA94), line2 = Color(0x2BA2B6A2),
            danger = Color(0xFFAD685E), amber = Color(0xFFA17A45), good = Color(0xFF52805D), night = Color(0xFF26372F), eyebrow = Color(0xFF82957D),
            highlight = Color(0xFFFBFDF7), fold = Color(0xFFDCE5D7), code = Color(0xFF28392F), codeText = Color(0xFFD4E8D3),
            ceramic = Brush.linearGradient(0f to Color(0xFFFBFDF7), 0.4f to Color(0xFFEFF3E9), 1f to Color(0xFFDCE5D7), start = Offset.Zero, end = Offset(900f, 700f)),
            ceramic2 = Brush.linearGradient(0f to Color(0xFFF4F8EF), 0.7f to Color(0xFFE5ECE0), 1f to Color(0xFFDCE6D7), start = Offset.Zero, end = Offset(800f, 600f)),
            night2 = Brush.linearGradient(listOf(Color(0xFF273C32), Color(0xFF344C3B)), start = Offset.Zero, end = Offset(900f, 700f)),
            primaryButton = Brush.linearGradient(listOf(Color(0xFF758F78), Color(0xFF4E6D55)), start = Offset.Zero, end = Offset(300f, 200f)),
            page = Brush.linearGradient(listOf(Color(0xFFE2E9DD), Color(0xFFD8E1D4)), start = Offset.Zero, end = Offset(1200f, 2400f)),
        )

        /** iOS: grouped greys, white (or #1C1C1E) cards, labels at UIKit's opacities, the system accent and status colours. */
        fun apple(accent: Color, dark: Boolean): Tokens {
            val solid = { c: Color -> Brush.linearGradient(listOf(c, c)) }
            val card = AppleColors.card(dark)
            val accentText = Retint.fit(accent, card, 3.0)
            return Tokens(
                base = AppleColors.groupedBackground(dark),
                base2 = Color(if (dark) 0xFF1C1C1E else 0xFFE5E5EA),
                ink = AppleColors.label(dark),
                muted = Color(if (dark) 0xFF8E8E93 else 0xFF8A8A8E),
                sub = Color(if (dark) 0xFFAEAEB2 else 0xFF6C6C70),
                accent = accent,
                accent2 = accentText,
                accent3 = accent.copy(alpha = if (dark) 0.28f else 0.16f).compositeOverCompat(card),
                line = AppleColors.separator(dark),
                line2 = if (dark) Color(0x4D545458) else Color(0x263C3C43),
                danger = AppleColors.red(dark),
                amber = AppleColors.orange(dark),
                good = AppleColors.green(dark),
                night = Color(0xFF1C1C1E),
                eyebrow = Color(if (dark) 0xFF8E8E93 else 0xFF6D6D72),
                highlight = card,
                fold = Color(if (dark) 0xFF2C2C2E else 0xFFE5E5EA),
                code = Color(if (dark) 0xFF1C1C1E else 0xFF1C1C1E),
                codeText = Color(0xFFEBEBF5),
                ceramic = solid(card),
                ceramic2 = solid(AppleColors.fill(dark)),
                night2 = Brush.linearGradient(listOf(Color(0xFF2C2C2E), Color(0xFF1C1C1E)), start = Offset.Zero, end = Offset(900f, 700f)),
                primaryButton = solid(accent),
                page = solid(AppleColors.groupedBackground(dark)),
            )
        }

        fun liquid(h: Float, dark: Boolean): Tokens {
            val bg = if (dark) hsl(h, 0.45f, 0.08f) else hsl(h, 0.40f, 0.95f)
            val ink = if (dark) hsl(h, 0.25f, 0.95f) else hsl(h, 0.32f, 0.15f)
            val accent = Retint.fit(if (dark) hsl(h, 0.75f, 0.66f) else hsl(h, 0.62f, 0.47f), bg, 3.2)
            val accent2 = Retint.fit(if (dark) hsl(h, 0.85f, 0.75f) else hsl(h, 0.70f, 0.37f), bg, 4.6)
            val button = Retint.fit(hsl(h, 0.78f, if (dark) 0.52f else 0.50f), Color.White, 3.4)
            val buttonHsl = Retint.toHsl(button)
            return Tokens(
                base = bg,
                base2 = if (dark) hsl(h, 0.38f, 0.12f) else hsl(h, 0.36f, 0.90f),
                ink = ink,
                muted = if (dark) hsl(h, 0.13f, 0.63f) else hsl(h, 0.12f, 0.47f),
                sub = if (dark) hsl(h, 0.16f, 0.77f) else hsl(h, 0.18f, 0.35f),
                accent = accent,
                accent2 = accent2,
                accent3 = if (dark) hsl(h, 0.42f, 0.27f) else hsl(h, 0.58f, 0.86f),
                line = if (dark) hsl(h, 0.35f, 0.75f, 0.22f) else hsl(h, 0.30f, 0.55f, 0.30f),
                line2 = if (dark) hsl(h, 0.30f, 0.75f, 0.12f) else hsl(h, 0.30f, 0.55f, 0.16f),
                danger = if (dark) hsl(6f, 0.72f, 0.68f) else hsl(6f, 0.55f, 0.50f),
                amber = if (dark) hsl(36f, 0.78f, 0.64f) else hsl(34f, 0.62f, 0.44f),
                good = if (dark) hsl(142f, 0.45f, 0.62f) else hsl(140f, 0.40f, 0.40f),
                night = hsl(h, 0.40f, if (dark) 0.16f else 0.14f),
                eyebrow = if (dark) hsl(h, 0.32f, 0.68f) else hsl(h, 0.30f, 0.44f),
                highlight = if (dark) hsl(h, 0.30f, 0.18f) else hsl(h, 0.50f, 0.99f),
                fold = if (dark) hsl(h, 0.35f, 0.20f) else hsl(h, 0.42f, 0.88f),
                code = hsl(h, 0.35f, if (dark) 0.07f else 0.12f),
                codeText = hsl(h, 0.40f, 0.88f),
                // Glass: a pale sheet that lets the backdrop through, brighter at the top where the light falls.
                ceramic = if (dark) {
                    Brush.verticalGradient(listOf(hsl(h, 0.45f, 0.78f, 0.17f), hsl(h, 0.40f, 0.62f, 0.08f)))
                } else {
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.74f), hsl(h, 0.60f, 0.97f, 0.48f)))
                },
                ceramic2 = if (dark) {
                    Brush.verticalGradient(listOf(hsl(h, 0.45f, 0.78f, 0.13f), hsl(h, 0.40f, 0.62f, 0.06f)))
                } else {
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.60f), hsl(h, 0.60f, 0.96f, 0.38f)))
                },
                night2 = Brush.linearGradient(
                    listOf(hsl(h - 10f, 0.55f, if (dark) 0.24f else 0.22f), hsl(h + 24f, 0.62f, if (dark) 0.14f else 0.13f)),
                    start = Offset.Zero, end = Offset(900f, 700f),
                ),
                primaryButton = Brush.linearGradient(
                    listOf(hsl(buttonHsl[0] - 14f, min(1f, buttonHsl[1] + 0.05f), min(0.9f, buttonHsl[2] + 0.08f)), hsl(buttonHsl[0] + 16f, buttonHsl[1], max(0.1f, buttonHsl[2] - 0.07f))),
                    start = Offset.Zero, end = Offset(300f, 200f),
                ),
                // The backdrop draws the page; pages over it stay clear.
                page = Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
            )
        }
    }
}

/** HSL in, colour out; the hue wraps. */
fun hsl(h: Float, s: Float, l: Float, a: Float = 1f): Color =
    Color.hsl(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f), a.coerceIn(0f, 1f))

/** How Liquid re-tints a colour of the Porcelain design. */
internal object Retint {
    /** Porcelain's neutrals sit around this hue (grey-green); their spread around it is kept, at a fraction. */
    private const val PORCELAIN_HUE = 115f

    fun adapt(argb: Long, hue: Float, dark: Boolean): Color {
        val c = Color(argb)
        val (h, s, l) = toHsl(c)
        val a = c.alpha
        // White highlights and rims: in the dark they fade to a faint sheen instead of glowing.
        if (s < 0.05f && l > 0.97f) return if (dark) c.copy(alpha = a * 0.30f) else c
        val warm = h < 60f && s > 0.1f // clay red and amber keep their hue: they carry meaning
        val nh = if (warm) h else hue + (h - PORCELAIN_HUE) * 0.6f
        var ns = if (warm) s else when {
            l > 0.80f -> s * 1.45f + 0.04f
            l > 0.30f -> s * 2.40f + 0.06f
            else -> s * 1.60f + 0.04f
        }
        ns = min(ns, if (warm) 0.9f else 0.80f)
        if (dark) ns *= if (warm) 0.85f else 0.9f
        val nl = if (dark) darkLightness(l) else l
        // Translucent pale surfaces let a little more of the backdrop through.
        val na = if (a < 0.999f && l > 0.80f) a * (if (dark) 0.75f else 0.80f) else a
        return hsl(nh, ns, nl, na)
    }

    fun hueOnly(argb: Long, hue: Float): Color {
        val c = Color(argb)
        val (h, s, l) = toHsl(c)
        if (s < 0.05f && l > 0.97f) return c
        if (h < 60f && s > 0.1f) return c
        return hsl(hue + (h - PORCELAIN_HUE) * 0.6f, min(0.75f, s * 1.6f + 0.04f), l, c.alpha)
    }

    /** Apple: neutrals become iOS greys, the glaze's greens the accent, clay and amber system red and orange. */
    fun apple(argb: Long, accent: Color, dark: Boolean): Color {
        val c = Color(argb)
        val (h, s, l) = toHsl(c)
        val a = c.alpha
        if (s < 0.05f && l > 0.97f) return if (dark) c.copy(alpha = a * 0.22f) else c
        if (h < 60f && s > 0.1f) {
            val base = if (h < 22f) AppleColors.red(dark) else AppleColors.orange(dark)
            val (bh, bs, bl) = toHsl(base)
            return if (l < 0.62f) hsl(bh, bs * 0.95f, if (dark) max(appleDark(l), 0.62f) else min(l, bl), a)
            else hsl(bh, min(1f, s * 1.25f), if (dark) appleDark(l) else l, a)
        }
        if (s >= 0.13f && l in 0.28f..0.74f) {
            val (ah, aS, al) = toHsl(accent)
            return hsl(ah, aS * 0.92f, if (dark) max(al, 0.55f) else (l + al) / 2f, a)
        }
        val nl = if (dark) appleDark(l) else if (l > 0.85f) min(0.995f, 0.85f + (l - 0.85f) * 1.45f) else l * 0.82f
        return hsl(240f, min(0.06f, s * 0.25f), nl, a)
    }

    /** Apple, for things dark by nature: a neutral graphite of the same lightness. */
    fun appleFixed(argb: Long): Color {
        val c = Color(argb)
        val (h, s, l) = toHsl(c)
        if (s < 0.05f && l > 0.97f) return c
        if (h < 60f && s > 0.1f) return c
        return hsl(240f, min(0.05f, s * 0.3f), l, c.alpha)
    }

    /** iOS dark: black pages, #1C1C1E cards, bright labels. */
    private val appleCurve = floatArrayOf(0f, 1f, 0.25f, 0.92f, 0.45f, 0.74f, 0.60f, 0.58f, 0.80f, 0.26f, 0.90f, 0.15f, 1f, 0.07f)

    private fun appleDark(l: Float) = curve(appleCurve, l)

    /** Light becomes dark and dark becomes light, with text kept bright and surfaces kept deep. */
    private val darkCurve = floatArrayOf(0f, 0.97f, 0.25f, 0.90f, 0.45f, 0.73f, 0.60f, 0.56f, 0.80f, 0.29f, 0.90f, 0.17f, 1f, 0.09f)

    private fun darkLightness(l: Float): Float = curve(darkCurve, l)

    private fun curve(points: FloatArray, l: Float): Float {
        var i = 0
        while (i < points.size - 2 && l > points[i + 2]) i += 2
        val (x0, y0, x1, y1) = listOf(points[i], points[i + 1], points[i + 2], points[i + 3])
        val t = if (x1 == x0) 0f else ((l - x0) / (x1 - x0)).coerceIn(0f, 1f)
        return y0 + (y1 - y0) * t
    }

    fun toHsl(c: Color): FloatArray {
        val r = c.red; val g = c.green; val b = c.blue
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d < 1e-6f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05; val lb = b.luminance() + 0.05
        return max(la, lb) / min(la, lb).toDouble()
    }

    /** Moves [c]'s lightness away from [against] until the two reach [ratio] (yellows need it most). */
    fun fit(c: Color, against: Color, ratio: Double): Color {
        val (h, s, l0) = toHsl(c)
        val down = against.luminance() > 0.4f
        var l = l0
        var out = c
        var steps = 0
        while (contrast(out, against) < ratio && steps < 40) {
            l = if (down) l - 0.015f else l + 0.015f
            if (l !in 0f..1f) break
            out = hsl(h, s, l, c.alpha)
            steps++
        }
        return out
    }
}

/** [this] laid over an opaque [under]: the opaque colour a translucent tint shows as. */
internal fun Color.compositeOverCompat(under: Color): Color {
    val a = alpha
    return Color(red * a + under.red * (1 - a), green * a + under.green * (1 - a), blue * a + under.blue * (1 - a), 1f)
}
