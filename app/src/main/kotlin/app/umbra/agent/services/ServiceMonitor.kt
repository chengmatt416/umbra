package app.umbra.agent.services

import app.umbra.agent.AppContainer
import app.umbra.agent.data.GuestManager
import app.umbra.agent.data.LayaSidecar
import app.umbra.core.control.HealthLevel
import app.umbra.core.control.LevelProbe
import app.umbra.core.control.ProbeResult
import app.umbra.core.control.ServiceHealth
import app.umbra.core.orchestrator.terminal.TerminalStatus
import app.umbra.core.session.SessionState
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.privileged.PrivilegedClient
import app.umbra.platform.privileged.ServerShell
import app.umbra.platform.api.ShellIdentity
import app.umbra.runtime.linux.LinuxMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A shared service Umbra runs or depends on (architecture spec §9). [singleton] says why it may not be replicated
 * into an elastic pool: none of these may, so the pool stays at one instance and says so.
 */
data class SharedService(val id: String, val name: String, val detail: String, val singleton: String, val supervised: Boolean)

/**
 * Probes the shared services at the four levels without side effects, and keeps their registered references equal to
 * the consumers Umbra knows of: live sessions, open terminals, the decision router while Laya serves it. Recovery
 * itself stays with the health supervisor, through [app.umbra.core.control.SharedServices] as its gate.
 */
class ServiceMonitor(private val c: AppContainer) {
    private val lock = Mutex()
    private val catalogState = MutableStateFlow(base())
    val catalog: StateFlow<List<SharedService>> = catalogState.asStateFlow()

    private fun base() = listOf(
        SharedService(LINUX, "Linux environment", "Debian, mounted for every agent and terminal", "One mount per phone: its files are the agents' shared state", supervised = true),
        SharedService(HELPER, "Privileged helper", "Shell-level helper: background displays, settings, input", "One per phone, privileged: a copy would be a second door", supervised = true),
        SharedService(ROOT_HELPER, "Root helper", "Runs the chroot and root's commands", "One per phone, root: never replicated", supervised = false),
        SharedService(X_DESKTOP, "Linux desktop (X server)", "Xvfb, openbox and the accessibility bridge", "A display is a singleton: two would be two desktops", supervised = false),
        SharedService(LAYA, "Laya decision model", "Fast typed decisions inside the app, on the NPU, GPU or CPU", "Stateless, but one model in memory (~1 GB): a second copy would not fit", supervised = false),
    )

    fun declareAll() {
        base().forEach { c.services.declare(it.id) }
    }

    /** Looks again: which tmux servers exist, who uses what, and how each service answers at every level. */
    suspend fun refresh() = lock.withLock {
        val groups = runCatching { c.terminals.groupNames() }.getOrDefault(emptySet()).sorted()
        groups.forEach { c.services.declare(tmux(it)) }
        catalogState.value = base() + groups.map {
            SharedService(tmux(it), "tmux server “$it”", "Its own server and socket (-L umbra-$it)", "Stateful: its sessions live inside it; never cloned", supervised = false)
        }
        syncReferences(groups)
        for (s in catalogState.value) runCatching { c.services.recordHealth(s.id, probe(s.id)) }
    }

    /** Probes in the background every [intervalMs]; screens ask for [refresh] more often while they are shown. */
    fun start(scope: CoroutineScope, intervalMs: Long = 60_000) = scope.launch {
        declareAll()
        while (true) {
            runCatching { refresh() }
            delay(intervalMs)
        }
    }

    private suspend fun syncReferences(groups: List<String>) {
        val live = c.sessionStore.list().filter { it.state == SessionState.RUNNING || it.state == SessionState.WAITING_APPROVAL || it.state == SessionState.PAUSED }
        val terminals = runCatching { c.terminals.terminals.value }.getOrDefault(emptyList()).filter { it.status != TerminalStatus.SERVER_LOST }
        val layaServes = c.laya.state.value == LayaSidecar.State.RUNNING
        val linuxConsumers = live.map { "session:${it.id}" }.toSet() + terminals.map { "terminal:${it.record.id}" }
        c.services.sync(LINUX, linuxConsumers)
        c.services.sync(HELPER, if (c.helper.configured) live.map { "session:${it.id}" }.toSet() else emptySet())
        // Under chroot the Linux environment itself runs through root.
        c.services.sync(ROOT_HELPER, if (c.guest.mode == LinuxMode.CHROOT && linuxConsumers.isNotEmpty()) setOf("linux") else emptySet())
        c.services.sync(LAYA, if (layaServes) setOf("decision-router") else emptySet())
        // Which sessions use the desktop is not tracked: no registered reference is claimed for it.
        c.services.sync(X_DESKTOP, emptySet())
        for (g in groups) c.services.sync(tmux(g), terminals.filter { it.record.group == g }.map { "terminal:${it.record.id}" }.toSet())
    }

