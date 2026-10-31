package app.umbra.agent.ui.agents

import app.umbra.agent.ui.porcelain.inkItem
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import app.umbra.agent.ui.current.scrollToEnd
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.agents.AgentProfile
import app.umbra.agent.agents.AgentRuntime
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIconButton
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.porcelain.ThinkingOrb
import app.umbra.agent.ui.porcelain.ThinkingOrbActivity
import app.umbra.agent.ui.porcelain.ThinkingOrbState
import app.umbra.agent.ui.porcelain.thinkingOrbState
import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.rememberPolled
import app.umbra.core.orchestrator.Block
import app.umbra.core.orchestrator.ChatItem
import app.umbra.core.orchestrator.ConversationState
import app.umbra.core.orchestrator.SessionBus
import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.session.SessionState
import app.umbra.runtime.omp.ContentBlock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One line in an agent's chat, as the Telegram-like view shows it. */
internal sealed interface Line {
    val key: String
    val at: Long

    data class Mine(override val key: String, override val at: Long, val text: String, val via: String?) : Line
    data class Theirs(override val key: String, override val at: Long, val text: String) : Line
    data class Note(override val key: String, override val at: Long, val text: String, val sessionRef: String? = null) : Line
    data class Report(override val key: String, override val at: Long, val who: String, val state: String, val title: String, val minutes: String, val body: String, val sessionRef: String? = null) : Line
    data class Peer(override val key: String, override val at: Long, val title: String, val body: String, val sessionRef: String? = null) : Line
    /** A card the agent showed (an intelligent card), drawn in its own design. */
    data class Card(override val key: String, override val at: Long, val call: app.umbra.core.orchestrator.Block.ToolCall, val pending: Boolean = false) : Line
    /** A live dashboard the agent started, as a link to it. */
    data class Dashboard(override val key: String, override val at: Long, val id: String, val title: String) : Line
    data class Day(override val key: String, override val at: Long, val day: LocalDate) : Line
}

/**
 * The agent's conversation turned into chat lines: the user's messages on the right, the agent's words on the left,
 * what the agent did with its tools as small notes, coding sessions' reports as cards; wake digests, "[quiet]" turns
 * and tool chatter left out.
 */
