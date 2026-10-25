package app.umbra.runtime.linux

import app.umbra.platform.api.ShellResult
import java.io.File
import java.io.InputStream

/** How the Debian environment runs (ADR 0003, ADR 0012). */
enum class LinuxMode {
    /** A chroot entered by root, with real mounts; the agent runs as the app's uid without capabilities. */
    CHROOT,

    /** proot, without root: path translation by ptrace, fake root for package installs, no isolation from the app. */
    PROOT,
}

/** How a rootfs archive is compressed. Android ships no `xz`, so the app decompresses and `tar` only ever sees plain input. */
enum class ArchiveFormat {
    XZ, GZIP, TAR;

    companion object {
        fun forName(name: String): ArchiveFormat = when {
            name.endsWith(".tar.xz") || name.endsWith(".txz") -> XZ
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> GZIP
            else -> TAR
        }
    }
}

/**
 * The Debian environment the agent works in, whichever way it runs. Paths in [layout] are host (Android) paths;
 * inside the environment the workspace is `/workspace` and the home is `/home/agent`.
 */
interface LinuxRuntime {
    val layout: RuntimeLayout
    val mode: LinuxMode

    suspend fun isInstalled(): Boolean

    suspend fun isMounted(): Boolean

    suspend fun status(): RuntimeState

    /** `bun --version` style probe results for the capability screen; null when the environment is not usable. */
    suspend fun versions(): Map<String, String>?

    /**
     * Extracts a rootfs archive over an empty directory and prepares it for this device. A failed extraction removes
     * what it wrote, so a retry starts clean. [open] is read once, front to back; [onProgress] gets archive bytes read.
     */
    suspend fun install(open: () -> InputStream, format: ArchiveFormat, onProgress: (bytesRead: Long) -> Unit = {}, timeoutMs: Long = 60 * 60_000L)

    suspend fun installFile(file: File, onProgress: (bytesRead: Long, total: Long) -> Unit = { _, _ -> }) {
        val total = file.length()
        install({ file.inputStream().buffered(256 * 1024) }, ArchiveFormat.forName(file.name), { onProgress(it, total) })
    }

    /** Extracts a plain tar into a directory of the environment (owned like the rest of its system part). */
    suspend fun extractTar(guestDir: String, open: () -> InputStream, onProgress: (bytesRead: Long) -> Unit = {}, timeoutMs: Long = 30 * 60_000L)

    /** Removes a directory of the environment and everything in it, links included but never followed. */
    suspend fun removeDir(guestDir: String)

    /** Bytes free on the file system that holds the environment. */
    suspend fun freeBytes(): Long

    /** Device-specific setup of a freshly extracted (or upgraded) environment. Safe to repeat. */
    suspend fun prepare()

    /** Takes from the agent what it must not change (ADR 0010); nothing to do where there is no isolation. */
    suspend fun secure()

    suspend fun mount()

    suspend fun unmount()

    /**
     * The host-side command line that runs [argv] in the environment as the agent ([asRoot]: as root, for package
     * installs). Run by a root shell for [LinuxMode.CHROOT] and by the app itself for [LinuxMode.PROOT].
     */
    fun hostCommand(
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: String? = null,
        asRoot: Boolean = false,
        envFile: String? = null,
        pidFile: String? = null,
        /** The command's stdin starts with [SecretStdin] lines, which the wrapper exports before running [argv]. */
        secretsOnStdin: Boolean = false,
    ): String

    suspend fun exec(
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: String? = null,
        asRoot: Boolean = false,
        stdin: String? = null,
        timeoutMs: Long = 120_000,
        maxOutputBytes: Int = 256 * 1024,
    ): ShellResult

    suspend fun execWithSecrets(
        argv: List<String>,
        secrets: Map<String, String>,
        env: Map<String, String> = emptyMap(),
        cwd: String? = null,
        timeoutMs: Long = 120_000,
        maxOutputBytes: Int = 1024 * 1024,
    ): ShellResult

    /** Installs an add-on (see `umbra-pack`) as root inside the environment. */
    suspend fun installPack(name: String): ShellResult =
        exec(listOf("umbra-pack", "install", name), asRoot = true, timeoutMs = 30 * 60_000L, maxOutputBytes = 1024 * 1024)

    /** Where a path of the environment is on the host. */
    fun hostPath(guestPath: String): String

    /** Writes a file owned by the agent (folders made on the way too); [mode] is octal. */
    suspend fun writeFile(guestPath: String, content: String, mode: String = "600")

    /** Writes a file of the system part (root's where there is a root). */
    suspend fun installRootFile(guestPath: String, content: String, mode: String = "644")

    /** Brings the app's own files in the environment up to date; true when files were written (ADR 0009). */
    suspend fun syncFiles(set: GuestFileSet, markerPath: String = "/opt/umbra/.files-version"): Boolean {
        if (readFile(markerPath, 64)?.trim() == set.version) return false
        for (f in set.files) installRootFile(f.path, f.content, f.mode)
        installRootFile(markerPath, set.version + "\n", "644")
        return true
    }

    /** Reads a small file from the environment, or null if it is not there. */
    suspend fun readFile(guestPath: String, maxBytes: Int = 64 * 1024): String?

    /** Kills a process of the environment and everything below it. */
    suspend fun killTree(pid: Long)

