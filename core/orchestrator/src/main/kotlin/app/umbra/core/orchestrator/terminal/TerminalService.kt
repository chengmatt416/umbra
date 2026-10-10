package app.umbra.core.orchestrator.terminal

import app.umbra.core.control.ControlStore
import app.umbra.core.control.InputLease
import app.umbra.core.control.InputLeases
import app.umbra.core.control.LeaseCheck
import app.umbra.core.control.Principal
import app.umbra.core.control.newId
import app.umbra.core.orchestrator.Redaction
import app.umbra.runtime.linux.tmux.ScreenLine
import app.umbra.runtime.linux.tmux.SgrParser
import app.umbra.runtime.linux.tmux.TerminalScreen
import app.umbra.runtime.linux.tmux.TmuxControl
import app.umbra.runtime.linux.tmux.TmuxEvent
import app.umbra.runtime.linux.tmux.TmuxHost
import app.umbra.runtime.linux.tmux.TmuxProtocol
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** A terminal as the control plane remembers it: a stable id mapped to group → server epoch → window → pane (§6.1). */
@Serializable
data class TerminalRecord(
    val id: String,
    /** Runtime group: one tmux server per group (§6.1 Per-Runtime Group tmux Servers). */
    val group: String,
    val title: String,
    /** The session this terminal belongs to; null for the user's own terminals. */
    val sessionId: String? = null,
    val cwd: String,
    val command: List<String>,
    val windowId: String,
    val paneId: String,
    /** `pid:start_time:boot_id` of the tmux server that holds the pane. A pane id means nothing in another epoch. */
    val serverEpoch: String,
    val createdAtMs: Long,
    /** `shell`, `agent` or `omp-tui`. */
    val purpose: String = "shell",
    /** `owner`: the input owner's view sets the size; `fixed`: always [fixedCols] × [fixedRows]. */
    val sizeMode: String = "owner",
    val fixedCols: Int = 120,
    val fixedRows: Int = 36,
) {
    val resourceId: String get() = "terminal:$id"
}

/** The minimal terminal state machine of spec §14. */
enum class TerminalStatus { ATTACHED, DETACHED, PANE_EXITED, SERVER_LOST, HISTORY_GAP }

data class TerminalState(
    val record: TerminalRecord,
    val status: TerminalStatus,
    val exitStatus: Int? = null,
    val viewers: Int = 0,
    val cols: Int = 0,
    val rows: Int = 0,
)

/** Result of input sent through the dispatcher. */
sealed interface InputResult {
    data object Sent : InputResult
    data class Fenced(val reason: String) : InputResult
}

/**
 * Terminals on tmux (architecture spec §6.1, §7): per-group tmux servers in the Linux guest, each terminal its own tmux
 * session with its own control-mode client, a registry mapping stable terminal ids to panes, and one input dispatcher
 * per terminal that delivers bytes only for the
 * holder of the terminal's input lease. A UI that goes away never ends a shell; a server that dies is reported as
 * lost, never as alive (INV-001).
 */