internal fun chatLines(state: ConversationState, toolNote: (String, Map<String, String>) -> String?): List<Line> {
    val out = ArrayList<Line>()
    var lastDay: LocalDate? = null
    fun day(at: Long) {
        val d = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
        if (d != lastDay) { out += Line.Day("d$d", at, d); lastDay = d }
    }
    for (item in state.items) {
        when (item) {
            is ChatItem.User -> {
                if (item.text.startsWith("[Wake")) continue
                day(item.timestamp)
                val t = item.text
                val bus = SessionBus.HEADER.find(t)
                when {
                    bus != null && bus.groupValues[1].startsWith("Report") -> {
                        val head = t.substringBefore('\n').removePrefix("[").removeSuffix("]").split(" · ")
                        out += Line.Report(item.key, item.timestamp, bus.groupValues[4], head.getOrNull(1).orEmpty(), head.getOrNull(2).orEmpty(), head.getOrNull(3).orEmpty(),
                            t.substringAfter('\n').lines().filterNot { it.startsWith("(This is a coding session") || it.startsWith("[Session: ") }.joinToString("\n").trim(),
                            sessionReference(t))
                    }
                    bus != null -> out += Line.Peer(item.key, item.timestamp, bus.groupValues[0].trim().removePrefix("[").removeSuffix("]"), t.removeRange(bus.range).lines().filterNot { it.startsWith("(If an answer") }.joinToString("\n").trim(), bus.groupValues[4])
                    t.startsWith("[Telegram] [Card · ") && "] " in t.removePrefix("[Telegram] ") -> t.removePrefix("[Telegram] ").let { c ->
                        out += Line.Mine(item.key, item.timestamp, c.substringAfter("] "), "Telegram · " + c.substringBefore("] ").removePrefix("["))
                    }
                    t.startsWith("[Telegram] ") -> out += Line.Mine(item.key, item.timestamp, t.removePrefix("[Telegram] "), "Telegram")
                    // An answer given on a card: the answer, labelled with the card it came from (the agent still gets the prefix).
                    t.startsWith("[Card · ") && "] " in t -> out += Line.Mine(item.key, item.timestamp, t.substringAfter("] "), t.substringBefore("] ").removePrefix("["))
                    t.startsWith("The conversation of the agent") -> Unit
                    else -> out += Line.Mine(item.key, item.timestamp, t, null)
                }
            }
            is ChatItem.Assistant -> {
                for (b in item.blocks) {
                    when (b) {
                        is Block.Text -> {
                            val text = b.text.trim()
                            if (text.isEmpty() || text.startsWith(AgentRuntime.QUIET)) continue
                            day(item.timestamp)
                            out += Line.Theirs(item.key + "/" + out.size, item.timestamp, text)
                        }
                        is Block.ToolCall -> {
                            if (b.name == app.umbra.agent.cards.CardCalls.SHOW) {
                                // A refused card (over the limit, or bad data) is not drawn; the agent answers in text.
                                if (state.tools[b.id]?.isError != true) { day(item.timestamp); out += Line.Card(item.key + "/" + b.id, item.timestamp, b, pending = state.tools[b.id] == null) }
                                continue
                            }
                            if (b.name == "dashboard_create") {
                                val created = state.tools[b.id]?.takeIf { !it.isError }?.result?.let { Regex("^Created ([A-Za-z0-9_-]+)\\.").find(it)?.groupValues?.get(1) }
                                if (created != null) {
                                    day(item.timestamp)
                                    out += Line.Dashboard(item.key + "/" + b.id, item.timestamp, created, (b.arguments["title"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                                    continue
                                }
                            }
                            val args = b.arguments.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }
                            val result = state.tools[b.id]
                            val ref = when (b.name) {
                                "coding_start" -> result?.takeIf { !it.isError }?.result?.let(::sessionReference)
                                    ?: result?.takeIf { !it.isError }?.result?.let { Regex("coding session (#[a-z0-9-]+)").find(it)?.groupValues?.get(1) }
                                "session_send" -> args["to"]
                                "subagent_start" -> result?.takeIf { !it.isError }?.result?.let { Regex("session ([a-zA-Z0-9-]+)").find(it)?.groupValues?.get(1) }
                                else -> null
                            }
                            if (b.name != "coding_start" || ref != null) toolNote(b.name, args)?.let {
                                day(item.timestamp); out += Line.Note(item.key + "/" + b.id, item.timestamp, it, ref)
                            }
                        }
                        else -> Unit
                    }
                }
            }
            else -> Unit
        }
    }
    return out
}

internal fun sessionReference(text: String): String? = Regex("""\[Session: ([a-zA-Z0-9-]+)]""").find(text)?.groupValues?.get(1)

@Composable
internal fun rememberToolNote(): (String, Map<String, String>) -> String? {
    val remembered = stringResource(R.string.ag_note_remembered)
    val timer = stringResource(R.string.ag_note_timer)
    val notified = stringResource(R.string.ag_note_notified)
    val coding = stringResource(R.string.ag_note_coding)
    val queued = stringResource(R.string.ag_note_queued)
    val rule = stringResource(R.string.ag_note_rule)
    val sent = stringResource(R.string.ag_note_sent)
    return { name, a ->
        when (name) {
            "memory_add" -> "$remembered: ${a["text"].orEmpty()}"
            "timer_set" -> "$timer: ${a["note"].orEmpty()} (${a["at"]?.takeIf { it.isNotBlank() } ?: a["cron"]?.takeIf { it.isNotBlank() } ?: a["in_minutes"]?.let { "+$it min" } ?: ""})"
            "notify_user" -> "$notified: ${a["text"].orEmpty()}"
            "coding_start" -> "$coding: ${a["title"]?.takeIf { it.isNotBlank() } ?: a["task"].orEmpty().take(80)}"
            "queue_add" -> "$queued: ${a["text"].orEmpty()}"
            "rule_add" -> "$rule: ${a["then"].orEmpty()}"
            "session_send" -> "$sent ${a["to"].orEmpty()}: ${a["message"].orEmpty().take(120)}"
            else -> null
        }
    }
}

/**
 * An agent's chat, like Telegram (docs/design/agents-2026-10.md §1, Figma 11.1): its face and state on top, the next
 * timers pinned, the conversation as bubbles, what the agent asks you to approve right under its message, a field
 * with pictures at the bottom.
 */
@Composable
fun AgentChatScreen(agentId: String, onBack: () -> Unit, onProfile: () -> Unit, onOpenSession: (String) -> Unit, onProactive: (Int) -> Unit,
    onDashboards: () -> Unit = {}, onDashboard: (String) -> Unit = {}) {
    val editPrefix = stringResource(R.string.ag_edit_prefix)
    val container = LocalContext.current.container
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by container.agents.store.profiles.collectAsStateWithLifecycle()
    val p = profiles.firstOrNull { it.id == agentId }
    if (p == null) {
        app.umbra.agent.ui.shell.PorcelainPage(stringResource(R.string.ag_title), onBack) { app.umbra.agent.ui.porcelain.PEmpty(stringResource(R.string.ag_gone), "", icon = PIcons.bot) }
        return
    }
    val sid = p.sessionId
    val flow by produceState<kotlinx.coroutines.flow.StateFlow<ConversationState>?>(null, sid) { value = sid?.let { runCatching { container.supervisor.conversation(it) }.getOrNull() } }
    val conversation = flow?.collectAsStateWithLifecycle()?.value ?: ConversationState()
    val sessions by remember { container.sessionStore.changes }.collectAsStateWithLifecycle(null)
    val session by produceState<app.umbra.core.session.Session?>(null, sid, sessions) { value = sid?.let { container.sessionStore.get(it) } }
    val delegated by produceState<app.umbra.core.session.Session?>(null, agentId, sessions) { value = activeDelegatedWork(agentId, container.sessionStore.list()) }
    val work by produceState(emptyList<app.umbra.core.session.Session>(), agentId, sid, sessions) {
        value = agentWork(agentId, sid, container.sessionStore.list(app.umbra.core.session.SessionFilter(limit = Int.MAX_VALUE)))
    }
    val approvals by container.approvals.pending.collectAsStateWithLifecycle()
    // Questions this agent, or a session it started, is waiting on: shown in its chat to answer in place.
    val allQuestions by container.questions.pending.collectAsStateWithLifecycle()
    val questions by produceState(emptyList<app.umbra.core.orchestrator.QuestionBroker.Pending>(), allQuestions, agentId) {
        value = allQuestions.filter { app.umbra.agent.ui.questions.agentOf(container, it.sessionId) == agentId }
    }
    val askers by produceState(emptyMap<String, String?>(), questions) {
        value = questions.associate { it.sessionId to app.umbra.agent.ui.questions.askerOf(container, it.sessionId) }
    }
    val mine = approvals.filter { it.sessionId == sid }
    val activity by container.agents.activity.collectAsStateWithLifecycle()
    val timers by rememberPolled(emptyList<app.umbra.agent.agents.AgentTimer>(), agentId, periodMs = 3_000) { container.agents.store.timers(agentId).sortedBy { it.nextMs } }
    val state by rememberPolled(app.umbra.agent.agents.AgentState(), agentId, periodMs = 5_000) { container.agents.store.state(agentId) }
    val toolNote = rememberToolNote()
    val lines = remember(conversation.items, conversation.tools, toolNote) { chatLines(conversation, toolNote) }
    // Sent but not yet in the agent's conversation (its process may be starting): shown at once, with a clock.
    val sending = remember(agentId) { androidx.compose.runtime.mutableStateListOf<Pair<Long, String>>() }
    LaunchedEffect(lines) {
        val seen = lines.filterIsInstance<Line.Mine>()
        sending.removeAll { (at, text) -> seen.any { it.at >= at - 5_000 && it.text.trim() == text } || System.currentTimeMillis() - at > 180_000 }
    }
    var draft by remember(agentId) { mutableStateOf("") }
    val pictures = remember(agentId) { mutableStateListOf<ContentBlock.Image>() }
    var error by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()

    // While the chat is on screen its messages are read, and need no notification.
    DisposableEffect(agentId) {
        container.agents.onScreen.value = agentId
        container.agents.markRead(agentId)
        onDispose { if (container.agents.onScreen.value == agentId) container.agents.onScreen.value = null }
    }
    // Follows new messages and the keyboard opening while the list is at its end (rememberStickToBottom).
    val stick by app.umbra.agent.ui.current.rememberStickToBottom(list)
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && stick) list.scrollToEnd(animate = true)
        container.agents.markRead(agentId)
    }
    // A new question for the user (an approval, or the agent's own question) is brought into view with its buttons,
    // wherever the list was.
    var approvalsSeen by remember(agentId) { mutableIntStateOf(0) }
    LaunchedEffect(mine.size + questions.size) {
        if (mine.size + questions.size > approvalsSeen) list.scrollToEnd(animate = true)
        approvalsSeen = mine.size + questions.size
    }
    androidx.activity.compose.BackHandler(onBack = onBack)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        scope.launch { uris.forEach { u -> app.umbra.agent.ui.current.encodePicture(context, u)?.let { pictures += it } } }
    }
    val ownWork = session?.state == SessionState.RUNNING || activity[agentId] != null
    val working = p.running && (ownWork || delegated != null)
    val orbState = if (!ownWork && delegated != null) ThinkingOrbState.WORKING else thinkingOrbState(conversation, activity[agentId] == "triage")
    val orbPaused = !ownWork && delegated?.state != SessionState.RUNNING
    val status = when {
        !p.running -> stringResource(R.string.ag_status_stopped)
        // omp waits the same way for a question as for an approval; the words say which one the user owes.
        session?.state == SessionState.WAITING_APPROVAL -> stringResource(if (questions.any { it.sessionId == session?.id }) R.string.ag_work_answer else R.string.ag_work_approval)
        session?.state == SessionState.PAUSED -> stringResource(R.string.ag_work_paused)
        ownWork -> currentAgentWork(session, conversation, activity[agentId])
        delegated != null -> stringResource(R.string.ag_work_delegated, delegated!!.title)
        state.nextWakeMs > 0 -> stringResource(R.string.ag_status_asleep, DateUtils.formatDateTime(context, state.nextWakeMs, DateUtils.FORMAT_SHOW_TIME))
        else -> stringResource(R.string.ag_status_on)
    }

    Column(Modifier.fillMaxSize().background(P.page).imePadding()) {
        // Top: back, face, name and state; a tap opens the agent's page.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PIconButton(PIcons.chevronLeft, stringResource(R.string.action_back), onBack, size = 36.dp)
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onProfile).padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentAvatar(p.avatar, p.name, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (working) ThinkingOrb(status, state = orbState, paused = orbPaused)
                        Text(status, Modifier.weight(1f), fontSize = 12.sp, color = if (working) P.good else P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            PIconButton(PIcons.more, stringResource(R.string.ag_profile), onProfile, size = 36.dp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            app.umbra.agent.ui.porcelain.PButton(stringResource(R.string.briefing_title), { onProactive(2) }, tiny = true, style = app.umbra.agent.ui.porcelain.ButtonStyle.Ghost, icon = PIcons.clock)
            val dashboards = app.umbra.agent.ui.dashboards.dashboardCount(agentId)
            if (dashboards > 0) app.umbra.agent.ui.porcelain.PButton(stringResource(R.string.dash_button, dashboards), onDashboards, tiny = true, style = app.umbra.agent.ui.porcelain.ButtonStyle.Ghost, icon = PIcons.dashboard)
        }
        if (work.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.ag_active_work, work.size), color = P.sub, fontSize = 12.sp)
            work.forEach { task ->
                app.umbra.agent.ui.porcelain.PButton("${task.title} · ${app.umbra.agent.ui.sessions.stateText(task.state)}", { onOpenSession(task.id) },
                    tiny = true, style = app.umbra.agent.ui.porcelain.ButtonStyle.Ghost)
            }
        }
        // Pinned: the next timers.
        if (timers.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(pc(0xFFF6F8F2)).clickable(role = Role.Button, onClick = onProfile).padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PIcon(PIcons.pin, size = 16.dp, tint = P.accent2)
                Text(
                    stringResource(R.string.ag_pinned_timers) + " " + timers.take(3).joinToString(" · ") { t -> DateUtils.formatDateTime(context, t.nextMs, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY) + " " + t.note },
                    fontSize = 13.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!p.running) Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Callout(stringResource(R.string.ag_stopped_banner, p.name), Tone.Warn)
                app.umbra.agent.ui.porcelain.PButton(stringResource(R.string.ag_start), { scope.launch { container.agents.setRunning(agentId, true) } }, tiny = true)
            }
        }
        // Answers on the agent's cards go to it as the user's own messages.
        val cardHost = remember(agentId) { app.umbra.agent.cards.CardHost(p.sessionId.orEmpty()) { text -> container.agents.talk(agentId, text) } }
        CompositionLocalProvider(app.umbra.agent.cards.LocalCardHost provides cardHost) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (lines.isEmpty()) item(key = "intro") {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AgentAvatar(p.avatar, p.name, 72.dp)
                    Text(stringResource(R.string.ag_intro, p.name), fontSize = 14.sp, color = P.sub, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
            items(lines, key = { it.key }) { line -> androidx.compose.foundation.layout.Box(inkItem(placement = false)) {
                when (line) {
                    is Line.Day -> SysNote(DateUtils.formatDateTime(context, line.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL))
                    is Line.Note -> if (line.sessionRef == null) SysNote(line.text) else SessionEventLink(line.text) {
                        scope.launch { container.sessionBus.resolve(line.sessionRef)?.let { onOpenSession(it.id) } }
                    }
                    is Line.Mine -> Bubble(line.text, line.at, mine = true, via = line.via)
                    is Line.Theirs -> Bubble(line.text, line.at, mine = false, via = null)
                    is Line.Card -> app.umbra.agent.cards.ToolCallCard(line.call, Modifier.padding(end = 20.dp), pending = line.pending)
                    is Line.Dashboard -> app.umbra.agent.ui.dashboards.DashboardChatLink(line.id, line.title, Modifier.padding(end = 48.dp), onOpen = onDashboard)
                    is Line.Peer -> PeerCard(line) { scope.launch { line.sessionRef?.let { container.sessionBus.resolve(it) }?.let { onOpenSession(it.id) } } }
                    is Line.Report -> ReportCard(line, onOpen = {
                        scope.launch {
                            val target = container.sessionBus.resolve(line.sessionRef ?: line.who)
                            target?.let { onOpenSession(it.id) }
                        }
                    })
                }
            } }
            items(sending, key = { "sending-" + it.first }) { (at, text) -> androidx.compose.foundation.layout.Box(inkItem(placement = false)) { Bubble(text, at, mine = true, via = null, pending = true) } }
            items(questions, key = { "question-" + it.request.id }) { q -> androidx.compose.foundation.layout.Box(inkItem(placement = false)) {
                app.umbra.agent.ui.questions.QuestionCard(q, askers[q.sessionId], onAnswer = { container.questions.answer(q.request.id, it) }, Modifier.padding(end = 20.dp))
            } }
            items(mine, key = { "approval-" + it.id }) { request -> androidx.compose.foundation.layout.Box(inkItem(placement = false)) {
                Column(Modifier.fillMaxWidth().padding(end = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // A skill install the user did not pick on the Skills page: say what and where, offer Install or not.
                    if (request.toolName == "skill_install") {
                        val repo = request.details["repo"].orEmpty()
                        val path = request.details["path"].orEmpty().takeIf { it != "." }
                        val target = request.details["target"].orEmpty()
                        val where = if (target == "global") stringResource(R.string.skills_msg_global_plain) else stringResource(R.string.skills_msg_project_plain, target)
                        Bubble(stringResource(R.string.skills_approve_body, path?.substringAfterLast('/') ?: repo.substringAfter('/'), repo, where), request.createdAtMs, mine = false, via = null, accent = true)
                        Row(Modifier.widthIn(max = 300.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            InlineButton(stringResource(R.string.skills_approve_yes), primary = true, Modifier.weight(1f)) { container.approvals.resolve(request.id, ApprovalDecision.Approved()) }
                            InlineButton(stringResource(R.string.ag_dont), primary = false, Modifier.weight(1f)) { container.approvals.resolve(request.id, ApprovalDecision.Denied("not now, from the agent's chat")) }
                        }
                        return@Column
                    }
                    Bubble(request.description, request.createdAtMs, mine = false, via = null, accent = true)
                    Row(Modifier.widthIn(max = 300.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InlineButton(stringResource(R.string.ag_send), primary = true, Modifier.weight(1f)) { container.approvals.resolve(request.id, ApprovalDecision.Approved()) }
                        InlineButton(stringResource(R.string.ag_edit), primary = false, Modifier.weight(1f)) {
                            container.approvals.resolve(request.id, ApprovalDecision.Denied("the user wants to change it"))
                            draft = editPrefix + " "
                        }
                        InlineButton(stringResource(R.string.ag_dont), primary = false, Modifier.weight(1f)) { container.approvals.resolve(request.id, ApprovalDecision.Denied("not now, from the agent's chat")) }
                    }
                }
            } }
            if (working) item(key = "typing") {
                ThinkingOrbActivity(status, orbState, Modifier.fillMaxWidth().padding(vertical = 8.dp), paused = orbPaused)
            }
        }
        }
        error?.let { Box(Modifier.padding(horizontal = 16.dp)) { Callout(it, Tone.Danger) } }
        // The field: pictures, words, send.
        if (pictures.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pictures.forEachIndexed { i, img ->
                Box(Modifier.clickable { pictures.removeAt(i) }) { app.umbra.agent.ui.current.Base64Picture(img.base64, 56) }
            }
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(pc(0xFFF6F8F2)).padding(start = 6.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                app.umbra.agent.ui.current.SessionFileButton(sid,
                    onReference = { draft = listOf(draft, it).filter(String::isNotBlank).joinToString("\n") }, onError = { error = it },
                    onPicture = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                    if (draft.isEmpty()) Text(stringResource(R.string.ag_message_hint), fontSize = 15.sp, color = P.muted)
                    val label = stringResource(R.string.cap_message)
                    BasicTextField(draft, { draft = it }, Modifier.fillMaxWidth().semantics { contentDescription = label }, textStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = P.ink), cursorBrush = SolidColor(P.accent2), maxLines = 6)
                }
            }
            val send = stringResource(R.string.cap_send)
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(P.accent2).clickable(role = Role.Button, enabled = draft.isNotBlank() || pictures.isNotEmpty(), onClickLabel = send) {
                    val text = draft.trim()
                    val imgs = pictures.toList()
                    draft = ""; pictures.clear(); error = null
                    val entry = System.currentTimeMillis() to text.ifEmpty { "(picture)" }
                    sending += entry
                    scope.launch { list.scrollToEnd(animate = true) }
                    scope.launch { runCatching { container.agents.talk(agentId, text.ifEmpty { "(picture)" }, imgs) }.onFailure { error = it.message; draft = text; sending -= entry } }
                }.semantics { contentDescription = send },
                contentAlignment = Alignment.Center,
            ) { PIcon(PIcons.send, size = 22.dp, tint = P.base) }
        }
    }
}

