package app.umbra.agent.data

import android.content.Context
import android.net.Uri
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.shell.ProcessShell
import app.umbra.runtime.linux.ArchiveFormat
import app.umbra.runtime.linux.DelegatingLinuxRuntime
import app.umbra.runtime.linux.LinuxMode
import app.umbra.runtime.linux.LinuxRuntime
import app.umbra.runtime.linux.ProotRuntime
import app.umbra.runtime.linux.RootChrootRuntime
import app.umbra.runtime.linux.RuntimeLayout
import app.umbra.runtime.linux.RuntimeState
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long

/**
 * The Linux environment on this phone (spec §11): where it lives, how it runs (a chroot started by root, or proot
 * without root, ADR 0012), whether it is installed, and the long-running steps that change that, with progress for
 * the UI. The environment and its add-ons come inside the APK (ADR 0011); a file or a download still work.
 */
class GuestManager(
    private val context: Context,
    private val root: ShellBackend,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Checking : State

        /** No root and no proot: nothing can run the environment on this phone. */
        data object NoRoot : State
        data object NotInstalled : State
        data class Downloading(val bytes: Long, val total: Long) : State
        data class Installing(val bytes: Long, val total: Long) : State
        data object Installed : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    /** The canonical data path: `/proc/mounts` lists mounts by it, never through the `/data/data` symlink. */
    private val base: File = File(context.filesDir.canonicalFile, "guest")
    val layout = RuntimeLayout(
        rootfs = File(base, "rootfs").path,
        workspaces = File(base, "workspaces").path,
        home = File(base, "home").path,
        uid = context.applicationInfo.uid,
    )
    private val downloads = File(base, "downloads")

    // ---- how the environment runs ----

    private val chroot = RootChrootRuntime(root, layout)

    /** proot as the APK carries it, or null where it is missing (an x86 build, a stripped APK). */
    private val proot: ProotRuntime? = run {
        val libs = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(libs, "libproot.so")
        if (!binary.canExecute()) null else ProotRuntime(
            layout,
            ProotRuntime.Binaries(
                proot = binary.path,
                loader = File(libs, "libproot-loader.so").path,
                libraryDir = libs.path,
                tmpDir = File(context.cacheDir, "proot").path,
            ),
            ProcessShell.app(),
        )
    }

    @Volatile
    private var current: LinuxRuntime = chroot

    /** The environment, whichever way it runs right now; stays the same object for the app's lifetime. */
    val runtime: LinuxRuntime = DelegatingLinuxRuntime { current }

    /** How the environment runs. */
    val mode: LinuxMode get() = current.mode

    /** Which mode the installed environment was unpacked for; files differ (owners), so it stays with that mode. */
    /** App-owned markers: `guest/` itself becomes root-owned once a chroot environment is secured (ADR 0010). */
    private val markers: File = File(context.filesDir.canonicalFile, "guest-state").apply { mkdirs() }
    private val modeFile = File(markers, "mode")

    private fun installedFor(): LinuxMode? = runCatching { LinuxMode.valueOf(modeFile.readText().trim()) }.getOrNull()

    /**
     * Root when it answers, proot otherwise. An environment unpacked under proot belongs to the app's uid, and the
     * chroot's guarantees (ADR 0010) need root's files, so it keeps running under proot even when root appears.
     */
    private suspend fun choose(): LinuxMode? {
        val rootOk = runCatching { root.isAvailable() }.getOrDefault(false)
        val installed = installedFor() ?: if (File(layout.rootfs, "usr/bin/bash").exists()) LinuxMode.CHROOT else null
        val mode = when {
            installed == LinuxMode.PROOT && proot != null -> LinuxMode.PROOT
            installed == LinuxMode.CHROOT && rootOk -> LinuxMode.CHROOT
            installed == LinuxMode.CHROOT && proot != null -> LinuxMode.PROOT // root went away: proot can still run it
            installed == null && rootOk -> LinuxMode.CHROOT
            proot != null -> LinuxMode.PROOT
            else -> null
        }
        current = if (mode == LinuxMode.PROOT) proot!! else chroot
        return mode
    }

    // ---- what the APK carries ----

    /** The environment and its add-ons inside the APK (ADR 0011), or null in a build without them. */
    data class Bundle(val rootfsBytes: Long, val rootfsSha256: String, val packsBytes: Long, val packsSha256: String, val components: Map<String, String>)

    val bundle: Bundle? by lazy {
        try {
            val json = context.assets.open("linux/bundle.json").use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
            val r = json["rootfs"]!!.jsonObject
            val p = json["packs"]!!.jsonObject
            val components = (json["manifest"]?.jsonObject?.get("components") as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
            Bundle((r["bytes"] as JsonPrimitive).long, (r["sha256"] as JsonPrimitive).content, (p["bytes"] as JsonPrimitive).long, (p["sha256"] as JsonPrimitive).content, components)
        } catch (_: Exception) {
            null
        }
    }

    /** Which bundled archive the installed environment came from, to offer an update when a newer app brings another. */
    private val bundleFile = File(markers, "bundle")

    /** True when the app carries a different environment than the one installed from it. */
    val updateAvailable: Boolean
        get() = bundle?.let { b -> bundleFile.exists() && runCatching { bundleFile.readText().trim() }.getOrNull() != b.rootfsSha256 } ?: false

    // ---- add-ons ----

    /** An optional toolchain the user can add to the guest (one of the `.apt` lists under guest/packs). */
    data class Pack(val id: String, val title: String, val detail: String, val marker: String, val diskMb: Int)

    val packs = listOf(
        Pack("dev", "Developer tools", "C and C++ compilers, make, cmake, pkg-config", "/usr/bin/gcc", 630),
        // The marker is looked for from outside the environment, where /usr/bin/java is a link to /etc/alternatives that points nowhere.
        Pack("java", "Java 21", "JDK for Gradle and Kotlin", "/usr/lib/jvm/java-21-openjdk-arm64/bin/java", 310),
        Pack("android", "Android app builds", "Java 21, aapt2, d8, apksigner and the Android 14 SDK platform, to build and sign apps", "/usr/local/bin/d8", 820),
        Pack("node", "Node.js", "Node.js and npm", "/usr/bin/node", 220),
        Pack("gui", "Desktop apps", "A virtual screen so the agent can run and operate Linux desktop programs", "/usr/bin/Xvfb", 420),
    )

    sealed interface PackState {
        data object Absent : PackState

        /** [step]: what is happening (preparing the packages that came with the app, or installing). */
        data class Installing(val step: String = "Installing…") : PackState
        data object Installed : PackState
        data class Failed(val message: String) : PackState
    }

    private val mutablePacks = MutableStateFlow(packs.associate { it.id to PackState.Absent as PackState })
    val packStates: StateFlow<Map<String, PackState>> = mutablePacks.asStateFlow()

    /** True once the desktop pack is installed: the agent's desktop tools are offered only then. */
    val desktopInstalled: Boolean get() = mutablePacks.value["gui"] == PackState.Installed

    private var cachedVersions: Pair<Long, Map<String, String>>? = null
    private val versionsLock = Mutex()

    /**
     * The versions of Debian, Bun, omp and git in the guest. Asking starts omp, which is not free on a busy phone,
     * and they only change when the guest is reinstalled, so a recent answer is reused.
     */
    suspend fun versions(maxAgeMs: Long = 10 * 60_000L): Map<String, String>? = versionsLock.withLock {
        val now = System.currentTimeMillis()
        cachedVersions?.let { (at, found) -> if (now - at < maxAgeMs) return@withLock found }
        try {
            runtime.versions()?.also { cachedVersions = now to it }
        } catch (e: BackendException) {
            null // root went away while asking: no answer now
        }
    }

    /** Looks for each pack's main program in the guest (from outside: the app can read the environment's files in both modes). */
    suspend fun refreshPacks() {
        if (mutableState.value != State.Ready && mutableState.value != State.Installed) return
        val found = withContext(Dispatchers.IO) {
            packs.associate { pack ->
                val present = File(layout.rootfs.trimEnd('/') + pack.marker).exists()
                pack.id to when {
                    mutablePacks.value[pack.id] is PackState.Installing -> mutablePacks.value[pack.id]!!
                    present -> PackState.Installed
                    else -> PackState.Absent
                }
            }
        }
        mutablePacks.value = found
    }

    /** Installs one add-on (see [installPacks]). */
    fun installPack(id: String) = installPacks(listOf(id))

    /**
     * Installs add-ons. When the APK carries the add-on repository (ADR 0011), it is unpacked into the environment
     * first and nothing is downloaded; it is removed again afterwards to give the space back.
     */
    fun installPacks(ids: List<String>) {
        if (ids.isEmpty()) return
        launchExclusive {
            for (id in ids) mutablePacks.value = mutablePacks.value + (id to PackState.Installing("Waiting…"))
            try {
                installPacksNow(ids)
            } finally {
                for (id in ids) if (mutablePacks.value[id] is PackState.Installing) mutablePacks.value = mutablePacks.value + (id to PackState.Absent)
                refreshPacks()
            }
        }
    }

    private suspend fun installPacksNow(ids: List<String>) {
        if (!ensureReady()) {
            for (id in ids) mutablePacks.value = mutablePacks.value + (id to PackState.Failed("the Linux environment is not ready"))
            return
        }
        val repo = bundle
        if (repo != null) {
            val needed = repo.packsBytes + ids.sumOf { id -> packs.first { it.id == id }.diskMb.toLong() } * 1024 * 1024
            val free = runCatching { runtime.freeBytes() }.getOrDefault(Long.MAX_VALUE)
            if (free < needed) {
                for (id in ids) mutablePacks.value = mutablePacks.value + (id to PackState.Failed("Not enough space: needs about ${needed / (1024 * 1024)} MB, ${free / (1024 * 1024)} MB free"))
                return
            }
            for (id in ids) mutablePacks.value = mutablePacks.value + (id to PackState.Installing("Preparing the packages that came with the app…"))
            runtime.extractTar(REPO, { context.assets.open("linux/packs.tar") }, onProgress = { bytes ->
                val step = "Preparing the packages that came with the app… ${bytes / (1024 * 1024)} of ${repo.packsBytes / (1024 * 1024)} MB"
                for (id in ids) mutablePacks.value = mutablePacks.value + (id to PackState.Installing(step))
            })
        }
        try {
            for (id in ids) {
                mutablePacks.value = mutablePacks.value + (id to PackState.Installing("Installing…"))
                val result = runtime.installPack(id)
                mutablePacks.value = mutablePacks.value + (id to if (result.ok) PackState.Installed else PackState.Failed((result.stderr.ifBlank { result.stdout }).lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" ").take(300)))
            }
        } finally {
            if (repo != null) runCatching { runtime.removeDir(REPO) }
        }
        if (mutableState.value is State.Failed) refresh()
    }

    private val mutableState = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val busy = Mutex()
    private var job: Job? = null

    /** Looks at the device again: how the environment can run, whether it is installed and mounted. */
    suspend fun refresh() {
        if (job?.isActive == true) return // an install is changing the state itself
        mutableState.value = try {
            when (choose()) {
                null -> State.NoRoot
                else -> when (runtime.status()) {
                    RuntimeState.NOT_INSTALLED -> State.NotInstalled
                    RuntimeState.INSTALLED -> State.Installed
                    RuntimeState.MOUNTED -> State.Ready
                }
            }
        } catch (e: Exception) {
            State.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
        // Which add-ons are in the guest decides which tools the agent is offered (the desktop ones), so it is kept as fresh as the state.
        if (mutableState.value == State.Ready || mutableState.value == State.Installed) refreshPacks()
    }

    /** Mounts an installed guest so omp can run in it. */
    suspend fun ensureReady(): Boolean = ensureReadyInner()

    private suspend fun ensureReadyInner(): Boolean {
        refresh()
        if (mutableState.value == State.Installed) {
            try {
                runtime.mount()
                mutableState.value = State.Ready
            } catch (e: Exception) {
                mutableState.value = State.Failed("Could not mount the Linux environment: ${e.message}")
            }
        }
        val ready = mutableState.value == State.Ready
        if (ready) {
            // Mounting is idempotent; on a guest that is already mounted it only makes sure the agent cannot change
            // the system part of it (see RootChrootRuntime.secure), once per run of the app.
            try {
                runtime.mount()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not mounted after all; the next start or task finds out and says why.
            }
            syncScripts()
        }
        return ready
    }

    @Volatile
    private var scriptsInSync = false
    private val syncLock = Mutex()

    /**
     * Puts the app's own scripts and package lists into the environment when it has older ones (a newer app, an older
     * archive). Done once per run of the app, before an agent starts; a failure leaves the scripts the environment has.
     */
    suspend fun syncScripts() {
        if (scriptsInSync) return
        syncLock.withLock {
            if (scriptsInSync) return
            try {
                runtime.syncFiles(GuestScripts.fileSet(context))
                scriptsInSync = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // The environment still works with the scripts it has; the next start tries again.
            }
        }
    }

    // ---- installing ----

    /** Installs the environment the APK carries: no download, no file to pick. */
    fun installBundled() {
        val b = bundle ?: return
        launchExclusive {
            install({ context.assets.open("linux/rootfs.tar.xz") }, ArchiveFormat.XZ, b.rootfsBytes, bundleSha = b.rootfsSha256)
        }
    }

    /** [installBundled] that waits until it is done (development tools). */
    suspend fun installBundledNow() {
        val b = bundle ?: throw IOException("this build of the app does not carry the Linux environment")
        busy.withLock { install({ context.assets.open("linux/rootfs.tar.xz") }, ArchiveFormat.XZ, b.rootfsBytes, bundleSha = b.rootfsSha256) }
    }

    /** [installPacks] that waits until it is done (development tools). */
    suspend fun installPacksAndWait(ids: List<String>) {
        busy.withLock {
            for (id in ids) mutablePacks.value = mutablePacks.value + (id to PackState.Installing("Waiting…"))
            try {
                installPacksNow(ids)
            } finally {
                for (id in ids) if (mutablePacks.value[id] is PackState.Installing) mutablePacks.value = mutablePacks.value + (id to PackState.Absent)
                refreshPacks()
            }
        }
    }

    /**
     * Replaces the installed environment with the one this app carries, and puts back the add-ons it had. The
     * workspaces and the agent's home (settings, sessions) live outside the environment and stay.
     */
    fun updateBundled() {
        val b = bundle ?: return
        launchExclusive {
            val had = mutablePacks.value.filterValues { it == PackState.Installed }.keys.toList()
            install({ context.assets.open("linux/rootfs.tar.xz") }, ArchiveFormat.XZ, b.rootfsBytes, bundleSha = b.rootfsSha256)
            if (had.isNotEmpty()) {
                for (id in had) mutablePacks.value = mutablePacks.value + (id to PackState.Installing("Waiting…"))
                installPacksNow(had)
                refreshPacks()
            }
        }
    }

    /** Installs from an archive the user picked (a content URI from the file picker). */
    fun installFrom(uri: Uri, displayName: String?) {
        launchExclusive {
            val name = displayName ?: uri.lastPathSegment.orEmpty()
            val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            install({ context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri") }, ArchiveFormat.forName(name), total)
        }
    }

    /** Installs from a file the app can read (development tools). Waits until it is done. */
    suspend fun installFileNow(file: File) {
        busy.withLock { install({ file.inputStream().buffered(256 * 1024) }, ArchiveFormat.forName(file.name), file.length()) }
    }

    /** Downloads the archive from [url] (resuming a partial download), checks it, and installs it. */
    fun downloadAndInstall(url: String) {
        launchExclusive {
            val file = withContext(Dispatchers.IO) { download(url) }
            install({ file.inputStream().buffered(256 * 1024) }, ArchiveFormat.forName(file.name), file.length())
            file.delete()
        }
    }

    fun uninstall() {
        launchExclusive {
            cachedVersions = null
            scriptsInSync = false
            runtime.uninstall()
            modeFile.delete()
            bundleFile.delete()
            mutableState.value = State.NotInstalled
        }
    }

    private suspend fun install(open: () -> java.io.InputStream, format: ArchiveFormat, total: Long, bundleSha: String? = null) {
        mutableState.value = State.Installing(0, total)
        cachedVersions = null
        scriptsInSync = false
        // Unpacked for the way it will run: root's files under root, the app's under proot.
        modeFile.delete()
        val rootOk = runCatching { root.isAvailable() }.getOrDefault(false)
        current = if (!rootOk && proot != null) proot else chroot
        if (current.mode == LinuxMode.CHROOT && !rootOk) throw BackendException(app.umbra.core.model.action.ErrorCode.BACKEND_UNAVAILABLE, "Neither root nor proot can run the Linux environment on this phone.")
        // An environment unpacked for the other mode goes first (its files have the wrong owners for this one).
        if (File(layout.rootfs).exists()) runCatching { (if (current.mode == LinuxMode.PROOT) chroot else proot)?.uninstall() }
        current.install(open, format, onProgress = { mutableState.value = State.Installing(it, total) })
        withContext(Dispatchers.IO) {
            base.mkdirs()
            modeFile.writeText(current.mode.name + "\n")
            if (bundleSha != null) bundleFile.writeText(bundleSha + "\n") else bundleFile.delete()
        }
        current.mount()
        mutableState.value = State.Ready
        refreshPacks()
    }

    private fun launchExclusive(block: suspend () -> Unit) {
        job = scope.launch(Dispatchers.IO) {
            busy.withLock {
                try {
                    block()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutableState.value = State.Failed(e.message ?: e::class.simpleName.orEmpty())
                }
            }
        }
    }

    // ---- download ----

    private fun download(url: String): File {
        require(url.startsWith("https://")) { "the archive must be downloaded over https" }
        downloads.mkdirs()
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "rootfs.tar.xz" }
        val file = File(downloads, name)
        val expected = fetchChecksum("$url.sha256", name)

        var have = if (file.exists()) file.length() else 0L
        var total = -1L
        fun open(from: Long): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }
        var connection = open(have)
        if (connection.responseCode == 416) { // the file is complete (or the server disagrees): start again
            connection.disconnect()
            file.delete()
            have = 0
            connection = open(0)
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("the download failed: HTTP ${connection.responseCode}")
            val resumed = connection.responseCode == 206
            if (!resumed) have = 0
            total = connection.contentLengthLong.let { if (it > 0) it + have else -1L }
            mutableState.value = State.Downloading(have, total)
            FileOutputStream(file, resumed).use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        have += n
                        if (have - lastReport >= 1024 * 1024) {
                            lastReport = have
                            mutableState.value = State.Downloading(have, total)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (expected != null) {
            val actual = sha256(file)
            if (!actual.equals(expected, ignoreCase = true)) {
                file.delete()
                throw IOException("the downloaded archive does not match its checksum; it was deleted, try again")
            }
        }
        return file
    }

    /** The published SHA-256 of the archive, or null when none is published next to it. */
    private fun fetchChecksum(url: String, fileName: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 10_000 }
        if (c.responseCode !in 200..299) null else {
            val line = c.inputStream.bufferedReader().readText().trim()
            Regex("^([0-9a-fA-F]{64})(\\s+\\*?(.*))?$").find(line)?.takeIf { it.groupValues[3].isBlank() || it.groupValues[3].trim() == fileName }?.groupValues?.get(1)
        }
    } catch (_: IOException) {
        null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Where the add-on repository goes while add-ons are installed (umbra-pack looks there). */
        const val REPO = "/var/cache/umbra-repo"
    }
}
