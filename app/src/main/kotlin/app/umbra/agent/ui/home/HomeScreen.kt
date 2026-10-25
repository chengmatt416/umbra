package app.umbra.agent.ui.home

import app.umbra.agent.ui.porcelain.inkItem
import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.capsule.TaskCompanion
import app.umbra.agent.container
import app.umbra.agent.data.HomeFilter
import app.umbra.agent.data.UiMode
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.sessions.stateText
import app.umbra.agent.ui.sessionsFlow
import app.umbra.agent.ui.shell.LocalChromeBottom
import app.umbra.agent.ui.shell.MenuButton
import app.umbra.agent.ui.shell.MenuEntries
import app.umbra.agent.ui.shell.MenuEntry
import app.umbra.agent.ui.shell.MenuPills
import app.umbra.agent.ui.agents.agentRows
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.shell.MenuScrim
import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.safety.ApprovalRequest
import app.umbra.core.safety.ApprovalSurface
import app.umbra.core.safety.ApproveGesture
import app.umbra.core.safety.approveGesture
import app.umbra.core.session.Session
import app.umbra.core.session.SessionState
import kotlinx.coroutines.launch

/**
 * Home: the tasks — those that need you first, then those at work, then the recent ones — and, in the thumb's reach,
 * the message field and the menu button. Nothing decorative: an empty Home is one sentence above the field.
 */
