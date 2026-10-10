package app.umbra.runtime.linux

import app.umbra.core.model.action.ErrorCode
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.ShellResult
import app.umbra.platform.api.exec
import app.umbra.platform.shell.ProcessShell.Companion.quote
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the Linux guest lives and who it runs as (ADR 0003). Everything here is a path on the *host*
 * (Android) side; inside the guest the workspace is `/workspace` and the home is `/home/agent`.
 */
data class RuntimeLayout(
    /** Directory the guest root filesystem is extracted to. */
    val rootfs: String,
    /** Directory shared with the app, mounted at `/workspace` in the guest. */
    val workspaces: String,
    /** The guest user's home, mounted at `/home/agent`. Outside the rootfs so reinstalling it keeps settings and sessions. */
    val home: String,
    /** The app's uid and gid: guest processes run as this user, so files they create belong to the app. */
    val uid: Int,
    val gid: Int = uid,
    /** Android groups the guest user needs: `inet` (3003) for sockets, nothing else by default. */
    val groups: List<Int> = listOf(3003),
    /** Extra host directories to expose, e.g. `/sdcard` at `/sdcard`. */
    val binds: List<Bind> = emptyList(),
) {
    data class Bind(val host: String, val guest: String, val readOnly: Boolean = false)

    companion object {
        const val GUEST_HOME = "/home/agent"
        const val GUEST_WORKSPACE = "/workspace"
    }
}

/** What [RootChrootRuntime.status] found. */
enum class RuntimeState { NOT_INSTALLED, INSTALLED, MOUNTED }

/**
 * The Debian guest as a chroot started by root (spec §11). Mounts are real kernel mounts, so [mount]
 * and [unmount] are idempotent and tell the truth from `/proc/mounts`. Commands run inside as the
 * app's own uid with no capabilities: root is used only to enter the chroot and drop privileges.
 */
