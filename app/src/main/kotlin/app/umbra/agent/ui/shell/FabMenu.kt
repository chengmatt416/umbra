package app.umbra.agent.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.ui.porcelain.IconSpec
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.pcHue
import kotlinx.coroutines.delay

/** One choice of the menu. */
class MenuEntry(val icon: IconSpec, val label: String, val onClick: () -> Unit)

/**
 * The menu button (Material 3 Expressive FAB menu): bottom right by default, in the thumb's easy reach. Open, it turns
 * into a round close button and 2–6 choices grow above it, anchored where it is.
 */
@Composable
fun MenuButton(open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val corner by animateDpAsState(if (open) 28.dp else 18.dp, spring(dampingRatio = 0.7f, stiffness = 500f), label = "fab-corner")
    val turn by animateFloatAsState(if (open) 90f else 0f, spring(dampingRatio = 0.6f, stiffness = 420f), label = "fab-turn")
    val shape = RoundedCornerShape(corner)
    val label = stringResource(if (open) R.string.r_menu_close else R.string.r_menu_open)
    Box(
        modifier
            .size(56.dp)
            .shadow(10.dp, shape, ambientColor = pcHue(0x3322352D), spotColor = pcHue(0x5522352D))
            .clip(shape)
            .background(if (open) P.ink else P.accent2)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onToggle)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        PIcon(if (open) PIcons.close else PIcons.plus, Modifier.graphicsLayer { rotationZ = turn }, size = 24.dp, tint = P.base)
    }
}

/** The choices, stacked above the button, right-aligned (left-aligned for the left hand), the nearest one first. */
@Composable
fun MenuEntries(open: Boolean, entries: List<MenuEntry>, leftHanded: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = if (leftHanded) Alignment.Start else Alignment.End) {
        entries.forEachIndexed { i, entry ->
            // The nearest choice (the last) appears first; each one a beat later than the one below it.
            val order = entries.size - 1 - i
            val state = remember { MutableTransitionState(false) }
            LaunchedEffect(open) {
                if (open) delay(order * 28L)
                state.targetState = open
            }
            AnimatedVisibility(
                state,
                enter = fadeIn(tween(140)) + slideInVertically(spring(dampingRatio = 0.72f, stiffness = 520f)) { it / 2 } + scaleIn(spring(dampingRatio = 0.7f, stiffness = 520f), initialScale = 0.86f),
                exit = fadeOut(tween(90)) + slideOutVertically(tween(120)) { it / 3 } + scaleOut(tween(120), targetScale = 0.9f),
            ) {
                val shape = RoundedCornerShape(28.dp)
                Row(
                    Modifier
                        .height(56.dp)
                        .shadow(6.dp, shape, ambientColor = pcHue(0x2222352D), spotColor = pcHue(0x3322352D))
                        .clip(shape)
                        .background(P.accent3)
                        .clickable(role = Role.Button, onClick = entry.onClick)
                        .padding(start = 18.dp, end = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PIcon(entry.icon, size = 22.dp, tint = P.ink)
                    Text(entry.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.ink)
                }
            }
        }
    }
}

/**
 * The menu's last row (Figma 10.3): two equal pills side by side, a new coding session and a new agent, nearest the
 * thumb; swapped for the left hand. They grow in with the entries above them.
 */
@Composable
fun MenuPills(open: Boolean, coding: MenuEntry, agent: MenuEntry, leftHanded: Boolean, modifier: Modifier = Modifier) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(open) { state.targetState = open }
    AnimatedVisibility(
        state, modifier,
        enter = fadeIn(tween(140)) + slideInVertically(spring(dampingRatio = 0.72f, stiffness = 520f)) { it / 2 } + scaleIn(spring(dampingRatio = 0.7f, stiffness = 520f), initialScale = 0.9f),
        exit = fadeOut(tween(90)) + slideOutVertically(tween(120)) { it / 3 } + scaleOut(tween(120), targetScale = 0.92f),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val pills = if (leftHanded) listOf(agent, coding) else listOf(coding, agent)
            pills.forEach { entry ->
                val shape = RoundedCornerShape(28.dp)
                Row(
                    Modifier
                        .height(56.dp)
                        .shadow(6.dp, shape, ambientColor = pcHue(0x2222352D), spotColor = pcHue(0x3322352D))
                        .clip(shape)
                        .background(P.accent3)
                        .clickable(role = Role.Button, onClick = entry.onClick)
                        .padding(start = 16.dp, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PIcon(PIcons.plus, size = 18.dp, tint = P.ink)
                    PIcon(entry.icon, size = 20.dp, tint = P.ink)
                    Text(entry.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.ink, maxLines = 1)
                }
            }
        }
    }
}

/** What covers the page while the menu is open: the page stays faintly visible, a tap anywhere closes the menu. */
@Composable
fun MenuScrim(open: Boolean, onClose: () -> Unit) {
    val alpha by animateFloatAsState(if (open) 0.8f else 0f, tween(180), label = "scrim")
    if (alpha <= 0.01f) return
    Box(
        Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(P.base)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
    )
}
