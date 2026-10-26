package app.umbra.agent.ui.resources

import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.PollWhileStarted
import app.umbra.agent.ui.pressureLevelRes
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
import kotlinx.coroutines.launch

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
    // Short enough for the narrow budget columns (L-07): it never wraps onto two lines.
    val independentName = stringResource(R.string.res_independent)
    fun nameOf(key: String) = if (key == ResourcePolicy.INDEPENDENT) independentName else projects[key]?.name ?: key
    val sample = container.governor.latestSample

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow(stringResource(R.string.res_eyebrow))
        Headline(stringResource(R.string.res_headline), stringResource(R.string.res_headline_tail), Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)

        PCard {
            Row {
                Column(Modifier.weight(1f)) {
                    Kicker(stringResource(R.string.res_now))
                    Text(pluralStringResource(R.plurals.res_agents_max, decision.maxConcurrentSessions, decision.maxConcurrentSessions), Modifier.padding(top = 4.dp), fontSize = 18.sp, color = P.ink)
                }
                Tag(stringResource(pressureLevelRes(decision.level)), if (decision.level >= PressureLevel.HIGH) Tone.Warn else Tone.Good)
            }
            sample?.let { s ->
                Subtle(listOfNotNull(s.memAvailMb?.let { a -> s.memTotalMb?.let { t -> stringResource(R.string.res_ram_free_of, a, t) } ?: stringResource(R.string.res_ram_free, a) }, s.storageFreeMb?.let { stringResource(R.string.res_storage_free, it) }).joinToString(" · "), Modifier.padding(top = 4.dp))
            }
            if (decision.reasons.isNotEmpty()) Subtle(decision.reasons.joinToString("; "), Modifier.padding(top = 2.dp))
            Gap(6.dp)
            Subtle(stringResource(R.string.res_measured))
        }
        Gap(12.dp)

        AdmissionCard(sessions, running, decision.maxConcurrentSessions, ::nameOf)
        Gap(12.dp)

        PCard {
            Row {
                Column(Modifier.weight(1f)) {
                    Kicker(if (draft != null) stringResource(R.string.res_draft) else stringResource(R.string.res_board))
                    Text(stringResource(R.string.res_board_title), Modifier.padding(top = 4.dp), fontSize = 18.sp, color = P.ink)
                }
                Tag(if (draft != null) stringResource(R.string.res_unpublished) else stringResource(R.string.res_rev, published?.number ?: 0), if (draft != null) Tone.Warn else Tone.Neutral)
            }
            Gap(6.dp)
            PSetting(stringResource(R.string.res_slots), stringResource(R.string.res_slots_d), divider = true) {
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
                        Text(nameOf(key), Modifier.weight(1f), fontSize = 14.sp, color = P.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(stringResource(R.string.res_budget_line, b.guaranteed, if (b.max == 0) stringResource(R.string.res_no_max) else stringResource(R.string.res_max, b.max), now), fontSize = 12.sp, color = P.sub)
                    }
                    Slider(b.guaranteed.toFloat(), { v -> container.policies.saveDraft(editing.copy(budgets = editing.budgets + (key to b.copy(guaranteed = v.toInt())))) }, valueRange = 0f..editing.slots.toFloat(), steps = (editing.slots - 1).coerceAtLeast(0))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.res_maximum), fontSize = 12.sp, color = P.muted, modifier = Modifier.padding(top = 6.dp))
                        PButton("−", { container.policies.saveDraft(editing.copy(budgets = editing.budgets + (key to b.copy(max = (b.max - 1).coerceAtLeast(0))))) }, style = ButtonStyle.Ghost, tiny = true)
                        PButton("+", { container.policies.saveDraft(editing.copy(budgets = editing.budgets + (key to b.copy(max = (b.max + 1).coerceAtMost(editing.slots))))) }, style = ButtonStyle.Ghost, tiny = true)
                    }
                    PBar(if (editing.slots > 0) now.toFloat() / editing.slots else 0f, tone = if (b.max in 1 until now) Tone.Warn else Tone.Good)
                }
            }
            val guaranteed = editing.budgets.values.sumOf { it.guaranteed }
            Gap(10.dp)
            Subtle(stringResource(R.string.res_total, guaranteed, editing.slots) + " " + if (guaranteed > editing.slots) stringResource(R.string.res_overcommit) else stringResource(R.string.res_headroom, editing.slots - guaranteed))
            Gap(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PButton(stringResource(R.string.res_simulate), {
                    simulation = container.policies.simulate(editing, running, decision.maxConcurrentSessions, projects.keys)
                }, style = ButtonStyle.Alt, tiny = true, enabled = draft != null)
                PButton(stringResource(R.string.res_publish), {
                    val sim = container.policies.simulate(editing, running, decision.maxConcurrentSessions, projects.keys)
                    simulation = sim
                    runCatching { container.policies.publish(sim) }.onFailure { error = it.message }.onSuccess { error = null }
                }, tiny = true, enabled = draft != null)
                if (draft != null) PButton(stringResource(R.string.res_discard), { container.policies.discardDraft() }, style = ButtonStyle.Ghost, tiny = true)
            }
            simulation?.let { sim ->
                Gap(10.dp)
                Callout(
                    if (!sim.valid) stringResource(R.string.res_rejected, sim.problems.joinToString("; "))
                    else stringResource(R.string.res_valid) + " " + (if (sim.converging.isEmpty()) stringResource(R.string.res_nothing_exceeds) else stringResource(R.string.res_converging, sim.converging.joinToString("; "))),
                    if (sim.valid) Tone.Good else Tone.Warn,
                )
                sim.shortfalls.forEach { Gap(6.dp); Callout(stringResource(R.string.res_shortfall, it), Tone.Warn) }
                sim.notes.forEach { Subtle(it, Modifier.padding(top = 6.dp)) }
            }
            error?.let { Gap(6.dp); Callout(it, Tone.Danger) }
        }
        Gap(12.dp)

        PCard(style = CardStyle.Soft) {
            Kicker(stringResource(R.string.res_studio))
            Steps(
                listOf(
                    StepItem(stringResource(R.string.res_step_draft), stringResource(R.string.res_step_draft_d), if (draft != null) StepState.Active else StepState.Done),
                    StepItem(stringResource(R.string.res_step_sim), stringResource(R.string.res_step_sim_d), if (simulation != null) StepState.Done else StepState.Wait),
                    StepItem(stringResource(R.string.res_step_apply), stringResource(R.string.res_step_apply_d), if (draft == null && published != null) StepState.Done else StepState.Wait),
                ),
            )
            PSetting(stringResource(R.string.res_published), stringResource(R.string.res_published_d)) { Text(stringResource(R.string.res_rev, published?.number ?: 0), fontSize = 13.sp, color = P.ink) }
            PSetting(stringResource(R.string.res_effective), stringResource(R.string.res_effective_d), divider = false) {
                Tag(if (published == null) stringResource(R.string.res_defaults) else if (status.effective) stringResource(R.string.res_effective_tag) else stringResource(R.string.res_converging_tag), if (status.effective) Tone.Good else Tone.Warn)
            }
            if (!status.effective) Subtle(stringResource(R.string.res_converging, status.converging.joinToString { nameOf(it) }), Modifier.padding(top = 4.dp))
            Gap(8.dp)
            revisions.values.sortedByDescending { it.number }.take(6).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(stringResource(R.string.res_rev_line, r.number, r.policy.slots, DateUtils.getRelativeTimeSpanString(r.publishedAtMs).toString()), Modifier.weight(1f), fontSize = 12.sp, color = P.sub)
                    PButton(stringResource(R.string.res_restore), { container.policies.restoreAsDraft(r.number) }, style = ButtonStyle.Ghost, tiny = true)
                }
            }
        }
        Gap(28.dp)
    }
    LaunchedEffect(Unit) { runCatching { container.governor.refreshNow() } }
}

