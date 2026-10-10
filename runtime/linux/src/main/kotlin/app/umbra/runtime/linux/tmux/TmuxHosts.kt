package app.umbra.runtime.linux.tmux

import app.umbra.runtime.linux.LinuxRuntime
import app.umbra.runtime.omp.LocalSpawner
import app.umbra.runtime.omp.OmpSpawner
import app.umbra.runtime.omp.OmpTransport
import app.umbra.runtime.omp.SpawnSpec
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** tmux in the Linux guest, as the agent's user (the same trust boundary as the agent: spec §6.1 is explicit that a
 *  tmux socket is not a sandbox). `-u` forces UTF-8 whatever the locale. */
class GuestTmuxHost(private val runtime: LinuxRuntime, private val spawner: OmpSpawner) : TmuxHost {
    private val env = mapOf("LANG" to "C.UTF-8", "LC_ALL" to "C.UTF-8", "TERM" to "xterm-256color", "HOME" to "/home/agent")

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
