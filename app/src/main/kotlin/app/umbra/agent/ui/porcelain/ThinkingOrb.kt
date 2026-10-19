package app.umbra.agent.ui.porcelain

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive

/** The upstream 20/64 presets, drawn without a WebView. Background and reduced-motion states stop the clock. */
@Composable
fun ThinkingOrb(label: String, modifier: Modifier = Modifier, size: Dp = 20.dp, paused: Boolean = false) {
    val owner = LocalLifecycleOwner.current
    var seconds by remember { mutableDoubleStateOf(1.0) }
    LaunchedEffect(owner, paused) {
        if (paused) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!ValueAnimator.areAnimatorsEnabled()) return@repeatOnLifecycle
            var previous = 0L
            while (isActive) withFrameNanos { frame ->
                if (frame - previous >= 33_000_000L) { seconds = frame / 1_000_000_000.0; previous = frame }
            }
        }
    }
    val preset = if (size >= 40.dp) 64 else 20
    val dark = P.base.luminance() < 0.5f
    Canvas(modifier.size(size).semantics { contentDescription = label }) {
        val scale = this.size.minDimension / preset
        ThinkingOrbGeometry.frame(preset, seconds).forEach { dot ->
            val ink = (if (dark) 1 - dot.white else dot.white).coerceIn(0.0, 1.0).toFloat()
            drawCircle(Color(ink, ink, ink, dot.alpha.toFloat()), (dot.radius * scale).toFloat(), Offset((dot.x * scale).toFloat(), (dot.y * scale).toFloat()))
        }
    }
}