    /** Kills every process that runs in the environment. */
    suspend fun killAll()

    /** Removes the environment completely; the workspaces and the home stay. */
    suspend fun uninstall()
}

/**
 * A [LinuxRuntime] whose implementation can change while the app runs (root granted or taken away, the environment
 * reinstalled for the other mode), handed to everything that holds on to "the runtime" for the app's lifetime.
 */
class DelegatingLinuxRuntime(private val current: () -> LinuxRuntime) : LinuxRuntime {
    private val d get() = current()
    override val layout get() = d.layout
    override val mode get() = d.mode
    override suspend fun isInstalled() = d.isInstalled()
    override suspend fun isMounted() = d.isMounted()
    override suspend fun status() = d.status()
    override suspend fun versions() = d.versions()
    override suspend fun install(open: () -> InputStream, format: ArchiveFormat, onProgress: (bytesRead: Long) -> Unit, timeoutMs: Long) = d.install(open, format, onProgress, timeoutMs)
    override suspend fun installFile(file: File, onProgress: (bytesRead: Long, total: Long) -> Unit) = d.installFile(file, onProgress)
    override suspend fun extractTar(guestDir: String, open: () -> InputStream, onProgress: (bytesRead: Long) -> Unit, timeoutMs: Long) = d.extractTar(guestDir, open, onProgress, timeoutMs)
    override suspend fun removeDir(guestDir: String) = d.removeDir(guestDir)
    override suspend fun freeBytes() = d.freeBytes()
    override suspend fun prepare() = d.prepare()
    override suspend fun secure() = d.secure()
    override suspend fun mount() = d.mount()
    override suspend fun unmount() = d.unmount()
    override fun hostCommand(argv: List<String>, env: Map<String, String>, cwd: String?, asRoot: Boolean, envFile: String?, pidFile: String?, secretsOnStdin: Boolean) =
        d.hostCommand(argv, env, cwd, asRoot, envFile, pidFile, secretsOnStdin)
    override suspend fun exec(argv: List<String>, env: Map<String, String>, cwd: String?, asRoot: Boolean, stdin: String?, timeoutMs: Long, maxOutputBytes: Int) =
        d.exec(argv, env, cwd, asRoot, stdin, timeoutMs, maxOutputBytes)
    override suspend fun execWithSecrets(argv: List<String>, secrets: Map<String, String>, env: Map<String, String>, cwd: String?, timeoutMs: Long, maxOutputBytes: Int) =
        d.execWithSecrets(argv, secrets, env, cwd, timeoutMs, maxOutputBytes)
    override suspend fun installPack(name: String) = d.installPack(name)
    override fun hostPath(guestPath: String) = d.hostPath(guestPath)
    override suspend fun writeFile(guestPath: String, content: String, mode: String) = d.writeFile(guestPath, content, mode)
    override suspend fun installRootFile(guestPath: String, content: String, mode: String) = d.installRootFile(guestPath, content, mode)
    override suspend fun syncFiles(set: GuestFileSet, markerPath: String) = d.syncFiles(set, markerPath)
    override suspend fun readFile(guestPath: String, maxBytes: Int) = d.readFile(guestPath, maxBytes)
    override suspend fun killTree(pid: Long) = d.killTree(pid)
    override suspend fun killAll() = d.killAll()
    override suspend fun uninstall() = d.uninstall()
}

/** Counts the bytes read from an archive, reporting about every megabyte. */
internal class CountingInputStream(private val delegate: InputStream, private val onProgress: (Long) -> Unit) : InputStream() {
    private var count = 0L
    private var lastReported = 0L

    private fun add(n: Int) {
        if (n <= 0) return
        count += n
        if (count - lastReported >= 1024 * 1024) {
            lastReported = count
            onProgress(count)
        }
    }

    override fun read(): Int = delegate.read().also { if (it >= 0) add(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len).also { add(it) }
    override fun available(): Int = delegate.available()
    override fun close() {
        onProgress(count)
        delegate.close()
    }
}

/** Remembers why decompression stopped, so an install can say the archive was bad rather than that `tar` ended early. */
internal class RecordingInputStream(private val delegate: InputStream, private val failure: java.util.concurrent.atomic.AtomicReference<Throwable?>) : InputStream() {
    override fun read(): Int = try { delegate.read() } catch (t: Throwable) { failure.set(t); throw t }
    override fun read(b: ByteArray, off: Int, len: Int): Int = try { delegate.read(b, off, len) } catch (t: Throwable) { failure.set(t); throw t }
    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
}

/** The plain tar inside an archive, decompressed in this process. */
internal fun decompressed(open: () -> InputStream, format: ArchiveFormat, onProgress: (Long) -> Unit, failure: java.util.concurrent.atomic.AtomicReference<Throwable?>): InputStream {
    val counted = CountingInputStream(open(), onProgress)
    try {
        val decoded: InputStream = when (format) {
            ArchiveFormat.XZ -> org.tukaani.xz.XZInputStream(counted)
            ArchiveFormat.GZIP -> java.util.zip.GZIPInputStream(counted, 256 * 1024)
            ArchiveFormat.TAR -> counted
        }
        return RecordingInputStream(decoded, failure)
    } catch (t: Throwable) {
        failure.set(t)
        counted.close()
        throw t
    }
}
