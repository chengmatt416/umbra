package app.umbra.agent.ui.agents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.pc
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The avatars an agent can wear (decided 2026-10-18: a generated set the user picks from, a letter as the fallback).
 * Each is a motif drawn on a colour of the Porcelain palette, so every theme re-tints them like the rest of the app.
 */
object AgentAvatars {
    enum class Motif { ECLIPSE, LEAF, WAVE, SUN, SPARK, PEAKS, DROP, ORBIT }

    data class Spec(val id: String, val motif: Motif, val background: Long, val ink: Long)

    private val palettes = listOf(
        0xFFC5D7C2 to 0xFF476D54, // sage
        0xFFEBD3CB to 0xFFA8604F, // clay
        0xFFF1E4CF to 0xFFA17A45, // amber
        0xFF26372F to 0xFFC5D7C2, // night
        0xFFDDE5D9 to 0xFF62786A, // mist
        0xFFB9CBB0 to 0xFF3C5A45, // moss
        0xFFE3DCE6 to 0xFF6B5A78, // dusk
        0xFFD3E3E2 to 0xFF3F6E6B, // sea
    )

    /** a1…a16: each motif twice, on two different colours. */
    val all: List<Spec> = Motif.entries.flatMapIndexed { i, motif ->
        listOf(palettes[i % 8], palettes[(i + 3) % 8]).mapIndexed { j, (bg, ink) -> Spec("a${i * 2 + j + 1}", motif, bg, ink) }
    }

    fun spec(id: String): Spec? = all.firstOrNull { it.id == id }
}

/** An agent's face: its generated avatar, or its initial on a colour (avatar "letter", or one this version lacks). */
@Composable
fun AgentAvatar(avatar: String, name: String, size: Dp, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val spec = AgentAvatars.spec(avatar)
    val base = modifier.size(size).clip(CircleShape)
        .then(if (selected) Modifier.border(3.dp, P.accent2, CircleShape) else Modifier)
        .then(if (onClick != null) Modifier.clickable(role = Role.RadioButton, onClick = onClick) else Modifier)
    if (spec == null) {
        Box(base.background(P.accent3), contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase().ifEmpty { "U" }, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium, color = P.accent2)
        }
        return
    }
    val bg = app.umbra.agent.ui.porcelain.pcGrey(spec.background)
    val ink = app.umbra.agent.ui.porcelain.pcGrey(spec.ink)
    Canvas(base.background(bg)) { drawMotif(spec.motif, ink, bg) }
}

private fun DrawScope.drawMotif(motif: AgentAvatars.Motif, ink: Color, bg: Color) {
    val s = size.minDimension
    val c = Offset(size.width / 2, size.height / 2)
    val stroke = Stroke(width = s * 0.07f, cap = StrokeCap.Round)
    when (motif) {
        AgentAvatars.Motif.ECLIPSE -> {
            drawCircle(ink, s * 0.27f, c)
            drawCircle(bg, s * 0.24f, c + Offset(s * 0.11f, -s * 0.08f))
        }
        AgentAvatars.Motif.LEAF -> {
            val p = Path().apply {
                moveTo(c.x - s * 0.22f, c.y + s * 0.22f)
                cubicTo(c.x - s * 0.26f, c.y - s * 0.12f, c.x + s * 0.02f, c.y - s * 0.28f, c.x + s * 0.24f, c.y - s * 0.24f)
                cubicTo(c.x + s * 0.26f, c.y + s * 0.04f, c.x + s * 0.06f, c.y + s * 0.26f, c.x - s * 0.22f, c.y + s * 0.22f)
                close()
            }
            drawPath(p, ink)
            drawLine(bg, Offset(c.x - s * 0.16f, c.y + s * 0.16f), Offset(c.x + s * 0.16f, c.y - s * 0.16f), strokeWidth = s * 0.035f, cap = StrokeCap.Round)
        }
        AgentAvatars.Motif.WAVE -> {
            for (row in 0..1) {
                val y = c.y - s * 0.08f + row * s * 0.17f
                val p = Path().apply {
                    moveTo(c.x - s * 0.28f, y)
                    for (k in 0..3) {
                        val x0 = c.x - s * 0.28f + k * s * 0.14f
                        quadraticTo(x0 + s * 0.035f, y + (if (k % 2 == 0) -1 else 1) * s * 0.09f, x0 + s * 0.14f, y)
                    }
                }
                drawPath(p, ink, style = stroke)
            }
        }
        AgentAvatars.Motif.SUN -> {
            drawCircle(ink, s * 0.14f, c)
            for (k in 0 until 8) {
                val a = k * PI / 4
                val from = c + Offset((cos(a) * s * 0.22f).toFloat(), (sin(a) * s * 0.22f).toFloat())
                val to = c + Offset((cos(a) * s * 0.31f).toFloat(), (sin(a) * s * 0.31f).toFloat())
                drawLine(ink, from, to, strokeWidth = s * 0.055f, cap = StrokeCap.Round)
            }
        }
        AgentAvatars.Motif.SPARK -> {
            val r = s * 0.29f
            val w = s * 0.07f
            val p = Path().apply {
                moveTo(c.x, c.y - r)
                quadraticTo(c.x + w, c.y - w, c.x + r, c.y)
                quadraticTo(c.x + w, c.y + w, c.x, c.y + r)
                quadraticTo(c.x - w, c.y + w, c.x - r, c.y)
                quadraticTo(c.x - w, c.y - w, c.x, c.y - r)
                close()
            }
            drawPath(p, ink)
            drawCircle(ink, s * 0.045f, c + Offset(s * 0.22f, -s * 0.22f))
        }
        AgentAvatars.Motif.PEAKS -> {
            val base = c.y + s * 0.2f
            drawPath(Path().apply { moveTo(c.x - s * 0.3f, base); lineTo(c.x - s * 0.06f, c.y - s * 0.2f); lineTo(c.x + s * 0.14f, base); close() }, ink)
            drawPath(Path().apply { moveTo(c.x - s * 0.02f, base); lineTo(c.x + s * 0.16f, c.y - s * 0.06f); lineTo(c.x + s * 0.32f, base); close() }, ink.copy(alpha = 0.7f))
            drawCircle(ink, s * 0.05f, c + Offset(s * 0.16f, -s * 0.24f))
        }
        AgentAvatars.Motif.DROP -> {
            val p = Path().apply {
                moveTo(c.x, c.y - s * 0.3f)
                cubicTo(c.x + s * 0.06f, c.y - s * 0.16f, c.x + s * 0.24f, c.y - s * 0.02f, c.x + s * 0.2f, c.y + s * 0.12f)
                cubicTo(c.x + s * 0.16f, c.y + s * 0.3f, c.x - s * 0.16f, c.y + s * 0.3f, c.x - s * 0.2f, c.y + s * 0.12f)
                cubicTo(c.x - s * 0.24f, c.y - s * 0.02f, c.x - s * 0.06f, c.y - s * 0.16f, c.x, c.y - s * 0.3f)
                close()
            }
            drawPath(p, ink)
            drawCircle(bg.copy(alpha = 0.55f), s * 0.05f, c + Offset(-s * 0.07f, s * 0.08f))
        }
        AgentAvatars.Motif.ORBIT -> {
            drawCircle(ink, s * 0.12f, c)
            drawOval(ink, topLeft = Offset(c.x - s * 0.32f, c.y - s * 0.12f), size = Size(s * 0.64f, s * 0.24f), style = Stroke(width = s * 0.045f))
            drawCircle(ink, s * 0.05f, c + Offset(s * 0.3f, -s * 0.02f))
        }
    }
}