@Composable
private fun SessionEventLink(text: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(P.base2).clickable(role = Role.Button, onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PIcon(PIcons.terminal, size = 16.dp, tint = P.accent2)
        Text(text, Modifier.weight(1f), color = P.accent2, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        PIcon(PIcons.chevronRight, size = 16.dp, tint = P.accent2)
    }
}

@Composable
internal fun SysNote(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
        Text(text, Modifier.clip(RoundedCornerShape(12.dp)).background(P.base2).padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 12.sp, color = P.sub, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Bubble(text: String, at: Long, mine: Boolean, via: String?, accent: Boolean = false, pending: Boolean = false) {
    val context = LocalContext.current
    val shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.padding(start = if (mine) 48.dp else 0.dp, end = if (mine) 0.dp else 48.dp).clip(shape)
                // Ink: the user's words are the ink bubbles, the agent's are paper.
                .background(if (mine) (if (app.umbra.agent.ui.porcelain.Theme.mono) P.accent2 else pc(0xFFDCE8D8)) else if (accent) P.amber.copy(alpha = 0.12f) else pc(0xFFF6F8F2))
                .then(if (accent) Modifier.border(1.dp, P.amber.copy(alpha = 0.4f), shape) else if (!mine && app.umbra.agent.ui.porcelain.Theme.mono) Modifier.border(1.dp, P.line2, shape) else Modifier)
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            val onBubble = if (mine && app.umbra.agent.ui.porcelain.Theme.mono) app.umbra.agent.ui.porcelain.inkOnPrimary else null
            if (mine) Text(text, fontSize = 15.sp, lineHeight = 21.sp, color = onBubble ?: P.ink)
            else app.umbra.agent.ui.MarkdownText(text, color = P.ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (via != null) Text(via, fontSize = 10.5.sp, color = P.muted)
                Text(DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME), fontSize = 11.sp, color = onBubble?.copy(alpha = 0.6f) ?: P.muted)
                if (mine) PIcon(if (pending) PIcons.clock else PIcons.check, size = 13.dp, tint = onBubble?.copy(alpha = 0.75f) ?: P.accent2)
            }
        }
    }
}

