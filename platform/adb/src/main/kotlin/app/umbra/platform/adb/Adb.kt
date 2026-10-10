package app.umbra.platform.adb

import app.umbra.core.model.action.ErrorCode
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.shell.ProcessShell
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AdbDevice(val serial: String, val state: String, val description: String)

class AdbResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Talks to one device through the `adb` binary. Used by development tooling and the integration
 * tests to drive a real emulator or phone the same way the app drives its own device: through shell
 * backends, which here run `adb shell`.
 */
class Adb private constructor(val executable: String, val serial: String) {
    private fun base(): List<String> = listOf(executable, "-s", serial)

    /** The shell user (uid 2000) over `adb shell`. */
    fun shell(): ProcessShell = ProcessShell(
        ShellIdentity.SHELL, base() + "shell", "adb_shell:$serial",
        binaryPrefix = base() + "exec-out",
        available = { state() == "device" },
    )

    /** Root through `su` on a userdebug image or a rooted phone; available only if `su` answers with uid 0. */
    fun root(): ProcessShell {
        lateinit var shell: ProcessShell
        shell = ProcessShell(
            ShellIdentity.ROOT, base() + listOf("shell", "su", "0", "sh", "-c"), "adb_root:$serial",
            binaryPrefix = base() + listOf("exec-out", "su", "0", "sh", "-c"),
            wrap = ProcessShell.Companion::quote,
        ) {
            try {
                shell.exec(app.umbra.platform.api.ShellRequest("id -u", timeoutMs = 10_000)).stdout.trim() == "0"
            } catch (_: BackendException) {
                false
            }
        }
        return shell
    }

    /** The argv of a process that runs [command] as root on the device (the long-lived counterpart of [root]). */
    fun rootCommand(command: String): List<String> = base() + listOf("shell", "su", "0", "sh", "-c", ProcessShell.quote(command))

    /** Runs an adb command (not a shell command): `install`, `push`, `forward`... */
    suspend fun run(vararg args: String, timeoutMs: Long = 60_000): AdbResult = runRaw(base() + args, timeoutMs)

    suspend fun state(): String = runRaw(base() + "get-state", 10_000).stdout.trim().ifEmpty { "offline" }

    suspend fun install(apk: File, replace: Boolean = true, grantAll: Boolean = false): AdbResult =
        run(*buildList { add("install"); if (replace) add("-r"); if (grantAll) add("-g"); add(apk.absolutePath) }.toTypedArray(), timeoutMs = 300_000)

    suspend fun push(local: File, remote: String): AdbResult = run("push", local.absolutePath, remote, timeoutMs = 600_000)

    suspend fun pull(remote: String, local: File): AdbResult = run("pull", remote, local.absolutePath, timeoutMs = 600_000)

    suspend fun forward(localPort: Int, remotePort: Int): AdbResult = run("forward", "tcp:$localPort", "tcp:$remotePort")

    suspend fun waitForBoot(timeoutMs: Long = 180_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (state() == "device" && run("shell", "getprop", "sys.boot_completed", timeoutMs = 10_000).stdout.trim() == "1") return true
            kotlinx.coroutines.delay(1_000)
        }
        return false
    }

    companion object {
        /** `adb` from ANDROID_HOME / the usual SDK location / PATH, or null. */
        fun locate(): String? {
            val candidates = buildList {
                System.getenv("ANDROID_HOME")?.let { add("$it/platform-tools/adb") }
                System.getenv("ANDROID_SDK_ROOT")?.let { add("$it/platform-tools/adb") }
                add("${System.getProperty("user.home")}/Library/Android/sdk/platform-tools/adb")
                add("${System.getProperty("user.home")}/Android/Sdk/platform-tools/adb")
                System.getenv("PATH").orEmpty().split(File.pathSeparator).forEach { add("$it/adb") }
            }
            return candidates.firstOrNull { File(it).canExecute() }
        }

        suspend fun devices(executable: String? = locate()): List<AdbDevice> {
            val exe = executable ?: return emptyList()
            return runRaw(listOf(exe, "devices", "-l"), 15_000).stdout.lineSequence().drop(1).mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 3)
                if (parts.size < 2) null else AdbDevice(parts[0], parts[1], parts.getOrElse(2) { "" })
            }.toList()
        }

        /**
         * The device to use: ANDROID_SERIAL if set, else the only (or first) device that is online.
         * Null when adb or a device is missing, so integration tests can skip instead of failing.
         */
        suspend fun connect(preferredSerial: String? = System.getenv("ANDROID_SERIAL")): Adb? {
            val exe = locate() ?: return null
            val online = devices(exe).filter { it.state == "device" }
            val chosen = online.firstOrNull { preferredSerial == null || it.serial == preferredSerial } ?: return null
            return Adb(exe, chosen.serial)
        }

        private suspend fun runRaw(command: List<String>, timeoutMs: Long): AdbResult = withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder(command).start()
            } catch (e: IOException) {
                throw BackendException(ErrorCode.BACKEND_UNAVAILABLE, "cannot run adb: ${e.message}", cause = e)
            }
            process.outputStream.close()
            // Read both streams on their own threads so a hung adb cannot block past the timeout.
            val out = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.readBytes().toString(Charsets.UTF_8) }
            val err = java.util.concurrent.CompletableFuture.supplyAsync { process.errorStream.readBytes().toString(Charsets.UTF_8) }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return@withContext AdbResult(ProcessShell.TIMEOUT_EXIT_CODE, "", "adb timed out after ${timeoutMs}ms")
            }
            AdbResult(process.exitValue(), out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS))
        }
    }
}
