package app.umbra.agent.capsule

import app.umbra.agent.AppContainer
import app.umbra.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Bounded reads of app-owned activity, triggered only by the main program's internal broadcast. */
internal class CapsuleWatch(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val signal: (Signal) -> Unit,
) {
    enum class Signal { REQUEST, DONE }

    val activity = MutableStateFlow<Map<String, SessionActivity>>(emptyMap())
    val finished = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val expiry = HashMap<String, Job>()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var before: Map<String, SessionState>? = null
            var requests: Set<String>? = null
            container.capsuleSignals.revision.collect {
                val sessions = container.sessionStore.list()
                before?.let { prev -> Signals.completed(prev, sessions).forEach(::markFinished) }
                before = sessions.associate { it.id to it.state }
                val ids = container.approvals.pending.value.map { it.id }.toSet() + container.questions.pending.value.map { it.request.id }
                requests?.let { if (Signals.arrived(it, ids)) signal(Signal.REQUEST) }
                requests = ids
                val now = System.currentTimeMillis()
                finished.update { current -> current.filterValues { now - it < Crews.DONE_MS } }
                val current = activity.value
                activity.value = sessions.filter(Crews::isLive).sortedByDescending { it.updatedAtMs }.take(FOLLOW_MAX).associate { session ->
                    val records = container.sessionStore.tail(session.id, Feed.TAIL)
                    val feed = Feed.of(records)
                    val live = container.supervisor.liveSession(session.id)?.conversation?.value
                    session.id to SessionActivity(
                        feed = feed,
                        toolRunning = live?.tools?.values?.any { it.running } == true || Feed.toolRunning(feed),
                        compacting = live?.compacting == true,
                        retrying = live?.retry != null,
                        contextPercent = live?.session?.contextUsage?.takeIf { it.contextWindow > 0 }?.percent?.roundToInt(),
                        runStartedAtMs = Feed.runStart(records) ?: current[session.id]?.runStartedAtMs,
                    )
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        expiry.values.forEach { it.cancel() }
        expiry.clear()
        activity.value = emptyMap()
        finished.value = emptyMap()
    }

    private fun markFinished(id: String) {
        finished.update { it + (id to System.currentTimeMillis()) }
        signal(Signal.DONE)
        expiry.remove(id)?.cancel()
        expiry[id] = scope.launch {
            delay(Crews.DONE_MS + 100)
            expiry.remove(id)
            container.capsuleSignals.request()
        }
    }

    private companion object { const val FOLLOW_MAX = 8 }
}