/**
 * Admission (AC-022): how many agents run against how many may, and the queue of logical agents waiting for a slot,
 * in the order they would start if nothing running finished, with each owner's share. A queued request has no
 * process; it can be taken out of the queue.
 */
@Composable
private fun AdmissionCard(sessions: List<app.umbra.core.session.Session>, running: Map<String?, Int>, slots: Int, nameOf: (String) -> String) {
    val container = LocalContext.current.container
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val queued = sessions.filter { it.state == SessionState.CREATED && it.queuedAtMs != null }
    val order = remember(queued, running) { app.umbra.core.orchestrator.AdmissionOrder.forecast(queued, running) }
    var reason by remember { mutableStateOf<String?>(null) }
    PollWhileStarted(queued.size, running, periodMs = 2_000) {
        reason = runCatching { container.supervisor.waitingReason(order.firstOrNull()?.projectId) }.getOrNull()
    }
    fun owner(p: String?) = nameOf(p ?: ResourcePolicy.INDEPENDENT)
    PCard {
        Row {
            Column(Modifier.weight(1f)) {
                Kicker(stringResource(R.string.res_admission))
                Text(stringResource(R.string.res_running_queued, running.values.sum(), queued.size), Modifier.padding(top = 4.dp), fontSize = 18.sp, color = P.ink)
            }
            Tag(pluralStringResource(R.plurals.res_slot_tag, slots, slots), Tone.Neutral)
        }
        val owners = (running.keys + queued.map { it.projectId }).distinct()
        owners.forEach { p ->
            PSetting(owner(p), stringResource(R.string.res_owner_line, running[p] ?: 0, queued.count { it.projectId == p }) +
                (queued.filter { it.projectId == p }.minOfOrNull { it.queuedAtMs!! }?.let { " " + stringResource(R.string.res_oldest, android.text.format.DateUtils.getRelativeTimeSpanString(it).toString()) } ?: ""), divider = false) {}
        }
        if (queued.isEmpty()) Subtle(stringResource(R.string.res_nothing_waits), Modifier.padding(top = 6.dp))
        else {
            reason?.let { Subtle(stringResource(R.string.res_waiting, it), Modifier.padding(top = 6.dp)) }
            Subtle(stringResource(R.string.res_next), Modifier.padding(top = 6.dp))
            order.take(8).forEachIndexed { i, s ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${s.title}", Modifier.weight(1f), fontSize = 13.sp, color = P.ink, maxLines = 1)
                    Text(owner(s.projectId), fontSize = 11.sp, color = P.sub, maxLines = 1)
                    PButton(stringResource(R.string.res_remove), { scope.launch { container.supervisor.dequeue(s.id) } }, Modifier.padding(start = 6.dp), style = ButtonStyle.Ghost, tiny = true)
                }
            }
            if (order.size > 8) Subtle(stringResource(R.string.res_more, order.size - 8), Modifier.padding(top = 4.dp))
        }
        Subtle(stringResource(R.string.res_queue_limit, app.umbra.core.orchestrator.AgentSupervisor.QUEUE_LIMIT), Modifier.padding(top = 6.dp))
    }
}
