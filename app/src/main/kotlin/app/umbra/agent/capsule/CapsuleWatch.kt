package app.umbra.agent.capsule

import app.umbra.agent.AppContainer
import app.umbra.agent.ui.eventFlow
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.orchestrator.ConversationState
import app.umbra.core.session.Session
import app.umbra.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * What the Capsule's companions and its activity feed show, kept current while the Capsule runs: for each live session
 * the last steps of its stored timeline and what its live conversation reports (a tool running, compacting, retrying,
 * context use); the sessions that just completed (their done moment); and [signal] for the haptics, when a request
 * arrives and when a session completes. Reads only what the app already records; nothing here acts on a session.
 */
internal class CapsuleWatch(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val signal: (Signal) -> Unit,
) {
    enum class Signal { REQUEST, DONE }

    val activity = MutableStateFlow<Map<String, SessionActivity>>(emptyMap())

    /** Sessions seen completing, with when: each keeps its done moment for [Crews.DONE_MS]. */
    val finished = MutableStateFlow<Map<String, Long>>(emptyMap())

    private val followed = HashMap<String, Job>()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch {
                var before: Map<String, SessionState>? = null
                container.sessionStore.sessionsFlow().collect { sessions ->
                    before?.let { prev -> Signals.completed(prev, sessions).forEach(::markFinished) }
                    before = sessions.associate { it.id to it.state }
                    follow(sessions)
                }
            }
            launch {
                var before: Set<String>? = null
                combine(container.approvals.pending, container.questions.pending) { approvals, questions ->
                    approvals.map { it.id }.toSet() + questions.map { it.request.id }
                }.collect { ids ->
                    // What already waited when the Capsule came up is not news.
                    before?.let { if (Signals.arrived(it, ids)) signal(Signal.REQUEST) }
                    before = ids
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        followed.values.forEach { it.cancel() }
        followed.clear()
        activity.value = emptyMap()
    }

    private fun markFinished(id: String) {
        val now = System.currentTimeMillis()
        finished.update { it + (id to now) }
        signal(Signal.DONE)
        scope.launch {
            delay(Crews.DONE_MS + 100)
            finished.update { if (it[id] == now) it - id else it }
        }
    }

    /** Follows the live sessions (the most recently active few), and lets go of the others. */
    private fun follow(sessions: List<Session>) {
        val live = sessions.filter(Crews::isLive).sortedByDescending { it.updatedAtMs }.take(FOLLOW_MAX).map { it.id }.toSet()
        (followed.keys - live).forEach { id ->
            followed.remove(id)?.cancel()
            activity.update { it - id }
        }
        (live - followed.keys).forEach { id -> followed[id] = scope.launch { watch(id) } }
    }

    private suspend fun watch(id: String) {
        var start: Long? = null
        combine(container.sessionStore.eventFlow(id, Feed.TAIL), conversation(id)) { records, live ->
            start = Feed.runStart(records) ?: start
            val feed = Feed.of(records)
            SessionActivity(
                feed = feed,
                toolRunning = live.toolRunning || Feed.toolRunning(feed),
                compacting = live.compacting,
                retrying = live.retrying,
                contextPercent = live.contextPercent,
                runStartedAtMs = start,
            )
        }.distinctUntilChanged().collect { a -> activity.update { it + (id to a) } }
    }

    /** The few things of the live conversation the Capsule shows; streaming text does not change them. */
    private data class Live(val toolRunning: Boolean = false, val compacting: Boolean = false, val retrying: Boolean = false, val contextPercent: Int? = null)

    /** The session's live conversation while its agent runs. An agent that starts later is picked up within seconds. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun conversation(id: String): Flow<Live> =
        flow {
            while (true) {
                emit(container.supervisor.liveSession(id))
                delay(LIVE_POLL_MS)
            }
        }.distinctUntilChanged()
            .flatMapLatest { session -> session?.conversation ?: flowOf<ConversationState?>(null) }
            .map { c ->
                if (c == null) Live() else Live(
                    toolRunning = c.tools.values.any { it.running },
                    compacting = c.compacting,
                    retrying = c.retry != null,
                    contextPercent = c.session?.contextUsage?.takeIf { it.contextWindow > 0 }?.percent?.roundToInt(),
                )
            }
            .distinctUntilChanged()

    private companion object {
        const val FOLLOW_MAX = 8
        const val LIVE_POLL_MS = 2_000L
    }
}
