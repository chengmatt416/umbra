package app.umbra.agent.capsule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
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
    private var pending = false
    private val power = context.getSystemService(PowerManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            synchronized(this@CapsuleSignals) {
                if (!registered) return
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> pending = true
                    Intent.ACTION_SCREEN_ON -> if (pending) request()
                    ACTION -> {
                        if (!power.isInteractive) { pending = true; return }
                        if (container.settings.value.overlayEnabled) container.capsule.start() else container.capsule.stop()
                        mutableRevision.value++
                    }
                    else -> Unit
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        synchronized(this) {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION).apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
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
                    delay(200) // Streaming updates share a broadcast; the sleeping screen needs none.
                }
            }
        }
    }

    /** Keep one invalidation while the screen sleeps, rather than waking the overlay for every streamed token. */
    fun request() = synchronized(this) {
        if (!registered) return@synchronized
        if (!power.isInteractive) { pending = true; return@synchronized }
        pending = false
        context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
    }

    fun stop() {
        job?.cancel()
        job = null
        synchronized(this) {
            if (registered) context.unregisterReceiver(receiver)
            registered = false
            pending = false
        }
    }

    internal fun <T> read(source: StateFlow<T>) = content { source.value }

    internal fun <T> content(read: suspend () -> T) = capsuleContent(revision, read)

    private companion object { const val ACTION = "app.umbra.agent.CAPSULE_CONTENT_CHANGED" }
}
