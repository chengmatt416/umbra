package app.umbra.runtime.linux

import app.umbra.platform.adb.Adb
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.exec
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The real Linux guest on a real device, for the live tests. Nothing here is simulated: the rootfs
 * archive built by `runtime-build/rootfs/build.sh` is streamed into `tar` running as root on the
 * emulator, mounted, and used exactly as the app uses it. Tests are skipped, not faked, when there is
 * no rooted device or no archive.
 */
object LiveGuest {
    const val PACKAGE = "app.umbra.agent"
    private val adb: Adb? by lazy { runBlocking { Adb.connect() } }

    /** The repository's `build/rootfs` archive, or skips the calling test. */
    fun archive(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val file = dir?.let { File(it, "build/rootfs/umbra-rootfs-dev-arm64.tar.xz") }
        assumeTrue(file != null && file.exists(), "no rootfs archive (run runtime-build/rootfs/build.sh)")
        return file!!
    }

    fun device(): Adb {
        assumeTrue(adb != null, "no adb device attached; skipping the live-guest test")
        return adb!!
    }

    fun root(): ShellBackend {
        val root = device().root()
        assumeTrue(runBlocking { root.isAvailable() }, "the device does not give root through su; skipping the live-guest test")
        return root
    }

    /** The uid of the installed app: the guest runs as it so it gets the app's network access. */
    suspend fun appUid(shell: ShellBackend): Int {
        val out = shell.exec("pm list packages -U $PACKAGE").stdout
        return Regex("""uid:(\d+)""").find(out)?.groupValues?.get(1)?.toInt()
            ?: error("$PACKAGE is not installed on the device; install the debug APK first")
    }

    fun layout(uid: Int, name: String = "guest-live"): RuntimeLayout {
        // The canonical data path: /proc/mounts lists mounts by it, never through the /data/data symlink.
        val base = "/data/user/0/$PACKAGE/files/$name"
        return RuntimeLayout(rootfs = "$base/rootfs", workspaces = "$base/workspaces", home = "$base/home", uid = uid)
    }

    /**
     * The guest, installed from the repository archive. An installation that already matches the
     * archive is reused, because extracting two gigabytes takes minutes.
     */
    suspend fun installed(name: String = "guest-live"): RootChrootRuntime {
        val archive = archive()
        val root = root()
        val runtime = RootChrootRuntime(root, layout(appUid(root), name))
        val stamp = "${runtime.layout.rootfs}/.umbra-archive"
        val wanted = "${archive.length()}:${archive.lastModified()}"
        val have = root.exec("cat $stamp 2>/dev/null").stdout.trim()
        if (!runtime.isInstalled() || have != wanted) {
            runtime.installFile(archive)
            root.exec("echo '$wanted' > $stamp")
        } else {
            runtime.prepare()
        }
        return runtime
    }
}
