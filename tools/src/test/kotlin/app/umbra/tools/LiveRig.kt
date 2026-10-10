package app.umbra.tools

import app.umbra.core.decision.DecisionRouter
import app.umbra.core.model.Clock
import app.umbra.core.safety.ApprovalBroker
import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.safety.ApprovalMode
import app.umbra.core.safety.ApprovalPolicy
import app.umbra.core.safety.ApprovalRequest
import app.umbra.core.safety.RiskClassifier
import app.umbra.core.safety.SafetyGate
import app.umbra.platform.adb.Adb
import app.umbra.platform.adb.JvmImageCodec
import app.umbra.platform.api.exec
import app.umbra.platform.logs.ActionJournal
import app.umbra.platform.logs.LogMonitor
import app.umbra.platform.shell.ShellDevice
import app.umbra.platform.shell.ShellLogs
import app.umbra.runtime.omp.ContentBlock
import app.umbra.runtime.omp.HostToolCall
import app.umbra.runtime.omp.HostToolPayload
import app.umbra.runtime.omp.HostToolProgress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The real tool stack (host tools, pipeline, safety gate, decision router, log monitor, shell
 * backends) wired to a real device through adb. Approvals are answered by [respond], standing in for
 * the user at the approval dialog; everything else is the production code.
 */
class LiveRig(adb: Adb, approvalMode: ApprovalMode = ApprovalMode.BALANCED, leaseWaitMs: Long = 2_000) : ToolRig {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val shell = adb.shell()
    val root = adb.root().takeIf { kotlinx.coroutines.runBlocking { it.isAvailable() } }
    val broker = ApprovalBroker()
    val approvals = CopyOnWriteArrayList<ApprovalRequest>()

    /** What the "user" answers when an approval is requested. */
    @Volatile
    var respond: (ApprovalRequest) -> ApprovalDecision = { ApprovalDecision.Approved() }

    private val answered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val journal = ActionJournal()
    val monitor = LogMonitor(ShellLogs(shell), journal, scope)
    val router = DecisionRouter(primary = null)
    val leases = DisplayLeaseManager()
    val pipeline = ActionPipeline(
        backends = ShellDevice.backends(shell, root),
        safety = SafetyGate(RiskClassifier(), { ApprovalPolicy(approvalMode) }, broker),
        decisions = router,
        leases = leases,
        journal = journal,
        logs = monitor,
        codec = JvmImageCodec(),
        clock = Clock.System,
        config = PipelineConfig(leaseWaitMs = leaseWaitMs, scrollSettleMs = 700),
    )
    private val sessions = HashMap<String, UmbraToolHost>()
    private val callIds = AtomicInteger()

    init {
        scope.launch {
            broker.pending.collect { list ->
                for (req in list) {
                    if (answered.add(req.id)) {
                        approvals += req
                        broker.resolve(req.id, respond(req))
                    }
                }
            }
        }
        monitor.start()
    }

    fun host(sessionId: String = "live"): UmbraToolHost = sessions.getOrPut(sessionId) { UmbraToolHost(pipeline, sessionId, router) }

    /** Calls a host tool exactly as omp would, with JSON arguments. */
    suspend fun payload(tool: String, args: String = "{}", session: String = "live"): HostToolPayload =
        host(session).handle(
            HostToolCall("host-${callIds.incrementAndGet()}", "call-${callIds.get()}", tool, Json.parseToJsonElement(args).jsonObject),
            HostToolProgress { },
        )

    override suspend fun call(tool: String, args: String, session: String): ToolReply =
        payload(tool, args, session).let { ToolReply(it.isError, it.text(), it.images()) }

    override fun answerApprovals(approve: Boolean) {
        respond = { if (approve) ApprovalDecision.Approved() else ApprovalDecision.Denied("not now") }
    }

    override fun approvalCount(): Int = approvals.size

    /** Puts the device in a known state: awake, unlocked, no animations, at the launcher. */
    override suspend fun reset() {
        shell.exec("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; settings put system screen_off_timeout 1800000")
        shell.exec("settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0")
        shell.exec("am force-stop com.android.settings; am force-stop com.google.android.settings.intelligence; input keyevent KEYCODE_HOME")
        approvals.clear()
        answerApprovals(true)
        host().state.lastObservation.clear()
        host().state.activeDisplay = 0
        // A new test starts without displays held by an earlier one.
        leases.leases().forEach { leases.release(it.sessionId, it.displayId) }
    }

    override fun close() {
        monitor.stop()
        scope.cancel()
    }

    companion object {
        fun HostToolPayload.text(): String = content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

        fun HostToolPayload.images(): List<ByteArray> =
            content.filterIsInstance<ContentBlock.Image>().map { Base64.getDecoder().decode(it.base64) }

        /** The element id shown for the first line of an observation that contains [fragment]. */
        fun idOf(observation: String, fragment: String): String? =
            observation.lineSequence().firstOrNull { fragment in it }?.let { Regex("""\[(\d+:[\d.]+)]""").find(it)?.groupValues?.get(1) }
    }
}