@Composable
private fun PeerCard(line: Line.Peer, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(end = 48.dp).clip(RoundedCornerShape(16.dp)).background(pc(0xFFF6F8F2)).clickable(role = Role.Button, onClick = onOpen).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PIcon(PIcons.swap, size = 14.dp, tint = P.accent2)
            Text(line.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = P.accent2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(line.body, fontSize = 14.sp, lineHeight = 20.sp, color = P.ink)
    }
}

@Composable
private fun ReportCard(line: Line.Report, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(end = 48.dp).clip(RoundedCornerShape(16.dp)).background(pc(0xFFF6F8F2)).border(1.dp, P.line, RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onOpen).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PIcon(PIcons.terminal, size = 16.dp, tint = if (line.state == "failed") P.danger else P.good)
            Text(stringResource(if (line.state == "failed") R.string.ag_report_failed else R.string.ag_report_done) + " · " + line.who, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (line.state == "failed") P.danger else P.good)
        }
        if (line.title.isNotBlank()) Text(line.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.ink)
        Text(line.body.take(600), fontSize = 13.sp, lineHeight = 19.sp, color = P.sub, maxLines = 6, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.minutes, Modifier.weight(1f), fontSize = 12.sp, color = P.muted)
            Text(stringResource(R.string.cap_open), Modifier.clip(RoundedCornerShape(14.dp)).background(P.accent3).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = P.accent2)
        }
    }
}

