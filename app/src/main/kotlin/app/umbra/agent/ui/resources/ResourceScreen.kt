package app.umbra.agent.ui.resources

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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.Headline
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PBar
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.PSetting
import app.umbra.agent.ui.porcelain.StepItem
import app.umbra.agent.ui.porcelain.StepState
import app.umbra.agent.ui.porcelain.Steps
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.control.Budget
import app.umbra.core.control.ResourcePolicy
import app.umbra.core.control.Simulation
import app.umbra.core.perf.PressureLevel
import app.umbra.core.session.SessionState

/**
 * Resource Manager (architecture spec §4.2): the device's measured capacity, the budget board (guaranteed, maximum,
 * weight per project), and the policy studio — a draft changes nothing until it is simulated and published as a new
 * revision; published, effective and converging are shown apart.
 */
@Composable
fun ResourceScreen() {
    val container = LocalContext.current.container
    val decision by container.governor.decision.collectAsStateWithLifecycle()
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val projects by container.projects.all.collectAsStateWithLifecycle()
    val revisions by container.policies.revisionsAll.collectAsStateWithLifecycle()
    val drafts by container.policies.draftAll.collectAsStateWithLifecycle()
    val published = revisions.values.maxByOrNull { it.number }
    val draft = drafts["draft"]
    val running = sessions.filter { it.state == SessionState.RUNNING || it.state == SessionState.WAITING_APPROVAL }.groupingBy { it.projectId }.eachCount()
    val status = remember(published, running) { container.policies.status(running) }
    var simulation by remember(draft) { mutableStateOf<Simulation?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val editing = draft?.policy ?: published?.policy ?: ResourcePolicy()
    val keys = listOf(ResourcePolicy.INDEPENDENT) + projects.values.sortedBy { it.createdAtMs }.map { it.id }
    fun nameOf(key: String) = if (key == ResourcePolicy.INDEPENDENT) "Independent sessions" else projects[key]?.name ?: key
    val sample = container.governor.latestSample

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow("Capacity · leases · policy")
        Headline("Resource governance,", "made legible.", Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)

        PCard {
            Row {
                Column(Modifier.weight(1f)) {
                    Kicker("This device now")
                    Text("${decision.maxConcurrentSessions} agents at most", Modifier.padding(top = 4.dp), fontSize = 18.sp, color = P.ink)
                }
                Tag(decision.level.name.lowercase().replaceFirstChar { it.uppercase() }, if (decision.level >= PressureLevel.HIGH) Tone.Warn else Tone.Good)
            }
            sample?.let { s ->
                Subtle(listOfNotNull(s.memAvailMb?.let { "RAM free ${it} MB" + (s.memTotalMb?.let { t -> " of $t" } ?: "") }, s.storageFreeMb?.let { "storage free ${it} MB" }).joinToString(" · "), Modifier.padding(top = 4.dp))
            }
            if (decision.reasons.isNotEmpty()) Subtle(decision.reasons.joinToString("; "), Modifier.padding(top = 2.dp))
            Gap(6.dp)
            Subtle("Measured, not promised: when the phone runs short, new agents wait and finished ones release their processes first.")
        }
        Gap(12.dp)

        PCard {
            Row {
                Column(Modifier.weight(1f)) {
                    Kicker(if (draft != null) "Draft · not in force" else "Budget board")
                    Text("Guaranteed · maximum · running", Modifier.padding(top = 4.dp), fontSize = 18.sp, color = P.ink)
                }
                Tag(if (draft != null) "Unpublished draft" else "Rev. ${published?.number ?: 0}", if (draft != null) Tone.Warn else Tone.Neutral)
            }
            Gap(6.dp)
            PSetting("Slots shared out", "One slot is one running agent process", divider = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PButton("−", { container.policies.saveDraft(editing.copy(slots = (editing.slots - 1).coerceAtLeast(1))) }, style = ButtonStyle.Alt, tiny = true)
                    Text("${editing.slots}", fontSize = 16.sp, color = P.ink, modifier = Modifier.padding(top = 6.dp))
                    PButton("+", { container.policies.saveDraft(editing.copy(slots = (editing.slots + 1).coerceAtMost(32))) }, style = ButtonStyle.Alt, tiny = true)
                }
            }
            keys.forEach { key ->
                val b = editing.budgets[key] ?: Budget(0, 0, 1)
                val now = running[if (key == ResourcePolicy.INDEPENDENT) null else key] ?: 0
                Column(Modifier.padding(top = 10.dp)) {
                    Row {
                        Text(nameOf(key), Modifier.weight(1f), fontSize = 14.sp, color = P.ink)
                        Text("${b.guaranteed} guaranteed · ${if (b.max == 0) "no max" else "max ${b.max}"} · $now running", fontSize = 12.sp, color = P.sub)
                    }
                    Slider(b.guaranteed.toFloat(), { v -> container.policies.saveDraft(editing.copy(budgets = editing.budgets + (key to b.copy(guaranteed = v.toInt())))) }, valueRange = 0f..editing.slots.toFloat(), steps = (editing.slots - 1).coerceAtLeast(0))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Maximum", fontSize = 12.sp, color = P.muted, modifier = Modifier.padding(top = 6.dp))
                        PButton("−", { container.policies.saveDraft(editing.copy(budgets = editing.budgets + (key to b.copy(max = (b.max - 1).coerceAtLeast(0))))) }, style = ButtonStyle.Ghost, tiny = true)
                        PButton("+", { container.policies.saveDraft(editing.copy(budgets = editing.budgets + (key to b.copy(max = (b.max + 1).coerceAtMost(editing.slots))))) }, style = ButtonStyle.Ghost, tiny = true)
                    }
                    PBar(if (editing.slots > 0) now.toFloat() / editing.slots else 0f, tone = if (b.max in 1 until now) Tone.Warn else Tone.Good)
                }
            }
            val guaranteed = editing.budgets.values.sumOf { it.guaranteed }
            Gap(10.dp)
            Subtle("Total guaranteed $guaranteed of ${editing.slots}" + if (guaranteed > editing.slots) " — overcommit, cannot be published" else " · headroom ${editing.slots - guaranteed}")
            Gap(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PButton("Dry-run simulator", {
                    simulation = container.policies.simulate(editing, running, decision.maxConcurrentSessions, projects.keys)
                }, style = ButtonStyle.Alt, tiny = true, enabled = draft != null)
                PButton("Publish revision", {
                    val sim = container.policies.simulate(editing, running, decision.maxConcurrentSessions, projects.keys)
                    simulation = sim
                    runCatching { container.policies.publish(sim) }.onFailure { error = it.message }.onSuccess { error = null }
                }, tiny = true, enabled = draft != null)
                if (draft != null) PButton("Discard", { container.policies.discardDraft() }, style = ButtonStyle.Ghost, tiny = true)
            }
            simulation?.let { sim ->
                Gap(10.dp)
                Callout(
                    if (!sim.valid) "Rejected: " + sim.problems.joinToString("; ")
                    else "Valid. " + (if (sim.converging.isEmpty()) "Nothing running exceeds it." else "Converging: " + sim.converging.joinToString("; ") + "."),
                    if (sim.valid) Tone.Good else Tone.Warn,
                )
                sim.shortfalls.forEach { Gap(6.dp); Callout("Shortfall: $it", Tone.Warn) }
                sim.notes.forEach { Subtle(it, Modifier.padding(top = 6.dp)) }
            }
            error?.let { Gap(6.dp); Callout(it, Tone.Danger) }
        }
        Gap(12.dp)

        PCard(style = CardStyle.Soft) {
            Kicker("Policy studio")
            Steps(
                listOf(
                    StepItem("Draft", "Edits stay apart from the scheduler", if (draft != null) StepState.Active else StepState.Done),
                    StepItem("Simulate", "Against what runs now and the device's capacity", if (simulation != null) StepState.Done else StepState.Wait),
                    StepItem("Apply", "A new revision; running work converges as it finishes", if (draft == null && published != null) StepState.Done else StepState.Wait),
                ),
            )
            PSetting("Published revision", "Policy history is append-only") { Text("Rev. ${published?.number ?: 0}", fontSize = 13.sp, color = P.ink) }
            PSetting("Effective status", "Published can lead what is in force while sessions finish", divider = false) {
                Tag(if (published == null) "Defaults" else if (status.effective) "Effective" else "Converging", if (status.effective) Tone.Good else Tone.Warn)
            }
            if (!status.effective) Subtle("Converging: " + status.converging.joinToString { nameOf(it) }, Modifier.padding(top = 4.dp))
            Gap(8.dp)
            revisions.values.sortedByDescending { it.number }.take(6).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text("Rev. ${r.number} · ${r.policy.slots} slots · ${DateUtils.getRelativeTimeSpanString(r.publishedAtMs)}", Modifier.weight(1f), fontSize = 12.sp, color = P.sub)
                    PButton("Restore as draft", { container.policies.restoreAsDraft(r.number) }, style = ButtonStyle.Ghost, tiny = true)
                }
            }
        }
        Gap(28.dp)
    }
    LaunchedEffect(Unit) { runCatching { container.governor.refreshNow() } }
}