@Composable
fun HomeScreen(onOpenSession: (String) -> Unit, onOpenAgent: (String) -> Unit, entries: List<MenuEntry>, onAccounts: () -> Unit, onComputers: () -> Unit, onOpenProactive: (String, Int) -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    var composerHeight by remember { mutableStateOf(0.dp) }
    val all by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val profiles by container.agents.store.profiles.collectAsStateWithLifecycle()
    val pairs by container.agents.store.pairsFlow.collectAsStateWithLifecycle()
    val sheet by container.ui.homeSheet.collectAsStateWithLifecycle()
    val agentsTab = prefs.homeAgents
    val defaultAgent = remember(profiles, prefs.defaultAgentId) { container.agents.defaultAgent() }
    // Coding tasks only: subagents are shown under the session that started them, an agent's conversation is its chat.
    val sessions = all.filter { it.kind == app.umbra.core.session.SessionKind.CODING }
    val subagents = all.filter { it.kind == app.umbra.core.session.SessionKind.SUBAGENT && !it.state.isTerminal }.groupingBy { it.spawnedBy }.eachCount()
    val approvals by container.approvals.pending.collectAsStateWithLifecycle()
    val questions by container.questions.pending.collectAsStateWithLifecycle()
    val filter by container.ui.homeFilter.collectAsStateWithLifecycle()
    var menu by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val power = prefs.uiMode != UiMode.SIMPLE

    val waitingOn = approvals.map { it.sessionId }.toSet() + questions.map { it.sessionId }
    val needs = sessions.filter { !it.state.isTerminal && (it.id in waitingOn || it.state == SessionState.WAITING_APPROVAL) }
    val running = sessions.filter { it !in needs && (it.state == SessionState.RUNNING || it.state == SessionState.PAUSED || (it.state == SessionState.CREATED && it.queuedAtMs == null)) }
    val queued = sessions.filter { it !in needs && it.state == SessionState.CREATED && it.queuedAtMs != null }
    val recent = sessions.filter { it.state.isTerminal }.sortedByDescending { it.updatedAtMs }.take(if (power) 20 else 8)
    val shown: List<Pair<String?, List<Session>>> = when (if (power) filter else HomeFilter.ALL) {
        HomeFilter.ALL -> listOf(null to (needs + running + queued), stringResource(R.string.r_home_recent) to recent)
        HomeFilter.NEEDS -> listOf(null to needs)
        HomeFilter.RUNNING -> listOf(null to running)
        HomeFilter.QUEUED -> listOf(null to queued)
    }
    val empty = sessions.isEmpty()
    val today = app.umbra.agent.ui.agents.rememberAgentsToday(profiles)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = maxOf(120.dp, composerHeight + 36.dp) + LocalChromeBottom.current),
            verticalArrangement = Arrangement.spacedBy(if (agentsTab) 2.dp else 10.dp),
        ) {
            // Agents | Coding (Figma 10.1/10.2); the tab remembers the last choice.
            item(key = "tabs") {
                app.umbra.agent.ui.porcelain.Segmented(
                    listOf(true to stringResource(R.string.ag_tab_agents), false to stringResource(R.string.ag_tab_coding)),
                    agentsTab, { on -> container.settings.update { it.copy(homeAgents = on) } }, Modifier.fillMaxWidth().padding(bottom = 6.dp), fill = true,
                )
            }
            if (agentsTab) {
                error?.let { item(key = "error") { Callout(it, Tone.Danger) } }
                agentRows(profiles, pairs, today, onOpenAgent, { container.ui.homeSheet.value = "agent" }, onOpenProactive, onOpenSession)
                return@LazyColumn
            }
            if (power && !empty) item(key = "filters") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(stringResource(R.string.r_home_all, sessions.size), filter == HomeFilter.ALL) { container.ui.homeFilter.value = HomeFilter.ALL }
                    FilterChip(stringResource(R.string.r_home_needs, needs.size), filter == HomeFilter.NEEDS) { container.ui.homeFilter.value = HomeFilter.NEEDS }
                    FilterChip(stringResource(R.string.r_home_running, running.size), filter == HomeFilter.RUNNING) { container.ui.homeFilter.value = HomeFilter.RUNNING }
                    if (queued.isNotEmpty()) FilterChip(stringResource(R.string.r_home_queued, queued.size), filter == HomeFilter.QUEUED) { container.ui.homeFilter.value = HomeFilter.QUEUED }
                }
            }
            error?.let { item(key = "error") { Callout(it, Tone.Danger) } }
            if (empty) item(key = "tasks-empty") { NoTasks { starter -> container.ui.saveDraft(starter) } }
            shown.forEach { (heading, list) ->
                if (heading != null && list.isNotEmpty()) item(key = "h-$heading") {
                    Text(heading, Modifier.padding(start = 4.dp, top = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = P.sub)
                }
                items(list, key = { it.id }) { s -> androidx.compose.foundation.layout.Box(inkItem()) {
                    TaskRow(
                        s, power, subagents[s.id] ?: 0,
                        approval = approvals.firstOrNull { it.sessionId == s.id },
                        question = questions.any { it.sessionId == s.id },
                        onOpen = { onOpenSession(s.id) },
                        onDecide = { request, decision -> container.approvals.resolve(request.id, decision) },
                    )
                } }
            }
        }

        MenuScrim(menu) { menu = false }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { composerHeight = with(density) { it.height.toDp() } }, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (prefs.leftHanded) MenuButton(menu, { menu = !menu })
                val toAgent = if (agentsTab) defaultAgent else null
                HomeComposer(
                    Modifier.weight(1f),
                    dimmed = menu,
                    hint = if (agentsTab) stringResource(R.string.ag_send_to, defaultAgent?.name ?: stringResource(R.string.ag_default_name)) else null,
                    agent = toAgent,
                    onAccounts = onAccounts,
                    onSend = { text ->
                        scope.launch {
                            try {
                                if (agentsTab) {
                                    // The Agents tab talks to the default agent (made on first need) and opens its chat.
                                    val agent = defaultAgent ?: container.agents.ensureDefault()
                                    container.capsule.screen.send(text, sessionId = null, projectId = null, fromCapsule = false, toAgent = agent.id)
                                    error = null
                                    onOpenAgent(agent.id)
                                } else {
                                    val id = container.capsule.screen.send(text, sessionId = null, projectId = container.ui.homeProject.value, fromCapsule = false)
                                    error = null
                                    onOpenSession(id)
                                }
                            } catch (e: Exception) {
                                error = e.message ?: e::class.simpleName
                            }
                        }
                    },
                )
                if (!prefs.leftHanded) MenuButton(menu, { menu = !menu })
            }
            Column(
                Modifier.align(if (prefs.leftHanded) Alignment.BottomStart else Alignment.BottomEnd).padding(bottom = 68.dp),
                horizontalAlignment = if (prefs.leftHanded) Alignment.Start else Alignment.End,
            ) {
                MenuEntries(menu, entries.map { e -> MenuEntry(e.icon, e.label) { menu = false; e.onClick() } }, prefs.leftHanded, Modifier.padding(bottom = 10.dp))
                MenuPills(
                    menu,
                    coding = MenuEntry(PIcons.terminal, stringResource(R.string.ag_pill_coding)) { menu = false; container.ui.homeSheet.value = "coding" },
                    agent = MenuEntry(PIcons.bot, stringResource(R.string.ag_pill_agent)) { menu = false; container.ui.homeSheet.value = "agent" },
                    leftHanded = prefs.leftHanded,
                )
            }
        }
    }
    when (sheet) {
        "agent" -> app.umbra.agent.ui.agents.NewAgentSheet(
            onDismiss = { container.ui.homeSheet.value = null },
            onCreated = { id -> container.ui.homeSheet.value = null; container.settings.update { it.copy(homeAgents = true) }; onOpenAgent(id) },
            onAccounts = { container.ui.homeSheet.value = null; onAccounts() },
        )
        "coding" -> app.umbra.agent.ui.agents.NewCodingSheet(
            onDismiss = { container.ui.homeSheet.value = null },
            onStarted = { id -> container.ui.homeSheet.value = null; onOpenSession(id) },
            onAccounts = { container.ui.homeSheet.value = null; onAccounts() },
            onComputers = { container.ui.homeSheet.value = null; onComputers() },
        )
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Text(
        label,
        Modifier.clip(shape).background(if (on) P.accent3 else pc(0xFFF6F8F2)).then(if (on) Modifier else Modifier.border(1.dp, P.line, shape))
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = P.ink,
    )
}

