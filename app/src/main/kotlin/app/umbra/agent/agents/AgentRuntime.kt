package app.umbra.agent.agents

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import app.umbra.agent.AgentService
import app.umbra.agent.AppContainer
import app.umbra.agent.MainActivity
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.LayaSidecar
import app.umbra.agent.data.UiMode
import app.umbra.core.decision.Answer
import app.umbra.core.decision.DecisionRequest
import app.umbra.core.decision.Option
import app.umbra.core.decision.Question
import app.umbra.core.orchestrator.Handles
import app.umbra.core.orchestrator.SessionSpec
import app.umbra.core.session.Effort
import app.umbra.core.session.ModelRef
import app.umbra.core.session.SessionKind
import app.umbra.core.session.SessionState
import app.umbra.core.session.StoreChange
import app.umbra.runtime.omp.ContentBlock
import app.umbra.runtime.omp.StreamingBehavior
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Something that reached the phone and an agent may care about: a message from a message app, or a notification.
 * [key] is unique per item; [source] is a connector id (whatsapp, sms…) or a package name.
 */
data class Incoming(
    val key: String,
    val kind: Kind,
    val source: String,
    val sourceLabel: String,
    val chat: String? = null,
    val from: String? = null,
    val text: String,
    val atMs: Long,
    /** A group chat: never answered on the agent's own, summarised rather than acted on. */
    val group: Boolean = false,
) {
    enum class Kind { MESSAGE, NOTIFICATION }

    fun line(): String {
        val time = DateTimeFormatter.ofPattern("HH:mm").format(LocalDateTime.ofInstant(Instant.ofEpochMilli(atMs), ZoneId.systemDefault()))
        val who = listOfNotNull(from, chat?.takeIf { it != from }?.let { "chat $it" }).joinToString(" · ")
        return "- [$sourceLabel${if (who.isNotEmpty()) " · $who" else ""} · $time] ${text.replace('\n', ' ').take(600)}"
    }
}

/** Where the wake loop finds what arrived since a time: the notification reader and the message apps add themselves. */
fun interface IncomingSource {
    suspend fun since(afterMs: Long): List<Incoming>
}

/**
 * The agents' life (docs/design/agents-2026-10.md §7, §8): each runs until the user stops it, but holds no agent
 * process between wakes. Wakes, cheapest first:
 *
 * 1. Heartbeat every [AgentProfile.heartbeatMinutes] (10 by default; set in Power and Dev): gather what is new (timers
 *    due, the user's queue, messages and notifications since the last look). Nothing new: back to sleep, no model.
 * 2. Triage on the phone: Laya (or plain rules without it) says now / later / ignore for each new item.
 * 3. Run: the agent's conversation gets the digest as a turn; its omp process is let go two minutes after it is idle.
 *
 * Timers and rules wake at once (3, skipping triage); a message from a message app wakes at once but only to triage
 * (2), which decides whether the agent runs. The phone's alarm keeps the schedule in Doze (allowed about once every
 * nine minutes per app, which suits the ten-minute default).
 */
class AgentRuntime(private val container: AppContainer) {
    private val context: Context get() = container.context
    val store = AgentStore(File(container.context.filesDir, "agents"))
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val sources = CopyOnWriteArrayList<IncomingSource>()
    private var started = false
    private val delivery = AgentDeliveryRouter(container.laya.engine) { container.laya.state.value == LayaSidecar.State.RUNNING }

    private suspend fun recipients(item: Incoming): Set<String> {
        val eligible = store.profiles.value.filter { it.running && watches(it, item) }
        val selected = delivery.recipients(item.key, item.line(), eligible.map { AgentDeliveryRouter.Recipient(it.id, it.name, store.soul(it.id)) })
        // A rule is an explicit user request and takes precedence over automatic relevance routing.
        return selected + eligible.filter { p -> store.rules(p.id).any { it.enabled && matches(it, item) } }.map { it.id }
    }

    /** The agent whose chat is on screen: its new messages need no notification. */
    val onScreen = MutableStateFlow<String?>(null)

