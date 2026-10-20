package app.umbra.agent.ui.porcelain

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlin.coroutines.coroutineContext

/** The upstream 20/64 presets, drawn without a WebView. Background and reduced-motion states stop the clock. */
@Composable
fun ThinkingOrb(label: String, modifier: Modifier = Modifier, size: Dp = 20.dp, paused: Boolean = false, state: ThinkingOrbState = ThinkingOrbState.WORKING) {
    val owner = LocalLifecycleOwner.current
    var seconds by remember { mutableDoubleStateOf(1.0) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(owner, paused, visible) {
        if (paused || !visible) return@LaunchedEffect
        val motion = coroutineContext[MotionDurationScale]
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { motion?.scaleFactor ?: if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f }.collectLatest { duration ->
                if (duration <= 0f) { seconds = 1.0; return@collectLatest }
                var previous = 0L
                while (isActive) withFrameNanos { frame ->
                    if (frame - previous >= 33_000_000L) { seconds = frame / 1_000_000_000.0 / duration; previous = frame }
                }
            }
        }
    }
    val preset = if (size >= 40.dp) 64 else 20
    val dark = P.base.luminance() < 0.5f
    Canvas(modifier.size(size).onGloballyPositioned { visible = !it.boundsInWindow().isEmpty }.semantics { contentDescription = label; role = Role.Image }) {
        val scale = this.size.minDimension / preset
        fun ink(white: Double, alpha: Double): Color {
            val value = (if (dark) 1 - white else white).coerceIn(0.0, 1.0).toFloat()
            return Color(value, value, value, alpha.coerceIn(0.0, 1.0).toFloat())
        }
        val frame = ThinkingOrbGeometry.frame(state, preset, seconds)
        frame.edges.forEach { edge ->
            drawLine(ink(edge.white, edge.alpha), Offset((edge.x1 * scale).toFloat(), (edge.y1 * scale).toFloat()), Offset((edge.x2 * scale).toFloat(), (edge.y2 * scale).toFloat()), (edge.width * scale).toFloat())
        }
        frame.dots.forEach { dot ->
            drawCircle(ink(dot.white, dot.alpha), (dot.radius * scale).toFloat(), Offset((dot.x * scale).toFloat(), (dot.y * scale).toFloat()))
        }
    }
}

@Composable
fun ThinkingOrbActivity(label: String, state: ThinkingOrbState, modifier: Modifier = Modifier, paused: Boolean = false) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = label; liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThinkingOrb(label, size = 64.dp, state = state, paused = paused)
        Text(label, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, color = P.sub)
    }
}
