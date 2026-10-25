package app.umbra.agent

import android.content.Context
import app.umbra.agent.services.ServiceMonitor
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
    internal val usageCounts = app.umbra.agent.data.UsageCounts.native(context) { settings.value.shareUsageCounts }

    /** The control plane's durable state (architecture spec §1.2): projects, journals, leases, policies, … */
    val control = app.umbra.core.control.ControlStore(File(context.filesDir.canonicalFile, "control").toPath())

    /** The credential vault's account (§12): scopes, rotation, revocation and every exposure, never values. */
    val vault = app.umbra.core.control.CredentialVault(control)

    /** The commit gate (§11): promotions of a repository's commit to a remote branch, conditional and journaled. */
    /** Git credentials the broker holds (AC-015): records here, values in [gitSecrets]. */
    val gitCredentials = app.umbra.core.control.GitCredentials(control)
    val gitBroker by lazy { app.umbra.agent.integration.brokeredGit(this) }
    val commits by lazy { app.umbra.core.control.CommitGate(control, gitBroker, rewritePermitted = { repositories.rewritePermitted(it) }) }

    /** The Repository Hub (§2.1): registered and cloned repositories, preflight, policy, project links. */
    val repositories = app.umbra.core.control.RepositoryHub(control)

    /** Paired nodes, their jobs and the placement policy (§10), and the phone's connections to them. */
    val nodes = app.umbra.core.control.NodeRegistry(control)
    val nodeConnections by lazy { app.umbra.agent.nodes.NodeConnections(this, scope) }

    /** Security & Evidence (§13): chain checks, independent anchors, recorded incidents, encrypted export. */
    val security = app.umbra.core.control.SecurityEvidence(control)

    /** The Shared Runtime Service Manager's state (§9): references, admission, breakers, fenced recovery owners. */
    val services = app.umbra.core.control.SharedServices(control).also { s ->
        listOf(ServiceMonitor.LINUX, ServiceMonitor.HELPER, ServiceMonitor.ROOT_HELPER, ServiceMonitor.X_DESKTOP, ServiceMonitor.LAYA).forEach(s::declare)
    }
    val serviceMonitor by lazy { ServiceMonitor(this) }
    val projects = app.umbra.core.control.ProjectRegistry(control)

    /** Resource policy revisions: budgets, Draft → Simulate → Apply (architecture spec §4.2). */
    val policies = app.umbra.core.control.ResourcePolicies(control)

    /** Session-scoped Resume Contracts (architecture spec §8.1). */
    val contracts = app.umbra.core.control.ResumeContracts(control)

    /** Immutable plan revisions per session (architecture spec §4.3). */
    val plans = app.umbra.core.control.PlanRegistry(control)

    /** Emergency Input Lockdown, persisted across restarts (architecture spec §8.2). */
    val emergency = app.umbra.core.control.EmergencyControl(control, scope)

    /** Input authority over terminals, the screen and desktops (architecture spec §7); fenced by a lockdown. */
    val inputLeases = app.umbra.core.control.InputLeases(control) { emergency.lockdownReason() }
    private val backgroundApps = ConcurrentHashMap<String, String>()
    private val lastInputSession = ConcurrentHashMap<String, String>()
    private val takeoverJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    fun noteAgentInput(resource: String, sessionId: String) { lastInputSession[resource] = sessionId }
    suspend fun userInput(resource: String, block: suspend () -> Unit) {
        val lease = requireNotNull(inputLeases.current(resource)) { "Take over this screen first" }
        check(lease.owner == app.umbra.core.control.Principal.User && lease.state == app.umbra.core.control.LeaseState.ACTIVE) { "You no longer hold this screen" }
        kotlinx.coroutines.withContext(app.umbra.platform.api.DispatchFence {
            check(inputLeases.check(resource, app.umbra.core.control.Principal.User, lease.generation) is app.umbra.core.control.LeaseCheck.Allowed) { "Screen control changed before this input was sent" }
        }) { app.umbra.platform.api.DispatchFence.checkCurrent(); block() }
    }
    fun takeOver(resource: String, sessionId: String? = null) {
        val previous = inputLeases.current(resource)
        if (previous?.owner == app.umbra.core.control.Principal.User && previous.state == app.umbra.core.control.LeaseState.ACTIVE) return
        val owner = sessionId ?: lastInputSession[resource] ?: previous?.lastAgentId
        inputLeases.takeBack(resource, lastAgentId = owner)
        if (owner != null) {
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try { if (sessionStore.get(owner)?.state?.isActive == true) supervisor.pause(owner, "The user took over $resource") }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* The immediate input fence remains in force even if a process cannot be paused. */ }
            }
            takeoverJobs[resource] = job
            job.start()
        }
    }
    fun returnControl(resource: String, sessionId: String? = null) {
        val lease = inputLeases.current(resource) ?: return
        if (lease.owner != app.umbra.core.control.Principal.User) return
        val owner = sessionId ?: lease.lastAgentId
        inputLeases.release(resource, app.umbra.core.control.Principal.User, "returned by the user; fresh observation required")
        val pause = takeoverJobs.remove(resource)
        if (owner != null) scope.launch {
            pause?.join()
            try {
                if (sessionStore.get(owner)?.state in setOf(SessionState.PAUSED, SessionState.RUNNING, SessionState.WAITING_APPROVAL)) supervisor.send(owner,
                    "The user returned control of $resource. Re-observe its current state before any input; user edits invalidate your previous plan. Then continue the original task within its existing approvals and usage limits.", messageSource = "control")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { runCatching { sessionStore.append(owner, app.umbra.core.session.SessionEvent.Notice("warning", "Control was returned, but the task could not resume. Review its state and usage limits before resuming.", "control")) } }
        }
    }

    private suspend fun claimBackgroundApp(pkg: String, session: String): String? {
        while (true) {
            val holder = backgroundApps.putIfAbsent(pkg, session) ?: return null
            if (holder == session) return null
            if (sessionStore.get(holder)?.state?.isActive == true) return holder
            backgroundApps.remove(pkg, holder)
        }
    }

    /**
     * The writer-side fence of every side-effecting Android or desktop action (INV-006): an input lockdown stops all of
     * them; the user holding a screen (Take Back) stops the ones that would touch it.
     */
    private suspend fun inputGate(request: app.umbra.core.model.action.ActionRequest, displayId: Int?, touchesDisplay: Boolean): String? {
        emergency.lockdownReason()?.let { return "Emergency input lockdown is in force ($it): no side effects until the user lifts it." }
        request.sessionId?.let { sessionStore.get(it) }?.let { task ->
            try { agents.proactive.checkAuthority(task) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { return e.message ?: "This task's execution permission changed" }
        }
        if (!touchesDisplay) return null
        if (displayId != null && displayId != 0 && request.target.environment != app.umbra.core.model.action.Environment.LINUX_GUI) {
            val pkg = request.target.app
            if (pkg != null && app.umbra.agent.agents.Channels.ofPackage(pkg) != null && !app.umbra.platform.android.UmbraAccessibilityService.connected.value) {
                return "Grant Umbra accessibility access before using message apps in the background, so it can yield when you open the same app."
            }
            if (pkg != null && app.umbra.agent.agents.Channels.ofPackage(pkg) != null && app.umbra.platform.android.UmbraAccessibilityService.userUsing(pkg)) {
                backgroundApps.remove(pkg)
                return "Paused $pkg because the user is using it. Do not type or tap there. Wait until they leave, then wait five seconds and reopen it on your background display before continuing."
            }
            if (pkg != null && app.umbra.agent.agents.Channels.ofPackage(pkg) != null) {
                request.sessionId?.let { sid -> claimBackgroundApp(pkg, sid)?.let { holder ->
                    return "$pkg is in use by session $holder. Wait for that session to finish or pause before using the app."
                } }
            }
        }
        val resource = if (request.target.environment == app.umbra.core.model.action.Environment.LINUX_GUI) "gui:0" else "display:${displayId ?: 0}"
        val lease = inputLeases.current(resource) ?: run { request.sessionId?.let { noteAgentInput(resource, it) }; return null }
        return if (lease.owner == app.umbra.core.control.Principal.User && lease.state == app.umbra.core.control.LeaseState.ACTIVE) {
            // A wait in the Resource Wait Graph: the session's work on this screen is blocked on the user (AC-005).
            request.sessionId?.let { inputLeases.recordBlocked(resource, app.umbra.core.control.Principal.agent(it), app.umbra.core.control.Principal.User) }
            "The user has taken back this screen ($resource). Wait until they return it, or work on a background display."
        } else if (lease.needsObservation) "The user changed this screen. Call android_observe, android_screenshot or gui_screenshot after control is returned before sending input."
        else { request.sessionId?.let { noteAgentInput(resource, it) }; null }
    }
    val ui = app.umbra.agent.data.UiState(context)

    /** The Material Capsule and Snapshot selection (§3.1); shown by the foreground service while the user has it on. */
    val capsule by lazy { app.umbra.agent.capsule.Capsule(context, this) }
    val capsuleSignals by lazy { app.umbra.agent.capsule.CapsuleSignals(context, this) }
    internal val capsuleSuggestions by lazy { app.umbra.agent.capsule.CapsuleSuggestions(this) }

    /** Screen recordings as context, started from the Capsule (capsule/ScreenRecorder). */
    val recorder by lazy { app.umbra.agent.capsule.ScreenRecorder(context, this) }
    val secrets = SecretStore(context)

    /** Git tokens, in a store of their own: nothing that hands provider keys to sessions ever lists them. */
    val gitSecrets = SecretStore(context, "git-secrets")

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
    val sessionFiles by lazy { app.umbra.agent.data.SessionFiles(this) }
    val guest = GuestManager(context, rootShell, scope, additionalBinds = { sessionFiles.binds })
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
    /** Fast typed decisions: the Laya sidecar when the user has installed and started it, built-in rules otherwise. */
    val laya = LayaSidecar(this, scope)

    /** The models Umbra offers, kept current from the site (docs/design/redesign-2026-10.md §8). */
    val modelDirectory = app.umbra.agent.data.ModelDirectory(context, scope)

    /** Signing in with a provider's account through omp's own login. */
    val ompLogin by lazy { app.umbra.agent.data.OmpLogin(this) }

    /** Auto: Laya picks each new task's model (data/AutoModel). */
    val autoModel by lazy { app.umbra.agent.data.AutoModel(this) }
    val decisions = DecisionRouter(primary = laya.engine)
    val safetyGate: SafetyGate = SafetyGate(
        RiskClassifier(), { ApprovalPolicy(approvalMode) }, approvals,
        securityCheck = app.umbra.core.safety.LayaActionSecurityCheck(laya.engine),
        commandRules = { settings.value.commandApprovals },
        saveCommandRule = { rule -> settings.update { it.copy(commandApprovals = it.commandApprovals.filterNot { old -> old.commandPrefix == rule.commandPrefix && old.workingDirectory == rule.workingDirectory } + rule) } },
        dlp = app.umbra.core.safety.OutboundDlpGuard(4) { protectedValues() },
        customRules = { sessionId -> agents.proactive.store.actionRules(sessionStore.get(sessionId)?.agentId) },
        originalIntent = { sessionId -> sessionStore.get(sessionId)?.let { it.latestUserIntent ?: it.request } },
        onHandoff = { request, ctx ->
            if (ctx.foregroundPackage != null || request.target.display != null || request.target.environment == app.umbra.core.model.action.Environment.LINUX_GUI) {
                val resource = if (request.target.environment == app.umbra.core.model.action.Environment.LINUX_GUI) "gui:0" else "display:${request.target.display ?: 0}"
                takeOver(resource, request.sessionId)
            }
            if (ctx.foregroundPackage == null && request.target.display == null && request.target.environment != app.umbra.core.model.action.Environment.LINUX_GUI) request.sessionId?.let { id -> scope.launch { supervisor.pause(id, "User takeover required") } }
        },
    )
    val journal = ActionJournal()
    val leases = DisplayLeaseManager(onBlocked = { session, display, holder ->
        inputLeases.recordBlocked("display:$display", app.umbra.core.control.Principal.agent(session), app.umbra.core.control.Principal.agent(holder))
    })
    val logMonitor = LogMonitor(ShellLogs(shellUser), journal, scope)

    val pipeline = ActionPipeline(
        backends = backends,
        safety = safetyGate,
        decisions = decisions,
        leases = leases,
        journal = journal,
        logs = logMonitor,
        codec = codec,
        clock = Clock.System,
        // The agent names files the way Linux sees them; tools that act on the phone's file system need the phone's path.
        config = PipelineConfig(pathMapper = { path -> workspaceLayout.toHost(path)?.toString() ?: path }),
        inputGate = ::inputGate,
        inputEpoch = { request, displayId, touchesDisplay ->
            val resource = if (request.target.environment == app.umbra.core.model.action.Environment.LINUX_GUI) "gui:0" else "display:${displayId ?: 0}"
            val op = request.operation
            emergency.current().generation to when {
                op is app.umbra.core.model.action.Operation.Display && op.action == app.umbra.core.model.action.DisplayAction.CREATE -> inputLeases.revision()
                touchesDisplay || op is app.umbra.core.model.action.Operation.Observe || op is app.umbra.core.model.action.Operation.Screenshot -> inputLeases.revision(resource)
                else -> 0L
            }
        },
        onObservation = { request, displayId, epoch ->
            val resource = if (request.target.environment == app.umbra.core.model.action.Environment.LINUX_GUI) "gui:0" else "display:${displayId ?: 0}"
            val captured = epoch as? Pair<*, *>
            if (captured?.first == emergency.current().generation) request.sessionId?.let { sid ->
                (captured.second as? Long)?.let { inputLeases.observed(resource, app.umbra.core.control.Principal.agent(sid), it) }
            }
        },
    )

    private val hosts = ConcurrentHashMap<String, UmbraToolHost>()

    /** The tools of one agent session (created on first use, one per session id). */
    fun toolHost(sessionId: String, ownerAgentId: String? = null, agentConversation: Boolean? = null): UmbraToolHost = hosts.getOrPut(sessionId) {
        // The Linux desktop tools are offered only once the desktop pack is installed.
        val desktop = guest.desktopInstalled
        val profile = ownerAgentId?.let { agents.store.get(it) } ?: agents.store.bySession(sessionId)
        val assistant = agentConversation ?: (agents.store.bySession(sessionId) != null)
        UmbraToolHost(
            pipeline, sessionId, decisions, exclude = { it.startsWith("gui_") && !desktop },
            // Every session can talk to the others; an agent's conversation also gets its memory, timers and the rest.
            extra = sessionTools.forSession(sessionId, allowSubagents = !assistant) + app.umbra.core.orchestrator.DeliverableTools(sessionStore, workspaces, environment).forSession(sessionId) + synthesizedTools.forSession(sessionId, profile?.id) + skillWorkflows.forSession(sessionId, profile?.id) + demonstrations.forSession(profile?.id) + credentialHandoff.tools(sessionId) + connectors.messages.tools(sessionId, profile?.id) + remoteNodeTools.forSession(sessionId) +
                (profile?.takeIf { assistant }?.let { agentTools.forAgent(it.id, sessionId) + proactiveTools.forAgent(it.id, sessionId) + teams.forSession(it.id, sessionId) } ?: emptyList()),
        )
    }

    /** Messages between sessions and agents (docs/design/agents-2026-10.md §3). */
    val sessionBus by lazy { app.umbra.core.orchestrator.SessionBus(supervisor, sessionStore, scope,
        agentSession = { id -> agents.store.get(id)?.sessionId },
        agentDelivery = { session, text ->
            val p = agents.store.bySession(session.id)
            if (p == null) false else {
                if (p.running && p.proactivityLevel >= 2) agents.proactive.discovery(p, text)
                else agents.proactive.store.stage(p.id, app.umbra.agent.agents.proactive.AttentionItem(
                    "message:${System.nanoTime()}", "Session report", text.take(2000), .3, .5, .8, "session", System.currentTimeMillis()))
                true
            }
        }) }

    /** The agents: profiles, their wake loop, their conversations (docs/design/agents-2026-10.md §6–§9). */
    val agents by lazy { app.umbra.agent.agents.AgentRuntime(this) }
    val agentTools by lazy { app.umbra.agent.agents.AgentTools(this) }
    val proactiveTools by lazy { app.umbra.agent.agents.proactive.ProactiveTools(this) }
    val personalContext by lazy { app.umbra.agent.agents.proactive.PersonalContext(this) }
    val skills by lazy { app.umbra.agent.agents.proactive.SkillStore(File(context.filesDir, "skills")) }
    val transformSandbox by lazy { app.umbra.agent.agents.proactive.TransformSandbox(context) }
    /**
     * The structured connectors an agent may use (spec §9.1): message apps it reads whose chosen methods include a
     * structured transport (a visual-only method is not one), plus its authorized personal-context grants.
     */
    fun structuredConnectors(agentId: String?): Set<String> {
        val profile = agentId?.let { agents.store.get(it) } ?: return emptySet()
        val structured = setOf(app.umbra.agent.connectors.Method.SYSTEM, app.umbra.agent.connectors.Method.API, app.umbra.agent.connectors.Method.UNOFFICIAL)
        val messages = profile.channels.filter { app -> connectors.settings.methods(app).any { it in structured } }
        val personal = agents.proactive.store.grants(profile.id).filter { it.enabled && it.authorizedAtMs != null }.map { it.connectorId }
        return (messages + personal).toSet()
    }
    val synthesizedTools by lazy { app.umbra.agent.agents.proactive.SynthesizedTools(this) }
    val teams by lazy { app.umbra.agent.agents.proactive.Teams(this) }
    val skillWorkflows by lazy { app.umbra.agent.agents.proactive.SkillWorkflows(this) }
    val demonstrations by lazy { app.umbra.agent.agents.proactive.Demonstrations(this) }
    val templateExchange by lazy { app.umbra.agent.agents.proactive.TemplateExchange(this) }
    val credentialHandoff by lazy { app.umbra.agent.credentials.CredentialHandoff(this) }
    fun protectedValues() = secrets.names().mapNotNull(secrets::get) + credentialHandoff.protectedValues()
    val remoteNodeTools by lazy { app.umbra.agent.nodes.RemoteNodeTools(this) }
    /** The message apps and how each is reached (docs/design/agents-2026-10.md §6). */
    val connectors by lazy { app.umbra.agent.connectors.Connectors(this) }
    val notificationReader by lazy { app.umbra.agent.notifications.NotificationReader(this) }
    /** The Telegram bot that reaches the agents from outside (§11). */
    val telegram by lazy { app.umbra.agent.telegram.TelegramBot(this) }

    /** sessions_*, session_* and subagent_* tools, given to every session. */
    val sessionTools by lazy { app.umbra.core.orchestrator.SessionTools(sessionBus, supervisor, sessionStore, resolveModel = ::resolveModel) }

    /**
     * A model the agent named for a subagent: `provider/id`, or a bare id or name looked up among the connected
     * accounts' models and the user's own providers. Null when no connected account has it.
     */
    fun resolveModel(name: String): app.umbra.core.session.ModelRef? {
        val prefs = settings.value
        val connected = prefs.providers.map { it.id }.toSet()
        if ('/' in name) {
            val (p, m) = name.split('/', limit = 2)
            return if (p in connected) app.umbra.core.session.ModelRef(p, m) else null
        }
        val wanted = name.trim().lowercase()
        app.umbra.agent.data.ModelDirectory.connected(modelDirectory.directory.value, prefs).forEach { p ->
            p.models.firstOrNull { it.id.lowercase() == wanted || it.name.lowercase() == wanted }?.let { return app.umbra.core.session.ModelRef(p.id, it.id) }
        }
        prefs.providers.filter { it.custom }.forEach { p -> p.models.firstOrNull { it.lowercase() == wanted }?.let { return app.umbra.core.session.ModelRef(p.id, it) } }
        return null
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

    val usageBudgets by lazy {
        app.umbra.core.orchestrator.UsageBudgets(
            File(context.filesDir, "ai-usage.json").toPath(),
            agentLimit = { id -> agents.store.get(id)?.usageLimit },
            sessionLookup = { sessionStore.get(it) },
            quota = { provider ->
                check(provider.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid provider" }
                val usageEnv = mapOf("PI_CODING_AGENT_DIR" to guestAgentFiles("default").ompPath, "NO_COLOR" to "1")
                check(guest.runtime.exec(listOf("omp", "usage", "invalidate", "--provider", provider, "--no-extensions"), env = usageEnv, cwd = "/home/agent", timeoutMs = 5_000).ok) { "Provider usage refresh failed" }
                val result = guest.runtime.exec(
                    listOf("omp", "usage", "--json", "--no-extensions", "--provider", provider),
                    env = usageEnv,
                    cwd = "/home/agent", timeoutMs = 15_000,
                )
                check(result.ok && !result.truncated) { "Provider usage unavailable" }
                app.umbra.core.orchestrator.ProviderQuota.parse(result.stdout, System.currentTimeMillis())
            },
        )
    }

    val supervisor: AgentSupervisor = AgentSupervisor(
        deps = SessionDeps(
            store = sessionStore,
            service = sessionService,
            workspaces = workspaces,
            environment = environment,
            providers = AppProviderResolver(settings, secrets, { name, provider -> vault.admits(name, provider) }, { modelDirectory.auto(it) }),
            toolHosts = { s -> toolHost(s.id, s.agentId, s.kind == app.umbra.core.session.SessionKind.AGENT) },
            gate = safetyGate,
            approvals = approvals,
            questions = questions,
            prompts = ::promptContext,
            backupVisionModel = { settings.value.backupVisionModel },
            validateModelInput = { text -> app.umbra.core.safety.OutboundDlpGuard(4) { protectedValues() }.requireSafe(text) },
            modelText = { text -> app.umbra.core.safety.OutboundDlpGuard(4) { protectedValues() }.redact(text) },
            validateModelPayload = { payload ->
                val values = protectedValues()
                app.umbra.core.safety.ModelPayloadGuard(app.umbra.core.safety.OutboundDlpGuard(4) { values }).requireSafe(payload)
            },
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
                sessionFiles.checkpointSession(session)
            },
            conversations = app.umbra.agent.data.ConversationFiles(java.io.File(context.filesDir, "conversations")),
            fence = { emergency.lockdownReason() },
            admission = { project, running -> policies.admit(project, running) },
            serviceAdmission = { sessionId -> services.admitConsumer(ServiceMonitor.LINUX, count = sessionId != null) },
            exposed = { sessionId, names, purpose -> vault.recordExposure(names, "session $sessionId", purpose) },
            onTodos = { id, phases ->
                plans.observe(id, phases.map { p -> app.umbra.core.control.PlanPhase(p.name, p.tasks.map { app.umbra.core.control.PlanTask(it.content, it.status) }) }, "agent")
            },
            // A deleted task's terminals (its shells and the omp TUI) end with it instead of running on unseen.
            onDeleted = { id ->
                try {
                    try { credentialHandoff.removeSession(id) }
                    finally { inputLeases.forgetSession(id) }
                } finally {
                    lastInputSession.entries.removeIf { it.value == id }
                    terminals.closeSession(id)
                }
            },
            beforeWork = { session ->
                agents.proactive.checkAuthority(session)
                sessionFiles.checkpointSession(session)
            },
            checkToolAuthority = { session -> agents.proactive.checkAuthority(session) },
            connectors = { session -> structuredConnectors(session.agentId) },
            usage = usageBudgets,
            // An agent's conversation is told its soul and memory each time its agent starts.
            sessionPrompt = { s ->
                val files = sessionFiles.prompt(s)
                val profile = if (s.kind != app.umbra.core.session.SessionKind.AGENT) null
                else s.agentId?.let { id -> agents.store.get(id) }?.let { p ->
                    app.umbra.agent.agents.AgentPrompt.of(p, agents.store.soul(p.id), agents.store.memory(p.id).contextual(s.request) + agents.store.teamMemory().contextual(s.request), agents.intervalMs(p)?.let { (it / 60_000).toInt() })
                }
                listOfNotNull(profile, files).joinToString("\n\n").ifBlank { null }
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
    val health = HealthSupervisor(subsystems(this) { sessionStore.list().any { it.state.isActive } }, gate = services, owner = "health-supervisor")

    /** Starts the background machinery. Called once when the process starts, off the main thread. */
    /** Terminals on tmux in the Linux guest (spec §6.1). */
    val terminals by lazy {
        app.umbra.core.orchestrator.terminal.TerminalService(
            app.umbra.runtime.linux.tmux.GuestTmuxHost(guest.runtime, environment.spawner),
            control, inputLeases, scope, File(context.filesDir, "terminal-logs").toPath(),
            admit = { group -> services.admitConsumer(ServiceMonitor.tmux(group)) ?: services.admitConsumer(ServiceMonitor.LINUX) },
        )
    }

    /** omp's own terminal UI on a session's conversation. */
    val ompTui by lazy { app.umbra.core.orchestrator.terminal.OmpTui(supervisor, terminals, environment) }

    /** New versions from the site, installed when nothing runs (update/AppUpdater). */
    val updater = app.umbra.agent.update.AppUpdater(
        context, scope, settings,
        privileged = FirstAvailableShell(ShellIdentity.SHELL, listOf(helperShell, ProcessShell.rootAsShell(), rootShell)),
        busy = {
            sessionStore.list().any { it.state == SessionState.RUNNING || it.state == SessionState.WAITING_APPROVAL } ||
                approvals.pending.value.isNotEmpty() || questions.pending.value.isNotEmpty() ||
                guest.state.value.let { it is GuestManager.State.Downloading || it is GuestManager.State.Installing }
        },
        appVisible = ui.appVisible,
    )

    /**
     * The privileged helper (background displays, screen capture, silent updates) starts with the app wherever the
     * phone lets it start without the person: Shizuku that may host it, or root. Looked at again every minute, since
     * Shizuku can come up after the app and the helper ends with every reboot. (Without either, the one adb command
     * in Settings stays the way.)
     */
    private suspend fun keepHelperUp() {
        while (true) {
            val up = runCatching { helper.client.isUp() }.getOrDefault(false)
            if (!up) {
                val viaShizuku = runCatching {
                    shizuku.state() == ShizukuLauncher.State.READY &&
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { shizuku.startHelper() }
                }.getOrDefault(false)
                if (!viaShizuku && runCatching { rootShell.isAvailable() }.getOrDefault(false)) runCatching { helper.startWithRoot(rootShell) }
            }
            kotlinx.coroutines.delay(60_000)
        }
    }

    fun start() {
        scope.launch {
            // Terminals outlive the app: once Linux is ready, find the tmux servers and panes again (AC-001).
            launch {
                guest.state.first { it == GuestManager.State.Ready }
                runCatching { terminals.reconcile() }
            }
            governor.start(scope)
            supervisor.start()
            skillWorkflows.start()
            credentialHandoff.start()
            sessionBus.start()
            notificationReader.start()
            agents.addSource(connectors.messages)
            connectors.messages.start()
            agents.start()
            telegram.start()
            val interrupted = sessionStore.list().filter { it.state == SessionState.RUNNING || it.state == SessionState.WAITING_APPROVAL }.map { it.id }
            supervisor.recover()
            launch { resumeUnderContracts(interrupted) }
            guest.refresh()
            laya.refresh()
            launch {
                // Keep the opted-in model warm between tasks; probe cheaply and recover with backoff.
                while (true) {
                    laya.maintain()
                    kotlinx.coroutines.delay(30_000)
                }
            }
            launch { keepHelperUp() }
            launch { startLogMonitor() }
            health.start(scope)
            serviceMonitor.start(scope)
            updater.start()
            modelDirectory.start()
            // A write the app died in the middle of is settled by reading the remote once Linux is ready (§11).
            launch {
                guest.state.first { it == GuestManager.State.Ready }
                runCatching { commits.recover() }
            }
            // The vault's account follows what the secret store holds and what the providers call each key.
            launch {
                kotlinx.coroutines.flow.combine(settings.flow, secrets.changes) { prefs, _ -> prefs }.collect { prefs ->
                    val labels = prefs.providers.mapNotNull { p -> p.apiKeyEnv?.let { it to (p.label to p.id) } }.toMap()
                    vault.reconcile(secrets.names().associateWith { labels[it] ?: (it to null) })
                }
            }
            launch { while (true) { runCatching { capabilities.refresh() }; kotlinx.coroutines.delay(60_000) } }
            // The capsule lives in the foreground service: it is there whenever the user has it on.
            launch {
                settings.flow.map { it.overlayEnabled }.distinctUntilChanged().filter { it }.collect { runCatching { AgentService.start(context) } }
            }
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

    /**
     * Sessions that were working when the app (or the phone) went away and hold a resume contract continue by
     * themselves once Linux is ready: each through the normal resume path, so a lockdown, missing permissions or a
     * full device still stop it, and the agent is told to look again before it acts (§8.1).
     */
    private suspend fun resumeUnderContracts(interrupted: List<String>) {
        if (interrupted.isEmpty()) return
        val eligible = interrupted.filter { id ->
            val why = contracts.eligibility(id)
            if (why != null && contracts[id] != null) contracts.record("contract.not_resumed", id, why)
            why == null
        }
        if (eligible.isEmpty()) return
        val ready = kotlinx.coroutines.withTimeoutOrNull(10 * 60_000) { guest.state.first { it == GuestManager.State.Ready } }
        for (id in eligible) {
            if (ready == null) { contracts.record("contract.not_resumed", id, "the Linux environment did not become ready"); continue }
            val c = contracts[id] ?: continue
            val note = "The app or the phone restarted while you were working. You are resuming under this session's resume contract" +
                (if (c.allowedWork.isNotBlank()) " (allowed: ${c.allowedWork})" else "") +
                ". Nothing you did before the restart can be assumed to have finished: look at the current state first, then continue."
            try {
                AgentService.start(context)
                supervisor.resume(id, note)
                contracts.record("contract.resumed", id, "resumed after a restart")
            } catch (e: Exception) {
                contracts.record("contract.not_resumed", id, e.message ?: e::class.simpleName.orEmpty())
            }
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