    private val mutableActivity = MutableStateFlow<Map<String, String>>(emptyMap())
    /** What each agent's loop is doing now ("triage", "working"), for the agent list. */
    val activity: StateFlow<Map<String, String>> = mutableActivity.asStateFlow()

    fun addSource(source: IncomingSource) {
        sources += source
    }

    fun start() {
        if (started) return
        started = true
        createChannel()
        container.scope.launch {
            reconcile()
            if (store.profiles.value.any { it.running }) runCatching { AgentService.start(context) }
            loop()
        }
        container.scope.launch { watchTurns() }
        container.scope.launch { watchPairs() }
    }

    // ---- agents ----

    fun profiles(): List<AgentProfile> = store.profiles.value

    /** The agent the Capsule and the Agents tab talk to: the one the user marked, else the first that runs. */
    fun defaultAgent(): AgentProfile? {
        val id = container.settings.value.defaultAgentId
        return store.profiles.value.firstOrNull { it.id == id && it.running } ?: store.profiles.value.firstOrNull { it.running }
    }

    /** Makes [agentId] the one the Capsule and Home talk to. */
    fun setDefault(agentId: String) {
        container.settings.update { it.copy(defaultAgentId = agentId) }
    }

    /** The Capsule's "switch agent": the next running agent becomes the default. Returns it. */
    fun cycleDefault(): AgentProfile? {
        val running = store.profiles.value.filter { it.running }
        if (running.isEmpty()) return null
        val at = running.indexOfFirst { it.id == defaultAgent()?.id }
        val next = running[(at + 1) % running.size]
        setDefault(next.id)
        return next
    }

    /** The default agent, made on first need (a personal assistant called Umbra) so the Capsule always has one. */
    suspend fun ensureDefault(): AgentProfile = defaultAgent() ?: create(
        name = context.getString(R.string.ag_default_name), avatar = "a1", template = Templates.ASSISTANT,
        soul = Templates.soul(Templates.ASSISTANT, context.getString(R.string.ag_default_name)),
    ).also { p -> container.settings.update { it.copy(defaultAgentId = p.id) } }

    suspend fun create(
        name: String,
        avatar: String,
        template: String,
        soul: String,
        model: ModelRef? = null,
        effort: Effort? = null,
        channels: List<String> = listOf("notifications"),
        heartbeatMinutes: Int = AgentProfile.DEFAULT_HEARTBEAT_MIN,
        usageLimit: app.umbra.core.session.AiUsageLimit? = null,
    ): AgentProfile {
        val now = System.currentTimeMillis()
        val id = "ag" + now.toString(36) + (1..4).map { "abcdefghijkmnpqrstuvwxyz23456789".random() }.joinToString("")
        val clean = name.trim().ifEmpty { context.getString(R.string.ag_default_name) }
        var profile = AgentProfile(id, clean, avatar, template, model, effort, heartbeatMinutes, createdAtMs = now, channels = channels, usageLimit = usageLimit)
        store.save(profile)
        store.setSoul(id, soul.ifBlank { Templates.soul(template, clean) })
        if (store.memory(id).entries().isEmpty()) store.memory(id).setRaw(Memory.HEADER + "\n")
        val session = container.supervisor.create(
            request = "The conversation of the agent $clean.", title = clean, model = model, effort = effort,
            kind = SessionKind.AGENT, handle = uniqueHandle(clean), agentId = id,
        )
        profile = store.update(id) { it.copy(sessionId = session.id) } ?: profile
        store.setState(id, AgentState(lastWakeMs = now, seenUntilMs = now))
        if (container.settings.value.defaultAgentId == null) container.settings.update { it.copy(defaultAgentId = id) }
        runCatching { AgentService.start(context) }
        reschedule()
        return profile
    }

    /** An @handle no other session has. */
    private suspend fun uniqueHandle(name: String): String {
        val base = Handles.clean(name)
        val taken = container.sessionStore.list().map { container.sessionBus.handleOf(it) }.toSet()
        if (base !in taken) return base
        return (2..99).map { "$base-$it" }.first { it !in taken }
    }

