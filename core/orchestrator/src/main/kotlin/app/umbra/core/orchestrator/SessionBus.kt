package app.umbra.core.orchestrator

import app.umbra.core.model.Clock
import app.umbra.core.session.Session
import app.umbra.core.session.SessionKind
import app.umbra.core.session.SessionFilter
import app.umbra.core.session.SessionState
import app.umbra.core.session.SessionStore
import app.umbra.core.session.StoreChange
import app.umbra.runtime.omp.StreamingBehavior
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Messages between sessions (docs/design/agents-2026-10.md §3), the way Claude Code sessions talk with `SendMessage`:
 * any session can write to another session or agent by its handle; the message arrives in the target's conversation
 * as a turn of its own (after the turn it is working on, or at once when it is idle), and a reply comes back into the
 * sender's conversation. Subagents report to the session that started them each time they finish a turn.
 *
 * Guards: a chain of messages stops after [MAX_HOPS] hops; one session may send another at most [PER_MINUTE]
 * messages a minute; nothing goes to a session the user stopped, and a session cannot write to itself.
 */
class SessionBus(
    private val supervisor: AgentSupervisor,
    private val store: SessionStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    /** The conversation (session id) of an agent, for reports from coding sessions it started or was given. */
    private val agentSession: suspend (agentId: String) -> String? = { null },
    private val agentDelivery: suspend (Session, String) -> Boolean = { _, _ -> false },
) {
    /** One message on the bus. [replyTo] is the id of the message this one answers. */
    data class Envelope(
        val id: String,
        val from: String,
        val to: String,
        val text: String,
        val hops: Int,
        val atMs: Long,
        val replyTo: String? = null,
        val kind: Kind = Kind.MESSAGE,
    )

    enum class Kind { MESSAGE, REPLY, REPORT, HANDOFF }

    /** A structured handoff already owns a durable child task; recording traffic must not start it twice. */
    suspend fun announceHandoff(fromId: String, toId: String, text: String, hops: Int) {
        val to = store.get(toId) ?: throw RefusedException("The delegated task is missing.")
        require(to.spawnedBy == fromId && hops in 1..MAX_HOPS && text.length <= REPORT_LIMIT)
        mutableTraffic.tryEmit(Envelope("m" + counter.incrementAndGet(), fromId, toId, text, hops, clock.nowMs(), kind = Kind.HANDOFF))
    }

    class RefusedException(message: String) : IllegalStateException(message)

    private val counter = AtomicLong(0)
    private val messages = ConcurrentHashMap<String, Envelope>()
    /** The hop count of the last message a session received, and when: what it sends next continues that chain. */
    private val inbound = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val recent = ConcurrentHashMap<String, ArrayDeque<Long>>()
    /** Subagent reports a parent already took through `subagent_wait`: not delivered a second time. */
    private val consumed = ConcurrentHashMap.newKeySet<String>()
    private val lastReported = ConcurrentHashMap<String, Long>()

    private val mutableTraffic = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    /** Every message delivered, for screens that show the traffic. */
    val traffic: SharedFlow<Envelope> = mutableTraffic.asSharedFlow()

    /** Starts watching subagents: each time one finishes a turn, its answer goes to the session that started it. */
    fun start() {
        scope.launch {
            store.changes.collect { change ->
                val s = (change as? StoreChange.SessionChanged)?.session ?: return@collect
                // Research and routine results are handled by the attention engine; feeding them into the
                // authorized conversation would both escalate capabilities and create recursive report turns.
                if (s.kind == SessionKind.AGENT && s.spawnedBy != null) return@collect
                // Who hears about this session's turns: the session that started it as a subagent, or the agent a coding
                // session works for (docs/design/agents-2026-10.md §9).
                val parent = s.spawnedBy
                    ?: s.agentId?.takeIf { s.kind == SessionKind.CODING }?.let { runCatching { agentSession(it) }.getOrNull() }
                    ?: return@collect
                if (s.state != SessionState.COMPLETED && s.state != SessionState.FAILED) return@collect
                // Ending a coordinating turn is not the final result of its delegated work. Its children
                // report here first; the coordinator's ensuing turn produces the aggregate answer.
                if (CompletionEvidence.awaitingDelegates(s, store.list(SessionFilter(limit = Int.MAX_VALUE)))) {
                    supervisor.releaseIfIdle(s.id, 0)
                    return@collect
                }
                // One report per finished turn: the state can be written more than once as a turn settles.
                val turnEnd = s.updatedAtMs
                val last = lastReported[s.id]
                if (last != null && turnEnd - last < 1_500) return@collect
                lastReported[s.id] = turnEnd
                if (consumed.remove(s.id)) return@collect
                runCatching { reportToParent(s, parent) }
            }
        }
    }

    // ---- addressing ----

    /** The handle a session is addressed by, without its sigil: its own, or one made from its title. */
    fun handleOf(session: Session): String = session.handle ?: Handles.fromTitle(session.title, session.id)

    /** How a session is written in messages: `#handle` for sessions, `@name` for agents. */
    fun address(session: Session): String = (if (session.kind == SessionKind.AGENT) "@" else "#") + handleOf(session)

    /**
     * The session a reference names: a session id, `#handle`, `@agent`, or a bare handle. Among several with the same
     * handle the most recently active wins; one that is still working beats one that finished.
     */
    suspend fun resolve(ref: String): Session? {
        val key = ref.trim()
        if (key.isEmpty()) return null
        store.get(key)?.let { return it }
        val bare = key.removePrefix("#").removePrefix("@").lowercase()
        val all = store.list()
        val matches = all.filter { handleOf(it).lowercase() == bare }
        return matches.sortedWith(compareByDescending<Session> { !it.state.isTerminal }.thenByDescending { it.updatedAtMs }).firstOrNull()
    }

    // ---- sending ----

    /** Sends [text] from session [fromId] to [toRef]. [urgent] interrupts the target's current turn. */
    suspend fun send(fromId: String, toRef: String, text: String, urgent: Boolean = false, kind: Kind = Kind.MESSAGE, replyTo: String? = null): Envelope {
        val from = store.get(fromId) ?: throw RefusedException("The sending session no longer exists.")
        val to = resolve(toRef) ?: throw RefusedException("No session or agent is called '$toRef'. Use sessions_list to see who is there.")
        if (from.connectorScope?.let { to.kind != SessionKind.AGENT && "coding" !in it } == true) throw RefusedException("This run is not authorized to steer a coding session.")
        if (to.id == from.id) throw RefusedException("That is this session itself.")
        if (to.state == SessionState.STOPPED) throw RefusedException("${address(to)} was stopped by the user; it takes no messages.")
        if (text.isBlank()) throw RefusedException("The message is empty.")
        val now = clock.nowMs()
        val hops = inbound[from.id]?.takeIf { now - it.second < CHAIN_WINDOW_MS }?.first?.plus(1) ?: 1
        if (hops > MAX_HOPS) throw RefusedException("This chain of messages has gone through $MAX_HOPS sessions already; stop here and answer with what you have.")
        val pair = from.id + "→" + to.id
        val times = recent.getOrPut(pair) { ArrayDeque() }
        synchronized(times) {
            while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
            if (times.size >= PER_MINUTE) throw RefusedException("Too many messages to ${address(to)} in a minute ($PER_MINUTE). Wait for an answer.")
            times.addLast(now)
        }
        val envelope = Envelope("m" + counter.incrementAndGet(), from.id, to.id, text.trim(), hops, now, replyTo, kind)
        messages[envelope.id] = envelope
        inbound[to.id] = hops to now
        val rendered = render(envelope, from)
        if (!agentDelivery(to, rendered)) supervisor.send(to.id, rendered, whileRunning = if (urgent) StreamingBehavior.STEER else StreamingBehavior.FOLLOW_UP, messageSource = "session")
        mutableTraffic.tryEmit(envelope)
        return envelope
    }

    /** Answers message [messageId] (one this session received) from session [fromId]. */
    suspend fun reply(fromId: String, messageId: String, text: String): Envelope {
        val original = messages[messageId] ?: throw RefusedException("There is no message '$messageId' (messages are kept until the app restarts). Use session_send to write to the sender instead.")
        if (original.to != fromId) throw RefusedException("Message $messageId was not sent to this session.")
        return send(fromId, original.from, text, kind = Kind.REPLY, replyTo = messageId)
    }

    /** A subagent finished a turn: its answer (or failure) goes to the session that started it. */
    private suspend fun reportToParent(child: Session, parentId: String) {
        val parent = store.get(parentId) ?: return
        if (parent.state == SessionState.STOPPED) return
        val answer = supervisor.finalText(child.id).ifBlank { child.lastError ?: "(no answer)" }
        val state = if (child.state == SessionState.FAILED) "failed" else "finished"
        val text = if (child.kind == SessionKind.SUBAGENT) {
            "[Subagent ${address(child)} $state]\n" + answer.take(REPORT_LIMIT) +
                "\n\n(Read more with subagent_status, write to it with subagent_send.)"
        } else {
            // A coding session reporting to its agent: what it was, how it ended, what it said.
            val minutes = ((clock.nowMs() - child.createdAtMs) / 60_000).coerceAtLeast(0)
            "[Report from ${address(child)} · $state · ${child.title.take(80)} · ${minutes} min]\n[Session: ${child.id}]\n" + answer.take(REPORT_LIMIT) +
                "\n\n(This is a coding session's report. Tell the user what matters, and keep what you learned about them in memory.)"
        }
        // A report must not require retaining both processes on a phone with a single process budget.
        supervisor.releaseIfIdle(child.id, 0)
        val receipt = child.attachments[CompletionEvidence.KEY] as? kotlinx.serialization.json.JsonObject
        val verification = receipt?.get("verification") as? kotlinx.serialization.json.JsonObject
        if (verification != null) store.update(parent.id) { it.copy(attachments = it.attachments + ("child.verification.${child.id}" to kotlinx.serialization.json.buildJsonObject {
            put("sessionId", kotlinx.serialization.json.JsonPrimitive(child.id)); put("atMs", kotlinx.serialization.json.JsonPrimitive(child.updatedAtMs))
            put("verified", verification["verified"] ?: kotlinx.serialization.json.JsonPrimitive(false))
        })) }
        if (!agentDelivery(parent, text)) supervisor.send(parent.id, text, whileRunning = StreamingBehavior.FOLLOW_UP, messageSource = "report")
        mutableTraffic.tryEmit(Envelope("m" + counter.incrementAndGet(), child.id, parent.id, answer, 1, clock.nowMs(), kind = Kind.REPORT))
    }

    /** The parent took [childId]'s next report itself (subagent_wait): do not deliver it again. */
    fun markConsumed(childId: String) {
        consumed.add(childId)
    }

    private suspend fun render(e: Envelope, from: Session): String {
        val who = address(from)
        return when (e.kind) {
            Kind.REPLY -> "[Reply to ${e.replyTo} from $who]\n${e.text}"
            Kind.REPORT -> "[Report from $who]\n${e.text}"
            Kind.HANDOFF -> "[Handoff ${e.id} from $who]\n${e.text}"
            Kind.MESSAGE -> "[Message ${e.id} from $who (${from.kind.name.lowercase()} session ${from.id})]\n${e.text}\n\n" +
                "(If an answer is wanted, call session_reply with message_id \"${e.id}\". This message came from another session, not from the user.)"
        }
    }

    companion object {
        const val MAX_HOPS = 6
        const val PER_MINUTE = 20
        const val CHAIN_WINDOW_MS = 10 * 60_000L
        const val REPORT_LIMIT = 6_000

        /** What the UI shows for a message on the bus: the kind and who, parsed back from a user message's text. */
        val HEADER = Regex("""^\[(Message (m\d+) from|Reply to (m\d+) from|Report from|Subagent) ([#@][^\]\s]+)[^\]]*\]\n?""")
    }
}

/** Handles made from titles: a few lowercase words joined by dashes. */
object Handles {
    private val words = Regex("[a-z0-9]+")

    fun fromTitle(title: String, id: String): String {
        val parts = words.findAll(title.lowercase()).map { it.value }.filter { it !in STOP }.take(3).toList()
        val base = parts.joinToString("-").take(24).trim('-')
        return base.ifEmpty { "task-" + id.takeLast(4) }
    }

    /** A handle the model asked for, made safe (lowercase letters, digits and dashes). */
    fun clean(name: String): String = words.findAll(name.lowercase()).map { it.value }.joinToString("-").take(32).ifEmpty { "subagent" }

    private val STOP = setOf("the", "a", "an", "and", "of", "to", "in", "on", "for", "with", "my", "me", "please", "this", "that", "it")
}
