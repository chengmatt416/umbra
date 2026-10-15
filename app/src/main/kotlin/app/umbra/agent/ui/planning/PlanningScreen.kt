package app.umbra.agent.ui.planning

import android.text.format.DateUtils
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.Disclosure
import app.umbra.agent.ui.porcelain.PBar
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PObject
import app.umbra.agent.ui.sessions.sessionTone
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.GraphFlow
import app.umbra.agent.ui.porcelain.GraphNode
import app.umbra.agent.ui.porcelain.Headline
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.StepItem
import app.umbra.agent.ui.porcelain.StepState
import app.umbra.agent.ui.porcelain.Steps
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.sessions.stateText
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.control.LeaseState
import app.umbra.core.control.PlanPhase
import app.umbra.core.control.Principal
import app.umbra.core.control.WaitEdge
import app.umbra.core.control.WaitGraph
import app.umbra.runtime.omp.OmpSubagent
import app.umbra.runtime.omp.subagents
import kotlinx.serialization.json.JsonPrimitive

private enum class PlanView(@param:StringRes val label: Int) { Progress(R.string.view_progress), Dag(R.string.view_phases), Tree(R.string.view_helpers), Wait(R.string.view_waits) }

private fun stepState(status: String) = when (status) {
    "completed", "done" -> StepState.Done
    "in_progress", "active" -> StepState.Active
    "abandoned", "skipped" -> StepState.Mute
    "failed", "blocked" -> StepState.Failed
    else -> StepState.Wait
}

private fun phaseTone(p: PlanPhase): Pair<Int, Tone> = when {
    p.tasks.isNotEmpty() && p.tasks.all { it.status == "completed" || it.status == "abandoned" } -> R.string.phase_completed to Tone.Good
    p.tasks.any { it.status == "in_progress" } -> R.string.phase_working to Tone.Good
    p.tasks.any { it.status == "completed" } -> R.string.phase_under_way to Tone.Neutral
    else -> R.string.phase_queued to Tone.Neutral
}

/**
 * Planning & Agents (architecture spec §4.1, §4.3): a session's plan as immutable revisions with what changed, its
 * phases in order, who delegated to whom, and who waits for what (with deadlock detection). These are different
 * graphs and are never folded into one.
 */