class RootChrootRuntime(
    /** A root shell (`su -c` on a phone, `adb shell su 0` for the development emulator). */
    private val root: ShellBackend,
    override val layout: RuntimeLayout,
) : LinuxRuntime {
    override val mode = LinuxMode.CHROOT

    private val r = quote(layout.rootfs)

    private suspend fun sh(command: String, timeoutMs: Long = 60_000): ShellResult = root.exec(command, timeoutMs = timeoutMs, maxOutputBytes = 1024 * 1024)

    private suspend fun shOrThrow(command: String, what: String, timeoutMs: Long = 60_000): String {
        val result = sh(command, timeoutMs)
        if (result.timedOut) throw BackendException(ErrorCode.TIMEOUT, "$what timed out", retryable = true)
        if (!result.ok) throw BackendException(ErrorCode.COMMAND_FAILED, "$what failed (exit ${result.exitCode}): ${result.combined.take(400)}")
        return result.stdout
    }

    // ---- state ----

    override suspend fun isInstalled(): Boolean = sh("test -x $r/bin/bash -a -x $r/usr/bin/setpriv").ok

    private suspend fun mountedPoints(): Set<String> =
        sh("cat /proc/mounts").stdout.lineSequence().mapNotNull { it.split(' ').getOrNull(1) }.toSet()

    override suspend fun isMounted(): Boolean = layout.rootfs.trimEnd('/') + "/proc" in mountedPoints()

    override suspend fun status(): RuntimeState = when {
        !isInstalled() -> RuntimeState.NOT_INSTALLED
        isMounted() -> RuntimeState.MOUNTED
        else -> RuntimeState.INSTALLED
    }

    /** `bun --version` style probe results for the capability screen; null when the guest is not usable. */
    override suspend fun versions(): Map<String, String>? {
        if (!isMounted()) return null
        val out = exec(listOf("bash", "-c", "echo bun=\$(bun --version 2>&1); echo omp=\$(omp --version 2>&1 | cut -d/ -f2); echo git=\$(git --version | cut -d' ' -f3); echo debian=\$(cat /etc/debian_version)"))
        if (!out.ok) return null
        return out.stdout.lineSequence().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].trim() } }.toMap()
    }

    // ---- install ----

    /**
     * Extracts a rootfs archive over an empty directory and prepares the parts that depend on this
     * device: users, DNS, ownership of the app's directories. A half-extracted guest from an earlier
     * attempt is removed first, and a failed extraction removes what it wrote, so a retry starts clean.
     *
     * The archive is decompressed here, in this process, and streamed to `tar` running as root. That
     * needs no `xz` on the device, no copy of the archive that root can read, and gives exact progress.
     *
     * @param open opens the archive (a file, or a content URI stream). It is read once, front to back.
     * @param onProgress called with the number of archive bytes consumed so far.
     */
    override suspend fun install(
        open: () -> java.io.InputStream,
        format: ArchiveFormat,
        onProgress: (bytesRead: Long) -> Unit,
        timeoutMs: Long,
    ) {
        if (isMounted()) unmount()
        shOrThrow("umask 022; rm -rf $r && mkdir -p $r && chmod 755 $r", "preparing ${layout.rootfs}")
        secured = false
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val source = { decompressed(open, format, onProgress, failure) }
        // --numeric-owner: names in the archive mean nothing on Android. As root, tar keeps owners and modes.
        val result = root.exec(ShellRequest("tar -x --numeric-owner -C $r -f -", stdinSource = source, timeoutMs = timeoutMs, maxOutputBytes = 64 * 1024))
        val problem = failure.get()
        if (!result.ok || problem != null || result.timedOut) {
            sh("rm -rf $r", 300_000) // never leave a half-extracted guest that looks installed
            val why = problem?.let { "the archive is damaged or unreadable: ${it.message}" }
                ?: if (result.timedOut) "tar timed out" else "tar failed (exit ${result.exitCode}): ${result.combined.take(400)}"
            throw BackendException(if (result.timedOut) ErrorCode.TIMEOUT else ErrorCode.COMMAND_FAILED, "extracting the rootfs: $why")
        }
        prepare()
    }

    override suspend fun extractTar(guestDir: String, open: () -> java.io.InputStream, onProgress: (bytesRead: Long) -> Unit, timeoutMs: Long) {
        val dir = quote(hostPath(guestDir))
        shOrThrow("umask 022; mkdir -p $dir", "preparing $guestDir")
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val result = root.exec(ShellRequest("tar -x --numeric-owner -C $dir -f -", stdinSource = { decompressed(open, ArchiveFormat.TAR, onProgress, failure) }, timeoutMs = timeoutMs, maxOutputBytes = 64 * 1024))
        val problem = failure.get()
        if (!result.ok || problem != null || result.timedOut) {
            sh("rm -rf $dir", 300_000)
            throw BackendException(ErrorCode.COMMAND_FAILED, "extracting into $guestDir: ${problem?.message ?: result.combined.take(400)}")
        }
        // Root's files, readable by everyone, changeable by nobody else (like the rest of the system part).
        sh("chown -R 0:0 $dir; chmod -R u+rwX,go+rX,go-w $dir", 300_000)
    }

    override suspend fun removeDir(guestDir: String) {
        require(guestDir.trim('/').isNotEmpty()) { "refusing to remove the root of the environment" }
        shOrThrow("rm -rf ${quote(hostPath(guestDir))}", "removing $guestDir", 300_000)
    }

    override suspend fun freeBytes(): Long {
        // df -k on the folder that holds the environment: the fourth column of the second line is the free space.
        val out = sh("df -k ${quote(layout.rootfs.substringBeforeLast('/'))} | tail -1", 30_000).stdout
        return out.trim().split(Regex("\\s+")).getOrNull(3)?.toLongOrNull()?.times(1024) ?: Long.MAX_VALUE
    }

    /** Device-specific setup of a freshly extracted (or upgraded) guest. Safe to repeat. */
    override suspend fun prepare() {
        val uid = layout.uid
        val gid = layout.gid
        val hosts = "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"
        shOrThrow(
            buildString {
                append("mkdir -p $r/workspace $r/home/agent $r/tmp $r/dev $r/proc $r/sys $r/mnt ${quote(layout.workspaces)} ${quote(layout.home)}; ")
                append("chmod 1777 $r/tmp; ")
                // The app's uid needs names for the tools that look them up (ssh, git, whoami, id).
                append("grep -q '^agent:' $r/etc/passwd || echo 'agent:x:$uid:$gid:Umbra agent:/home/agent:/bin/bash' >> $r/etc/passwd; ")
                append("grep -q '^agent:' $r/etc/group || echo 'agent:x:$gid:' >> $r/etc/group; ")
                append("printf %s ${quote(hosts)} > $r/etc/hosts; echo umbra > $r/etc/hostname; ")
                append("printf 'nameserver 1.1.1.1\\nnameserver 8.8.8.8\\n' > $r/etc/resolv.conf; ")
                append("chown $uid:$gid ${quote(layout.workspaces)} ${quote(layout.home)}")
            },
            "preparing the guest for this device",
        )
        secure()
    }

    @Volatile
    private var secured = false
    private val secureLock = Mutex()

    /**
     * Makes sure the agent cannot change the system part of the guest. Root enters the guest by running programs
     * from it (`setpriv`, `env`, `bash`, the loader and libraries behind them), so anything the agent could replace
     * there would be a way to root. Two things have gone wrong on real images: the folder the guest lives in took the
     * umask of the root shell that made it (an adb shell's is 0, which left `/` writable by everyone), and Bun
     * installs a few scripts writable by everyone. This takes write permission from others on all of it, except the
     * places that are the agent's by design (its home, the workspace, temporary folders, devices). Cheap enough to
     * do once per run of the app, and a no-op on a guest that is already right.
     */
    override suspend fun secure() = secureLock.withLock {
        val sweep = """
            chown 0:0 $r && chmod 755 $r || exit 1
            find $r -xdev \( -path $r/tmp -o -path $r/var/tmp -o -path $r/home/agent -o -path $r/workspace -o -path $r/dev -o -path $r/proc -o -path $r/sys \) -prune \
              -o ! -type l -perm -0002 ! \( -type d -perm -1000 \) -exec chmod go-w {} +
        """.trimIndent()
        shOrThrow(sweep, "securing the guest", timeoutMs = 300_000)
        secured = true
    }

    // ---- mounts ----

    private class MountStep(val target: String, val command: String, val required: Boolean = true)

    override suspend fun mount() {
        if (!secured) secure()
        if (isMounted()) {
            hideOtherUsersProcesses()
            return
        }
        val have = mountedPoints()
        val base = layout.rootfs.trimEnd('/')
        val steps = buildList {
            add(MountStep("$base/dev", "mount -o bind /dev $r/dev"))
            add(MountStep("$base/dev/pts", "mount -t devpts -o newinstance,ptmxmode=0666,mode=0620 devpts $r/dev/pts"))
            add(MountStep("$base/dev/shm", "mkdir -p $r/dev/shm && mount -t tmpfs -o mode=1777 tmpfs $r/dev/shm", required = false))
            // hidepid=2: processes of other users are invisible. Without it the agent can read every process's command
            // line, and the helpers' tokens are on theirs.
            // A kernel that does not know the option still gets a working /proc.
            add(MountStep("$base/proc", "mount -t proc -o hidepid=2 proc $r/proc || mount -t proc proc $r/proc"))
            add(MountStep("$base/sys", "mount -t sysfs sysfs $r/sys", required = false))
            add(MountStep("$base/tmp", "mount -t tmpfs -o mode=1777 tmpfs $r/tmp"))
            add(MountStep("$base/workspace", "mkdir -p ${quote(layout.workspaces)} && mount -o bind ${quote(layout.workspaces)} $r/workspace"))
            add(MountStep("$base/home/agent", "mkdir -p ${quote(layout.home)} && mount -o bind ${quote(layout.home)} $r/home/agent"))
            for (b in layout.binds) {
                val target = "$base/${b.guest.trimStart('/')}"
                add(MountStep(target, "mkdir -p ${quote(target)} && mount -o bind ${quote(b.host)} ${quote(target)}" + if (b.readOnly) " && mount -o remount,bind,ro ${quote(target)}" else "", required = false))
            }
        }
        try {
            for (step in steps) {
                if (step.target in have) continue
                if (step.required) shOrThrow(step.command, "mounting ${step.target}") else sh(step.command)
            }
        } catch (e: BackendException) {
            unmount() // do not leave a half-mounted guest behind
            throw e
        }
    }

    /** A guest mounted by an earlier version of the app still shows every process; hide those of other users in place. */
    private suspend fun hideOtherUsersProcesses() {
        val point = layout.rootfs.trimEnd('/') + "/proc"
        val options = sh("grep ' ${point.replace("'", "")} proc ' /proc/mounts").stdout
        if (options.isNotBlank() && !options.contains("hidepid=2") && !options.contains("hidepid=invisible")) {
            sh("mount -o remount,hidepid=2 $r/proc")
        }
    }

    /** Unmounts everything under the rootfs, deepest first, lazily so a busy mount cannot block shutdown. */
    override suspend fun unmount() {
        val base = layout.rootfs.trimEnd('/')
        val mounted = mountedPoints().filter { it == base || it.startsWith("$base/") }.sortedByDescending { it.length }
        for (point in mounted) sh("umount -l ${quote(point)}")
    }

    // ---- running things ----

    /**
     * The host-side command line (to be run by a root shell) that runs [argv] in the guest as the
     * app user: enter the chroot, drop to the app uid and group with no capabilities and no way to
     * regain privileges, clear the environment, then `exec` the command in [cwd].
     *
     * @param envFile guest path of a shell file with extra variables (credentials). It is sourced and
     *   deleted before the command starts, so the values never appear in a command line.
     * @param pidFile guest path that receives the pid of the command, so it can be killed from outside.
     */
    override fun hostCommand(
        argv: List<String>,
        env: Map<String, String>,
        cwd: String?,
        asRoot: Boolean,
        envFile: String?,
        pidFile: String?,
    ): String {
        require(argv.isNotEmpty()) { "empty command" }
        val environment = linkedMapOf(
            "HOME" to RuntimeLayout.GUEST_HOME,
            "USER" to "agent",
            "LOGNAME" to "agent",
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
            // Android's root shells keep whatever umask they were started with (an adb shell's is 0), which would make
            // every file and folder made in the guest writable by anyone in it. Debian's habit is 022.
            append("umask 022; ")
            if (envFile != null) append("set -a; . ${quote(envFile)}; set +a; rm -f ${quote(envFile)}; ")
            if (pidFile != null) append("echo \$\$ > ${quote(pidFile)}; ")
            // Root starts in `/`, never in the agent's workspace: nothing in a folder the agent can fill should be near root.
            append("cd ${quote(cwd ?: if (asRoot) "/" else RuntimeLayout.GUEST_WORKSPACE)} 2>/dev/null || cd /; exec \"\$@\"")
        }
        val run = "/usr/bin/env -i $envArgs /bin/bash -c ${quote(inner)} umbra ${argv.joinToString(" ") { quote(it) }}"
        val dropped = if (asRoot) run else
            "/usr/bin/setpriv --reuid=${layout.uid} --regid=${layout.gid} --groups=${layout.groups.joinToString(",")} " +
                "--no-new-privs --inh-caps=-all --bounding-set=-all -- $run"
        return "chroot $r $dropped"
    }

    /** Runs a command in the guest and collects its output. */
    override suspend fun exec(
        argv: List<String>,
        env: Map<String, String>,
        cwd: String?,
        asRoot: Boolean,
        stdin: String?,
        timeoutMs: Long,
        maxOutputBytes: Int,
    ): ShellResult = root.exec(ShellRequest(hostCommand(argv, env, cwd, asRoot), stdin = stdin, timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes))

    /**
     * Like [exec], for commands that need credentials in their environment. The values travel in a
     * private file that the command sources and deletes before it starts, so they never appear in a
     * command line or process listing.
     */
    override suspend fun execWithSecrets(
        argv: List<String>,
        secrets: Map<String, String>,
        env: Map<String, String>,
        cwd: String?,
        timeoutMs: Long,
        maxOutputBytes: Int,
    ): ShellResult {
        if (secrets.isEmpty()) return exec(argv, env, cwd, timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes)
        val file = "/tmp/umbra-exec-${System.nanoTime().toString(36)}.env"
        writeFile(file, secrets.entries.joinToString("\n", postfix = "\n") { (k, v) ->
            require(k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "bad environment variable name '$k'" }
            "export $k=${quote(v)}"
        })
        try {
            return root.exec(ShellRequest(hostCommand(argv, env, cwd, envFile = file), timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes))
        } finally {
            sh("rm -f ${quote(hostPath(file))}")
        }
    }


    // ---- files and processes ----

    /** Where a guest path is on the host (valid while the guest is mounted, and for paths that are not mount points). */
    override fun hostPath(guestPath: String): String = layout.rootfs.trimEnd('/') + "/" + guestPath.trimStart('/')

    /**
     * Writes a file inside the guest, owned by the app user (the guest has to be mounted). [mode] is an
     * octal string; the file is created private first so the content is never readable by anyone else,
     * even briefly.
     */
    override suspend fun writeFile(guestPath: String, content: String, mode: String) {
        val host = quote(hostPath(guestPath))
        val owner = "${layout.uid}:${layout.gid}"
        // Directories created on the way belong to the app user too, or the agent could not write beside the file.
        val script = """
            host=$host; dir=${'$'}(dirname "${'$'}host"); new=""; d="${'$'}dir"
            while [ ! -d "${'$'}d" ]; do new="${'$'}d ${'$'}new"; d=${'$'}(dirname "${'$'}d"); done
            mkdir -p "${'$'}dir" || exit 1
            for x in ${'$'}new; do chown $owner "${'$'}x"; chmod 755 "${'$'}x"; done
            ( umask 077; cat > "${'$'}host" ) || exit 1
            chown $owner "${'$'}host" && chmod $mode "${'$'}host"
        """.trimIndent()
        val result = root.exec(ShellRequest(script, stdin = content, timeoutMs = 30_000))
        if (!result.ok) throw BackendException(ErrorCode.COMMAND_FAILED, "writing $guestPath failed (exit ${result.exitCode}): ${result.combined.take(300)}")
    }

    /**
     * Writes a file that belongs to root inside the guest: the agent (the app user) can read and run it but not
     * change it, which matters for scripts that root runs later (`umbra-pack`). The old file is replaced in one
     * step, so a program that is running it is not disturbed.
     */
    override suspend fun installRootFile(guestPath: String, content: String, mode: String) {
        require(mode.matches(Regex("[0-7]{3,4}"))) { "bad mode '$mode'" }
        val host = quote(hostPath(guestPath))
        val script = """
            umask 022; host=$host; mkdir -p "${'$'}(dirname "${'$'}host")" || exit 1
            ( umask 077; cat > "${'$'}host.new" ) || exit 1
            chown 0:0 "${'$'}host.new" && chmod $mode "${'$'}host.new" && mv -f "${'$'}host.new" "${'$'}host"
        """.trimIndent()
        val result = root.exec(ShellRequest(script, stdin = content, timeoutMs = 30_000))
        if (!result.ok) throw BackendException(ErrorCode.COMMAND_FAILED, "installing $guestPath failed (exit ${result.exitCode}): ${result.combined.take(300)}")
    }

    /** Reads a small file from the guest, or null if it is not there. */
    override suspend fun readFile(guestPath: String, maxBytes: Int): String? {
        val r = root.exec(ShellRequest("cat ${quote(hostPath(guestPath))}", maxOutputBytes = maxBytes))
        return if (r.ok) r.stdout else null
    }

    /**
     * Kills [pid] and everything below it. The process table is read once from outside the guest and
     * the family worked out here; only processes whose root directory is the rootfs are signalled, so a
     * pid that was recycled for an Android process can never be hit.
     */
    override suspend fun killTree(pid: Long) {
        if (pid <= 1) return
        val table = sh("ps -A -o PID,PPID", timeoutMs = 15_000)
        val family = (if (table.ok) ProcessTable.descendantsOf(pid, table.stdout) else emptyList()) + pid
        killInGuest(family)
    }

    /**
     * Kills every process that runs inside the guest. A process belongs to the guest when its root
     * directory is the rootfs, which is exactly what `chroot` set, so nothing else is touched.
     */
    override suspend fun killAll() {
        sh("for p in /proc/[0-9]*; do [ \"\$(readlink \$p/root 2>/dev/null)\" = $r ] && kill -9 \${p#/proc/} 2>/dev/null; done; true")
    }

    private suspend fun killInGuest(pids: List<Long>) {
        if (pids.isEmpty()) return
        sh("for p in ${pids.joinToString(" ")}; do [ \"\$(readlink /proc/\$p/root 2>/dev/null)\" = $r ] && kill -9 \$p 2>/dev/null; done; true", timeoutMs = 15_000)
    }

    /** Removes the guest completely (the user's workspaces and home stay: they live outside the rootfs). */
    override suspend fun uninstall() {
        unmount()
        shOrThrow("rm -rf $r", "removing the guest", 300_000)
    }
}
