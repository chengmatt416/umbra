package app.umbra.runtime.linux

import app.umbra.platform.shell.ProcessShell.Companion.quote
import app.umbra.runtime.omp.OmpSpawner
import app.umbra.runtime.omp.OmpTransport
import app.umbra.runtime.omp.SpawnSpec
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts omp inside the Linux guest. In the chroot, a root shell enters it and drops to the app's uid, so the
 * agent's processes are ordinary app-uid processes that merely see a Debian filesystem; under proot (no root) the
 * app starts proot itself.
 *
 * @param launcher starts a shell command as root: [SuLauncher] on a phone (`su -c`), [HelperLauncher] where a
 *   root-owned helper is the only root the app has. The development tools go through `adb shell su 0 sh -c`.
 *   Not used for [LinuxMode.PROOT], whose command lines the app runs as itself ([AppLauncher]).
 */
class GuestOmpSpawner(
    private val runtime: LinuxRuntime,
    private val launcher: RootLauncher,
) : OmpSpawner {
    constructor(runtime: LinuxRuntime, rootCommand: (shellCommand: String) -> List<String>) : this(runtime, SuLauncher(rootCommand))

    private val counter = AtomicLong()

    override suspend fun spawn(spec: SpawnSpec): OmpTransport {
        runtime.mount()
        val id = "${System.currentTimeMillis().toString(36)}-${counter.incrementAndGet()}"
        val pidFile = "/tmp/umbra-omp-$id.pid"
        var envFile: String? = null
        if (spec.secrets.isNotEmpty()) {
            envFile = "/tmp/umbra-omp-$id.env"
            runtime.writeFile(envFile, spec.secrets.entries.joinToString("\n", postfix = "\n") { (k, v) ->
                require(k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "bad environment variable name '$k'" }
                "export $k=${quote(v)}"
            })
        }
        val command = runtime.hostCommand(
            argv = spec.command,
            env = spec.environment,
            cwd = spec.workingDirectory,
            envFile = envFile,
            pidFile = pidFile,
        )
        val process = (if (runtime.mode == LinuxMode.PROOT) AppLauncher else launcher).start(command, spec.stderrLines)
        return GuestTransport(process, runtime, pidFile, envFile)
    }
}

/** The pipes of a guest process, plus the means to end it: killing the `su` wrapper alone would leave omp running. */
internal class GuestTransport(
    private val wrapper: OmpTransport,
    private val runtime: LinuxRuntime,
    private val pidFile: String,
    private val envFile: String?,
) : OmpTransport by wrapper {
    @Volatile
    private var cachedPid: Long? = null

    /** The id omp has inside the guest (the launcher wrote it to [pidFile] just before starting it). */
    override suspend fun processId(): Long? {
        cachedPid?.let { return it }
        return runtime.readFile(pidFile, 64)?.trim()?.toLongOrNull()?.also { cachedPid = it }
    }

    override suspend fun destroy() {
        wrapper.destroy()
        // Even if the caller is being cancelled, the guest process must not be left behind.
        withContext(NonCancellable) {
            withTimeoutOrNull(20_000) {
                val pid = runtime.readFile(pidFile, 64)?.trim()?.toLongOrNull()
                if (pid != null) runtime.killTree(pid)
                runtime.exec(listOf("rm", "-f", pidFile) + listOfNotNull(envFile), asRoot = true, timeoutMs = 5_000)
            }
        }
    }
}

/** omp's config directory inside the guest, written through the root shell (for hosts that cannot touch the guest's files directly). */
class GuestAgentFiles(private val runtime: LinuxRuntime, override val ompPath: String) : app.umbra.runtime.omp.AgentFiles {
    override suspend fun write(name: String, content: String) {
        runtime.writeFile("${ompPath.trimEnd('/')}/$name", content, mode = "644")
    }
}
