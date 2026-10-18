package app.umbra.agent.ui.hubs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.shell.Dest
import app.umbra.agent.ui.shell.NavRow
import app.umbra.agent.ui.shell.PageColumn
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.agent.ui.shell.RowGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Safety (Power): stopping everything first — held for a second so it is never pressed by accident — then the vault,
 * the approval history, the security events and the emergency control, each the page it was.
 */
@Composable
fun SafetyHub(onBack: () -> Unit, onGo: (Dest) -> Unit) {
    val container = LocalContext.current.container
    val approvals by container.approvals.pending.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    PorcelainPage(stringResource(R.string.r_safety_title), onBack) {
        PageColumn {
            HoldToStop { confirm = true }
            RowGroup {
                NavRow(PIcons.key, stringResource(R.string.r_safety_vault), stringResource(R.string.r_safety_vault_body)) { onGo(Dest.Vault) }
                NavRow(PIcons.check, stringResource(R.string.r_safety_history), if (approvals.isNotEmpty()) stringResource(R.string.r_home_needs, approvals.size) else null) { onGo(Dest.ApprovalHistory) }
                NavRow(PIcons.shield, stringResource(R.string.r_safety_events)) { onGo(Dest.Security) }
                NavRow(PIcons.alert, stringResource(R.string.r_safety_emergency)) { onGo(Dest.Emergency) }
            }
        }
    }
    if (confirm) app.umbra.agent.ui.emergency.StopAllConfirm(onDismiss = { confirm = false })
}

/** A danger button that acts only after being held for [holdMs]; the fill shows how long is left. */
@Composable
private fun HoldToStop(holdMs: Int = 1000, onHeld: () -> Unit) {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    var job by remember { mutableStateOf<Job?>(null) }
    val label = stringResource(R.string.r_safety_stop_all)
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier.fillMaxWidth().height(60.dp).clip(shape).background(P.danger)
            .semantics { role = Role.Button; onClick(label) { onHeld(); true } }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    job = scope.launch {
                        progress.snapTo(0f)
                        // The fill only shows it; the clock decides (with animations off the fill is instant).
                        launch { progress.animateTo(1f, tween(holdMs, easing = LinearEasing)) }
                        kotlinx.coroutines.delay(holdMs.toLong())
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onHeld()
                    }
                    waitForUpOrCancellation()
                    job?.cancel()
                    scope.launch { progress.animateTo(0f, tween(160)) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.value).background(Color.Black.copy(alpha = 0.18f)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PIcon(PIcons.alert, size = 22.dp, tint = Color.White)
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
    }
}

/** Developer (Dev mode): every page the app had, grouped as before, each opening exactly as it did. */
@Composable
fun DeveloperHub(onBack: () -> Unit, onGo: (Dest) -> Unit) {
    PorcelainPage(stringResource(R.string.r_dev_title), onBack) {
        PageColumn {
            Dest.entries.filter { it.group != null }.groupBy { it.group!! }.forEach { (group, dests) ->
                RowGroup(stringResource(group)) {
                    dests.forEach { d -> NavRow(d.icon, stringResource(d.title)) { onGo(d) } }
                }
            }
        }
    }
}
