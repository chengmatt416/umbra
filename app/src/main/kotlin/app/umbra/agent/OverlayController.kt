package app.umbra.agent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.safety.ApprovalRequest
import app.umbra.core.safety.RiskLevel
import app.umbra.core.session.Session
import app.umbra.core.session.SessionState
import app.umbra.platform.android.UmbraOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The floating controls (spec §26): a small bubble that shows the agent is working and where the user
 * can stop it, expanding into a card with the current task, pending approvals and pause, stop and open
 * buttons. It is drawn over other apps while a task runs, only when the user turned it on and granted
 * the draw-over-apps permission, and its window carries a title the accessibility backend skips, so
 * the agent never sees or touches its own controls.
 */
class OverlayController(private val context: Context, private val container: AppContainer, private val scope: CoroutineScope) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var job: Job? = null
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false
    private var typing = false
    private var latest = Snapshot()

    private data class Snapshot(val sessions: List<Session> = emptyList(), val approvals: List<ApprovalRequest> = emptyList(), val questions: Int = 0)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(container.settings.flow, container.sessionStore.sessionsFlow(), container.approvals.pending, container.questions.pending) { prefs, sessions, approvals, questions ->
                val active = sessions.filter { it.state == SessionState.RUNNING || it.state == SessionState.WAITING_APPROVAL }
                if (prefs.overlayEnabled && Settings.canDrawOverlays(context) && (active.isNotEmpty() || approvals.isNotEmpty())) Snapshot(active, approvals, questions.size) else null
            }.collect { snapshot ->
                if (snapshot == null) remove() else {
                    latest = snapshot
                    // A decision waiting for the user opens the card by itself; the bubble alone would be easy to miss.
                    if (snapshot.approvals.isNotEmpty()) expanded = true
                    // Redrawing would throw away what the user is typing.
                    if (!typing) render()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        remove()
    }

    private fun dp(value: Int) = (value * density).toInt()

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(12)
        y = dp(96)
        title = UmbraOverlay.WINDOW_TITLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun render() {
        val view = root ?: FrameLayout(context).also { frame ->
            val p = layoutParams()
            params = p
            root = frame
            windowManager.addView(frame, p)
            enableDragging(frame)
        }
        view.removeAllViews()
        view.addView(if (expanded) card() else bubble())
    }

    /** The card can take typing only while the user is typing: otherwise the overlay must never steal focus from the app under it. */
    private fun setTyping(on: Boolean) {
        typing = on
        val p = params
        val frame = root
        if (p != null && frame != null) {
            p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { windowManager.updateViewLayout(frame, p) }
        }
        if (!on) frame?.let { f -> context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(f.windowToken, 0) }
        render()
    }

    private fun remove() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        params = null
        expanded = false
        typing = false
    }

    private fun bubble(): View {
        val waiting = latest.approvals.isNotEmpty() || latest.questions > 0
        val color = if (waiting) Color.parseColor("#E29A19") else Color.parseColor("#3B82F6")
        return TextView(context).apply {
            text = if (waiting) "!" else "●"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            // No elevation: in a see-through overlay window its shadow shows as a pale square around the shape.
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.parseColor("#66FFFFFF")) }
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { expanded = true; render() }
            contentDescription = "Umbra is working. Tap for controls."
        }
    }

    private fun card(): View {
        val dark = Color.parseColor("#1F2330")
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(dark); setStroke(dp(1), Color.parseColor("#33FFFFFF")) }
            layoutParams = FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val session = latest.sessions.firstOrNull()
        column.addView(label(session?.title ?: "Umbra", 15f, Color.WHITE, bold = true, lines = 2))
        column.addView(label(
            when {
                latest.approvals.isNotEmpty() || latest.questions > 0 -> "Needs your decision"
                session?.state == SessionState.RUNNING -> "Working… ${session.plan.count { it.status == "done" }.let { done -> if (session.plan.isNotEmpty()) "step $done of ${session.plan.size}" else "" }}"
                else -> "Ready"
            }, 12f, Color.parseColor("#A9B0C3"),
        ))
        latest.approvals.firstOrNull()?.let { request ->
            val tone = if (request.assessment.level >= RiskLevel.HIGH) Color.parseColor("#D64545") else Color.parseColor("#E29A19")
            column.addView(label(request.description, 13f, Color.WHITE, lines = 4).also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8) })
            column.addView(label(request.assessment.describe(), 11f, tone, lines = 3))
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            row.addView(button("Deny", Color.parseColor("#3A4054")) { container.approvals.resolve(request.id, ApprovalDecision.Denied("denied from the floating controls")) })
            if (request.assessment.level < RiskLevel.CRITICAL) row.addView(button("Approve", tone) { container.approvals.resolve(request.id, ApprovalDecision.Approved()) })
            column.addView(row)
        }
        if (typing) {
            val input = android.widget.EditText(context).apply {
                hint = "Tell Umbra…"
                setHintTextColor(Color.parseColor("#7E869A"))
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 3
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#2A3042")) }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            }
            column.addView(input)
            val send = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            send.addView(button("Send", Color.parseColor("#3B82F6")) {
                val text = input.text.toString().trim()
                val target = session
                if (text.isNotEmpty() && target != null) scope.launch { runCatching { container.supervisor.send(target.id, text) } }
                setTyping(false)
            })
            send.addView(button("Cancel", Color.parseColor("#3A4054")) { setTyping(false) })
            column.addView(send)
            input.post { input.requestFocus(); context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(input, 0) }
        }
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        if (!typing && session != null) controls.addView(button("Message", Color.parseColor("#3A4054")) { setTyping(true) })
        controls.addView(button("Open", Color.parseColor("#3A4054")) {
            context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP).apply { session?.let { putExtra(MainActivity.EXTRA_SESSION, it.id) } })
            expanded = false; render()
        })
        controls.addView(button("Pause", Color.parseColor("#3A4054")) { session?.let { s -> scope.launch { container.supervisor.pause(s.id) } } })
        controls.addView(button("Stop", Color.parseColor("#D64545")) { latest.sessions.forEach { s -> scope.launch { container.supervisor.stop(s.id) } } })
        column.addView(controls)
        column.addView(button("Hide", Color.TRANSPARENT) { expanded = false; render() }.apply { (layoutParams as? LinearLayout.LayoutParams)?.gravity = Gravity.END })
        return column
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false, lines: Int = 1) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        maxLines = lines
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun button(text: String, background: Int, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        this.background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(background) }
        minHeight = 0; minimumHeight = 0
        setPadding(dp(14), dp(6), dp(14), dp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    /** The whole overlay can be dragged by any part that is not a button. */
    @SuppressLint("ClickableViewAccessibility")
    private fun enableDragging(frame: FrameLayout) {
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        var moved = false
        frame.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; originX = p.x; originY = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                    if (moved) {
                        p.x = originX + dx.toInt()
                        p.y = originY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(frame, p) }
                    }
                }
            }
            moved
        }
    }
}