/** One task: its companion, title, what it is doing, when; what it waits for, answerable right here. */
@Composable
private fun TaskRow(s: Session, power: Boolean, subagents: Int, approval: ApprovalRequest?, question: Boolean, onOpen: () -> Unit, onDecide: (ApprovalRequest, ApprovalDecision) -> Unit) {
    val container = LocalContext.current.container
    val needsYou = approval != null || question
    val shape = RoundedCornerShape(22.dp)
    val step = s.plan.indexOfFirst { it.status == "active" }.takeIf { it >= 0 }
    val status = when {
        approval != null -> approval.description
        question -> stringResource(R.string.cap_question)
        s.state == SessionState.FAILED -> s.lastError ?: stateText(s)
        s.state == SessionState.RUNNING && step != null -> s.plan[step].text + " · " + stringResource(R.string.r_home_step, step + 1, s.plan.size)
        else -> stateText(s)
    }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(pc(0xFFF6F8F2))
            .then(if (needsYou) Modifier.border(1.dp, P.amber.copy(alpha = 0.5f), shape) else Modifier)
            .clickable(role = Role.Button, onClick = onOpen).padding(16.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TaskCompanion(s, needsYou)
            Column(Modifier.weight(1f)) {
                Text(s.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    status, Modifier.padding(top = 2.dp), fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = when { needsYou -> P.amber; s.state == SessionState.FAILED -> P.danger; else -> P.sub },
                )
                if (power) {
                    val project = s.projectId?.let { container.projects[it]?.name }
                    val meta = listOfNotNull(project, s.model?.modelId, if (s.plan.isNotEmpty()) "${s.plan.count { it.status == "done" }}/${s.plan.size}" else null, if (subagents > 0) stringResource(R.string.ses_home_subagents, subagents) else null).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, Modifier.padding(top = 2.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(whenText(s), fontSize = 12.sp, color = P.muted)
        }
        if (approval != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PButton(stringResource(R.string.r_home_see), onOpen, Modifier.weight(1f), style = ButtonStyle.Ghost)
                PButton(stringResource(R.string.appr_deny), { onDecide(approval, ApprovalDecision.Denied("denied from Home")) }, Modifier.weight(1f), style = ButtonStyle.Alt)
                if (approveGesture(approval.assessment.level, ApprovalSurface.CAPSULE) == ApproveGesture.ONE_TAP) {
                    PButton(stringResource(R.string.r_allow), { onDecide(approval, ApprovalDecision.Approved()) }, Modifier.weight(1f))
                }
            }
        } else if (question) {
            PButton(stringResource(R.string.r_home_answer), onOpen, Modifier.fillMaxWidth(), style = ButtonStyle.Alt)
        }
    }
}

/** "now", the elapsed time of a running task, the time of day, "yesterday", or the date. */
@Composable
private fun whenText(s: Session): String {
    val now = System.currentTimeMillis()
    val context = LocalContext.current
    if (s.state == SessionState.RUNNING) return app.umbra.agent.capsule.elapsedText(now - s.createdAtMs)
    val at = s.updatedAtMs
    return when {
        now - at < 60_000 -> stringResource(R.string.r_now)
        DateUtils.isToday(at) -> DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME)
        DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> stringResource(R.string.r_yesterday)
        else -> DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
    }
}
