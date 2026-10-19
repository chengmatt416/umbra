package app.umbra.agent.ui.agents

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import app.umbra.agent.ui.porcelain.pc
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
import kotlinx.coroutines.delay
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
    data class Day(override val key: String, override val at: Long, val day: LocalDate) : Line
}

/**
 * The agent's conversation turned into chat lines: the user's messages on the right, the agent's words on the left,
 * wakes and what the agent did with its tools as small notes, coding sessions' reports as cards; "[quiet]" turns and
 * tool chatter left out.
 */
internal fun chatLines(state: ConversationState, wakeNote: (String) -> String, toolNote: (String, Map<String, String>) -> String?): List<Line> {
    val out = ArrayList<Line>()
    var lastDay: LocalDate? = null
    fun day(at: Long) {
        val d = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
        if (d != lastDay) { out += Line.Day("d$d", at, d); lastDay = d }
    }
    for (item in state.items) {
        when (item) {
            is ChatItem.User -> {
                day(item.timestamp)
                val t = item.text
                val bus = SessionBus.HEADER.find(t)
                when {
                    t.startsWith("[Wake") -> out += Line.Note(item.key, item.timestamp, wakeNote(t))
                    bus != null && bus.groupValues[1].startsWith("Report") -> {
                        val head = t.substringBefore('\n').removePrefix("[").removeSuffix("]").split(" · ")
                        out += Line.Report(item.key, item.timestamp, bus.groupValues[4], head.getOrNull(1).orEmpty(), head.getOrNull(2).orEmpty(), head.getOrNull(3).orEmpty(),
                            t.substringAfter('\n').lines().filterNot { it.startsWith("(This is a coding session") || it.startsWith("[Session: ") }.joinToString("\n").trim(),
                            sessionReference(t))
                    }
                    bus != null -> out += Line.Peer(item.key, item.timestamp, bus.groupValues[0].trim().removePrefix("[").removeSuffix("]"), t.removeRange(bus.range).lines().filterNot { it.startsWith("(If an answer") }.joinToString("\n").trim(), bus.groupValues[4])
                    t.startsWith("[Telegram] ") -> out += Line.Mine(item.key, item.timestamp, t.removePrefix("[Telegram] "), "Telegram")
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

/** "Woke up · heartbeat · 2 messages, 1 timer" from a wake digest. */
@Composable
internal fun rememberWakeNote(): (String) -> String {
    val woke = stringResource(R.string.ag_note_woke)
    return { digest ->
        val reason = Regex("""\[Wake · [^·]+ · ([^\]]+)\]""").find(digest)?.groupValues?.get(1).orEmpty()
        val counts = listOf("Timers due:", "Rules that fired:", "New messages:", "New notifications:", "The user asked you to do").mapNotNull { h ->
            val i = digest.indexOf(h)
            if (i < 0) null else {
                val n = digest.substring(i).lines().drop(1).takeWhile { it.startsWith("- ") || it.startsWith("  ") }.count { it.startsWith("- ") }
                when (h) { "Timers due:" -> "$n ⏰"; "Rules that fired:" -> "$n ⚡"; "New messages:" -> "$n ✉"; "New notifications:" -> "$n 🔔"; else -> "$n ☰" }
            }
        }
        listOf(woke, reason).filter { it.isNotBlank() }.joinToString(" · ") + if (counts.isEmpty()) "" else " · " + counts.joinToString(" ")
    }
}

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
fun AgentChatScreen(agentId: String, onBack: () -> Unit, onProfile: () -> Unit, onOpenSession: (String) -> Unit) {
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
    val approvals by container.approvals.pending.collectAsStateWithLifecycle()
    val mine = approvals.filter { it.sessionId == sid }
    val activity by container.agents.activity.collectAsStateWithLifecycle()
    val timers by produceState(emptyList<app.umbra.agent.agents.AgentTimer>(), agentId) { while (true) { value = container.agents.store.timers(agentId).sortedBy { it.nextMs }; delay(3_000) } }
    val state by produceState(app.umbra.agent.agents.AgentState(), agentId) { while (true) { value = container.agents.store.state(agentId); delay(5_000) } }
    val wakeNote = rememberWakeNote()
    val toolNote = rememberToolNote()
    val lines = remember(conversation.items, conversation.tools, wakeNote, toolNote) { chatLines(conversation, wakeNote, toolNote) }
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
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size + mine.size) ; container.agents.markRead(agentId) }
    androidx.activity.compose.BackHandler(onBack = onBack)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        scope.launch { uris.forEach { u -> app.umbra.agent.ui.current.encodePicture(context, u)?.let { pictures += it } } }
    }
    val ownWork = session?.state == SessionState.RUNNING || activity[agentId] != null
    val working = ownWork || delegated != null
    val status = when {
        !p.running -> stringResource(R.string.ag_status_stopped)
        session?.state == SessionState.WAITING_APPROVAL -> stringResource(R.string.ag_work_approval)
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
                    Text(status, fontSize = 12.sp, color = if (working) P.good else P.sub, maxLines = 1)
                }
            }
            PIconButton(PIcons.more, stringResource(R.string.ag_profile), onProfile, size = 36.dp)
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
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (lines.isEmpty()) item(key = "intro") {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AgentAvatar(p.avatar, p.name, 72.dp)
                    Text(stringResource(R.string.ag_intro, p.name), fontSize = 14.sp, color = P.sub, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
            items(lines, key = { it.key }) { line ->
                when (line) {
                    is Line.Day -> SysNote(DateUtils.formatDateTime(context, line.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL))
                    is Line.Note -> if (line.sessionRef == null) SysNote(line.text) else SessionEventLink(line.text) {
                        scope.launch { container.sessionBus.resolve(line.sessionRef)?.let { onOpenSession(it.id) } }
                    }
                    is Line.Mine -> Bubble(line.text, line.at, mine = true, via = line.via)
                    is Line.Theirs -> Bubble(line.text, line.at, mine = false, via = null)
                    is Line.Peer -> PeerCard(line) { scope.launch { line.sessionRef?.let { container.sessionBus.resolve(it) }?.let { onOpenSession(it.id) } } }
                    is Line.Report -> ReportCard(line, onOpen = {
                        scope.launch {
                            val target = container.sessionBus.resolve(line.sessionRef ?: line.who)
                            target?.let { onOpenSession(it.id) }
                        }
                    })
                }
            }
            items(mine, key = { "approval-" + it.id }) { request ->
                Column(Modifier.fillMaxWidth().padding(end = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            }
            if (working) item(key = "typing") {
                Row(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    app.umbra.agent.ui.porcelain.ThinkingOrb(status)
                    Text(status, fontSize = 12.sp, color = P.sub)
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
                    scope.launch { runCatching { container.agents.talk(agentId, text.ifEmpty { "(picture)" }, imgs) }.onFailure { error = it.message; draft = text } }
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
private fun Bubble(text: String, at: Long, mine: Boolean, via: String?, accent: Boolean = false) {
    val context = LocalContext.current
    val shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.padding(start = if (mine) 48.dp else 0.dp, end = if (mine) 0.dp else 48.dp).clip(shape)
                .background(if (mine) pc(0xFFDCE8D8) else if (accent) P.amber.copy(alpha = 0.12f) else pc(0xFFF6F8F2))
                .then(if (accent) Modifier.border(1.dp, P.amber.copy(alpha = 0.4f), shape) else Modifier)
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (mine) Text(text, fontSize = 15.sp, lineHeight = 21.sp, color = P.ink)
            else app.umbra.agent.ui.MarkdownText(text, color = P.ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (via != null) Text(via, fontSize = 10.5.sp, color = P.muted)
                Text(DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME), fontSize = 11.sp, color = P.muted)
                if (mine) PIcon(PIcons.check, size = 13.dp, tint = P.accent2)
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
internal fun lastLine(state: ConversationState, lastReadMs: Long): Pair<String, Int> {
    var last = ""
    var unread = 0
    for (item in state.items) {
        when (item) {
            is ChatItem.User -> if (!item.text.startsWith("[Wake") && !item.text.startsWith("The conversation of the agent")) last = item.text.removePrefix("[Telegram] ").lineSequence().first()
            is ChatItem.Assistant -> {
                val t = item.text.trim()
                if (t.isNotEmpty() && !t.startsWith(AgentRuntime.QUIET)) {
                    last = t.lineSequence().first()
                    if (item.timestamp > lastReadMs) unread++
                }
            }
            else -> Unit
        }
    }
    return last to unread
}

/** The agent's face with its state for a row: whether it is working, its next wake. */
@Composable
internal fun agentStatus(p: AgentProfile): String {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val state by produceState(app.umbra.agent.agents.AgentState(), p.id) { while (true) { value = container.agents.store.state(p.id); delay(10_000) } }
    val changes by container.sessionStore.changes.collectAsStateWithLifecycle(null)
    val session by produceState<app.umbra.core.session.Session?>(null, p.sessionId, changes) { value = p.sessionId?.let { container.sessionStore.get(it) } }
    val activities by container.agents.activity.collectAsStateWithLifecycle()
    val delegated by produceState<app.umbra.core.session.Session?>(null, p.id, changes) { value = activeDelegatedWork(p.id, container.sessionStore.list()) }
    return when {
        !p.running -> stringResource(R.string.ag_status_stopped)
        session?.state == SessionState.WAITING_APPROVAL -> stringResource(R.string.ag_work_approval)
        session?.state == SessionState.PAUSED -> stringResource(R.string.ag_work_paused)
        session?.state == SessionState.RUNNING || activities[p.id] != null -> currentAgentWork(session, null, activities[p.id])
        delegated != null -> stringResource(R.string.ag_work_delegated, delegated!!.title)
        state.nextWakeMs > 0 -> stringResource(R.string.ag_next_wake, DateUtils.formatDateTime(context, state.nextWakeMs, DateUtils.FORMAT_SHOW_TIME))
        else -> stringResource(R.string.ag_status_on)
    }
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