@Composable
private fun InlineButton(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(if (primary) P.accent2 else pc(0xFFF6F8F2))
            .then(if (primary) Modifier else Modifier.border(1.dp, P.line, RoundedCornerShape(10.dp)))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (primary) P.base else P.ink) }
}

/** What the agent list shows for an agent: its last words and how many the user has not read. */
/** The first line of a Markdown answer as a list preview reads it: no heading marks, emphasis or code ticks. */
internal fun plainPreview(text: String): String {
    var fenced = false
    val line = text.lineSequence().map { it.trim() }.firstOrNull { l ->
        if (l.startsWith("```")) { fenced = !fenced; false } else !fenced && l.isNotEmpty()
    }.orEmpty()
    return line.replace(Regex("^(#{1,6}\\s+|>\\s*|[-*+]\\s+|\\d+[.)]\\s+)"), "")
        .replace(Regex("(\\*\\*|__|`)"), "")
}

internal fun lastLine(state: ConversationState, lastReadMs: Long): Pair<String, Int> {
    var last = ""
    var unread = 0
    for (item in state.items) {
        when (item) {
            is ChatItem.User -> if (!item.text.startsWith("[Wake") && !item.text.startsWith("The conversation of the agent")) last = item.text.removePrefix("[Telegram] ").lineSequence().first()
            is ChatItem.Assistant -> {
                val t = item.text.trim()
                if (t.isNotEmpty() && !t.startsWith(AgentRuntime.QUIET)) {
                    last = plainPreview(t)
                    if (item.timestamp > lastReadMs) unread++
                }
            }
            else -> Unit
        }
    }
    return last to unread
}