class TerminalService(
    private val host: TmuxHost,
    private val store: ControlStore,
    val leases: InputLeases,
    private val scope: CoroutineScope,
    private val logDir: Path,
    /** Size a pane keeps while no foreground view owns its input (agents work headless). */
    private val stableCols: Int = 120,
    private val stableRows: Int = 36,
    /** Shared-service admission (§9) of a group's tmux server: null admits a new terminal, otherwise why not. */
    private val admit: (group: String) -> String? = { null },
) {
    private val docs = store.docs("terminals", TerminalRecord.serializer(), TerminalRecord::id)
    private val groups = ConcurrentHashMap<String, Group>()
    private val groupLock = Mutex()
    private val runtime = MutableStateFlow<Map<String, Runtime>>(emptyMap())
    private val screens = ConcurrentHashMap<String, Screen>()
    private val inputLocks = ConcurrentHashMap<String, Mutex>()

    private class Group(val name: String, val socket: String, val epoch: String)

    /** One control client per terminal (each terminal is its own tmux session in its group's server). */
    private class Client(val control: TmuxControl, val epoch: String, var watcher: Job? = null)

    private val clients = ConcurrentHashMap<String, Client>()
    private val clientLock = Mutex()

    private data class Runtime(val status: TerminalStatus, val exitStatus: Int? = null, val cols: Int = 0, val rows: Int = 0)

    private class Screen(val flow: MutableStateFlow<TerminalScreen?>, val dirty: Channel<Unit>, val viewers: AtomicInteger, var job: Job? = null)

    private val states = MutableStateFlow<List<TerminalState>>(emptyList())

    /** Every terminal with its verified status, viewers and size. */
    val terminals: StateFlow<List<TerminalState>> = states.asStateFlow()

    init {
        scope.launch { docs.all.collect { publish() } }
        scope.launch { runtime.collect { publish() } }
        // Panes that exit write nothing, so statuses are also swept now and then for terminals nobody watches.
        scope.launch {
            while (true) {
                delay(STATUS_SWEEP_MS)
                groups.values.toList().forEach { g -> runCatching { refreshStatuses(g) } }
            }
        }
    }

    private fun publish() {
        val rt = runtime.value
        states.value = docs.list().sortedBy { it.createdAtMs }.map { r ->
            val x = rt[r.id] ?: Runtime(TerminalStatus.DETACHED)
            TerminalState(r, x.status, x.exitStatus, screens[r.id]?.viewers?.get() ?: 0, x.cols, x.rows)
        }
    }

    fun record(id: String): TerminalRecord? = docs[id]

    // ---- groups ----

    private fun socketOf(group: String): String {
        require(group.matches(Regex("[a-z0-9-]{1,32}"))) { "bad terminal group: $group" }
        return "umbra-$group"
    }

    /** What one look at a group's tmux server found, by level (§9). Nothing is started or changed by looking. */
    data class GroupProbe(
        val group: String,
        /** Sessions the server listed: null when it did not answer (no server, or no transport to it). */
        val sessions: List<String>?,
        val serverPid: Int?,
        /** Umbra's own control clients attached to the group's terminals, and whether they are open. */
        val controlClients: Int,
        val openControlClients: Int,
        /** A harmless round trip (`display-message -p`) returned what it was given. */
        val roundTrip: Boolean,
        val epoch: String?,
    )

    /** The groups Umbra has terminals in, and the ones whose servers it has met. */
    fun groupNames(): Set<String> = docs.list().map { it.group }.toSet() + groups.keys

    suspend fun probeGroup(name: String): GroupProbe {
        val socket = socketOf(name)
        val sessions = runCatching { host.run(listOf("-L", socket, "list-sessions", "-F", "#{session_name}")).lines().map { it.trim() }.filter { it.isNotEmpty() } }.getOrNull()
        val pid = if (sessions == null) null else runCatching { host.run(listOf("-L", socket, "display-message", "-p", "#{pid}")).trim().toInt() }.getOrNull()
        val echo = sessions != null && runCatching { host.run(listOf("-L", socket, "display-message", "-p", "umbra-probe")).trim() == "umbra-probe" }.getOrDefault(false)
        val mine = docs.list().filter { it.group == name }.mapNotNull { clients[it.id] }
        return GroupProbe(name, sessions, pid, mine.size, mine.count { it.control.isOpen.value }, echo, groups[name]?.epoch)
    }

    /** Starts (or finds) the group's tmux server. */
    private suspend fun group(name: String): Group = groupLock.withLock {
        groups[name]?.let { g ->
            if (runCatching { host.run(listOf("-L", g.socket, "has-session", "-t", "umbra")) }.isSuccess) return@withLock g
            groups.remove(name)
        }
        val socket = socketOf(name)
        val exists = runCatching { host.run(listOf("-L", socket, "has-session", "-t", "umbra")) }.isSuccess
        // The server starts with tmux's defaults; each terminal's session gets its options when it is created.
        if (!exists) host.run(listOf("-L", socket, "new-session", "-d", "-s", "umbra", "-n", "anchor", "--", "tail", "-f", "/dev/null"))
        val epoch = host.run(listOf("-L", socket, "display-message", "-p", "-t", "umbra", "#{pid}:#{start_time}")).trim() + ":" + host.bootId()
        val g = Group(name, socket, epoch)
        groups[name] = g
        g
    }

    /** The control client of one terminal's session, attached on first use. */
    private suspend fun client(r: TerminalRecord, g: Group): TmuxControl = clientLock.withLock {
        clients[r.id]?.takeIf { it.control.isOpen.value && it.epoch == g.epoch }?.let { return@withLock it.control }
        val control = TmuxControl(host.spawn(listOf("-L", g.socket, "-C", "attach-session", "-t", TmuxProtocol.arg(r.id))), scope)
        val c = Client(control, g.epoch)
        c.watcher = scope.launch { watch(r.id, g, control) }
        clients[r.id] = c
        control
    }

    private suspend fun watch(id: String, g: Group, control: TmuxControl) {
        control.events.collect { e ->
            when (e) {
                is TmuxEvent.Output -> {
                    screens[id]?.dirty?.trySend(Unit)
                    log(id, e.data)
                }
                is TmuxEvent.Notification -> if (e.name == "session-closed" || e.name == "sessions-changed" || e.name == "window-close") scope.launch { refreshStatuses(g) }
                is TmuxEvent.Closed -> {
                    // The client is gone; whether the session lives is a separate question, answered by probing it.
                    clients.remove(id)
                    scope.launch { reconcile() }
                }
            }
        }
    }

    // ---- lifecycle ----

    /**
     * Opens a terminal running [command] (a login shell by default) in [cwd]. [owner] gets its input lease: the
     * user for terminals they open, the agent's session for the agent's.
     */
    suspend fun open(
        title: String,
        cwd: String,
        command: List<String> = listOf("/bin/bash", "-l"),
        group: String = "user",
        sessionId: String? = null,
        purpose: String = "shell",
        owner: Principal = Principal.User,
    ): TerminalRecord {
        admit(group)?.let { throw IllegalStateException(it) }
        val g = group(group)
        val id = newId("term", store.clock.nowMs())
        val out = host.run(
            listOf("-L", g.socket, "new-session", "-d", "-P", "-F", "#{window_id} #{pane_id}", "-s", id, "-x", "$stableCols", "-y", "$stableRows", "-c", cwd, "--") + command,
        ).trim().split(' ')
        val record = TerminalRecord(id, group, title.ifBlank { command.first().substringAfterLast('/') }, sessionId, cwd, command, out[0], out[1], g.epoch, store.clock.nowMs(), purpose)
        // Per-session options: keep an exited pane (its status is reported), no status line (the pane is the whole
        // window), a size only Umbra sets (the input owner's view, §6.1), a generous scrollback.
        for (opt in listOf(
            listOf("set-option", "-t", id, "remain-on-exit", "on"),
            listOf("set-option", "-t", id, "status", "off"),
            listOf("set-option", "-t", id, "history-limit", "20000"),
            listOf("set-option", "-w", "-t", id, "window-size", "manual"),
        )) runCatching { host.run(listOf("-L", g.socket) + opt) }
        runCatching { host.run(listOf("-L", g.socket, "resize-window", "-t", id, "-x", "$stableCols", "-y", "$stableRows")) }
        docs.put(record)
        leases.acquire(record.resourceId, owner, "opened the terminal", g.epoch)
        setRuntime(id, Runtime(TerminalStatus.ATTACHED, cols = stableCols, rows = stableRows))
        return record
    }

    /** Closes one terminal: its window only, never the server and never anything else (§8.2). */
    suspend fun close(id: String) {
        val r = docs[id] ?: return
        val g = groups[r.group]
        clients.remove(id)?.let { c -> c.watcher?.cancel(); runCatching { c.control.close() } }
        if (g != null && g.epoch == r.serverEpoch) runCatching { host.run(listOf("-L", g.socket, "kill-session", "-t", TmuxProtocol.arg(r.id))) }
        screens.remove(id)?.job?.cancel()
        docs.delete(id)
        leases.forget(r.resourceId)
        runtime.update { it - id }
    }

    /**
     * Probes every group and terminal and records what is true now (AC-001/AC-002): a pane in the same server epoch
     * is reattached; a dead pane is reported with its exit status; a lost or restarted server loses its panes.
     */
    suspend fun reconcile() {
        val byGroup = docs.list().groupBy { it.group }
        for ((name, records) in byGroup) {
            val socket = socketOf(name)
            val alive = runCatching { host.run(listOf("-L", socket, "has-session", "-t", "umbra")) }.isSuccess
            if (!alive) {
                groups.remove(name)
                records.forEach { r -> setRuntime(r.id, Runtime(TerminalStatus.SERVER_LOST)); leases.markUncertain(r.resourceId, "tmux server lost") }
                continue
            }
            val g = runCatching { group(name) }.getOrNull()
            if (g == null) {
                records.forEach { setRuntime(it.id, Runtime(TerminalStatus.DETACHED)) }
                continue
            }
            records.filter { it.serverEpoch != g.epoch }.forEach { r ->
                setRuntime(r.id, Runtime(TerminalStatus.SERVER_LOST))
                leases.markUncertain(r.resourceId, "the tmux server restarted (epoch ${g.epoch})")
            }
            refreshStatuses(g)
        }
    }

    private suspend fun refreshStatuses(g: Group) {
        val lines = runCatching { host.run(listOf("-L", g.socket, "list-panes", "-a", "-F", "#{window_id} #{pane_id} #{pane_dead} #{pane_dead_status} #{window_width} #{window_height}")).lines().filter { it.isNotBlank() } }.getOrNull() ?: return
        val panes = lines.mapNotNull { l -> l.split(' ').takeIf { it.size >= 6 }?.let { it[1] to it } }.toMap()
        docs.list().filter { it.group == g.name && it.serverEpoch == g.epoch }.forEach { r ->
            val p = panes[r.paneId]
            setRuntime(
                r.id,
                when {
                    p == null -> Runtime(TerminalStatus.SERVER_LOST)
                    p[2] == "1" -> Runtime(TerminalStatus.PANE_EXITED, p[3].toIntOrNull(), p[4].toIntOrNull() ?: 0, p[5].toIntOrNull() ?: 0)
                    else -> Runtime(TerminalStatus.ATTACHED, null, p[4].toIntOrNull() ?: 0, p[5].toIntOrNull() ?: 0)
                },
            )
        }
    }

    private fun setRuntime(id: String, r: Runtime) = runtime.update { it + (id to r) }

    private suspend fun live(id: String): Pair<TerminalRecord, TmuxControl>? {
        val r = docs[id] ?: return null
        val g = runCatching { group(r.group) }.getOrNull() ?: return null
        if (g.epoch != r.serverEpoch) {
            setRuntime(id, Runtime(TerminalStatus.SERVER_LOST))
            return null
        }
        return runCatching { r to client(r, g) }.getOrNull()
    }

    // ---- input: the single dispatcher (§6.1, §7) ----

    /**
     * Delivers [bytes] to the terminal for [from], only while [from] holds its input lease (and, when given, with the
     * same [generation]). Taking input away fences new bytes; it does not stop a command already running (AC-004).
     */
    suspend fun send(id: String, bytes: ByteArray, from: Principal, generation: Long? = null): InputResult {
        val r = docs[id] ?: return InputResult.Fenced("no such terminal")
        return inputLocks.getOrPut(id) { Mutex() }.withLock {
            when (val check = leases.check(r.resourceId, from, generation)) {
                is LeaseCheck.Fenced -> InputResult.Fenced(check.reason)
                is LeaseCheck.Allowed -> {
                    val (rec, control) = live(id) ?: return@withLock InputResult.Fenced("the terminal's tmux server is not the one it was opened in")
                    bytes.toList().chunked(256).forEach { chunk ->
                        control.command("send-keys -t ${TmuxProtocol.arg(rec.paneId)} -H ${TmuxProtocol.hex(chunk.toByteArray())}")
                    }
                    InputResult.Sent
                }
            }
        }
    }

    suspend fun sendText(id: String, text: String, from: Principal): InputResult = send(id, text.toByteArray(Charsets.UTF_8), from)

    /** The user takes input now; the agent's next byte is refused. A running command is not stopped by this. */
    fun takeControl(id: String): InputLease? = docs[id]?.let { leases.takeBack(it.resourceId, runnerEpoch = it.serverEpoch) }

    /** Asks the agent to hand over at its next safe point (coordinated handoff). */
    fun requestHandoff(id: String): InputLease? = docs[id]?.let { leases.requestHandoff(it.resourceId) }

    /** The user gives input back to the session that owns the terminal. */
    fun returnControl(id: String): InputLease? = docs[id]?.let { r -> r.sessionId?.let { leases.grant(r.resourceId, Principal.agent(it)) } }

    // ---- size: the input owner decides (§6.1 Input Owner-driven Resize) ----

    private val resizeJobs = ConcurrentHashMap<String, Job>()

    /**
     * A view reports its size. Only the input owner's view resizes the pane (read-only viewers never do), resizes
     * are coalesced, and without a foreground owner the pane keeps the stable virtual size.
     */
    fun viewSize(id: String, cols: Int, rows: Int, viewer: Principal) {
        val r = docs[id] ?: return
        val lease = leases.current(r.resourceId)
        val (c, rw) = when {
            r.sizeMode == "fixed" -> r.fixedCols to r.fixedRows
            lease?.owner == viewer -> cols.coerceIn(20, 400) to rows.coerceIn(5, 200)
            else -> return
        }
        resizeJobs.remove(id)?.cancel()
        resizeJobs[id] = scope.launch {
            delay(150)
            val (rec, control) = live(id) ?: return@launch
            runCatching { control.command("resize-window -t ${TmuxProtocol.arg(rec.windowId)} -x $c -y $rw") }
            runtime.update { m -> m[id]?.let { m + (id to it.copy(cols = c, rows = rw)) } ?: m }
            screens[id]?.dirty?.trySend(Unit)
        }
    }

    /** No view owns input any more: back to the stable virtual size. */
    fun releaseSize(id: String) {
        val r = docs[id] ?: return
        resizeJobs.remove(id)?.cancel()
        scope.launch {
            val (rec, control) = live(id) ?: return@launch
            val (c, rw) = if (r.sizeMode == "fixed") r.fixedCols to r.fixedRows else stableCols to stableRows
            runCatching { control.command("resize-window -t ${TmuxProtocol.arg(rec.windowId)} -x $c -y $rw") }
        }
    }

    fun setSizeMode(id: String, mode: String, cols: Int = 120, rows: Int = 36) {
        docs.update(id) { it.copy(sizeMode = mode, fixedCols = cols.coerceIn(20, 400), fixedRows = rows.coerceIn(5, 200)) }
        if (mode == "fixed") releaseSize(id)
    }

    // ---- screens ----

    /** What the pane shows, refreshed when it writes; refreshing runs only while someone watches. */
    fun screen(id: String): Flow<TerminalScreen?> {
        val s = screens.getOrPut(id) { Screen(MutableStateFlow(null), Channel(Channel.CONFLATED), AtomicInteger(0)) }
        return s.flow
            .onStart {
                if (s.viewers.incrementAndGet() == 1) s.job = scope.launch { refreshLoop(id, s) }
                s.dirty.trySend(Unit)
                publish()
            }
            .onCompletion {
                if (s.viewers.decrementAndGet() == 0) s.job?.cancel()
                publish()
            }
    }

    private suspend fun refreshLoop(id: String, s: Screen) {
        while (true) {
            // Output marks the screen dirty; a pane that dies or a cursor that moves without output is caught by
            // the slow poll.
            withTimeoutOrNull(WATCHED_POLL_MS) { s.dirty.receive() }
            try {
                s.flow.value = capture(id) ?: s.flow.value
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The server or client went away; reconcile reports it.
            }
            delay(33)
        }
    }

    private suspend fun capture(id: String): TerminalScreen? {
        val (r, control) = live(id) ?: return null
        val pane = TmuxProtocol.arg(r.paneId)
        val info = control.command("display-message -p -t $pane \"#{cursor_x} #{cursor_y} #{cursor_flag} #{pane_width} #{pane_height} #{pane_dead} #{pane_dead_status} #{alternate_on} #{pane_title}\"").firstOrNull().orEmpty().split(' ', limit = 9)
        val lines = control.command("capture-pane -p -e -N -t $pane")
        val dead = info.getOrNull(5) == "1"
        val screen = TerminalScreen(
            lines = SgrParser.parseLines(lines.joinToString("\n")),
            cols = info.getOrNull(3)?.toIntOrNull() ?: 0,
            rows = info.getOrNull(4)?.toIntOrNull() ?: 0,
            cursorX = info.getOrNull(0)?.toIntOrNull() ?: 0,
            cursorY = info.getOrNull(1)?.toIntOrNull() ?: 0,
            cursorVisible = info.getOrNull(2) == "1",
            dead = dead,
            deadStatus = info.getOrNull(6)?.toIntOrNull(),
            title = info.getOrNull(8).orEmpty(),
            alternate = info.getOrNull(7) == "1",
        )
        setRuntime(id, Runtime(if (dead) TerminalStatus.PANE_EXITED else TerminalStatus.ATTACHED, screen.deadStatus.takeIf { dead }, screen.cols, screen.rows))
        return screen
    }

    /** Older lines from tmux's bounded scrollback (not a durable log: see [logFile]). */
    suspend fun scrollback(id: String, lines: Int = 2000): List<ScreenLine> {
        val (r, control) = live(id) ?: return emptyList()
        return SgrParser.parseLines(control.command("capture-pane -p -e -N -S -${lines.coerceIn(1, 20000)} -E -1 -t ${TmuxProtocol.arg(r.paneId)}").joinToString("\n"))
    }

    // ---- logs: bounded, segmented, redacted (§6.1: scrollback is not a durable execution log) ----

    fun logFile(id: String): Path = logDir.resolve("$id.log")

    private val logLocks = ConcurrentHashMap<String, Any>()
    private val ansi = Regex("\u001b\\[[0-9;?<>=!]*[ -/]*[@-~]|\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)|\u001bP[^\u001b]*\u001b\\\\|\u001b_[^\u001b]*\u001b\\\\|\u001b[()][A-Za-z0-9]|\u001b[=>78c]|\r")

    private fun log(id: String, data: ByteArray) {
        scope.launch(Dispatchers.IO) {
            val text = Redaction.text(String(data, Charsets.UTF_8).replace(ansi, ""))
            if (text.isEmpty()) return@launch
            synchronized(logLocks.getOrPut(id) { Any() }) {
                Files.createDirectories(logDir)
                val file = logFile(id)
                if (Files.exists(file) && Files.size(file) > LOG_SEGMENT_BYTES) {
                    // file -> .1 -> .2 …; the oldest segment falls off.
                    for (n in LOG_SEGMENTS - 1 downTo 1) {
                        val src = if (n == 1) file else logDir.resolve("$id.log.${n - 1}")
                        if (Files.exists(src)) Files.move(src, logDir.resolve("$id.log.$n"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                Files.write(file, text.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
    }

    /** Searches the terminal's saved log (newest segment first). */
    suspend fun searchLog(id: String, query: String, limit: Int = 200): List<String> = withContext(Dispatchers.IO) {
        val files = listOf(logFile(id)) + (1 until LOG_SEGMENTS).map { logDir.resolve("$id.log.$it") }
        files.filter { Files.exists(it) }.flatMap { Files.readAllLines(it).reversed() }.filter { it.contains(query, ignoreCase = true) }.take(limit)
    }

    /** Detaches every control client (the servers and their shells keep running). */
    suspend fun detachAll() {
        clients.values.toList().forEach { c -> c.watcher?.cancel(); runCatching { c.control.close() } }
        clients.clear()
        groups.clear()
    }

    companion object {
        const val LOG_SEGMENT_BYTES = 1_000_000L
        const val WATCHED_POLL_MS = 1_000L
        const val STATUS_SWEEP_MS = 5_000L
        const val LOG_SEGMENTS = 3
    }
}
