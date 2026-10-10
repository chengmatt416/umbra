package app.umbra.core.orchestrator

import app.umbra.core.workspace.WorkspaceLayout
import app.umbra.runtime.linux.GuestOmpSpawner
import app.umbra.runtime.linux.LinuxRuntime
import app.umbra.runtime.linux.RuntimeLayout
import app.umbra.runtime.linux.RuntimeState
import app.umbra.runtime.omp.AgentFiles
import app.umbra.runtime.omp.LocalAgentFiles
import app.umbra.runtime.omp.LocalSpawner
import app.umbra.runtime.omp.OmpSpawner
import java.nio.file.Path

/**
 * Where omp runs, and how paths translate between the app and the process. On a phone omp runs in the
 * Linux guest; on a development machine it runs directly, which is how the orchestrator is exercised
 * against the real omp binary without a phone.
 */
interface AgentEnvironment {
    val name: String
    val spawner: OmpSpawner

    /** The command that starts omp, as the environment itself resolves it. */
    val ompExecutable: List<String>

    /** Variables every omp process gets (HOME, PATH, ...). */
    val environment: Map<String, String>

    /** The workspace tree: the app's view ([WorkspaceLayout.hostRoot]) and omp's view ([WorkspaceLayout.guestRoot]). */
    val workspaces: WorkspaceLayout

    /** Per-profile omp state (models, settings overlay, auth, caches). Profiles isolate accounts from each other. */
    fun agentFiles(profile: String): AgentFiles

    /** Where omp stores the conversation of [sessionId], as omp sees the path. */
    fun ompSessionDir(sessionId: String): String

    /** Makes the environment usable (mounts the guest). Safe to repeat. */
    suspend fun prepare()

    /** Why omp cannot start here, or null when it can. */
    suspend fun problem(): String?

    /** Ends processes a previous run of the app left behind. */
    suspend fun cleanup()

    /** What omp (process [rootPid]) has started and is still running: tool commands, servers, builds. */
    suspend fun descendants(rootPid: Long): List<app.umbra.core.session.ProcessRef> = emptyList()
}

/** The Debian guest started by root. [hostRoot] paths are the ones the app itself can read and write. */
class GuestEnvironment(
    val runtime: LinuxRuntime,
    launcher: app.umbra.runtime.linux.RootLauncher,
    /** The app's view of the directories the guest sees as `/workspace` and `/home/agent`. */
    private val hostWorkspaces: Path,
    private val hostHome: Path,
    private val directWrites: Boolean = true,
    /** Runs once the guest is mounted, before any agent process starts in it (the app brings its own scripts up to date here). */
    private val afterMount: suspend () -> Unit = {},
) : AgentEnvironment {
    override val name = "linux-guest"
    override val spawner: OmpSpawner = GuestOmpSpawner(runtime, launcher)
    override val ompExecutable = listOf("omp")
    override val environment = mapOf(
        "OTEL_SDK_DISABLED" to "true",
        "BUN_INSTALL" to "/opt/bun",
        // Where the Android pack puts the SDK platform and the dex compiler; harmless when the pack is not installed.
        "ANDROID_HOME" to "/opt/android-sdk",
        "ANDROID_SDK_ROOT" to "/opt/android-sdk",
        // omp is a large bundle; keeping Bun's transpiled copy on persistent storage makes every start after the first faster.
        "BUN_RUNTIME_TRANSPILER_CACHE_PATH" to "${RuntimeLayout.GUEST_HOME}/.cache/bun-transpiler",
        // Android has no usable /etc/ssl in the app's view, but the guest ships its own certificates.
        "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
    )
    override val workspaces = WorkspaceLayout(hostRoot = hostWorkspaces, guestRoot = RuntimeLayout.GUEST_WORKSPACE)

    override fun agentFiles(profile: String): AgentFiles {
        val guestDir = "${RuntimeLayout.GUEST_HOME}/.umbra/agents/$profile"
        return if (directWrites) {
            LocalAgentFiles(hostHome.resolve(".umbra/agents/$profile"), ompPath = guestDir)
        } else {
            app.umbra.runtime.linux.GuestAgentFiles(runtime, guestDir)
        }
    }

    override fun ompSessionDir(sessionId: String) = "${RuntimeLayout.GUEST_HOME}/.umbra/omp-sessions/$sessionId"

    override suspend fun prepare() {
        runtime.mount()
        afterMount()
    }

    override suspend fun problem(): String? = when (runtime.status()) {
        RuntimeState.NOT_INSTALLED -> "The Linux environment is not installed yet."
        RuntimeState.INSTALLED, RuntimeState.MOUNTED -> null
    }

    override suspend fun cleanup() {
        if (runtime.isInstalled()) runtime.killAll()
    }

    override suspend fun descendants(rootPid: Long): List<app.umbra.core.session.ProcessRef> {
        val out = runtime.exec(listOf("ps", "-e", "-o", "pid=,ppid=,etimes=,args="), timeoutMs = 10_000)
        if (!out.ok) return emptyList()
        class Row(val pid: Long, val ppid: Long, val age: Long, val args: String)
        val rows = out.stdout.lineSequence().mapNotNull { line ->
            Regex("""^\s*(\d+)\s+(\d+)\s+(\d+)\s+(.*)$""").find(line)?.let { Row(it.groupValues[1].toLong(), it.groupValues[2].toLong(), it.groupValues[3].toLong(), it.groupValues[4]) }
        }.toList()
        val children = rows.groupBy { it.ppid }
        val found = ArrayList<Row>()
        fun walk(pid: Long) {
            for (child in children[pid].orEmpty()) {
                found += child
                walk(child.pid)
            }
        }
        walk(rootPid)
        val now = System.currentTimeMillis()
        return found.filter { !it.args.startsWith("ps ") }.map {
            app.umbra.core.session.ProcessRef(it.pid.toInt(), it.args.substringBefore(' ').substringAfterLast('/'), "linux", now - it.age * 1000)
        }
    }
}

/** omp running directly on this machine, for development and tests against the real binary. */
class LocalEnvironment(
    private val ompBinary: String,
    private val home: Path,
    private val workspaceRoot: Path,
    override val environment: Map<String, String> = emptyMap(),
) : AgentEnvironment {
    override val name = "local"
    override val spawner: OmpSpawner = LocalSpawner
    override val ompExecutable = listOf(ompBinary)
    override val workspaces = WorkspaceLayout(hostRoot = workspaceRoot, guestRoot = workspaceRoot.toAbsolutePath().normalize().toString())

    override fun agentFiles(profile: String): AgentFiles = LocalAgentFiles(home.resolve(".umbra/agents/$profile"))

    override fun ompSessionDir(sessionId: String) = home.resolve(".umbra/omp-sessions/$sessionId").toString()

    override suspend fun prepare() = Unit

    override suspend fun problem(): String? = null

    override suspend fun cleanup() = Unit

    override suspend fun descendants(rootPid: Long): List<app.umbra.core.session.ProcessRef> {
        val root = ProcessHandle.of(rootPid).orElse(null) ?: return emptyList()
        return root.descendants().toList().map { h ->
            app.umbra.core.session.ProcessRef(h.pid().toInt(), h.info().command().orElse("?").substringAfterLast('/'), "local", h.info().startInstant().map { it.toEpochMilli() }.orElse(null))
        }
    }
}