/** The agent's face with its state for a row: whether it is working, its next wake. */
internal data class AgentStatus(val label: String, val working: Boolean, val orb: ThinkingOrbState, val paused: Boolean)

@Composable
internal fun agentStatus(p: AgentProfile, conversation: ConversationState): AgentStatus {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val state by rememberPolled(app.umbra.agent.agents.AgentState(), p.id, periodMs = 10_000) { container.agents.store.state(p.id) }
    val changes by container.sessionStore.changes.collectAsStateWithLifecycle(null)
    val session by produceState<app.umbra.core.session.Session?>(null, p.sessionId, changes) { value = p.sessionId?.let { container.sessionStore.get(it) } }
    val activities by container.agents.activity.collectAsStateWithLifecycle()
    val delegated by produceState<app.umbra.core.session.Session?>(null, p.id, changes) { value = activeDelegatedWork(p.id, container.sessionStore.list()) }
    val ownWork = session?.state == SessionState.RUNNING || activities[p.id] != null
    val pendingQuestions by container.questions.pending.collectAsStateWithLifecycle()
    val label = when {
        !p.running -> stringResource(R.string.ag_status_stopped)
        session?.state == SessionState.WAITING_APPROVAL -> stringResource(if (pendingQuestions.any { it.sessionId == p.sessionId }) R.string.ag_work_answer else R.string.ag_work_approval)
        session?.state == SessionState.PAUSED -> stringResource(R.string.ag_work_paused)
        ownWork -> currentAgentWork(session, conversation, activities[p.id])
        delegated != null -> stringResource(R.string.ag_work_delegated, delegated!!.title)
        state.nextWakeMs > 0 -> stringResource(R.string.ag_next_wake, DateUtils.formatDateTime(context, state.nextWakeMs, DateUtils.FORMAT_SHOW_TIME))
        else -> stringResource(R.string.ag_status_on)
    }
    return AgentStatus(label, p.running && (ownWork || delegated != null),
        if (!ownWork && delegated != null) ThinkingOrbState.WORKING else thinkingOrbState(conversation, activities[p.id] == "triage"),
        !ownWork && delegated?.state != SessionState.RUNNING)
}

