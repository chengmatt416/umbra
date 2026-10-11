package app.umbra.runtime.linux.tmux

import app.umbra.runtime.linux.LinuxRuntime
import app.umbra.runtime.omp.LocalSpawner
import app.umbra.runtime.omp.OmpSpawner
import app.umbra.runtime.omp.OmpTransport
import app.umbra.runtime.omp.SpawnSpec
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** tmux in the Linux guest, as the agent's user (the same trust boundary as the agent: spec §6.1 is explicit that a
 *  tmux socket is not a sandbox). `-u` forces UTF-8 whatever the locale. */
class GuestTmuxHost(private val runtime: LinuxRuntime, private val spawner: OmpSpawner) : TmuxHost {
    private val env = mapOf("LANG" to "C.UTF-8", "LC_ALL" to "C.UTF-8", "TERM" to "xterm-256color", "HOME" to "/home/agent")
    private val servers = java.util.concurrent.ConcurrentHashMap<String, OmpTransport>()
    private val serverLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Under proot every command runs with --kill-on-exit: a server tmux daemonizes from a short command dies with it.
     * There the group's server is started in the foreground (`-D`, which also keeps it when it has no session) as a
     * long-lived process of its own, and every other command talks to it through its socket.
     */
    override suspend fun ensureServer(socket: String) {
        if (runtime.mode != app.umbra.runtime.linux.LinuxMode.PROOT) return
        serverLock.withLock {
            if (answers(socket)) return
            // A handle whose server no longer answers is dropped; the new server replaces it.
            servers.remove(socket)
            servers[socket] = spawner.spawn(SpawnSpec(command = listOf("tmux", "-u", "-L", socket, "-D"), environment = env, workingDirectory = "/"))
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                if (answers(socket)) return
                kotlinx.coroutines.delay(200)
            }
            throw TmuxCommandException("-L $socket -D", listOf("the tmux server did not start"))
        }
    }

    /** True when a server listens on [socket] (with or without sessions). */
    private suspend fun answers(socket: String): Boolean {
        val r = runtime.exec(listOf("tmux", "-u", "-L", socket, "list-sessions"), env = env, cwd = "/", timeoutMs = 10_000)
        val said = (r.stderr + r.stdout).lowercase()
        return r.exitCode == 0 || !("no server running" in said || "error connecting" in said || "no such file" in said)
    }

    override suspend fun run(args: List<String>, timeoutMs: Long): String {
        val r = runtime.exec(listOf("tmux", "-u") + args, env = env, cwd = "/", timeoutMs = timeoutMs)
        if (r.exitCode != 0) throw TmuxCommandException(args.joinToString(" "), listOf(r.stderr.ifBlank { r.stdout }.trim()))
        return r.stdout
    }

    override suspend fun spawn(args: List<String>): OmpTransport =
        spawner.spawn(SpawnSpec(command = listOf("tmux", "-u") + args, environment = env, workingDirectory = "/"))

    override suspend fun bootId(): String =
        runtime.exec(listOf("cat", "/proc/sys/kernel/random/boot_id"), timeoutMs = 10_000).stdout.trim()
}

/** tmux on this machine: development and the host-side tests. */
class LocalTmuxHost(private val binary: String = "tmux", private val env: Map<String, String> = emptyMap()) : TmuxHost {
    override suspend fun run(args: List<String>, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val p = ProcessBuilder(listOf(binary, "-u") + args).redirectErrorStream(false).apply { environment().putAll(env) }.start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); throw TmuxCommandException(args.joinToString(" "), listOf("timed out")) }
        if (p.exitValue() != 0) throw TmuxCommandException(args.joinToString(" "), listOf(err.ifBlank { out }.trim()))
        out
    }

    override suspend fun spawn(args: List<String>): OmpTransport =
        LocalSpawner.spawn(SpawnSpec(command = listOf(binary, "-u") + args, environment = env, workingDirectory = System.getProperty("user.home")))

    override suspend fun bootId(): String = withContext(Dispatchers.IO) {
        File("/proc/sys/kernel/random/boot_id").takeIf { it.canRead() }?.readText()?.trim()
            ?: ProcessBuilder("sysctl", "-n", "kern.bootsessionuuid").start().inputStream.bufferedReader().readText().trim().ifBlank { "unknown" }
    }
}
