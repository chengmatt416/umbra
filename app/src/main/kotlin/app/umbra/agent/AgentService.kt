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
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.safety.ApprovalRequest
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
    private val lastStates = HashMap<String, SessionState>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlay: OverlayController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Agent status", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_RESULTS, "Task results", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            started = true
            startForeground(ONGOING_ID, ongoing(emptyList(), emptyList(), 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            observe()
            overlay = OverlayController(this, container, scope).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        overlay?.stop()
        holdAwake(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        val container = container
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
        // Stop being a foreground service after a quiet while; opening the app starts it again.
        scope.launch {
            var idleSince = System.currentTimeMillis()
            while (true) {
                delay(15_000)
                val busy = container.sessionStore.list().any { it.state == SessionState.RUNNING || it.state == SessionState.WAITING_APPROVAL || it.state == SessionState.PAUSED } ||
                    container.approvals.pending.value.isNotEmpty() || container.questions.pending.value.isNotEmpty()
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

    private fun broadcast(action: String, requestId: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        this, code, Intent(this, ApprovalActionReceiver::class.java).setAction(action).putExtra(EXTRA_REQUEST, requestId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoing(active: List<Session>, paused: List<Session>, waiting: Int): Notification {
        val title = when {
            waiting > 0 -> "Umbra needs you"
            active.size == 1 -> "Working: ${active.first().title}"
            active.size > 1 -> "Working on ${active.size} tasks"
            paused.size == 1 -> "Paused: ${paused.first().title}"
            paused.size > 1 -> "${paused.size} tasks paused"
            else -> "Umbra is ready"
        }
        val text = when {
            waiting > 0 -> "$waiting decision${if (waiting > 1) "s" else ""} waiting"
            active.isNotEmpty() -> "${active.size} running${if (paused.isNotEmpty()) ", ${paused.size} paused" else ""} · tap to watch"
            paused.isNotEmpty() -> "Tap to resume or review"
            else -> "Tap to start a task"
        }
        val builder = Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_umbra)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open((active.singleOrNull() ?: paused.singleOrNull())?.id))
        if (active.size == 1) builder.addAction(action("Pause", ACTION_PAUSE, active.single().id, 2))
        paused.firstOrNull()?.let { builder.addAction(action("Resume", ACTION_RESUME, it.id, 3)) }
        if (active.isNotEmpty() || paused.isNotEmpty()) builder.addAction(action("Stop", ACTION_STOP_ALL, null, 1))
        return builder.build()
    }

    private fun action(label: String, action: String, sessionId: String?, code: Int): Notification.Action = Notification.Action.Builder(
        null, label,
        PendingIntent.getBroadcast(
            this, code, Intent(this, ApprovalActionReceiver::class.java).setAction(action).apply { sessionId?.let { putExtra(EXTRA_SESSION, it) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    ).build()

    /** One heads-up notification per pending approval, answerable from the shade. */
    private fun syncApprovals(pending: List<ApprovalRequest>) {
        val enabled = container.settings.value.notifyOnApproval
        val ids = pending.map { it.id }.toSet()
        approvalNotifications.keys.filter { it !in ids }.forEach { manager.cancel(approvalNotifications.remove(it)!!) }
        if (!enabled) return
        for (request in pending) {
            if (request.id in approvalNotifications) continue
            val id = NEXT_ID_BASE + approvalNotifications.size + (request.id.hashCode() and 0xFFFF)
            approvalNotifications[request.id] = id
            val risk = request.assessment.level
            val notification = Notification.Builder(this, CHANNEL_APPROVALS)
                .setSmallIcon(R.drawable.ic_stat_umbra)
                .setContentTitle(if (risk >= RiskLevel.HIGH) "Approval needed — ${risk.name.lowercase()} risk" else "Approval needed")
                .setContentText(request.description)
                .setStyle(Notification.BigTextStyle().bigText(request.description + "\n" + request.assessment.describe()))
                .setContentIntent(open(request.sessionId))
                .setAutoCancel(false)
                .addAction(Notification.Action.Builder(null, "Deny", broadcast(ACTION_DENY, request.id, id * 2)).build())
                .apply { if (risk < RiskLevel.CRITICAL) addAction(Notification.Action.Builder(null, "Approve", broadcast(ACTION_APPROVE, request.id, id * 2 + 1)).build()) }
                .build()
            manager.notify(id, notification)
        }
    }

    private fun announceResults(sessions: List<Session>) {
        for (s in sessions) {
            val before = lastStates.put(s.id, s.state)
            if (before == null || before == s.state) continue
            if (before != SessionState.RUNNING && before != SessionState.WAITING_APPROVAL) continue
            val (title, text) = when (s.state) {
                SessionState.COMPLETED -> "Task finished" to s.title
                SessionState.FAILED -> "Task failed" to "${s.title}: ${s.lastError ?: "see the task for details"}"
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
        private const val ONGOING_ID = 1
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
            AgentService.ACTION_APPROVE -> intent.getStringExtra(AgentService.EXTRA_REQUEST)?.let { container.approvals.resolve(it, ApprovalDecision.Approved()) }
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
                        container.sessionStore.list().filter { !it.state.isTerminal && it.state != SessionState.CREATED }.forEach { container.supervisor.stop(it.id) }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