@Composable
internal fun currentAgentWork(session: app.umbra.core.session.Session?, conversation: ConversationState?, loop: String?): String {
    if (loop == "triage") return stringResource(R.string.ag_work_review)
    session?.activity?.takeIf { it.isNotBlank() }?.let { return it }
    val tool = conversation?.tools?.values?.lastOrNull { it.running }?.name
    return stringResource(when {
        tool == "coding_start" || tool?.startsWith("session_") == true -> R.string.ag_work_coordinate
        tool?.startsWith("memory_") == true -> R.string.ag_work_memory
        tool?.startsWith("timer_") == true || tool?.startsWith("rule_") == true || tool?.startsWith("queue_") == true -> R.string.ag_work_organize
        tool?.startsWith("android_") == true -> R.string.ag_work_apps
        tool == "read" || tool == "grep" || tool?.startsWith("messages_") == true -> R.string.ag_work_review
        else -> R.string.ag_work_response
    })
}

internal fun activeDelegatedWork(agentId: String, sessions: List<app.umbra.core.session.Session>): app.umbra.core.session.Session? =
    sessions.filter { it.kind == app.umbra.core.session.SessionKind.CODING && it.agentId == agentId && !it.state.isTerminal }
        .sortedWith(compareByDescending<app.umbra.core.session.Session> { it.state == SessionState.RUNNING }.thenByDescending { it.createdAtMs }).firstOrNull()
