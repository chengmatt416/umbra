package app.umbra.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import app.umbra.agent.ui.localizedDescription
import app.umbra.agent.notifications.shouldAnnounceResult
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.safety.ApprovalRequest
import app.umbra.core.safety.ApprovalSurface
import app.umbra.core.safety.ApproveGesture
import app.umbra.core.safety.approveGesture
import app.umbra.core.safety.RiskLevel
import app.umbra.core.session.Session
import app.umbra.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app's process alive while agent sessions run (spec §27). Its notification is the user's
 * always-visible handle on the agent: what is running, a way to stop it, and approval requests that
 * can be answered from the shade without opening the app.
 */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: NotificationManager
    private var started = false
    private val approvalNotifications = HashMap<String, Int>()
    private val lastMinuteWarned = HashSet<String>()
    private val lastStates = HashMap<String, SessionState>()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, getString(R.string.notif_channel_status), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_APPROVALS, getString(R.string.notif_channel_approvals), NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_RESULTS, getString(R.string.notif_channel_results), NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEACH_STOP) scope.launch { container.demonstrations.stop() }
        if (!started) {
            started = true
            startForeground(ONGOING_ID, ongoing(emptyList(), emptyList(), 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            observe()
            container.capsuleSignals.start(scope)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        container.capsuleSignals.stop()
        container.capsule.stop()
        holdAwake(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        val container = container
        scope.launch {
            container.demonstrations.active.collect { recording ->
                if (recording == null) manager.cancel(TEACH_ID)
                else manager.notify(TEACH_ID, Notification.Builder(this@AgentService, CHANNEL_STATUS)
                    .setSmallIcon(R.drawable.ic_stat_umbra).setContentTitle(getString(R.string.teach_recording))
                    .setContentText(getString(R.string.teach_stop)).setOngoing(true).setOnlyAlertOnce(true)
                    .setContentIntent(openPage("agents", TEACH_ID))
                    .addAction(Notification.Action.Builder(null, getString(R.string.teach_stop), PendingIntent.getService(this@AgentService, TEACH_ID,
                        Intent(this@AgentService, AgentService::class.java).setAction(ACTION_TEACH_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()).build())
            }
        }
        scope.launch {
            combine(container.sessionStore.sessionsFlow(), container.approvals.pending, container.questions.pending) { sessions, approvals, questions -> Triple(sessions, approvals, questions.size) }
                .collect { (sessions, approvals, questionCount) ->
                    val active = sessions.filter { it.state == SessionState.RUNNING || it.state == SessionState.WAITING_APPROVAL }
                    val paused = sessions.filter { it.state == SessionState.PAUSED }
                    manager.notify(ONGOING_ID, ongoing(active, paused, approvals.size + questionCount))
                    holdAwake(active.isNotEmpty())
                    syncApprovals(approvals)
                    announceResults(sessions)
                }
        }
        // An unanswered approval is denied when its time runs out: warn once more in its last minute (S-04).
        scope.launch {
            while (true) {
                delay(5_000)
                val now = System.currentTimeMillis()
                for (request in container.approvals.pending.value) {
                    val left = request.createdAtMs + request.timeoutMs - now
                    if (left in 1..60_000 && lastMinuteWarned.add(request.id)) notifyApproval(request, lastMinute = true)
                }
                lastMinuteWarned.retainAll(container.approvals.pending.value.map { it.id }.toSet())
            }
        }
        // Stop being a foreground service after a quiet while; opening the app starts it again.
        scope.launch {
            var idleSince = System.currentTimeMillis()
            while (true) {
                delay(15_000)
                val busy = container.sessionStore.list().any { it.state == SessionState.RUNNING || it.state == SessionState.WAITING_APPROVAL || it.state == SessionState.PAUSED } ||
                    container.approvals.pending.value.isNotEmpty() || container.questions.pending.value.isNotEmpty() ||
                    // An agent runs all the time: its wake loop lives in this process.
                    (container.agents.profiles().any { it.running } || container.telegram.active) ||
                    container.settings.value.layaWanted || container.demonstrations.active.value != null || container.credentialHandoff.pending.value != null ||
                    // The capsule is on screen: the process must stay for it to stay.
                    (container.settings.value.overlayEnabled && (android.provider.Settings.canDrawOverlays(this@AgentService) || app.umbra.platform.android.UmbraAccessibilityService.connected.value))
                if (busy) idleSince = System.currentTimeMillis()
                else if (System.currentTimeMillis() - idleSince > IDLE_STOP_MS) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
            }
        }
    }

    /** Keeps the CPU running while a task works, so it continues with the screen off. Released as soon as nothing runs. */
    private fun holdAwake(on: Boolean) {
        if (on && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "umbra:agent").apply { acquire(6 * 60 * 60 * 1000L) }
        } else if (!on) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private fun open(sessionId: String? = null): PendingIntent = PendingIntent.getActivity(
        this, sessionId.hashCode(),
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK).apply { sessionId?.let { putExtra(MainActivity.EXTRA_SESSION, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Opens a page of the app by its route: "inbox", or "stop-all" for the stop confirmation. */
    private fun openPage(page: String, code: Int): PendingIntent = PendingIntent.getActivity(
        this, code,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_PAGE, page),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(action: String, requestId: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        this, code, Intent(this, ApprovalActionReceiver::class.java).setAction(action).putExtra(EXTRA_REQUEST, requestId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoing(active: List<Session>, paused: List<Session>, waiting: Int): Notification {
        val title = when {
            waiting > 0 -> getString(R.string.notif_needs_you)
            active.size == 1 -> getString(R.string.notif_working_one, active.first().title)
            active.size > 1 -> getString(R.string.notif_working_many, active.size)
            paused.size == 1 -> getString(R.string.notif_paused_one, paused.first().title)
            paused.size > 1 -> getString(R.string.notif_paused_many, paused.size)
            else -> getString(R.string.notif_ready)
        }
        val text = when {
            waiting > 0 -> resources.getQuantityString(R.plurals.notif_decisions, waiting, waiting)
            active.isNotEmpty() && paused.isNotEmpty() -> getString(R.string.notif_running_paused, active.size, paused.size)
            active.isNotEmpty() -> getString(R.string.notif_running, active.size)
            paused.isNotEmpty() -> getString(R.string.notif_paused_text)
            else -> getString(R.string.notif_idle_text)
        }
        val builder = Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_umbra)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open((active.singleOrNull() ?: paused.singleOrNull())?.id))
        if (active.size == 1) builder.addAction(action(getString(R.string.notif_pause), ACTION_PAUSE, active.single().id, 2))
        paused.firstOrNull()?.let { builder.addAction(action(getString(R.string.notif_resume), ACTION_RESUME, it.id, 3)) }
        // Stopping everything is confirmed in the app, where it says how many working, paused and queued tasks it ends.
        if (active.isNotEmpty() || paused.isNotEmpty()) builder.addAction(Notification.Action.Builder(null, getString(R.string.stop_all_short), openPage(PAGE_STOP_ALL, 1)).build())
        return builder.build()
    }

    private fun action(label: String, action: String, sessionId: String?, code: Int): Notification.Action = Notification.Action.Builder(
        null, label,
        PendingIntent.getBroadcast(
            this, code, Intent(this, ApprovalActionReceiver::class.java).setAction(action).apply { sessionId?.let { putExtra(EXTRA_SESSION, it) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    ).build()

    /**
     * One heads-up notification per pending approval. The shade shows one line and needs no unlock, so it answers only
     * what the shared rule lets it (LOW and MEDIUM); anything riskier opens the Inbox (UX report C-07, C-08).
     */
    private suspend fun syncApprovals(pending: List<ApprovalRequest>) {
        val enabled = container.settings.value.notifyOnApproval
        val local = pending.filterNot { fromTelegram(it.sessionId) }
        val ids = local.map { it.id }.toSet()
        approvalNotifications.keys.filter { it !in ids }.forEach { manager.cancel(approvalNotifications.remove(it)!!) }
        if (!enabled) return
        for (request in local) if (request.id !in approvalNotifications) notifyApproval(request, lastMinute = false)
    }

    private suspend fun notifyApproval(request: ApprovalRequest, lastMinute: Boolean) {
        if (!container.settings.value.notifyOnApproval) return
        if (fromTelegram(request.sessionId)) return
        val id = approvalNotifications.getOrPut(request.id) { NEXT_ID_BASE + approvalNotifications.size + (request.id.hashCode() and 0xFFFF) }
        val risk = request.assessment.level
        val riskText = getString(app.umbra.agent.ui.riskRes(risk))
        val oneTap = approveGesture(request, ApprovalSurface.NOTIFICATION) == ApproveGesture.ONE_TAP
        val title = when {
            lastMinute -> getString(R.string.notif_approval_last_minute, riskText)
            risk >= RiskLevel.HIGH -> getString(R.string.notif_approval_risk, riskText)
            else -> getString(R.string.notif_approval)
        }
        val body = buildString {
            append(if (lastMinute) getString(R.string.notif_approval_last_minute_text, request.description) else request.description)
            append("\n").append(request.assessment.localizedDescription(resources))
            if (!oneTap) append("\n\n").append(getString(R.string.notif_high_note))
        }
        val notification = Notification.Builder(this, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_stat_umbra)
            .setContentTitle(title)
            .setContentText(request.description)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(if (oneTap) open(request.sessionId) else openPage("inbox", id * 2 + 1))
            .setAutoCancel(false)
            .setOnlyAlertOnce(!lastMinute)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_deny), broadcast(ACTION_DENY, request.id, id * 2)).build())
            .addAction(
                if (oneTap) Notification.Action.Builder(null, getString(R.string.notif_approve), broadcast(ACTION_APPROVE, request.id, id * 2 + 1)).build()
                else Notification.Action.Builder(null, getString(R.string.notif_review), openPage("inbox", id * 2 + 1)).build(),
            )
            .build()
        manager.notify(id, notification)
    }

    private suspend fun fromTelegram(sessionId: String): Boolean =
        container.sessionStore.get(sessionId)?.agentId?.let { container.agents.repliesOnTelegram(it) } == true

    private suspend fun announceResults(sessions: List<Session>) {
        for (s in sessions) {
            val before = lastStates.put(s.id, s.state)
            if (!shouldAnnounceResult(s, before)) continue
            if (s.agentId?.let { container.agents.repliesOnTelegram(it) } == true) continue
            val (title, text) = when (s.state) {
                SessionState.COMPLETED -> getString(R.string.notif_finished) to s.title
                SessionState.FAILED -> getString(R.string.notif_failed) to getString(R.string.notif_failed_text, s.title, s.lastError ?: getString(R.string.notif_see_task))
                else -> continue
            }
            manager.notify(
                RESULT_BASE + (s.id.hashCode() and 0xFFFF),
                Notification.Builder(this, CHANNEL_RESULTS).setSmallIcon(R.drawable.ic_stat_umbra).setContentTitle(title).setContentText(text)
                    .setContentIntent(open(s.id)).setAutoCancel(true).build(),
            )
        }
    }

    companion object {
        const val CHANNEL_STATUS = "agent"
        const val CHANNEL_APPROVALS = "approvals"
        const val CHANNEL_RESULTS = "results"
        const val ACTION_APPROVE = "app.umbra.agent.APPROVE"
        const val ACTION_DENY = "app.umbra.agent.DENY"
        const val ACTION_STOP_ALL = "app.umbra.agent.STOP_ALL"
        const val ACTION_PAUSE = "app.umbra.agent.PAUSE"
        const val ACTION_RESUME = "app.umbra.agent.RESUME"
        const val EXTRA_REQUEST = "request"
        const val EXTRA_SESSION = "session"
        const val PAGE_STOP_ALL = "stop-all"
        private const val ONGOING_ID = 1
        private const val TEACH_ID = 4109
        private const val ACTION_TEACH_STOP = "app.umbra.agent.TEACH_STOP"
        private const val NEXT_ID_BASE = 1_000
        private const val RESULT_BASE = 100_000
        private const val IDLE_STOP_MS = 90_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AgentService::class.java))
        }
    }
}

/** Answers approvals and stops tasks from notification buttons. */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        when (intent.action) {
            AgentService.ACTION_APPROVE -> intent.getStringExtra(AgentService.EXTRA_REQUEST)?.let { id ->
                // The shade may approve only what the shared rule allows there, whatever the intent says.
                val request = container.approvals.pending.value.firstOrNull { it.id == id } ?: return
                if (approveGesture(request, ApprovalSurface.NOTIFICATION) == ApproveGesture.ONE_TAP) container.approvals.resolve(id, ApprovalDecision.Approved())
            }
            AgentService.ACTION_DENY -> intent.getStringExtra(AgentService.EXTRA_REQUEST)?.let { container.approvals.resolve(it, ApprovalDecision.Denied("denied from the notification")) }
            AgentService.ACTION_PAUSE, AgentService.ACTION_RESUME -> {
                val id = intent.getStringExtra(AgentService.EXTRA_SESSION) ?: return
                val pending = goAsync()
                container.scope.launch {
                    try {
                        if (intent.action == AgentService.ACTION_PAUSE) container.supervisor.pause(id) else container.supervisor.resume(id)
                    } finally {
                        pending.finish()
                    }
                }
            }
            AgentService.ACTION_STOP_ALL -> {
                val pending = goAsync()
                container.scope.launch {
                    try {
                        container.supervisor.stopAll()
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
