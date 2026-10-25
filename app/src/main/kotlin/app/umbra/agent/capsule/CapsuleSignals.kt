package app.umbra.agent.capsule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.umbra.agent.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** The main program publishes invalidations; the overlay reads app-owned content only after receiving one. */
class CapsuleSignals(private val context: Context, private val container: AppContainer) {
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private var job: Job? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION || !registered) return
            if (container.settings.value.overlayEnabled) container.capsule.start() else container.capsule.stop()
            mutableRevision.value++
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        job = scope.launch {
            container.settings.flow.map { it.overlayEnabled }.distinctUntilChanged().collectLatest { enabled ->
                if (!enabled) { request(); return@collectLatest }
                merge(
                    container.sessionStore.changes.map { Unit },
                    container.approvals.pending.map { Unit },
                    container.questions.pending.map { Unit },
                    container.credentialHandoff.pending.map { Unit },
                    container.settings.flow.map { Unit },
                    container.emergency.state.map { Unit },
                    container.inputLeases.all.map { Unit },
                    container.ui.focusedSession.map { Unit },
                    container.ui.appVisible.map { Unit },
                    container.ui.composerTarget.map { Unit },
                    container.ui.draft.map { Unit },
                    container.ui.draftContext.map { Unit },
                    container.ui.homeProject.map { Unit },
                    container.projects.all.map { Unit },
                    container.agents.store.profiles.map { Unit },
                    container.agents.proactive.store.revision.map { Unit },
                    container.recorder.state.map { Unit },
                    container.demonstrations.active.map { Unit },
                    app.umbra.platform.android.UmbraAccessibilityService.connected.map { Unit },
                ).conflate().collect {
                    request()
                    delay(75) // Coalesce bursts of timeline and streaming events; no timer while idle.
                }
            }
        }
    }

    fun request() {
        if (registered) context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
    }

    fun stop() {
        job?.cancel()
        job = null
        if (registered) context.unregisterReceiver(receiver)
        registered = false
    }

    internal fun <T> read(source: StateFlow<T>) = content { source.value }

    internal fun <T> content(read: suspend () -> T) = capsuleContent(revision, read)

    private companion object { const val ACTION = "app.umbra.agent.CAPSULE_CONTENT_CHANGED" }
}