@Composable
fun PlanningScreen(onOpenSession: (String) -> Unit) {
    val container = LocalContext.current.container
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val revisionsAll by container.plans.all.collectAsStateWithLifecycle()
    val progressAll by container.plans.progressAll.collectAsStateWithLifecycle()
    val leases by container.inputLeases.all.collectAsStateWithLifecycle()
    val timeline by container.inputLeases.recent.collectAsStateWithLifecycle()
    val focused by container.ui.focusedSession.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf(PlanView.Progress) }
    var picking by remember { mutableStateOf(false) }
    val sid = selected ?: focused?.takeIf { f -> sessions.any { it.id == f } } ?: sessions.firstOrNull()?.id
    val session = sessions.firstOrNull { it.id == sid }
    val history = revisionsAll.values.filter { it.sessionId == sid }.sortedBy { it.number }
    val progress = progressAll[sid]
    val current = history.lastOrNull()
    // AC-005: the session's screen work refused because the user holds that screen, while they still hold it.
    val heldByYou = leases.values.filter { it.owner == Principal.User && it.state == LeaseState.ACTIVE }.map { it.resourceId }.toSet()
    val blockedOn = timeline.lastOrNull { e ->
        e.kind == "blocked" && e.timeMs >= System.currentTimeMillis() - 10 * 60_000 &&
            (e.data as? kotlinx.serialization.json.JsonObject)?.let { (it["by"] as? JsonPrimitive)?.content == Principal.agent(sid ?: "").toString() && (it["resource"] as? JsonPrimitive)?.content in heldByYou } == true
    }?.let { ((it.data as kotlinx.serialization.json.JsonObject)["resource"] as JsonPrimitive).content }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow(stringResource(R.string.plan_eyebrow))
        Headline(stringResource(R.string.plan_headline), stringResource(R.string.plan_headline_tail), Modifier.padding(top = 6.dp, bottom = 10.dp), size = 26)
        if (session == null) {
            Subtle(stringResource(R.string.plan_empty))
            return@Column
        }
        // The session picker: the whole title, ellipsized at a word's end by the text layout, never cut mid-word (L-01).
        Box {
            PObject(session.title, current?.let { stringResource(R.string.plan_revision, it.number) } ?: stringResource(R.string.plan_none), PIcons.layers, onClick = { picking = true }, trailing = {
                Tag(stateText(session), sessionTone(session))
                PIcon(PIcons.chevronDown, size = 16.dp, tint = P.muted)
            })
            DropdownMenu(picking, { picking = false }) {
                sessions.take(20).forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (s.id == sid) FontWeight.SemiBold else FontWeight.Normal) },
                        onClick = { picking = false; selected = s.id },
                    )
                }
            }
        }
        Gap(10.dp)
        Segmented(PlanView.entries.map { it to stringResource(it.label) }, view, { view = it }, fill = true)
        Gap(10.dp)
        PCard {
            val phases = progress?.phases ?: current?.phases.orEmpty()
            when (view) {
                PlanView.Progress -> {
                    blockedOn?.let { Callout(stringResource(R.string.plan_blocked, it), Tone.Warn, Modifier.padding(bottom = 8.dp)) }
                    val tasks = phases.flatMap { it.tasks }
                    if (tasks.isEmpty()) Subtle(stringResource(R.string.plan_no_todos))
                    else {
                        val done = tasks.count { it.status == "completed" || it.status == "abandoned" }
                        val now = tasks.firstOrNull { it.status == "in_progress" }
                        val next = tasks.firstOrNull { it.status == "pending" }
                        val phase = phases.firstOrNull { p -> p.tasks.any { it.status == "in_progress" } } ?: phases.firstOrNull { p -> p.tasks.any { it.status == "pending" } }
                        Text(stringResource(R.string.plan_progress_of, done, tasks.size), fontFamily = P.serif, fontSize = 22.sp, color = P.ink, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = P.tabular))
                        Box(Modifier.padding(top = 10.dp)) { PBar(done / tasks.size.toFloat()) }
                        phase?.let { Subtle(stringResource(R.string.plan_phase_now, it.name), Modifier.padding(top = 12.dp)) }
                        now?.let { Text(stringResource(R.string.plan_now, it.content), Modifier.padding(top = 4.dp), fontSize = 14.sp, lineHeight = 20.sp, color = P.ink) }
                        next?.let { Subtle(stringResource(R.string.plan_next, it.content), Modifier.padding(top = 4.dp)) }
                    }
                }
                PlanView.Dag -> {
                    blockedOn?.let { Callout(stringResource(R.string.plan_blocked, it), Tone.Warn, Modifier.padding(bottom = 8.dp)) }
                    if (phases.isEmpty()) Subtle(stringResource(R.string.plan_no_todos))
                    else {
                        val working = phases.indexOfFirst { p -> p.tasks.any { it.status == "in_progress" } }
                        val blockedLabel = blockedOn?.let { stringResource(R.string.plan_blocked_on, it) }
                        GraphFlow(phases.mapIndexed { i, p -> val (label, tone) = phaseTone(p); if (blockedLabel != null && i == working) GraphNode(p.name, blockedLabel, Tone.Warn) else GraphNode(p.name, stringResource(label), tone) })
                        Subtle(stringResource(R.string.plan_phases_note))
                        phases.forEach { p ->
                            Kicker(p.name, Modifier.padding(top = 10.dp))
                            Steps(p.tasks.map { StepItem(it.content, null, stepState(it.status)) })
                        }
                    }
                }
                PlanView.Tree -> AgentTree(sid!!, session.title, session.subagents.map { it.id to (it.role ?: "helper") + " · " + it.state })
                PlanView.Wait -> {
                    val edges = waitEdges(leases.values.toList(), timeline)
                    val cycles = WaitGraph.cycles(edges)
                    val holds = leases.values.filter { it.owner != null }.map { GraphNode(it.owner.toString(), stringResource(R.string.plan_holds, it.resourceId), Tone.Good) }
                    if (holds.isEmpty() && edges.isEmpty()) Subtle(stringResource(R.string.plan_nobody_holds))
                    if (holds.isNotEmpty()) GraphFlow(holds, edge = "·")
                    edges.takeLast(10).forEach { e -> Text(stringResource(R.string.plan_waited, e.waiter, e.resource, e.holder), fontSize = 12.sp, color = P.sub, modifier = Modifier.padding(top = 3.dp)) }
                    Gap(6.dp)
                    if (cycles.isNotEmpty()) Callout(stringResource(R.string.plan_cycle, (cycles.first() + cycles.first().first()).joinToString(" → ")), Tone.Warn)
                    else Callout(stringResource(R.string.plan_no_cycle), Tone.Good)
                }
            }
        }
        Gap(12.dp)
        PCard(style = CardStyle.Soft) {
            Kicker(stringResource(R.string.plan_changes, history.size))
            if (history.isEmpty()) Subtle(stringResource(R.string.plan_no_changes), Modifier.padding(top = 6.dp))
            else Disclosure(stringResource(R.string.details)) {
                history.reversed().forEach { r ->
                    Row(Modifier.padding(top = 10.dp)) {
                        Text(stringResource(R.string.plan_rev, r.number), fontSize = 14.sp, color = P.ink, modifier = Modifier.weight(1f))
                        Tag(stringResource(if (r.source == "user") R.string.plan_by_you else R.string.plan_by_agent), if (r.source == "user") Tone.Neutral else Tone.Good)
                    }
                    Subtle(pluralStringResource(R.plurals.plan_rev_meta, r.phases.size, DateUtils.getRelativeTimeSpanString(r.createdAtMs).toString(), r.phases.size, r.phases.sumOf { it.tasks.size }) + " · ${r.graphHash.take(10)}")
                    r.changes.take(6).forEach { Text("• $it", fontSize = 12.sp, color = P.sub) }
                    r.conflict?.let { Gap(4.dp); Callout(it, Tone.Warn) }
                }
            }
            Gap(8.dp)
            PButton(stringResource(R.string.plan_open_session), { onOpenSession(session.id) }, style = ButtonStyle.Alt, tiny = true)
        }
        Gap(28.dp)
    }
}

