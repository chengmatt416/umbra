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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.container
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

private enum class PlanView(val label: String) { Dag("Phase DAG"), Tree("Agent tree"), Wait("Wait graph") }

private fun stepState(status: String) = when (status) {
    "completed", "done" -> StepState.Done
    "in_progress", "active" -> StepState.Active
    "abandoned", "skipped" -> StepState.Mute
    "failed", "blocked" -> StepState.Failed
    else -> StepState.Wait
}

private fun phaseTone(p: PlanPhase): Pair<String, Tone> = when {
    p.tasks.isNotEmpty() && p.tasks.all { it.status == "completed" || it.status == "abandoned" } -> "Completed" to Tone.Good
    p.tasks.any { it.status == "in_progress" } -> "Working" to Tone.Good
    p.tasks.any { it.status == "completed" } -> "Under way" to Tone.Neutral
    else -> "Queued" to Tone.Neutral
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
    var view by remember { mutableStateOf(PlanView.Dag) }
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
        Eyebrow("Agent planning & integration")
        Headline("Plans that can", "evolve safely.", Modifier.padding(top = 6.dp, bottom = 10.dp), size = 26)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sessions.take(12).forEach { s -> PButton(s.title.take(22), { selected = s.id }, style = if (s.id == sid) ButtonStyle.Primary else ButtonStyle.Alt, tiny = true) }
        }
        Gap(10.dp)
        if (session == null) {
            Subtle("No sessions yet. Plans appear here as agents write their todo lists.")
            return@Column
        }
        PCard {
            Row {
                Column(Modifier.weight(1f)) {
                    Kicker(current?.let { "Plan revision ${it.number}" } ?: "No plan yet")
                    Text(session.title, Modifier.padding(top = 4.dp), fontSize = 18.sp, color = P.ink)
                }
                Tag(stateText(session), Tone.Neutral)
            }
            Gap(8.dp)
            Segmented(PlanView.entries.map { it to it.label }, view, { view = it })
            Gap(8.dp)
            when (view) {
                PlanView.Dag -> {
                    val phases = progress?.phases ?: current?.phases.orEmpty()
                    blockedOn?.let { Callout("Blocked: its actions on $it wait for you, who took that screen back. The phase that needs it waits; its commands and builds go on.", Tone.Warn, Modifier.padding(bottom = 8.dp)) }
                    if (phases.isEmpty()) Subtle("The agent has not written a todo list for this session.")
                    else {
                        val working = phases.indexOfFirst { p -> p.tasks.any { it.status == "in_progress" } }
                        GraphFlow(phases.mapIndexed { i, p -> val (label, tone) = phaseTone(p); if (blockedOn != null && i == working) GraphNode(p.name, "Blocked on $blockedOn", Tone.Warn) else GraphNode(p.name, label, tone) })
                        Subtle("omp's phases run in order: each phase depends on the one before it.")
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
                    val holds = leases.values.filter { it.owner != null }.map { GraphNode(it.owner.toString(), "holds ${it.resourceId}", Tone.Good) }
                    if (holds.isEmpty() && edges.isEmpty()) Subtle("Nobody holds input on a terminal, screen or desktop right now.")
                    if (holds.isNotEmpty()) GraphFlow(holds, edge = "·")
                    edges.takeLast(10).forEach { e -> Text("${e.waiter} waited for ${e.resource}, held by ${e.holder}", fontSize = 12.sp, color = P.sub, modifier = Modifier.padding(top = 3.dp)) }
                    Gap(6.dp)
                    if (cycles.isNotEmpty()) Callout("Potential circular wait: ${cycles.first().joinToString(" → ")} → ${cycles.first().first()}. Resolve it by releasing one lease or by coordination; nothing is ended automatically.", Tone.Warn)
                    else Callout("No circular wait among the recent waits.", Tone.Good)
                }
            }
        }
        Gap(12.dp)
        PCard(style = CardStyle.Soft) {
            Kicker("Revision history · read-only")
            if (history.isEmpty()) Subtle("No revisions yet.", Modifier.padding(top = 6.dp))
            history.reversed().forEach { r ->
                Row(Modifier.padding(top = 10.dp)) {
                    Text("Rev. ${r.number}", fontSize = 14.sp, color = P.ink, modifier = Modifier.weight(1f))
                    Tag(if (r.source == "user") "You" else "Agent", if (r.source == "user") Tone.Neutral else Tone.Good)
                }
                Subtle("${DateUtils.getRelativeTimeSpanString(r.createdAtMs)} · ${r.phases.size} phases · ${r.phases.sumOf { it.tasks.size }} tasks · ${r.graphHash.take(10)}")
                r.changes.take(6).forEach { Text("• $it", fontSize = 12.sp, color = P.sub) }
                r.conflict?.let { Gap(4.dp); Callout(it, Tone.Warn) }
            }
            Gap(8.dp)
            PButton("Open the session", { onOpenSession(session.id) }, style = ButtonStyle.Alt, tiny = true)
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
    GraphFlow(listOf(GraphNode("Coordinator", title.take(24), Tone.Good)) + nodes)
    if (nodes.isEmpty()) Subtle("This session has not delegated to subagents.")
    Subtle("Delegation tree ≠ phase order ≠ which processes belong to whom: do not infer what a halt would stop from this view.", Modifier.padding(top = 6.dp))
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
