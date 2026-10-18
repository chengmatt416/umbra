package app.umbra.agent.capsule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.PorcelainTheme
import app.umbra.agent.ui.voice.VoicePanel

/**
 * Speech for the Capsule's composers, which live in overlay windows: this see-through activity shows Umbra's
 * listening panel over whatever app is open (being on screen is also what lets Android give it the microphone),
 * puts the words into the shared draft and goes away. The composer that asked stays where it is.
 */
class VoiceInputActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PorcelainTheme {
                val rise = remember { Animatable(1f) }
                LaunchedEffect(Unit) { rise.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 380f)) }
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f * (1f - rise.value)))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
                ) {
                    VoicePanel(
                        onHeard = { heard ->
                            val ui = container.ui
                            ui.saveDraft(listOf(ui.draft.value, heard).filter { it.isNotBlank() }.joinToString(" "))
                        },
                        onClose = { finish() },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding()
                            .padding(horizontal = 12.dp, vertical = 16.dp)
                            .graphicsLayer { translationY = rise.value * 240.dp.toPx(); alpha = 1f - rise.value }
                            // taps on the panel stay on the panel
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    )
                }
            }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, android.R.anim.fade_out)
    }
}