    suspend fun probe(id: String): ServiceHealth = when {
        id == LINUX -> probeLinux()
        id == HELPER -> probeHelper(c.helper.client, ShellIdentity.SHELL, "2000")
        id == ROOT_HELPER -> probeHelper(c.helper.rootClient, ShellIdentity.ROOT, "0")
        id == X_DESKTOP -> probeDesktop()
        id == LAYA -> probeLaya()
        id.startsWith("tmux.") -> probeTmux(id.removePrefix("tmux."))
        else -> health(unknown("not a known service"))
    }

    private suspend fun probeLinux(): ServiceHealth {
        val state = c.guest.state.value
        if (state != GuestManager.State.Ready && state != GuestManager.State.Installed) return health(unknown("Linux is not installed ($state)"))
        val mounted = runCatching { c.guest.runtime.isMounted() }.getOrDefault(false)
        val l1 = if (mounted) pass("mounted (${if (c.guest.mode == LinuxMode.PROOT) "proot" else "chroot"})") else fail("not mounted")
        if (!mounted) return health(l1, unknown("not tried: not mounted"), unknown("not tried"), unknown("not tried"))
        val l2 = guest(listOf("true"))?.let { if (it.exitCode == 0) pass("a command runs in it") else fail("exit ${it.exitCode}: ${it.stderr.take(120)}") } ?: fail("no command could be run")
        val l3 = guest(listOf("sh", "-c", "command -v omp && command -v tmux"))?.let { if (it.exitCode == 0) pass("omp and tmux are there") else fail("omp or tmux missing") } ?: unknown("not tried")
        val l4 = guest(listOf("echo", "umbra-probe"))?.let { if (it.stdout.trim() == "umbra-probe") pass("echo round trip") else fail("echo returned “${it.stdout.trim().take(40)}”") } ?: unknown("not tried")
        return health(l1, l2, l3, l4)
    }

    private suspend fun guest(argv: List<String>) = runCatching { c.guest.runtime.exec(argv, timeoutMs = 15_000) }.getOrNull()

    private suspend fun probeHelper(client: PrivilegedClient, identity: ShellIdentity, uid: String): ServiceHealth {
        val up = runCatching { client.isUp() }.getOrDefault(false)
        if (!up) return health(unknown("its process cannot be seen from the app (other users' processes are hidden)"), fail("does not answer"), unknown("not tried"), unknown("not tried"))
        val out = runCatching { ServerShell(client, identity).exec(ShellRequest("echo \$PPID; id -u", timeoutMs = 10_000)) }.getOrNull()
        val lines = out?.stdout?.lines()?.map { it.trim() }.orEmpty()
        val pid = lines.getOrNull(0)?.toIntOrNull()
        val l1 = if (pid != null) pass("pid $pid (as the helper reports it)") else unknown("its process cannot be seen from the app")
        val l3 = if (out != null && out.exitCode == 0) pass("runs commands") else fail("a command failed: ${out?.stderr?.take(120) ?: "no reply"}")
        val l4 = if (lines.getOrNull(1) == uid) pass("runs as uid $uid") else fail("runs as uid ${lines.getOrNull(1) ?: "?"}, expected $uid")
        return health(l1, pass("hello answered"), l3, l4, instance = pid?.let { "pid $it" })
    }

