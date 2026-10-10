package app.umbra.agent

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import app.umbra.agent.data.AndroidResourceSampler
import app.umbra.agent.data.AppApprovalLedger
import app.umbra.agent.data.AppProviderResolver
import app.umbra.agent.data.AppSettings
import app.umbra.agent.data.GuestManager
import app.umbra.agent.data.LayaSidecar
import app.umbra.agent.data.SecretStore
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.decision.DecisionRouter
import app.umbra.core.model.Clock
import app.umbra.core.orchestrator.AgentSupervisor
import app.umbra.core.orchestrator.ArtifactSink
import app.umbra.core.orchestrator.GuestEnvironment
import app.umbra.core.orchestrator.PromptContext
import app.umbra.core.orchestrator.QuestionBroker
import app.umbra.core.orchestrator.SessionDeps
import app.umbra.core.perf.ResourceGovernor
import app.umbra.core.recovery.HealthSupervisor
import app.umbra.core.recovery.RecoveryEvent
import app.umbra.core.safety.ApprovalBroker
import app.umbra.core.safety.ApprovalMode
import app.umbra.core.safety.ApprovalPolicy
import app.umbra.core.safety.RiskClassifier
import app.umbra.core.safety.SafetyGate
import app.umbra.core.session.FileSessionStore
import app.umbra.core.session.SessionEvent
import app.umbra.core.session.SessionState
import app.umbra.core.session.SessionService
import app.umbra.core.session.SessionStore
import app.umbra.core.workspace.WorkspaceLayout
import app.umbra.core.workspace.WorkspaceManager
import app.umbra.platform.android.AccessibilityUiBackend
import app.umbra.platform.android.AndroidClipboardBackend
import app.umbra.platform.android.AndroidImageCodec
import app.umbra.platform.android.AndroidNotificationBackend
import app.umbra.platform.android.UmbraAccessibilityService
import app.umbra.platform.android.UmbraNotificationListener
import app.umbra.platform.api.BackendRegistry
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.logs.ActionJournal
import app.umbra.platform.logs.LogMonitor
import app.umbra.platform.privileged.ServerDisplays
import app.umbra.platform.privileged.ServerScreenshots
import app.umbra.platform.privileged.ServerShell
import app.umbra.platform.shell.CompositeDisplays
import app.umbra.platform.shell.FirstAvailableShell
import app.umbra.platform.shell.OverlayDisplays
import app.umbra.platform.shell.ProcessShell
import app.umbra.platform.shell.ShellDevice
import app.umbra.platform.shell.ShellLogs
import app.umbra.platform.shell.SystemGrants
import app.umbra.platform.privileged.RootViaHelperShell
import app.umbra.runtime.linux.FallbackLauncher
import app.umbra.runtime.linux.GuestGuiBackend
import app.umbra.runtime.linux.HelperLauncher
import app.umbra.runtime.linux.SuLauncher
import app.umbra.tools.ActionPipeline
import app.umbra.tools.DisplayLeaseManager
import app.umbra.tools.PipelineConfig
import app.umbra.tools.UmbraToolHost
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Builds the real stack once per process: device access (accessibility service, root and shell-user
 * shells, notification listener, clipboard), the safety gate and approval broker, the decision router,
 * the log monitor, the action pipeline that every tool call goes through, and the agent orchestrator
 * that runs omp in the Linux environment on top of all that.
 */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val packageName: String = context.packageName

    // ---- user data ----
    val settings = AppSettings(context)

    /** The control plane's durable state (architecture spec §1.2): projects, journals, leases, policies, … */
    val control = app.umbra.core.control.ControlStore(File(context.filesDir.canonicalFile, "control").toPath())
    val projects = app.umbra.core.control.ProjectRegistry(control)

    /** Immutable plan revisions per session (architecture spec §4.3). */
    val plans = app.umbra.core.control.PlanRegistry(control)

    /** Emergency Input Lockdown, persisted across restarts (architecture spec §8.2). */
    val emergency = app.umbra.core.control.EmergencyControl(control, scope)

    /** Input authority over terminals, the screen and desktops (architecture spec §7); fenced by a lockdown. */
    val inputLeases = app.umbra.core.control.InputLeases(control) { emergency.lockdownReason() }

    /**
     * The writer-side fence of every side-effecting Android or desktop action (INV-006): an input lockdown stops all of
     * them; the user holding a screen (Take Back) stops the ones that would touch it.
     */
    private suspend fun inputGate(request: app.umbra.core.model.action.ActionRequest, displayId: Int?, touchesDisplay: Boolean): String? {
        emergency.lockdownReason()?.let { return "Emergency input lockdown is in force ($it): no side effects until the user lifts it." }
        if (!touchesDisplay) return null
        val resource = if (request.target.environment == app.umbra.core.model.action.Environment.LINUX_GUI) "gui:0" else "display:${displayId ?: 0}"
        val lease = inputLeases.current(resource) ?: return null
        return if (lease.owner == app.umbra.core.control.Principal.User && lease.state == app.umbra.core.control.LeaseState.ACTIVE) {
            "The user has taken back this screen ($resource). Wait until they return it, or work on a background display."
        } else null
    }
    val ui = app.umbra.agent.data.UiState(context)
    val secrets = SecretStore(context)

    // ---- privileged helper (shell user): started by root, Shizuku or adb; see HelperController ----
    val helper = HelperController(context)
    val shizuku = ShizukuLauncher(context, helper)
    private val helperShell: ShellBackend = ServerShell(helper.client)

    // ---- shells ----
    val appShell: ShellBackend = ProcessShell.app()

    /** `su` on a rooted phone. */
    private val suRoot: ShellBackend = ProcessShell.root()

    /** Root: `su` where the app may call it, otherwise a helper that runs as root (an emulator image, or Shizuku as root). */
    val rootShell: ShellBackend = FirstAvailableShell(ShellIdentity.ROOT, listOf(suRoot, RootViaHelperShell(helper.rootClient)))

    /** Shell-user rights or better: the helper, then root dropped to uid 2000, then full root, then the app itself. */
    val shellUser: ShellBackend = FirstAvailableShell(ShellIdentity.SHELL, listOf(helperShell, ProcessShell.rootAsShell(), rootShell, appShell))

    // ---- device access ----
    val accessibility = AccessibilityUiBackend(
        context.getSystemService(DisplayManager::class.java),
        enabledInSettings = {
            val enabled = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            enabled.split(':').any { it.equals("${context.packageName}/${UmbraAccessibilityService::class.java.name}", ignoreCase = true) }
        },
    )
    val notifications = AndroidNotificationBackend(context)
    val clipboard = AndroidClipboardBackend(context)
    val codec = AndroidImageCodec()

    private val overlayDisplays = OverlayDisplays(shellUser)

    /** Invisible virtual displays from the helper when it runs; the system's overlay display otherwise. */
    val displays = CompositeDisplays(listOf(ServerDisplays(helper.client) { overlayDisplays.list() }, overlayDisplays))

    // ---- Linux environment (the desktop backend needs it, and the Android backends do not) ----
    val guest = GuestManager(context, rootShell, scope)
    val linuxGui = GuestGuiBackend(guest.runtime)

    /** The shared workspace tree, seen from the phone (host) and from Linux (`/workspace`). */
    val workspaceLayout = WorkspaceLayout(hostRoot = File(guest.layout.workspaces).toPath(), guestRoot = "/workspace", stagingRoot = "/data/local/tmp/umbra")

    val backends: BackendRegistry = ShellDevice.backends(
        shell = shellUser,
        root = rootShell,
        app = appShell,
        ui = listOf(accessibility),
        displays = displays,
        screenshots = listOf(accessibility, ServerScreenshots(helper.client)),
        notifications = notifications,
        clipboard = clipboard,
        linuxGui = linuxGui,
    )

    val grants = SystemGrants(
        shell = shellUser,
        packageName = packageName,
        accessibilityService = UmbraAccessibilityService::class.java.name,
        notificationListener = UmbraNotificationListener::class.java.name,
    )

    // ---- sessions' persistent state (the approval ledger needs it, and the safety system needs the ledger) ----
    val sessionStore: SessionStore = FileSessionStore(File(context.filesDir.canonicalFile, "sessions").toPath())
    val ledger = AppApprovalLedger(File(context.filesDir, "approvals.jsonl"), sessionStore)

    // ---- safety, decisions, pipeline ----
    val approvals = ApprovalBroker(ledger = ledger)
    val approvalMode: ApprovalMode get() = settings.value.approvalMode
    private val gate = SafetyGate(RiskClassifier(), { ApprovalPolicy(approvalMode) }, approvals)
    /** Fast typed decisions: the Laya sidecar when the user has installed and started it, built-in rules otherwise. */
    val laya = LayaSidecar(this, scope)
    val decisions = DecisionRouter(primary = laya.engine)
    val journal = ActionJournal()
    val leases = DisplayLeaseManager(onBlocked = { session, display, holder ->
        inputLeases.recordBlocked("display:$display", app.umbra.core.control.Principal.agent(session), app.umbra.core.control.Principal.agent(holder))
    })
    val logMonitor = LogMonitor(ShellLogs(shellUser), journal, scope)

    val pipeline = ActionPipeline(
        backends = backends,
        safety = gate,
        decisions = decisions,
        leases = leases,
        journal = journal,
        logs = logMonitor,
        codec = codec,
        clock = Clock.System,
        // The agent names files the way Linux sees them; tools that act on the phone's file system need the phone's path.
        config = PipelineConfig(pathMapper = { path -> workspaceLayout.toHost(path)?.toString() ?: path }),
        inputGate = ::inputGate,
    )

    private val hosts = ConcurrentHashMap<String, UmbraToolHost>()

    /** The tools of one agent session (created on first use, one per session id). */
    fun toolHost(sessionId: String): UmbraToolHost = hosts.getOrPut(sessionId) {
        // The Linux desktop tools are offered only once the desktop pack is installed.
        val desktop = guest.desktopInstalled
        UmbraToolHost(pipeline, sessionId, decisions, exclude = { it.startsWith("gui_") && !desktop })
    }

    fun releaseSession(sessionId: String) {
        hosts.remove(sessionId)
        leases.releaseAll(sessionId)
        approvals.cancelSession(sessionId)
    }

    // ---- sessions, orchestrator ----
    val sessionService = SessionService(sessionStore)
    val workspaces = WorkspaceManager(workspaceLayout)
    val questions = QuestionBroker()
    val governor = ResourceGovernor(AndroidResourceSampler(context))
    private val environment = GuestEnvironment(
        runtime = guest.runtime,
        // The agent process starts through `su`, or through the root helper where the app has no `su`.
        launcher = FallbackLauncher(SuLauncher { listOf("su", "-c", it) }, { suRoot.isAvailable() }, HelperLauncher(helper.rootClient)),
        hostWorkspaces = File(guest.layout.workspaces).toPath(),
        hostHome = File(guest.layout.home).toPath(),
        afterMount = {
            // The scripts and package lists the agent relies on (the desktop helper, the pack installer) are the app's own.
            guest.syncScripts()
            rebindLogStream()
        },
    )

    private val logStreamRebound = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Starts the log stream once root or the helper can run it. A `logcat` the app starts itself is a process of the
     * agent's uid (see [rebindLogStream]), and at launch the helper and root have usually not answered yet, so
     * give them a few seconds. With neither (no Linux environment, so no agent), the app's own shell does it.
     */
    suspend fun startLogMonitor() {
        kotlinx.coroutines.withTimeoutOrNull(10_000) {
            while (!(runCatching { rootShell.isAvailable() }.getOrDefault(false) || runCatching { helper.client.isUp() }.getOrDefault(false))) kotlinx.coroutines.delay(500)
        }
        logMonitor.start()
    }

    /** Makes the Linux environment ready for an agent: mounted, secured, its scripts current, no log stream running as the app. */
    suspend fun prepareAgentEnvironment(): Boolean {
        if (!guest.ensureReady()) return false
        environment.prepare()
        return true
    }

    /**
     * The agent runs as this app's uid, so a process of the app that is not the app itself (a `logcat` started while
     * neither root nor the helper was known yet) is one the agent could look into through /proc. Before the first agent
     * starts, the log stream is started again so it runs as the shell user or root, which are other uids.
     */
    private fun rebindLogStream() {
        if (logStreamRebound.compareAndSet(false, true)) logMonitor.restart()
    }

    /** omp's state directory for a profile, written by the app directly (for probes outside a session). */
    fun guestAgentFiles(profile: String) = environment.agentFiles(profile)

    val supervisor = AgentSupervisor(
        deps = SessionDeps(
            store = sessionStore,
            service = sessionService,
            workspaces = workspaces,
            environment = environment,
            providers = AppProviderResolver(settings, secrets),
            toolHosts = ::toolHost,
            gate = gate,
            approvals = approvals,
            questions = questions,
            prompts = ::promptContext,
            logMonitor = logMonitor,
            journal = journal,
            artifacts = ArtifactSink { sessionId, name, bytes ->
                val dir = workspaces.sessionDirs(sessionId).artifacts
                dir.resolve(name).also { java.nio.file.Files.write(it, bytes) }.toString()
            },
            approvalMode = { approvalMode },
            onFinished = { session ->
                // A finished task holds nothing: its background displays go away and its leases are freed.
                session.displays.forEach { id -> runCatching { displays.destroy(id) } }
                leases.releaseAll(session.id)
                hosts.remove(session.id)
            },
            conversations = app.umbra.agent.data.ConversationFiles(java.io.File(context.filesDir, "conversations")),
            fence = { emergency.lockdownReason() },
            onTodos = { id, phases ->
                plans.observe(id, phases.map { p -> app.umbra.core.control.PlanPhase(p.name, p.tasks.map { app.umbra.core.control.PlanTask(it.content, it.status) }) }, "agent")
            },
        ),
        scope = scope,
        governor = governor,
    )

    private suspend fun promptContext(): PromptContext {
        val display = context.resources.displayMetrics
        val status = grants.status()
        return PromptContext(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidVersion = "Android ${Build.VERSION.RELEASE}",
            screen = "${display.widthPixels}x${display.heightPixels}",
            capabilities = buildList {
                add("Screen control (accessibility): ${if (accessibility.isAvailable()) "available" else "not enabled"}")
                add("Root shell: ${if (rootShell.isAvailable()) "available" else "not available"}")
                add("Background displays: ${if (displays.isAvailable()) "available" else "not available"}")
                status.filter { !it.granted }.forEach { add("Not granted: ${it.title}") }
            },
            extraInstructions = settings.value.standingInstructions,
        )
    }

    /** What this phone can do, probed for real, and the strategy chosen from it (spec §29). */
    val capabilities = capabilityDetector(this)

    /** Watches what the agent depends on and repairs it while a task needs it (spec §30). */
    val health = HealthSupervisor(subsystems(this) { sessionStore.list().any { it.state.isActive } })

    /** Starts the background machinery. Called once when the process starts, off the main thread. */
    /** Terminals on tmux in the Linux guest (spec §6.1). */
    val terminals by lazy {
        app.umbra.core.orchestrator.terminal.TerminalService(
            app.umbra.runtime.linux.tmux.GuestTmuxHost(guest.runtime, environment.spawner),
            control, inputLeases, scope, File(context.filesDir, "terminal-logs").toPath(),
        )
    }

    /** omp's own terminal UI on a session's conversation. */
    val ompTui by lazy { app.umbra.core.orchestrator.terminal.OmpTui(supervisor, terminals, environment) }

    fun start() {
        scope.launch {
            // Terminals outlive the app: once Linux is ready, find the tmux servers and panes again (AC-001).
            launch {
                guest.state.first { it == GuestManager.State.Ready }
                runCatching { terminals.reconcile() }
            }
            governor.start(scope)
            supervisor.start()
            supervisor.recover()
            guest.refresh()
            laya.refresh()
            launch { startLogMonitor() }
            health.start(scope)
            launch { while (true) { runCatching { capabilities.refresh() }; kotlinx.coroutines.delay(60_000) } }
            // The foreground service (and its notification) exists while a task runs, however the task was started.
            launch {
                sessionStore.sessionsFlow()
                    .map { sessions -> sessions.any { it.state == SessionState.RUNNING || it.state == SessionState.WAITING_APPROVAL } }
                    .distinctUntilChanged()
                    .filter { it }
                    .collect { runCatching { AgentService.start(context) } }
            }
            health.events.collect { event -> tell(event) }
        }
    }

    /** Puts failures and repairs into the timeline of every task that is running, so the user sees what happened. */
    private suspend fun tell(event: RecoveryEvent) {
        val entry: SessionEvent = when (event) {
            is RecoveryEvent.ProbeFailed -> SessionEvent.Failure(event.subsystem, event.reason)
            is RecoveryEvent.RestartFailed -> SessionEvent.Failure(event.subsystem, "Could not restart: ${event.reason}")
            is RecoveryEvent.Recovered -> SessionEvent.Recovery(event.subsystem, "restored", success = true, detail = "after ${event.attempts} attempt(s)")
            is RecoveryEvent.NeedsUser -> SessionEvent.Failure(event.subsystem, event.action, recoverable = false)
            is RecoveryEvent.CircuitOpened -> SessionEvent.Failure(event.subsystem, "Repeated failures; pausing automatic repair for a while.", recoverable = false)
            else -> return
        }
        for (session in sessionStore.list()) {
            if (session.state.isActive) runCatching { sessionStore.append(session.id, entry) }
        }
    }
}