    /** The user writes to an agent (from its chat, the Capsule, the Agents tab or Telegram). */
    suspend fun talk(agentId: String, text: String, images: List<ContentBlock.Image> = emptyList(), via: String? = null) {
        val p = store.get(agentId) ?: error("no such agent")
        check(p.running) { context.getString(R.string.ag_stopped_cant_talk, p.name) }
        val sid = p.sessionId ?: error("the agent has no conversation")
        val body = if (via != null) "[$via] $text" else text
        AgentService.start(context)
        container.supervisor.send(sid, body, images, whileRunning = StreamingBehavior.FOLLOW_UP)
        markRead(agentId)
    }

    suspend fun setRunning(agentId: String, running: Boolean) {
        val p = store.update(agentId) { it.copy(running = running) } ?: return
        if (!running) p.sessionId?.let { sid ->
            if (container.sessionStore.get(sid)?.state?.isActive == true) runCatching { container.supervisor.pause(sid) }
            runCatching { container.supervisor.releaseIfIdle(sid, 0) }
        } else {
            store.setState(agentId, store.state(agentId).copy(lastWakeMs = System.currentTimeMillis()))
            runCatching { AgentService.start(context) }
        }
        reschedule()
    }

    suspend fun delete(agentId: String) {
        val p = store.get(agentId) ?: return
        p.sessionId?.let { runCatching { container.supervisor.delete(it) } }
        store.delete(agentId)
        if (container.settings.value.defaultAgentId == agentId) container.settings.update { it.copy(defaultAgentId = store.profiles.value.firstOrNull()?.id) }
        reschedule()
    }

    fun markRead(agentId: String) {
        store.update(agentId) { it.copy(lastReadMs = System.currentTimeMillis()) }
    }

    /** Sessions of the agent's past whose conversation was lost (deleted by hand): a new one keeps the agent alive. */
    private suspend fun reconcile() {
        for (p in store.profiles.value) {
            val sid = p.sessionId
            if (sid != null && container.sessionStore.get(sid) != null) continue
            val session = container.supervisor.create(
                request = "The conversation of the agent ${p.name}.", title = p.name, model = p.model, effort = p.effort,
                kind = SessionKind.AGENT, handle = uniqueHandle(p.name), agentId = p.id,
            )
            store.update(p.id) { it.copy(sessionId = session.id) }
        }
    }

    // ---- the wake loop ----

    enum class Reason(val word: String) { HEARTBEAT("heartbeat"), TIMER("timer"), RULE("rule"), MESSAGE("message"), MANUAL("asked") }

    private suspend fun loop() {
        while (true) {
            runCatching { tick() }.onFailure { Log.w(TAG, "tick", it) }
            val next = reschedule()
            val wait = (next - System.currentTimeMillis()).coerceIn(5_000, 60_000)
            delay(wait)
        }
    }

    /** One look at every running agent: due timers wake at once; a heartbeat when its interval has passed. */
    suspend fun tick() {
        val now = System.currentTimeMillis()
        for (p in store.profiles.value.filter { it.running && it.sessionId != null }) {
            val due = store.timers(p.id).filter { it.nextMs <= now }
            val state = store.state(p.id)
            val interval = intervalMs(p)
            when {
                due.isNotEmpty() -> wake(p.id, Reason.TIMER, timers = due)
                interval != null && now - state.lastWakeMs >= interval -> wake(p.id, Reason.HEARTBEAT)
            }
        }
    }

    /**
     * The time between heartbeats now: the agent's setting (Simple mode keeps ten minutes), doubled in battery saver or
     * when the phone is hot; none below 15% battery (then only timers, rules and messages wake it).
     */
    fun intervalMs(p: AgentProfile): Long? {
        val minutes = if (container.settings.value.uiMode == UiMode.SIMPLE) AgentProfile.DEFAULT_HEARTBEAT_MIN else p.heartbeatMinutes.coerceIn(5, 360)
        val b = battery()
        val prefs = container.settings.value
        if (prefs.agentPauseLowBattery && b.level != null && b.level < 15 && !b.charging) return null
        val slow = prefs.agentSlowWhenHot && (b.powerSave || (b.temperatureC ?: 0f) >= 42f)
        return minutes * 60_000L * (if (slow) 2 else 1)
    }

