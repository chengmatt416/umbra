package app.umbra.agent.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.pc
import kotlinx.coroutines.launch

/**
 * The Coding tab before the first task: the same centred shape as the Agents tab's empty state, so the two tabs read
 * as one place. Starters only fill the composer; nothing runs until the user sends.
 */
@Composable
internal fun NoTasks(onStarter: (String) -> Unit) {
    val starters = listOf(R.string.r_home_starter_1, R.string.r_home_starter_2, R.string.r_home_starter_3).map { stringResource(it) }
    Column(
        Modifier.fillMaxWidth().padding(top = 32.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
            listOf(PIcons.terminal to 0xFFC5D6C2, PIcons.code to 0xFFB9CDB5, PIcons.phone to 0xFFC9D9C6).forEachIndexed { i, (icon, tone) ->
                // The three coins settle in one after another: a small arrival, not a show.
                val rise = remember { Animatable(14f) }
                val fade = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(60L * i)
                    launch { rise.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 380f)) }
                    fade.animateTo(1f, spring(stiffness = 500f))
                }
                Box(
                    Modifier.size(52.dp).graphicsLayer { translationY = rise.value * density; alpha = fade.value }
                        .clip(CircleShape).background(pc(tone)),
                    contentAlignment = Alignment.Center,
                ) { PIcon(icon, size = 24.dp, tint = P.accent) }
            }
        }
        Text(stringResource(R.string.r_home_empty), fontFamily = P.serif, fontSize = 24.sp, lineHeight = 30.sp, color = P.ink, textAlign = TextAlign.Center, style = androidx.compose.ui.text.TextStyle(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading))
        Text(stringResource(R.string.r_home_empty_body), fontSize = 14.sp, lineHeight = 21.sp, color = P.sub, textAlign = TextAlign.Center)
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            starters.forEach { text ->
                Text(
                    text,
                    Modifier.clip(RoundedCornerShape(16.dp)).background(pc(0xFFF6F8F2)).clickable(role = Role.Button) { onStarter(text) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 13.5.sp, color = P.ink,
                )
            }
        }
    }
}
