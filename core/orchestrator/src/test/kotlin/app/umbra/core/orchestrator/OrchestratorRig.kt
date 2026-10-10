package app.umbra.core.orchestrator

import app.umbra.core.decision.DecisionRouter
import app.umbra.core.model.Clock
import app.umbra.core.safety.ApprovalBroker
import app.umbra.core.safety.ApprovalMode
import app.umbra.core.safety.ApprovalPolicy
import app.umbra.core.safety.RiskClassifier
import app.umbra.core.safety.SafetyGate
import app.umbra.core.session.EventRecord
import app.umbra.core.session.FileSessionStore
import app.umbra.core.session.ModelRef
import app.umbra.core.session.Session
import app.umbra.core.session.SessionService
import app.umbra.core.session.SessionState
import app.umbra.core.workspace.WorkspaceManager
import app.umbra.platform.adb.JvmImageCodec
import app.umbra.platform.logs.ActionJournal
import app.umbra.platform.logs.LogMonitor
import app.umbra.platform.shell.ProcessShell
import app.umbra.platform.shell.ShellDevice
import app.umbra.platform.shell.ShellLogs
import app.umbra.runtime.omp.OmpClientConfig
import app.umbra.runtime.omp.OmpModel
import app.umbra.runtime.omp.OmpProvider
import app.umbra.tools.ActionPipeline
import app.umbra.tools.DisplayLeaseManager
import app.umbra.tools.PipelineConfig
import app.umbra.tools.UmbraToolHost
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * The real orchestrator on a development machine: the production [AgentSupervisor] and
 * [AgentSession] drive the real `omp` binary, with real shell backends behind the host tools, a real
 * session store on disk, the real safety gate and approval broker. The only stand-in is the model
 * endpoint, a scripted OpenAI-compatible server (see [ScriptedModel]); there is no LLM key to spend.
 */
class OrchestratorRig(
    private val dir: Path,
    private val ompBinary: File,
    modelUrl: String,
    approvalMode: ApprovalMode = ApprovalMode.BALANCED,
    recovery: RecoveryLimits = RecoveryLimits(restartBackoffMs = 100, providerRetryBackoffMs = 100, pressureIdleMs = 1_000),
    val governor: app.umbra.core.perf.ResourceGovernor? = null,
    fence: () -> String? = { null },
    admission: (String?, Map<String?, Int>) -> String? = { _, _ -> null },
) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val home: Path = Files.createDirectories(dir.resolve("home"))
    val store = FileSessionStore(dir.resolve("sessions"))
    val approvals = ApprovalBroker()
    val questions = QuestionBroker()
    val artifacts = CopyOnWriteArrayList<String>()

    /** Plan revisions, fed the way the app feeds them (§4.3). */
    val plans = app.umbra.core.control.PlanRegistry(app.umbra.core.control.ControlStore(dir.resolve("control")))

    /** Transcripts the sessions saved, as the app's file cache would hold them. */
    val savedConversations = java.util.concurrent.ConcurrentHashMap<String, List<kotlinx.serialization.json.JsonObject>>()

    private val shell = ProcessShell.app()
    private val journal = ActionJournal()
    private val gate = SafetyGate(RiskClassifier(), { ApprovalPolicy(approvalMode) }, approvals)
    private val pipeline = ActionPipeline(
        backends = ShellDevice.backends(shell),
        safety = gate,
        decisions = DecisionRouter(primary = null),
        leases = DisplayLeaseManager(),
        journal = journal,
        logs = LogMonitor(ShellLogs(shell), journal, scope),
        codec = JvmImageCodec(),
        clock = Clock.System,
        config = PipelineConfig(),
    )
    private val hosts = HashMap<String, UmbraToolHost>()

    val workspaceRoot: Path = Files.createDirectories(dir.resolve("workspaces"))
    val workspaces = WorkspaceManager(app.umbra.core.workspace.WorkspaceLayout(workspaceRoot, guestRoot = workspaceRoot.toAbsolutePath().normalize().toString()))

    val environment = LocalEnvironment(
        ompBinary = ompBinary.path,
        home = home,
        workspaceRoot = workspaceRoot,
        environment = mapOf(
            "HOME" to home.toString(),
            "OTEL_SDK_DISABLED" to "true",
            "PATH" to listOfNotNull("${System.getProperty("user.home")}/.bun/bin", "/opt/homebrew/bin", "/usr/local/bin", System.getenv("PATH")).joinToString(File.pathSeparator),
        ),
    )

    private val deps = SessionDeps(
        store = store,
        service = SessionService(store),
        workspaces = workspaces,
        environment = environment,
        providers = ProviderResolver {
            ProviderSelection(
                profile = "test",
                providers = listOf(OmpProvider("fake", modelUrl, auth = "none", models = listOf(OmpModel("fake-model", "Fake Model")))),
                model = ModelRef("fake", "fake-model"),
            )
        },
        toolHosts = { id -> synchronized(hosts) { hosts.getOrPut(id) { UmbraToolHost(pipeline, id, DecisionRouter(primary = null), exclude = { it.startsWith("gui_") }) } } },
        gate = gate,
        approvals = approvals,
        questions = questions,
        prompts = { PromptContext(deviceModel = "test machine", capabilities = listOf("Shell: available")) },
        artifacts = ArtifactSink { _, name, _ -> name.also { artifacts += it } },
        approvalMode = { approvalMode },
        clientConfig = OmpClientConfig(readyTimeoutMs = 60_000, requestTimeoutMs = 30_000),
        policy = recovery,
        fence = fence,
        admission = admission,
        onTodos = { id, phases ->
            plans.observe(id, phases.map { p -> app.umbra.core.control.PlanPhase(p.name, p.tasks.map { app.umbra.core.control.PlanTask(it.content, it.status) }) }, "agent")
        },
        conversations = object : ConversationCache {
            override suspend fun load(sessionId: String) = savedConversations[sessionId]
            override suspend fun save(sessionId: String, messages: List<kotlinx.serialization.json.JsonObject>) { savedConversations[sessionId] = messages }
            override suspend fun delete(sessionId: String) { savedConversations.remove(sessionId) }
        },
    )

    val supervisor = AgentSupervisor(deps, scope, governor).also { it.start() }

    // ---- helpers for tests ----

    suspend fun session(id: String): Session = store.get(id) ?: error("no session $id")

    suspend fun events(id: String): List<EventRecord> = store.events(id, limit = 10_000)

    /** Waits until the session is in one of [states] and the timeline records that change (the state is stored first, then the event). */
    suspend fun awaitState(id: String, vararg states: SessionState, timeoutMs: Long = 60_000): Session = withTimeout(timeoutMs) {
        while (true) {
            val s = store.get(id)
            if (s != null && s.state in states &&
                events(id).any { (it.event as? app.umbra.core.session.SessionEvent.StateChanged)?.to == s.state }
            ) return@withTimeout s
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    suspend fun awaitEvent(id: String, timeoutMs: Long = 60_000, predicate: (app.umbra.core.session.SessionEvent) -> Boolean): EventRecord = withTimeout(timeoutMs) {
        while (true) {
            events(id).firstOrNull { predicate(it.event) }?.let { return@withTimeout it }
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    override fun close() {
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(20_000) { supervisor.shutdown() } }
        scope.cancel()
    }
}