    data class Battery(val level: Int?, val charging: Boolean, val powerSave: Boolean, val temperatureC: Float?)

    fun battery(): Battery {
        val i = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = i?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1); if (l >= 0 && s > 0) l * 100 / s else null }
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        val saver = runCatching { context.getSystemService(PowerManager::class.java).isPowerSaveMode }.getOrDefault(false)
        return Battery(level, plugged, saver, temp)
    }

    fun inQuietHours(p: AgentProfile, at: LocalDateTime = LocalDateTime.now()): Boolean {
        val start = p.quietStartMin ?: return false
        val end = p.quietEndMin ?: return false
        val m = at.hour * 60 + at.minute
        return if (start <= end) m in start until end else m >= start || m < end
    }

    /**
     * Wakes one agent. Builds the digest of what is new; with nothing new it goes back to sleep without the model.
     * New messages and notifications go through triage first unless [skipTriage]; timers, rules and the user's queue
     * always run.
     */
    suspend fun wake(agentId: String, reason: Reason, timers: List<AgentTimer> = emptyList(), fired: List<Pair<AgentRule, Incoming>> = emptyList(), extra: List<Incoming> = emptyList(), skipTriage: Boolean = false) {
        val mutex = locks.getOrPut(agentId) { Mutex() }
        mutex.withLock {
            val p = store.get(agentId) ?: return
            if (!p.running) return
            val sid = p.sessionId ?: return
            val now = System.currentTimeMillis()
            var state = store.state(agentId).let { s -> if (s.day != LocalDate.now().toString()) s.copy(day = LocalDate.now().toString(), wakesToday = 0, runsToday = 0, activeMsToday = 0) else s }
            state = state.copy(lastWakeMs = now, wakesToday = state.wakesToday + 1)

            // Timers: one-shot ones are done, repeating ones move to their next time.
            if (timers.isNotEmpty()) {
                val firing = timers.map { it.id }.toSet()
                store.setTimers(agentId, store.timers(agentId).mapNotNull { t ->
                    if (t.id !in firing) t else t.cron?.let { c -> runCatching { t.copy(nextMs = Cron(c).next(now)) }.getOrNull() }
                })
            }

            // What arrived since the last look, from the agent's own sources.
            val arrived = (sources.flatMap { runCatching { it.since(state.seenUntilMs) }.getOrDefault(emptyList()) } + extra)
                .filter { watches(p, it) }.distinctBy { it.key }.sortedBy { it.atMs }
            val newSeen = maxOf(state.seenUntilMs, arrived.maxOfOrNull { it.atMs } ?: 0, if (reason == Reason.HEARTBEAT) now else 0)
            val queue = store.queue(agentId).filter { !it.done }
            val freshQueue = queue.filter { it.addedAtMs > state.lastRunMs }

            // Triage: what needs the agent now, what can wait for its next run, what it can ignore.
            mutableActivity.update { it + (agentId to "triage") }
            val (routed, verdicts) = try {
                val relevant = arrived.filter { agentId in recipients(it) }
                relevant to if (skipTriage || relevant.isEmpty()) relevant.associateWith { Verdict.NOW } else triage(p, relevant)
            } finally {
                mutableActivity.update { it - agentId }
            }
            val now_ = routed.filter { verdicts[it] == Verdict.NOW }
            val later = routed.filter { verdicts[it] == Verdict.LATER }.map { it.line() }

            val runNow = timers.isNotEmpty() || fired.isNotEmpty() || now_.isNotEmpty() || freshQueue.isNotEmpty() || reason == Reason.MANUAL
            if (!runNow) {
                store.setState(agentId, state.copy(seenUntilMs = newSeen, later = (state.later + later).takeLast(MAX_LATER)))
                Log.i(TAG, "${p.name}: woke (${reason.word}), nothing to do (${arrived.size} new, ${later.size} for later)")
                return
            }
            val digest = digest(p, reason, timers, fired, now_, freshQueue, state.later + later)
            store.setState(agentId, state.copy(seenUntilMs = newSeen, later = emptyList(), lastRunMs = now, runsToday = state.runsToday + 1))
            Log.i(TAG, "${p.name}: runs (${reason.word}): ${timers.size} timers, ${fired.size} rules, ${now_.size} now, ${freshQueue.size} queued")
            runCatching { AgentService.start(context) }
            container.supervisor.send(sid, digest, whileRunning = StreamingBehavior.FOLLOW_UP)
        }
    }

    /** Something arrived (a message, a notification): rules fire at once; otherwise a quick triage decides. */
    suspend fun onIncoming(item: Incoming) {
        val targets = if (item.kind == Incoming.Kind.MESSAGE) recipients(item)
            else store.profiles.value.filter { it.running && watches(it, item) }.map { it.id }.toSet()
        for (p in store.profiles.value.filter { it.running && it.id in targets }) {
            val rules = store.rules(p.id).filter { r -> r.enabled && matches(r, item) }
            if (rules.isNotEmpty()) {
                wake(p.id, Reason.RULE, fired = rules.map { it to item }, extra = listOf(item), skipTriage = true)
            } else if (item.kind == Incoming.Kind.MESSAGE) {
                // A message wakes the agent at once, but only to decide whether it needs to work.
                wake(p.id, Reason.MESSAGE, extra = listOf(item))
            }
            // A plain notification waits for the next heartbeat.
        }
    }

    private fun watches(p: AgentProfile, item: Incoming): Boolean = when (item.kind) {
        Incoming.Kind.MESSAGE -> item.source in p.channels
        Incoming.Kind.NOTIFICATION -> "notifications" in p.channels
    }

    private fun matches(r: AgentRule, item: Incoming): Boolean =
        (r.whenSource == null || r.whenSource.equals(item.source, true) || r.whenSource.equals(item.sourceLabel, true)) &&
            (r.whenText == null || item.text.contains(r.whenText, true) || item.from?.contains(r.whenText, true) == true || item.chat?.contains(r.whenText, true) == true)

    enum class Verdict { NOW, LATER, IGNORE }

    /**
     * Laya reads each new item with the agent's role and says now / later / ignore (one request, up to eight items).
     * Without Laya: a message in a one-to-one chat is now, group chatter and notifications are later.
     */
    private suspend fun triage(p: AgentProfile, items: List<Incoming>): Map<Incoming, Verdict> {
        val fallback = items.associateWith { if (it.kind == Incoming.Kind.MESSAGE && !it.group) Verdict.NOW else Verdict.LATER }
        if (container.laya.state.value != LayaSidecar.State.RUNNING) return fallback
        val batch = items.takeLast(8)
        val role = store.soul(p.id).take(600)
        val question = { it: Incoming ->
            Question.Choice(
                options = listOf(
                    Option("now", "Needs the assistant now: a person waits for an answer, something is urgent, or it matches what the user asked to be told."),
                    Option("later", "Worth a look at the next regular check: informative, not urgent."),
                    Option("ignore", "Not relevant: promotions, automated noise, things the user does not care about."),
                ),
                instructions = "Decide what the assistant should do with this item.\n${it.line()}",
                intent = "triage an incoming item",
            )
        }
        val request = DecisionRequest(
            state = "An assistant on the user's phone. Its role:\n$role",
            questions = batch.mapIndexed { i, it -> "i$i" to question(it) }.toMap(),
        )
        val answers = withTimeoutOrNull(8_000) { runCatching { container.laya.engine.decide(request) }.getOrNull() }?.answers ?: return fallback
        return items.associateWith { item ->
            val i = batch.indexOf(item)
            val a = (answers["i$i"] as? Answer.ChoiceAnswer)?.takeIf { it.confidence >= 0.65 }?.choice
            when (a) { "now" -> Verdict.NOW; "ignore" -> Verdict.IGNORE; "later" -> Verdict.LATER; else -> fallback.getValue(item) }
        }
    }

    private fun digest(
        p: AgentProfile,
        reason: Reason,
        timers: List<AgentTimer>,
        fired: List<Pair<AgentRule, Incoming>>,
        items: List<Incoming>,
        queue: List<QueueItem>,
        later: List<String>,
    ): String = buildString {
        val time = DateTimeFormatter.ofPattern("EEE HH:mm").format(LocalDateTime.now())
        append("[Wake · $time · ${reason.word}]")
        val b = battery()
        val quiet = inQuietHours(p)
        appendLine()
        appendLine(listOfNotNull(if (quiet) "Quiet hours now: notify only if urgent, send nothing." else null, b.level?.let { "Battery $it%${if (b.charging) " (charging)" else ""}." }).joinToString(" ").ifEmpty { "" }.trim())
        if (timers.isNotEmpty()) {
            appendLine("Timers due:")
            timers.forEach { appendLine("- ${it.id} (set by ${it.setBy}${it.cron?.let { c -> ", repeats $c" } ?: ""}): ${it.note}") }
        }
        if (fired.isNotEmpty()) {
            appendLine("Rules that fired:")
            fired.forEach { (r, i) -> appendLine("- ${r.id} \"${r.then}\" because of:"); appendLine("  " + i.line()) }
        }
        val messages = items.filter { it.kind == Incoming.Kind.MESSAGE }
        val notes = items.filter { it.kind == Incoming.Kind.NOTIFICATION }
        if (messages.isNotEmpty()) { appendLine("New messages:"); messages.forEach { appendLine(it.line()) } }
        if (notes.isNotEmpty()) { appendLine("New notifications:"); notes.forEach { appendLine(it.line()) } }
        if (queue.isNotEmpty()) { appendLine("The user asked you to do (your queue):"); queue.forEach { appendLine("- ${it.id}: ${it.text}") } }
        if (later.isNotEmpty()) { appendLine("Set aside earlier for this run:"); later.takeLast(MAX_LATER).forEach { appendLine(it) } }
        appendLine()
        append("Do what is needed now. Write to the user only when there is something they should know or decide; otherwise end your turn with exactly $QUIET")
    }

    // ---- turns: notify the user, let the process go ----

    private suspend fun watchTurns() {
        val notified = ConcurrentHashMap<String, Long>()
        val started = ConcurrentHashMap<String, Long>()
        container.sessionStore.changes.collect { change ->
            val s = (change as? StoreChange.SessionChanged)?.session ?: return@collect
            if (s.kind != SessionKind.AGENT) return@collect
            val p = s.agentId?.let { store.get(it) } ?: return@collect
            if (s.state == SessionState.RUNNING) { started.putIfAbsent(s.id, System.currentTimeMillis()); return@collect }
            if (s.state != SessionState.COMPLETED && s.state != SessionState.FAILED) return@collect
            // How long the model worked this turn: today's total feeds the battery estimate.
            started.remove(s.id)?.let { from ->
                val worked = (System.currentTimeMillis() - from).coerceIn(0, 6 * 3_600_000L)
                val today = LocalDate.now().toString()
                val st = store.state(p.id)
                store.setState(p.id, if (st.day == today) st.copy(activeMsToday = st.activeMsToday + worked) else st.copy(day = today, wakesToday = 0, runsToday = 0, activeMsToday = worked))
            }
            if (notified[s.id]?.let { s.updatedAtMs - it < 1_500 } == true) return@collect
            notified[s.id] = s.updatedAtMs
            container.scope.launch {
                // Tell the user what the agent said, unless they are looking at its chat or it had nothing to say.
                val text = container.supervisor.finalText(s.id).trim()
                if (text.isNotEmpty() && !text.startsWith(QUIET) && onScreen.value != p.id && !repliesOnTelegram(p.id)) notify(p, text, urgent = false)
                // Yield memory to delegated work immediately, including on phones that admit only one turn.
                val delegated = container.sessionStore.list().any { it.agentId == p.id && it.kind == SessionKind.CODING && !it.state.isTerminal }
                if (delegated) runCatching { container.supervisor.releaseIfIdle(s.id, 0) }
                else {
                    delay(RELEASE_AFTER_MS)
                    runCatching { container.supervisor.releaseIfIdle(s.id, RELEASE_AFTER_MS) }
                }
            }
        }
    }

    /** Messages between two agents: the Agents list shows their conversation as a row of its own. */
    private suspend fun watchPairs() {
        container.sessionBus.traffic.collect { e ->
            val from = store.bySession(e.from) ?: return@collect
            val to = store.bySession(e.to) ?: return@collect
            if (e.kind == app.umbra.core.orchestrator.SessionBus.Kind.REPORT) return@collect
            runCatching { store.notePair(from.id, to.id, e.text, e.atMs) }
        }
    }

    /**
     * Today's battery use by an agent, estimated from what was measured: each wake (about 0.2 s of CPU, no model) and
     * the time its model worked (the agent process and the network at about 1.2 W), against the battery's capacity
     * (from its charge counter when the phone reports one, else a typical 4 500 mAh). Percent of a full charge.
     */
    fun estimatedBatteryPercent(state: AgentState): Double {
        val manager = context.getSystemService(BatteryManager::class.java)
        val counter = runCatching { manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrDefault(Long.MIN_VALUE)
        val level = battery().level
        val fullMah = if (counter > 0 && level != null && level > 5) counter / 1000.0 / (level / 100.0) else 4_500.0
        val capacityWh = fullMah / 1000.0 * 3.85
        val joules = state.wakesToday * 0.2 * 1.0 + state.activeMsToday / 1000.0 * 1.2
        return joules / (capacityWh * 3_600.0) * 100.0
    }

    suspend fun repliesOnTelegram(agentId: String): Boolean {
        val sid = store.get(agentId)?.sessionId ?: return false
        return try { repliesOnTelegram(container.supervisor.conversation(sid).value) }
        catch (_: app.umbra.core.session.SessionNotFoundException) { false }
    }

    suspend fun notify(p: AgentProfile, text: String, urgent: Boolean) {
        if (repliesOnTelegram(p.id)) {
            container.telegram.notifyAgent(text)
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            context, p.id.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_AGENT, p.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(context, if (urgent) CHANNEL_URGENT else CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_umbra)
            .setContentTitle(p.name)
            .setContentText(text.lineSequence().first().take(200))
            .setStyle(android.app.Notification.BigTextStyle().bigText(text.take(1_500)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFY_BASE + (p.id.hashCode() and 0xffff), n) }
    }

    private fun createChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.ag_channel), NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_URGENT, context.getString(R.string.ag_channel_urgent), NotificationManager.IMPORTANCE_HIGH))
    }

    // ---- the phone's alarm ----

    /** When the next agent needs a look; the alarm is set for it so Doze cannot delay it much. Returns that time. */
    fun reschedule(): Long {
        val now = System.currentTimeMillis()
        var next = Long.MAX_VALUE
        for (p in store.profiles.value.filter { it.running }) {
            store.timers(p.id).minOfOrNull { it.nextMs }?.let { next = minOf(next, it) }
            intervalMs(p)?.let { next = minOf(next, store.state(p.id).lastWakeMs + it) }
            val mine = minOf(store.timers(p.id).minOfOrNull { it.nextMs } ?: Long.MAX_VALUE, intervalMs(p)?.let { store.state(p.id).lastWakeMs + it } ?: Long.MAX_VALUE)
            if (mine != Long.MAX_VALUE) store.setState(p.id, store.state(p.id).copy(nextWakeMs = mine))
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 7_001, Intent(context, AgentWakeReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (next == Long.MAX_VALUE) {
            alarms.cancel(pending)
            return now + 60_000
        }
        val at = maxOf(next, now + 5_000)
        runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
            .onFailure { runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) } }
        return at
    }

    companion object {
        private const val TAG = "AgentRuntime"
        const val QUIET = "[quiet]"
        const val RELEASE_AFTER_MS = 2 * 60_000L
        private const val MAX_LATER = 30
        const val CHANNEL = "agents"
        const val CHANNEL_URGENT = "agents-urgent"
        private const val NOTIFY_BASE = 40_000
    }
}

/** The phone's alarm for the next agent wake: the process wakes, the loop looks at every agent. */
class AgentWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = context.container
        container.scope.launch {
            try {
                container.agents.tick()
                container.agents.reschedule()
            } finally {
                pending.finish()
            }
        }
    }
}
