package app.umbra.agent.capsule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.umbra.agent.ui.porcelain.Theme
import app.umbra.agent.ui.porcelain.motionAllowed
import app.umbra.core.session.Session
import app.umbra.core.session.SessionState

/**
 * A task's companion outside the Capsule (the Home rows, the task page): the same eclipse and the same poses
 * (docs/design/capsule-companion.md). A finished task keeps the full disc, a failed one the clay crescent.
 */
@Composable
fun TaskCompanion(session: Session, needsYou: Boolean, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val mood = Crews.moodOf(session, null, needsYou, null, false, now) ?: when (session.state) {
        SessionState.COMPLETED -> Mood.DONE
        SessionState.FAILED -> Mood.ERROR
        else -> Mood.IDLE
    }
    val moving = remember(mood) { motionAllowed(context) } && Theme.current.motion && Crews.moves(mood) && mood != Mood.IDLE
    val clock = rememberCompanionClock(setOf(mood), moving)
    CompanionGlyph(mood, { clock.value }, moving, modifier, size)
}

/** The companion at rest, for places that stand for "Umbra" rather than one task. */
@Composable
fun RestingCompanion(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val clock = rememberCompanionClock(setOf(Mood.IDLE), false)
    CompanionGlyph(Mood.IDLE, { clock.value }, false, modifier, size)
}
