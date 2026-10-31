package app.umbra.agent

import android.app.Notification
import app.umbra.runtime.omp.AskAnswer
import app.umbra.runtime.omp.UiResponse
import app.umbra.runtime.omp.UiRequest
import app.umbra.core.orchestrator.QuestionBroker
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
import kotlinx.coroutines.flow.collectLatest
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
    private val questionNotifications = HashMap<String, Int>()
    private var staleQuestionsCleared = false
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
            combine(container.sessionStore.sessionsFlow(), container.approvals.pending, container.questions.pending) { sessions, approvals, questions -> Triple(sessions, approvals, questions) }
                .collect { (sessions, approvals, questions) ->
                    val questionCount = questions.size
                    val active = sessions.filter { it.state == SessionState.RUNNING || it.state == SessionState.WAITING_APPROVAL }
                    val paused = sessions.filter { it.state == SessionState.PAUSED }
                    manager.notify(ONGOING_ID, ongoing(active, paused, approvals.size + questionCount))
                    // Only work keeps the CPU awake: a task waiting for the user's decision needs nothing until they answer
                    // (an approval arrives through a notification or the app, which wake the phone anyway).
                    holdAwake(sessions.any { it.state == SessionState.RUNNING })
                    syncApprovals(approvals)
                    syncQuestions(questions)
                    announceResults(sessions)
                }
        }
        // An unanswered approval is denied when its time runs out: warn once more in its last minute (S-04). It sleeps until
        // the next warning is due instead of looking every 5 s.
        scope.launch {
            container.approvals.pending.collectLatest { pending ->
                lastMinuteWarned.retainAll(pending.map { it.id }.toSet())
                while (true) {
                    val now = System.currentTimeMillis()
                    for (request in pending) {
                        val left = request.createdAtMs + request.timeoutMs - now
                        if (left in 1..60_000 && lastMinuteWarned.add(request.id)) notifyApproval(request, lastMinute = true)
                    }
                    val next = pending.filter { it.id !in lastMinuteWarned }.map { it.createdAtMs + it.timeoutMs - 60_000 }.filter { it > now }.minOrNull() ?: break
                    delay(next - now)
                }
            }
        }
        // Stop being a foreground service after a quiet while; opening the app starts it again.
        scope.launch {
            var idleSince = System.currentTimeMillis()
            while (true) {
                delay(30_000)
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

    /**
     * A question an agent or session is waiting on (omp's ask, select, confirm, text): a heads-up notification that
     * answers it where it can — up to three options or No/Yes as buttons, a short text as a reply — and otherwise opens
     * it. Answered anywhere, it goes away. Agents talking on Telegram ask there instead, as approvals do.
     */
    private suspend fun syncQuestions(pending: List<QuestionBroker.Pending>) {
        if (!staleQuestionsCleared) {
            staleQuestionsCleared = true
            // Questions live in memory: one shown before the app last stopped belongs to a turn that is gone, and its
            // buttons would do nothing.
            manager.activeNotifications.map { it.id }.filter { it in QUESTION_BASE..QUESTION_BASE + 0xFFFF }.forEach(::closeQuestion)
        }
        val local = pending.filterNot { fromTelegram(it.sessionId) }
        val ids = local.map { it.request.id }.toSet()
        questionNotifications.keys.filter { it !in ids }.forEach { closeQuestion(questionNotifications.remove(it)!!) }
        if (!container.settings.value.notifyOnApproval) return
        for (q in local) if (q.request.id !in questionNotifications) notifyQuestion(q)
    }

    private suspend fun notifyQuestion(q: QuestionBroker.Pending) {
        val id = questionNotifications.getOrPut(q.request.id) { QUESTION_BASE + (q.request.id.hashCode() and 0xFFFF) }
        val asker = app.umbra.agent.ui.questions.askerOf(container, q.sessionId)
        val agent = app.umbra.agent.ui.questions.agentOf(container, q.sessionId)
        val single = (q.request as? UiRequest.Ask)?.questions?.singleOrNull()
        val text = when (val r = q.request) {
            is UiRequest.Select -> r.title
            is UiRequest.Confirm -> listOf(r.title, r.message).filter { it.isNotBlank() }.joinToString("\n")
            is UiRequest.Input -> r.title
            is UiRequest.Editor -> r.title
            is UiRequest.Ask -> r.questions.joinToString("\n") { it.question }
        }
        val choices = when (val r = q.request) {
            is UiRequest.Select -> r.options
            is UiRequest.Confirm -> listOf(getString(R.string.q_no), getString(R.string.q_yes))
            is UiRequest.Ask -> single?.takeIf { !it.multi }?.options?.map { it.label }.orEmpty()
            else -> emptyList()
        }
        val typed = q.request is UiRequest.Input || single?.options?.isEmpty() == true
        val opens = if (agent != null) openPage("agent/$agent", id * 4) else open(q.sessionId)
        val builder = Notification.Builder(this, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_stat_umbra)
            .setContentTitle(asker?.let { getString(R.string.qa_asks, it) } ?: getString(R.string.qa_asks_anon))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(opens)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
        when {
            choices.size in 1..3 -> choices.forEachIndexed { i, label ->
                builder.addAction(Notification.Action.Builder(null, label, PendingIntent.getBroadcast(this, id * 4 + i,
                    Intent(this, ApprovalActionReceiver::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_REQUEST, q.request.id).putExtra(EXTRA_OPTION, i),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            }
            typed -> {
                // A reply needs a mutable PendingIntent for Android to fill in the words; the intent is explicit.
                val reply = PendingIntent.getBroadcast(this, id * 4 + 3,
                    Intent(this, ApprovalActionReceiver::class.java).setAction(ACTION_ANSWER_TEXT).putExtra(EXTRA_REQUEST, q.request.id),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(Notification.Action.Builder(null, getString(R.string.qa_reply), reply)
                    .addRemoteInput(android.app.RemoteInput.Builder(EXTRA_ANSWER_TEXT).setLabel(getString(R.string.qa_your_answer)).build()).build())
            }
            else -> builder.addAction(Notification.Action.Builder(null, getString(R.string.qa_answer), opens).build())
        }
        manager.notify(id, builder.build())
    }

    /**
     * A question's notification goes when the question does. One answered from its reply field is kept by Android to
     * show the reply, and the app may not cancel it then: it becomes a quiet "Answered" that times out instead.
     */
    private fun closeQuestion(id: Int) {
        val replied = manager.activeNotifications.any { it.id == id && it.notification.flags and FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY != 0 }
        if (!replied) { manager.cancel(id); return }
        manager.notify(id, Notification.Builder(this, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_stat_umbra)
            .setContentTitle(getString(R.string.qa_answered))
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(1_500)
            .build())
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
        const val ACTION_ANSWER = "app.umbra.agent.ANSWER"
        const val ACTION_ANSWER_TEXT = "app.umbra.agent.ANSWER_TEXT"
        const val EXTRA_OPTION = "option"
        const val EXTRA_ANSWER_TEXT = "answer"
        private const val QUESTION_BASE = 200_000
        /** Notification.FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY, hidden in the SDK: set while Android keeps a replied notification. */
        private const val FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY = 0x00010000
        const val EXTRA_SESSION = "session"
        const val PAGE_STOP_ALL = "stop-all"
        private const val ONGOING_ID = 1
        private const val TEACH_ID = 4109
        private const val ACTION_TEACH_STOP = "app.umbra.agent.TEACH_STOP"
        private const val NEXT_ID_BASE = 1_000
        private const val RESULT_BASE = 100_000
        private const val IDLE_STOP_MS = 90_000L

        /**
         * Android refuses a foreground-service start from the background unless an exemption applies (an exact alarm,
         * the battery-optimization exemption the app asks for, a visible window). Refused, the work goes on in this
         * process while it lives and the next wake tries again; it must not crash whoever asked.
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, AgentService::class.java))
            } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                android.util.Log.w("AgentService", "foreground start refused in the background", e)
            }
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
            // A question answered from its notification: the answer is rebuilt from the question still waiting, never
            // taken from the intent beyond which option was tapped or the words typed.
            AgentService.ACTION_ANSWER -> {
                val id = intent.getStringExtra(AgentService.EXTRA_REQUEST) ?: return
                val index = intent.getIntExtra(AgentService.EXTRA_OPTION, -1)
                val q = container.questions.pending.value.firstOrNull { it.request.id == id } ?: return
                val response = when (val r = q.request) {
                    is UiRequest.Select -> r.options.getOrNull(index)?.let { UiResponse.Value(it) }
                    is UiRequest.Confirm -> when (index) { 0 -> UiResponse.Confirmed(false); 1 -> UiResponse.Confirmed(true); else -> null }
                    is UiRequest.Ask -> r.questions.singleOrNull()?.takeIf { !it.multi }?.let { one ->
                        one.options.getOrNull(index)?.let { UiResponse.Answers(listOf(AskAnswer(one.id, listOf(it.label), null))) }
                    }
                    else -> null
                } ?: return
                container.questions.answer(id, response)
            }
            AgentService.ACTION_ANSWER_TEXT -> {
                val id = intent.getStringExtra(AgentService.EXTRA_REQUEST) ?: return
                val text = android.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AgentService.EXTRA_ANSWER_TEXT)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return
                val q = container.questions.pending.value.firstOrNull { it.request.id == id } ?: return
                val response = when (val r = q.request) {
                    is UiRequest.Input -> UiResponse.Value(text)
                    is UiRequest.Ask -> r.questions.singleOrNull()?.let { UiResponse.Answers(listOf(AskAnswer(it.id, emptyList(), text))) }
                    else -> null
                } ?: return
                container.questions.answer(id, response)
            }
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
