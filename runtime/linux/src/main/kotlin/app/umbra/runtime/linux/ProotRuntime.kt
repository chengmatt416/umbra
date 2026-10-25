package app.umbra.runtime.linux

import app.umbra.core.model.action.ErrorCode
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.ShellResult
import app.umbra.platform.api.exec
import app.umbra.platform.shell.ProcessShell.Companion.quote
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Debian environment without root (ADR 0012): proot translates every path the environment's programs use into
 * the rootfs folder, with ptrace, and fakes root for package installs. The programs are the app's own processes, so
 * nothing separates them from the app: this mode trades the isolation of [RootChrootRuntime] for working on any phone.
 *
 * @param binaries proot, its loader and their libraries, as the APK carries them (`lib*.so` in the native library folder).
 * @param shell runs commands as the app itself.
 */
class ProotRuntime(
    override val layout: RuntimeLayout,
    private val binaries: Binaries,
    private val shell: ShellBackend,
    private val additionalBinds: () -> List<RuntimeLayout.Bind> = { emptyList() },
) : LinuxRuntime {
    data class Binaries(
        /** The proot executable (`libproot.so`). */
        val proot: String,
        /** proot's loader (`libproot-loader.so`), run inside every traced program. */
        val loader: String,
        /** Where proot's libraries are (`libtalloc.so`, `libandroid-shmem.so`). */
        val libraryDir: String,
        /** A private folder proot can write its temporary files to. */
        val tmpDir: String,
    )

    override val mode = LinuxMode.PROOT

    private val rootfs = File(layout.rootfs)

    override suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) {
        File(rootfs, "usr/bin/bash").isFile && File(rootfs, "usr/bin/env").isFile
    }

    override suspend fun isMounted(): Boolean = isInstalled()

    override suspend fun status(): RuntimeState = if (isInstalled()) RuntimeState.MOUNTED else RuntimeState.NOT_INSTALLED

    override suspend fun versions(): Map<String, String>? {
        if (!isInstalled()) return null
        val out = exec(listOf("bash", "-c", "echo bun=\$(bun --version 2>&1); echo omp=\$(omp --version 2>&1 | cut -d/ -f2); echo git=\$(git --version | cut -d' ' -f3); echo debian=\$(cat /etc/debian_version)"), timeoutMs = 120_000)
        if (!out.ok) return null
        return out.stdout.lineSequence().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].trim() } }.toMap()
    }

    // ---- install ----

    override suspend fun install(open: () -> InputStream, format: ArchiveFormat, onProgress: (bytesRead: Long) -> Unit, timeoutMs: Long) {
        killAll()
        removeAll(rootfs)
        val failure = AtomicReference<Throwable?>()
        try {
            withContext(Dispatchers.IO) {
                decompressed(open, format, onProgress, failure).buffered(256 * 1024).use { TarReader.extract(it, rootfs) }
            }
        } catch (e: Exception) {
            removeAll(rootfs) // never leave a half-extracted environment that looks installed
            val why = failure.get()?.let { "the archive is damaged or unreadable: ${it.message}" } ?: (e.message ?: e::class.simpleName)
            throw BackendException(ErrorCode.COMMAND_FAILED, "extracting the rootfs: $why")
        }
        prepare()
    }

    override suspend fun extractTar(guestDir: String, open: () -> InputStream, onProgress: (bytesRead: Long) -> Unit, timeoutMs: Long) {
        val dir = File(hostPath(guestDir))
        val failure = AtomicReference<Throwable?>()
        try {
            withContext(Dispatchers.IO) {
                decompressed(open, ArchiveFormat.TAR, onProgress, failure).buffered(256 * 1024).use { TarReader.extract(it, dir) }
            }
        } catch (e: Exception) {
            removeAll(dir)
            throw BackendException(ErrorCode.COMMAND_FAILED, "extracting into $guestDir: ${failure.get()?.message ?: e.message}")
        }
    }

    override suspend fun removeDir(guestDir: String) {
        require(guestDir.trim('/').isNotEmpty()) { "refusing to remove the root of the environment" }
        removeAll(File(hostPath(guestDir)))
    }

    override suspend fun freeBytes(): Long = withContext(Dispatchers.IO) { (rootfs.takeIf { it.exists() } ?: rootfs.parentFile ?: rootfs).usableSpace }

    /** `rm -rf` (toybox's, which never follows links), as the app. */
    private suspend fun removeAll(dir: File) {
        if (!dir.exists() && !java.nio.file.Files.isSymbolicLink(dir.toPath())) return
        val r = shell.exec(ShellRequest("chmod -R u+rwX ${quote(dir.path)} 2>/dev/null; rm -rf ${quote(dir.path)}", timeoutMs = 600_000))
        if (!r.ok && dir.exists()) throw BackendException(ErrorCode.COMMAND_FAILED, "removing ${dir.path} failed: ${r.combined.take(300)}")
    }

    override suspend fun prepare() = withContext(Dispatchers.IO) {
        for (d in listOf("workspace", "home/agent", "tmp", "dev", "proc", "sys", "mnt", "var/tmp")) File(rootfs, d).mkdirs()
        File(layout.workspaces).mkdirs()
        File(layout.home).mkdirs()
        TarReader.setMode(File(rootfs, "tmp"), 0b111_111_111)
        TarReader.setMode(File(rootfs, "var/tmp"), 0b111_111_111)
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        File(rootfs, "etc/hostname").writeText("umbra\n")
        File(rootfs, "etc/resolv.conf").apply { delete(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        // The app's uid needs names for the tools that look them up (ssh, git, whoami, id).
        appendLineIfMissing(File(rootfs, "etc/passwd"), "agent:", "agent:x:${layout.uid}:${layout.gid}:Umbra agent:/home/agent:/bin/bash")
        appendLineIfMissing(File(rootfs, "etc/group"), "agent:", "agent:x:${layout.gid}:")
        writeFakeProc()
    }

    private fun appendLineIfMissing(file: File, prefix: String, line: String) {
        val lines = if (file.exists()) file.readLines() else emptyList()
        // An environment installed for another uid (reinstalled app) gets the current one.
        val kept = lines.filterNot { it.startsWith(prefix) }
        file.writeText((kept + line).joinToString("\n", postfix = "\n"))
    }

    /**
     * Android does not let apps read some of /proc (stat, loadavg, uptime, version, vmstat), and programs (ps, top,
     * Bun, the JVM) stop when they cannot. proot puts files with plausible contents in their place, as Termux does.
     */
    private fun writeFakeProc() {
        val dir = File(rootfs, "proc/.umbra").apply { mkdirs() }
        File(dir, "loadavg").writeText("0.12 0.07 0.02 2/165 765\n")
        File(dir, "stat").writeText("cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\nctxt 140223\nbtime 1680020856\nprocesses 772\nprocs_running 2\nprocs_blocked 0\n")
        File(dir, "uptime").writeText("124.08 932.80\n")
        File(dir, "version").writeText("Linux version 6.1.0-umbra (umbra@localhost) (gcc version 12) #1 SMP PREEMPT\n")
        File(dir, "vmstat").writeText("nr_free_pages 136970\nnr_inactive_anon 54\nnr_active_anon 3\npgpgin 0\npgpgout 0\n")
    }

    override suspend fun secure() = Unit // nothing separates the environment from the app in this mode (ADR 0012)

    override suspend fun mount() {
        if (!isInstalled()) throw BackendException(ErrorCode.BACKEND_UNAVAILABLE, "the Linux environment is not installed")
        withContext(Dispatchers.IO) {
            File(layout.workspaces).mkdirs()
            File(layout.home).mkdirs()
            File(binaries.tmpDir).mkdirs()
        }
    }

    override suspend fun unmount() = Unit

    // ---- running things ----

    /** The binds that make the environment look like the chroot: devices, the kernel's views, the shared folders. */
    private fun binds(): List<String> = buildList {
        add("/dev")
        add("/proc")
        add("/sys")
        add("/dev/urandom:/dev/random")
        add("${layout.workspaces}:${RuntimeLayout.GUEST_WORKSPACE}")
        add("${layout.home}:${RuntimeLayout.GUEST_HOME}")
        for (name in listOf("loadavg", "stat", "uptime", "version", "vmstat")) {
            if (!File("/proc/$name").canRead()) add("${layout.rootfs}/proc/.umbra/$name:/proc/$name")
        }
        for (b in layout.binds) add("${b.host}:${b.guest}")
        for (b in additionalBinds()) {
            require(b.guest.startsWith("/storage/") && ':' !in b.host && ':' !in b.guest) { "Unsupported shared-storage mount path" }
            add("${b.host}:${b.guest}")
        }
    }

    override fun hostCommand(argv: List<String>, env: Map<String, String>, cwd: String?, asRoot: Boolean, envFile: String?, pidFile: String?, secretsOnStdin: Boolean): String {
        require(argv.isNotEmpty()) { "empty command" }
        val environment = linkedMapOf(
            "HOME" to RuntimeLayout.GUEST_HOME,
            "USER" to if (asRoot) "root" else "agent",
            "LOGNAME" to if (asRoot) "root" else "agent",
            "SHELL" to "/bin/bash",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "PATH" to "/opt/umbra/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "TMPDIR" to "/tmp",
        ).apply { putAll(env) }
        val envArgs = environment.entries.joinToString(" ") { (k, v) ->
            require(k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "bad environment variable name '$k'" }
            "$k=${quote(v)}"
        }
        val inner = buildString {
            append("umask 022; ")
            if (secretsOnStdin) append(SecretStdin.PREAMBLE)
            if (envFile != null) append("set -a; . ${quote(envFile)}; set +a; rm -f ${quote(envFile)}; ")
            if (pidFile != null) append("echo \$\$ > ${quote(pidFile)}; ")
            append("cd ${quote(cwd ?: if (asRoot) "/" else RuntimeLayout.GUEST_WORKSPACE)} 2>/dev/null || cd /; exec \"\$@\"")
        }
        val proot = buildList {
            add(binaries.proot)
            add("--kill-on-exit")
            add("--link2symlink")
            add("--sysvipc")
            if (asRoot) add("-0")
            add("-r"); add(layout.rootfs)
            for (b in binds()) { add("-b"); add(b) }
            add("-w"); add("/")
        }.joinToString(" ") { quote(it) }
        val prootEnv = "PROOT_TMP_DIR=${quote(binaries.tmpDir)} PROOT_LOADER=${quote(binaries.loader)} LD_LIBRARY_PATH=${quote(binaries.libraryDir)}"
        return "$prootEnv exec $proot /usr/bin/env -i $envArgs /bin/bash -c ${quote(inner)} umbra ${argv.joinToString(" ") { quote(it) }}"
    }

    override suspend fun exec(argv: List<String>, env: Map<String, String>, cwd: String?, asRoot: Boolean, stdin: String?, timeoutMs: Long, maxOutputBytes: Int): ShellResult {
        mount()
        return shell.exec(ShellRequest(hostCommand(argv, env, cwd, asRoot), stdin = stdin, timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes))
    }

    override suspend fun execWithSecrets(argv: List<String>, secrets: Map<String, String>, env: Map<String, String>, cwd: String?, timeoutMs: Long, maxOutputBytes: Int): ShellResult {
        if (secrets.isEmpty()) return exec(argv, env, cwd, timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes)
        mount()
        return shell.exec(ShellRequest(hostCommand(argv, env, cwd, secretsOnStdin = true), stdin = SecretStdin.payload(secrets), timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes))
    }

    // ---- files and processes ----

    /** The workspace and the home are bound from outside the rootfs, so their paths lead there. */
    override fun hostPath(guestPath: String): String {
        val p = guestPath(guestPath)
        fun under(prefix: String) = p == prefix || p.startsWith("$prefix/")
        return when {
            under(RuntimeLayout.GUEST_WORKSPACE) -> layout.workspaces.trimEnd('/') + p.removePrefix(RuntimeLayout.GUEST_WORKSPACE)
            under(RuntimeLayout.GUEST_HOME) -> layout.home.trimEnd('/') + p.removePrefix(RuntimeLayout.GUEST_HOME)
            else -> layout.rootfs.trimEnd('/') + p
        }
    }

    override suspend fun writeFile(guestPath: String, content: String, mode: String) = withContext(Dispatchers.IO) {
        fileMode(mode)
        val file = File(hostPath(guestPath))
        val parent = requireNotNull(file.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw BackendException(ErrorCode.COMMAND_FAILED, "creating parent of $guestPath failed")
        val tmp = File.createTempFile(".umbra-write-", ".tmp", parent)
        try {
            TarReader.setMode(tmp, 0b110_000_000)
            tmp.writeText(content)
            TarReader.setMode(tmp, mode.toInt(8))
            if (!tmp.renameTo(file)) throw BackendException(ErrorCode.COMMAND_FAILED, "writing $guestPath failed")
        } finally {
            tmp.delete()
        }
    }

    override suspend fun installRootFile(guestPath: String, content: String, mode: String) = writeFile(guestPath, content, mode)

    override suspend fun readFile(guestPath: String, maxBytes: Int): String? = withContext(Dispatchers.IO) {
        val f = File(hostPath(guestPath))
        if (!f.isFile) return@withContext null
        f.inputStream().use { input -> String(input.readNBytes(maxBytes), Charsets.UTF_8) }
    }

    override suspend fun killTree(pid: Long) {
        if (pid <= 1) return
        val table = shell.exec(ShellRequest("ps -A -o PID,PPID", timeoutMs = 15_000))
        val family = (if (table.ok) ProcessTable.descendantsOf(pid, table.stdout) else emptyList()) + pid
        shell.exec(ShellRequest("kill -9 ${family.joinToString(" ")} 2>/dev/null; true", timeoutMs = 15_000))
    }

    /** Every proot of this environment, and what runs under it. Only the app's own processes can be seen or touched. */
    override suspend fun killAll() {
        val table = shell.exec(ShellRequest("ps -A -o PID,ARGS", timeoutMs = 15_000))
        if (!table.ok) return
        val marker = "-r ${layout.rootfs}"
        val roots = table.stdout.lineSequence().mapNotNull { line ->
            val t = line.trim()
            val pid = t.substringBefore(' ').toLongOrNull() ?: return@mapNotNull null
            pid.takeIf { t.contains(binaries.proot) && t.contains(marker) }
        }.toList()
        for (pid in roots) killTree(pid)
    }

    override suspend fun uninstall() {
        killAll()
        removeAll(rootfs)
    }
}