/** The Delegation Tree: the session's agent and the subagents it started (live from omp when it runs). */
@Composable
private fun AgentTree(sessionId: String, title: String, recorded: List<Pair<String, String>>) {
    val container = LocalContext.current.container
    var live by remember(sessionId) { mutableStateOf<List<OmpSubagent>?>(null) }
    LaunchedEffect(sessionId) {
        // Only asks omp when its process already runs: looking at a plan must not start an agent.
        if (container.supervisor.liveSession(sessionId)?.isProcessAlive == true) live = runCatching { container.supervisor.omp(sessionId) { it.subagents() } }.getOrNull()
    }
    val nodes = live?.map { GraphNode("${it.agent} #${it.index}", it.status, if (it.status == "running") Tone.Good else Tone.Neutral) }
        ?: recorded.map { (id, what) -> GraphNode(id.take(10), what, Tone.Neutral) }
    GraphFlow(listOf(GraphNode(stringResource(R.string.plan_coordinator), title, Tone.Good)) + nodes)
    if (nodes.isEmpty()) Subtle(stringResource(R.string.plan_no_helpers))
    Subtle(stringResource(R.string.plan_tree_note), Modifier.padding(top = 6.dp))
}

private fun waitEdges(leases: List<app.umbra.core.control.InputLease>, timeline: List<app.umbra.core.control.JournalEntry>): List<WaitEdge> {
    val cutoff = System.currentTimeMillis() - 10 * 60_000
    return timeline.filter { it.kind == "blocked" && it.timeMs >= cutoff }.mapNotNull { e ->
        val o = e.data as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content
        val resource = s("resource") ?: return@mapNotNull null
        val holder = s("holder") ?: leases.firstOrNull { it.resourceId == resource }?.owner?.toString() ?: return@mapNotNull null
        WaitEdge(s("by") ?: return@mapNotNull null, resource, holder)
    }.distinct()
}