    private suspend fun probeDesktop(): ServiceHealth {
        if (c.guest.state.value != GuestManager.State.Ready) return health(unknown("Linux is not ready"))
        val proc = guest(listOf("sh", "-c", "pgrep -x Xvfb | head -1"))
        val pid = proc?.stdout?.trim()?.toIntOrNull()
        val l1 = if (pid != null) pass("Xvfb pid $pid") else fail("no X server running (it starts when a desktop task needs it)")
        if (pid == null) return health(l1, unknown("not tried"), unknown("not tried"), unknown("not tried"))
        val l2 = guest(listOf("sh", "-c", "ls /tmp/.X11-unix/"))?.let { if (it.exitCode == 0 && it.stdout.isNotBlank()) pass("socket ${it.stdout.trim().lines().first()}") else fail("no X socket") } ?: unknown("not tried")
        val display = guest(listOf("sh", "-c", "ls /tmp/.X11-unix/ | head -1 | tr -dc 0-9"))?.stdout?.trim().orEmpty().ifEmpty { "0" }
        val l3 = guest(listOf("xdpyinfo", "-display", ":$display"))?.let { if (it.exitCode == 0) pass("display :$display answers") else fail("display :$display does not answer") } ?: unknown("not tried")
        val l4 = guest(listOf("sh", "-c", "DISPLAY=:$display wmctrl -m"))?.let { if (it.exitCode == 0) pass(it.stdout.lines().firstOrNull()?.trim().orEmpty().ifEmpty { "a window manager answers" }) else fail("no window manager answers") } ?: unknown("not tried")
        return health(l1, l2, l3, l4, instance = "pid $pid")
    }

    private fun probeLaya(): ServiceHealth {
        val state = c.laya.state.value
        if (state == LayaSidecar.State.NOT_INSTALLED) return health(unknown("not downloaded"), unknown("not downloaded"), unknown("not downloaded"), unknown("not downloaded"))
        val backend = c.laya.backend.value
        val l1 = if (state != LayaSidecar.State.INSTALLING) pass("files downloaded and verified") else fail("downloading")
        val l2 = if (backend != null) pass("loaded on the ${backend.uppercase()}") else fail("state ${state.name.lowercase()}")
        val l3 = if (state == LayaSidecar.State.RUNNING) pass("its reference decision matched when it loaded") else fail("state ${state.name.lowercase()}")
        // A real decision would spend a model pass: not a probe.
        return health(l1, l2, l3, unknown("not probed: a decision would run the model"), instance = backend)
    }

    private suspend fun probeTmux(group: String): ServiceHealth {
        val p = runCatching { c.terminals.probeGroup(group) }.getOrNull() ?: return health(unknown("could not ask"))
        val alive = p.serverPid?.let { pid -> guest(listOf("test", "-d", "/proc/$pid"))?.exitCode == 0 }
        val l1 = when {
            p.serverPid == null -> fail("no server (or no answer from it)")
            alive == true -> pass("server pid ${p.serverPid}")
            else -> fail("pid ${p.serverPid} not found")
        }
        val sessions = p.sessions
        val l2 = if (sessions != null) pass("${sessions.size} session(s) listed") else fail("the socket does not answer")
        val l3 = when {
            sessions == null -> unknown("not tried")
            "umbra" !in sessions -> fail("its anchor session is gone")
            p.controlClients > p.openControlClients -> fail("${p.controlClients - p.openControlClients} of Umbra's control clients closed")
            else -> pass("anchor present; ${p.openControlClients} control client(s) open")
        }
        val l4 = if (sessions == null) unknown("not tried") else if (p.roundTrip) pass("display-message round trip") else fail("no round trip")
        return health(l1, l2, l3, l4, instance = p.epoch?.let { "server ${it.substringBefore(':')} · epoch ${it.hashCode().toUInt().toString(16)}" } ?: p.serverPid?.let { "pid $it" })
    }

    private fun health(vararg levels: LevelProbe, instance: String? = null): ServiceHealth {
        val list = if (levels.size == 1) List(4) { levels[0] } else levels.toList()
        return ServiceHealth(HealthLevel.entries.zip(list).toMap(), System.currentTimeMillis(), instance)
    }

    private fun pass(detail: String) = LevelProbe(ProbeResult.PASS, detail)
    private fun fail(detail: String) = LevelProbe(ProbeResult.FAIL, detail)
    private fun unknown(detail: String) = LevelProbe(ProbeResult.UNKNOWN, detail)

    companion object {
        const val LINUX = "linux"
        const val HELPER = "helper"
        const val ROOT_HELPER = "root-helper"
        const val X_DESKTOP = "x-desktop"
        const val LAYA = "laya"
        fun tmux(group: String) = "tmux.$group"
    }
}
