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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.FactGrid
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
 * the screen back. Each fence is listed where it is actually enforced; nothing claims that commands already sent
 * were undone or that running programs stopped.
 */
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow("Execution safety controller")
        Headline("Take back,", "without pretending to stop time.", Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)

        PCard(style = if (locked) CardStyle.Soft else CardStyle.Ceramic) {
            Kicker("Global emergency control")
            Text(if (locked) "Input Lockdown active" else "All managed inputs governed", Modifier.padding(top = 6.dp), fontSize = 20.sp, color = P.ink)
            Gap(10.dp)
            Callout(
                if (locked) "New managed input and every side-effecting tool dispatch are refused. Commands already sent and programs already running may continue: a lockdown does not undo them."
                else "Every agent still needs valid input, permission and resource leases. A lockdown fences all of them at once and stays in force across restarts until you lift it.",
                if (locked) Tone.Warn else Tone.Good,
            )
            Gap(10.dp)
            if (locked) {
                Subtle("Since ${DateUtils.getRelativeTimeSpanString(state.sinceMs)} · by ${state.by} · generation ${state.generation} · ${state.reason.orEmpty()}")
                Gap(8.dp)
                PButton("Lift lockdown", { confirm = "lift" }, style = ButtonStyle.Alt, icon = PIcons.unlock)
            } else {
                PButton("Emergency Input Lockdown", { confirm = "lockdown" }, style = ButtonStyle.Danger, icon = PIcons.lock)
            }
        }
        Gap(12.dp)

        PCard {
            Kicker("Where the fence is enforced")
            listOf(
                Triple("Android actions", "Checked in the action pipeline right before each side-effecting action", true),
                Triple("Linux terminals (tmux)", "Checked by the terminal input dispatcher before any keystroke", true),
                Triple("Linux desktop", "Checked in the action pipeline for desktop input", true),
                Triple("omp's own tools", "The approval bridge denies every tool omp asks about", true),
                Triple("New messages to agents", "Sessions refuse new input, resume and commands", true),
                Triple("Remote nodes", "No remote node is paired with this phone", false),
            ).forEachIndexed { i, (name, how, applies) ->
                PSetting(name, how, divider = i < 5) {
                    Tag(if (!applies) "Not applicable" else if (locked) "Fence enforced" else "Operational", if (!applies) Tone.Neutral else if (locked) Tone.Warn else Tone.Good)
                }
            }
        }
        Gap(12.dp)

        PCard {
            Kicker("Background first · conditional takeover")
            Text("The phone's screen", Modifier.padding(top = 6.dp), fontSize = 18.sp, color = P.ink)
            PSetting("Screen input owner", if (userHoldsScreen) "You hold the screen: agents' screen actions wait until you return it." else "Agents may act on the screen through their safety checks.", divider = false) {
                Tag(if (userHoldsScreen) "You" else screenLease?.owner?.toString() ?: "Agents", if (userHoldsScreen) Tone.Neutral else Tone.Good)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!userHoldsScreen) PButton("Take Back Screen", { container.inputLeases.takeBack("display:0") }, icon = PIcons.hand)
                else PButton("Return to agents", { container.inputLeases.release("display:0", Principal.User, "returned by the user") }, style = ButtonStyle.Alt)
            }
            Subtle("Taking the screen back refuses new agent input on it. It does not cancel an action already sent.", Modifier.padding(top = 8.dp))
        }
        Gap(12.dp)

        if (working.isNotEmpty()) {
            PCard(style = CardStyle.Soft) {
                Kicker("Working now")
                working.forEach { s -> PSetting(s.title, "Pausing stops its current turn at the next safe point; it can be resumed.") { PButton("Pause", { scope.launch { container.supervisor.pause(s.id) } }, style = ButtonStyle.Alt, tiny = true) } }
                PButton("Pause all", { scope.launch { working.forEach { runCatching { container.supervisor.pause(it.id) } } } }, style = ButtonStyle.Warn, tiny = true)
            }
            Gap(12.dp)
        }

        PCard(style = CardStyle.Soft) {
            Kicker("What a lockdown does not guarantee")
            Gap(8.dp)
            FactGrid(
                listOf(
                    "Input Lockdown" to "≠ stopping running programs",
                    "Taking input" to "≠ cancelling a sent command",
                    "App restart" to "≠ lifting the lockdown",
                    "Lifting" to "≠ resuming any session",
                ),
            )
        }
        Gap(12.dp)

        PCard {
            Kicker("Unified control timeline")
            (emergencyLog.takeLast(5).map { it.timeMs to "emergency · ${it.kind}" } + timeline.takeLast(12).map { e ->
                e.timeMs to "${e.kind} · " + (e.data?.toString()?.take(120) ?: "")
            }).sortedByDescending { it.first }.take(15).forEach { (t, line) ->
                Text("${DateUtils.formatDateTime(container.context, t, DateUtils.FORMAT_SHOW_TIME)}  $line", Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
            }
            if (emergencyLog.isEmpty() && timeline.isEmpty()) Subtle("Nothing recorded yet.")
        }
        Gap(28.dp)
    }

    when (confirm) {
        "lockdown" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Lock down all managed input?") },
            text = { Text("Agents can still read and think, but nothing they do may change the phone, its files or its apps until you lift the lockdown. Commands already running are not stopped.") },
            confirmButton = { TextButton(onClick = { confirm = null; container.emergency.lockdown("locked down by the user") }) { Text("Lock down") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
        "lift" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Lift the lockdown?") },
            text = { Text("Agents may act again. Nothing resumes by itself: paused sessions stay paused until you resume them.") },
            confirmButton = { TextButton(onClick = { confirm = null; container.emergency.lift() }) { Text("Lift") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
