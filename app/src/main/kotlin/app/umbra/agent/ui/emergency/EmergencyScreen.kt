package app.umbra.agent.ui.emergency

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.Fact
import app.umbra.agent.ui.porcelain.PConfirm
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.Headline
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PSetting
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.control.EmergencyLevel
import app.umbra.core.control.LeaseState
import app.umbra.core.control.Principal
import app.umbra.core.session.SessionState
import kotlinx.coroutines.launch

/**
 * Emergency Control (architecture spec §8.2): Input Lockdown, persisted until the user lifts it, and the user taking
 * the phone screen back. Each fence is listed where it is actually enforced; what each control guarantees, and what
 * it does not, is written down next to it. Nothing claims that commands already sent were undone or that running
 * programs stopped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmergencyScreen() {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val state by container.emergency.state.collectAsStateWithLifecycle()
    val leases by container.inputLeases.all.collectAsStateWithLifecycle()
    val timeline by container.inputLeases.recent.collectAsStateWithLifecycle()
    val emergencyLog by container.emergency.recent.collectAsStateWithLifecycle()
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    var confirm by remember { mutableStateOf<String?>(null) }
    val locked = state.level == EmergencyLevel.LOCKDOWN
    val screenLease = leases.values.firstOrNull { it.resourceId == "display:0" }
    val userHoldsScreen = screenLease?.owner == Principal.User && screenLease.state == LeaseState.ACTIVE
    val working = sessions.filter { it.state == SessionState.RUNNING || it.state == SessionState.WAITING_APPROVAL }
    val paused = sessions.filter { it.state == SessionState.PAUSED }
    val inBackground = working.filter { s -> s.displays.any { it != 0 } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow(stringResource(R.string.em_eyebrow))
        Headline(stringResource(R.string.em_headline), stringResource(R.string.em_headline_tail), Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)

        PCard(style = if (locked) CardStyle.Soft else CardStyle.Ceramic) {
            Kicker(stringResource(R.string.em_global))
            Text(stringResource(if (locked) R.string.em_lockdown_active else R.string.em_governed), Modifier.padding(top = 6.dp), fontFamily = P.serif, fontSize = 22.sp, color = P.ink)
            Gap(10.dp)
            Callout(stringResource(if (locked) R.string.em_locked_body else R.string.em_free_body), if (locked) Tone.Warn else Tone.Good)
            Gap(10.dp)
            if (locked) {
                Subtle(stringResource(R.string.em_since, DateUtils.getRelativeTimeSpanString(state.sinceMs).toString(), state.by.toString(), state.generation, state.reason.orEmpty()))
                Gap(8.dp)
                PButton(stringResource(R.string.em_lift), { confirm = "lift" }, style = ButtonStyle.Alt, icon = PIcons.unlock)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton(stringResource(R.string.em_lockdown), { confirm = "lockdown" }, style = ButtonStyle.Danger, icon = PIcons.lock)
                    PButton(stringResource(R.string.stop_all), { confirm = "stop" }, style = ButtonStyle.Alt, icon = PIcons.stop)
                }
            }
        }
        Gap(12.dp)

        PCard {
            Kicker(stringResource(R.string.em_screen_kicker))
            Text(stringResource(R.string.em_screen_title), Modifier.padding(top = 6.dp), fontFamily = P.serif, fontSize = 20.sp, color = P.ink)
            PSetting(stringResource(R.string.em_screen_owner), stringResource(if (userHoldsScreen) R.string.em_screen_you else R.string.em_screen_agents), divider = false) {
                Tag(stringResource(if (userHoldsScreen) R.string.em_owner_you else R.string.em_owner_agents), if (userHoldsScreen) Tone.Neutral else Tone.Good)
            }
            if (!userHoldsScreen) PButton(stringResource(R.string.em_take_back), { container.inputLeases.takeBack("display:0") }, icon = PIcons.hand)
            else PButton(stringResource(R.string.em_return), { container.inputLeases.release("display:0", Principal.User, "returned by the user") }, style = ButtonStyle.Alt)
            Subtle(stringResource(R.string.em_take_back_note), Modifier.padding(top = 8.dp))
            // Taking back display 0 leaves the background displays to their agents: say so, and offer to pause them.
            if (userHoldsScreen && inBackground.isNotEmpty()) {
                Gap(10.dp)
                Callout(pluralStringResource(R.plurals.em_background_still, inBackground.size, inBackground.size), Tone.Warn)
                Gap(8.dp)
                PButton(stringResource(R.string.em_pause_background), { scope.launch { inBackground.forEach { runCatching { container.supervisor.pause(it.id) } } } }, style = ButtonStyle.Warn, tiny = true, icon = PIcons.pause)
            }
        }
        Gap(12.dp)

        if (working.isNotEmpty()) {
            PCard(style = CardStyle.Soft) {
                Kicker(stringResource(R.string.em_working_now))
                working.forEach { s -> PSetting(s.title, stringResource(R.string.em_g_pause_does)) { PButton(stringResource(R.string.em_pause), { scope.launch { container.supervisor.pause(s.id) } }, style = ButtonStyle.Alt, tiny = true) } }
                Gap(8.dp)
                PButton(stringResource(R.string.em_pause_all), { scope.launch { working.forEach { runCatching { container.supervisor.pause(it.id) } } } }, style = ButtonStyle.Warn, tiny = true)
            }
            Gap(12.dp)
        }
        if (!locked && paused.isNotEmpty()) {
            PCard(style = CardStyle.Soft) {
                Kicker(stringResource(R.string.em_paused_kicker))
                Subtle(pluralStringResource(R.plurals.em_paused_body, paused.size, paused.size), Modifier.padding(top = 6.dp))
                Gap(8.dp)
                PButton(stringResource(R.string.em_resume_all), { scope.launch { paused.forEach { runCatching { container.supervisor.resume(it.id) } } } }, style = ButtonStyle.Alt, tiny = true, icon = PIcons.play)
            }
            Gap(12.dp)
        }

        PCard(style = CardStyle.Soft) {
            Kicker(stringResource(R.string.em_guarantees))
            listOf(
                Triple(R.string.em_g_pause, R.string.em_g_pause_does, R.string.em_g_pause_not),
                Triple(R.string.em_g_stop, R.string.em_g_stop_does, R.string.em_g_stop_not),
                Triple(R.string.em_g_lock, R.string.em_g_lock_does, R.string.em_g_lock_not),
            ).forEach { (verb, does, not) -> Guarantee(stringResource(verb), stringResource(does), stringResource(not)) }
            Subtle(stringResource(R.string.em_halt_note), Modifier.padding(top = 10.dp))
        }
        Gap(12.dp)

        PCard {
            Kicker(stringResource(R.string.em_enforced_where))
            listOf(
                Triple(R.string.em_fence_android, R.string.em_fence_android_how, true),
                Triple(R.string.em_fence_tmux, R.string.em_fence_tmux_how, true),
                Triple(R.string.em_fence_desktop, R.string.em_fence_desktop_how, true),
                Triple(R.string.em_fence_omp, R.string.em_fence_omp_how, true),
                Triple(R.string.em_fence_messages, R.string.em_fence_messages_how, true),
                Triple(R.string.em_fence_nodes, R.string.em_fence_nodes_how, false),
            ).forEachIndexed { i, (name, how, applies) ->
                PSetting(stringResource(name), stringResource(how), divider = i < 5) {
                    Tag(stringResource(if (!applies) R.string.em_na else if (locked) R.string.em_enforced else R.string.em_operational), if (!applies) Tone.Neutral else if (locked) Tone.Warn else Tone.Good)
                }
            }
        }
        Gap(12.dp)

        PCard {
            Kicker(stringResource(R.string.em_timeline))
            (emergencyLog.takeLast(5).map { it.timeMs to stringResource(R.string.em_log_emergency, it.kind) } + timeline.takeLast(12).map { e ->
                e.timeMs to "${e.kind} · " + (e.data?.toString()?.take(120) ?: "")
            }).sortedByDescending { it.first }.take(15).forEach { (t, line) ->
                Text("${DateUtils.formatDateTime(container.context, t, DateUtils.FORMAT_SHOW_TIME)}  $line", Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
            }
            if (emergencyLog.isEmpty() && timeline.isEmpty()) Subtle(stringResource(R.string.em_nothing_recorded))
        }
        Gap(28.dp)
    }

    when (confirm) {
        "lockdown" -> PConfirm(
            stringResource(R.string.em_lockdown_title), stringResource(R.string.em_lockdown_body), stringResource(R.string.em_lockdown_confirm),
            onDismiss = { confirm = null }, onConfirm = { confirm = null; container.emergency.lockdown("locked down by the user") },
        )
        "lift" -> PConfirm(
            stringResource(R.string.em_lift_title), stringResource(R.string.em_lift_body), stringResource(R.string.em_lift_confirm), danger = false,
            onDismiss = { confirm = null }, onConfirm = { confirm = null; container.emergency.lift() },
            extra = if (paused.isEmpty()) null else {
                {
                    Text(pluralStringResource(R.plurals.em_lift_paused, paused.size, paused.size), fontSize = 13.sp, color = P.ink)
                    paused.take(6).forEach { Text("• ${it.title}", fontSize = 13.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            },
        )
        "stop" -> StopAllConfirm(onDismiss = { confirm = null })
    }
}

/** One control: what it does and what it does not, side by side in plain words. */
@Composable
private fun Guarantee(verb: String, does: String, not: String) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(verb, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = P.ink)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact(stringResource(R.string.em_does), does, Modifier.weight(1f))
            Fact(stringResource(R.string.em_does_not), not, Modifier.weight(1f))
        }
    }
}
