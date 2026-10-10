package app.umbra.platform.shell

import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.action.SysInfoQuery
import app.umbra.core.model.observation.DeviceState
import app.umbra.core.model.observation.ForegroundInfo
import app.umbra.core.model.observation.ProcessInfo
import app.umbra.platform.api.AppBackend
import app.umbra.platform.api.AppLaunch
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.FileBackend
import app.umbra.platform.api.FileEntry
import app.umbra.platform.api.LaunchOutcome
import app.umbra.platform.api.PackageBackend
import app.umbra.platform.api.PackageDetails
import app.umbra.platform.api.PackageSummary
import app.umbra.platform.api.ProcessBackend
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellInput.quote
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.ShellResult
import app.umbra.platform.api.SysInfo
import app.umbra.platform.api.SysInfoBackend
import app.umbra.platform.api.exec
import app.umbra.platform.api.execOrThrow

/*
 * Everything about a device that can be done by running the tools Android already has (am, pm, cmd,
 * dumpsys, ps, stat). They run over any ShellBackend: the shell user via Shizuku or `su 2000`, root,
 * or adb from a development machine.
 */

private fun requireOk(r: ShellResult, what: String): String {
    if (r.timedOut) throw BackendException(ErrorCode.TIMEOUT, "$what timed out", retryable = true)
    if (!r.ok) throw BackendException(ErrorCode.COMMAND_FAILED, "$what failed (exit ${r.exitCode}): ${r.combined.take(300)}")
    return r.stdout
}

class ShellApps(private val shell: ShellBackend) : AppBackend {
    override val name = "shell_apps"

    override suspend fun isAvailable() = shell.isAvailable()

    /** The launcher activity of [packageName] as `pkg/.Activity`, or null when it has none. */
    suspend fun launcherActivity(packageName: String): String? {
        val r = shell.exec("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${quote(packageName)}")
        return if (r.ok) AmParsers.parseResolvedComponent(r.stdout) else null
    }

    override suspend fun launch(launch: AppLaunch): LaunchOutcome {
        val component = when {
            launch.activity != null && launch.packageName != null ->
                if ('/' in launch.activity!!) launch.activity else "${launch.packageName}/${launch.activity}"
            launch.activity != null -> launch.activity
            launch.packageName != null && launch.intentAction == null && launch.uri == null ->
                launcherActivity(launch.packageName!!) ?: return LaunchOutcome(false, message = "${launch.packageName} has no launcher activity (is it installed?)")
            else -> null
        }
        val command = buildString {
            append("am start")
            launch.displayId?.let { append(" --display $it") }
            component?.let { append(" -n ").append(quote(it)) }
            launch.intentAction?.let { append(" -a ").append(quote(it)) }
            launch.uri?.let { append(" -d ").append(quote(it)) }
            if (component == null) launch.packageName?.let { append(" -p ").append(quote(it)) }
            for ((k, v) in launch.extras) append(" --es ").append(quote(k)).append(' ').append(quote(v))
        }
        val r = shell.exec(command, timeoutMs = 20_000)
        if (r.timedOut) throw BackendException(ErrorCode.TIMEOUT, "am start timed out", retryable = true)
        return AmParsers.parseStart(r.combined)
    }

    override suspend fun stop(packageName: String, force: Boolean) {
        requireOk(shell.exec("am ${if (force) "force-stop" else "kill"} ${quote(packageName)}"), "stopping $packageName")
    }

    override suspend fun foreground(displayId: Int?): List<ForegroundInfo> {
        val dump = requireOk(shell.exec("dumpsys activity activities", timeoutMs = 20_000, maxOutputBytes = 8 * 1024 * 1024), "dumpsys activity")
        return ActivityParsers.foreground(dump).filter { displayId == null || it.displayId == displayId }
    }

    override suspend fun bringToFront(packageName: String, displayId: Int?): Boolean {
        val component = launcherActivity(packageName) ?: return false
        return launch(AppLaunch(packageName = packageName, activity = component, displayId = displayId)).started
    }
}

class ShellPackages(private val shell: ShellBackend, private val apps: ShellApps = ShellApps(shell)) : PackageBackend {
    override val name = "shell_pm"

    override suspend fun isAvailable() = shell.isAvailable()

    private fun user(userId: Int) = "--user $userId"

    override suspend fun list(filter: String?, includeSystem: Boolean, userId: Int): List<PackageSummary> {
        val all = PmParsers.parseList(requireOk(shell.exec("pm list packages -f --show-versioncode -U ${user(userId)}", maxOutputBytes = 4 * 1024 * 1024), "pm list"))
        val system = PmParsers.names(requireOk(shell.exec("pm list packages -s ${user(userId)}", maxOutputBytes = 4 * 1024 * 1024), "pm list -s"))
        val disabled = PmParsers.names(requireOk(shell.exec("pm list packages -d ${user(userId)}"), "pm list -d"))
        return all.map { it.copy(system = it.packageName in system, enabled = it.packageName !in disabled) }
            .filter { (includeSystem || !it.system) && (filter == null || it.packageName.contains(filter, ignoreCase = true)) }
            .sortedBy { it.packageName }
    }

    override suspend fun details(packageName: String, userId: Int): PackageDetails? {
        val dump = shell.exec("dumpsys package ${quote(packageName)}", timeoutMs = 30_000, maxOutputBytes = 4 * 1024 * 1024)
        if (!dump.ok) return null
        val parsed = PmParsers.parseDetails(packageName, dump.stdout) ?: return null
        return parsed.copy(launcherActivity = apps.launcherActivity(packageName))
    }

    override suspend fun install(apkPath: String, replace: Boolean, grantAll: Boolean, userId: Int): String {
        val flags = buildString {
            if (replace) append(" -r")
            if (grantAll) append(" -g")
            append(" ").append(user(userId))
        }
        // An app's private folder (a workspace) is closed to the shell user but not to this process: it reads the
        // APK itself and feeds `pm install -S`, which takes the package on standard input, as `adb install` does.
        val local = java.io.File(apkPath)
        val streamed = local.isFile && local.canRead() && !shell.exec("test -r ${quote(apkPath)}", timeoutMs = 10_000).ok
        val r = if (streamed) {
            shell.exec(ShellRequest("pm install$flags -S ${local.length()}", timeoutMs = 180_000, stdinSource = { local.inputStream().buffered(256 * 1024) }))
        } else {
            shell.exec("pm install$flags ${quote(apkPath)}", timeoutMs = 180_000)
        }
        if (r.timedOut) throw BackendException(ErrorCode.TIMEOUT, "pm install timed out", retryable = true)
        return PmParsers.resultOf(r.combined).getOrElse { throw BackendException(ErrorCode.COMMAND_FAILED, "install failed: ${it.message}") }
    }

    override suspend fun uninstall(packageName: String, keepData: Boolean, userId: Int): String {
        val r = shell.exec("pm uninstall${if (keepData) " -k" else ""} ${user(userId)} ${quote(packageName)}")
        return PmParsers.resultOf(r.combined).getOrElse { throw BackendException(ErrorCode.COMMAND_FAILED, "uninstall failed: ${it.message}") }
    }

    override suspend fun clearData(packageName: String, userId: Int): String {
        val r = shell.exec("pm clear ${user(userId)} ${quote(packageName)}")
        return PmParsers.resultOf(r.combined).getOrElse { throw BackendException(ErrorCode.COMMAND_FAILED, "clear failed: ${it.message}") }
    }

    override suspend fun setEnabled(packageName: String, enabled: Boolean, userId: Int): String =
        requireOk(shell.exec(if (enabled) "pm enable ${user(userId)} ${quote(packageName)}" else "pm disable-user ${user(userId)} ${quote(packageName)}"), "pm enable/disable").trim()

    override suspend fun grant(packageName: String, permission: String, userId: Int): String {
        requireOk(shell.exec("pm grant ${user(userId)} ${quote(packageName)} ${quote(permission)}"), "pm grant")
        return "Granted $permission to $packageName"
    }

    override suspend fun revoke(packageName: String, permission: String, userId: Int): String {
        requireOk(shell.exec("pm revoke ${user(userId)} ${quote(packageName)} ${quote(permission)}"), "pm revoke")
        return "Revoked $permission from $packageName"
    }
}

class ShellSysInfo(private val shell: ShellBackend) : SysInfoBackend {
    override val name = "shell_sysinfo"

    override suspend fun isAvailable() = shell.isAvailable()

    private suspend fun text(command: String, maxBytes: Int = 1024 * 1024): String? =
        shell.exec(command, timeoutMs = 20_000, maxOutputBytes = maxBytes).takeIf { it.ok }?.stdout

    override suspend fun query(query: SysInfoQuery, argument: String?, maxChars: Int): SysInfo {
        val words = argument?.trim().orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { quote(it) }
        val command = when (query) {
            SysInfoQuery.BATTERY -> "dumpsys battery"
            SysInfoQuery.MEMORY -> "head -12 /proc/meminfo"
            SysInfoQuery.STORAGE -> "df -h /data /sdcard 2>&1"
            SysInfoQuery.NETWORK -> "dumpsys connectivity | grep -E 'Active default network|NetworkAgentInfo\\{' | head -8"
            SysInfoQuery.THERMAL -> "dumpsys thermalservice | head -40"
            SysInfoQuery.DISPLAYS -> "cmd display get-displays"
            SysInfoQuery.PROCESSES -> if (words.isEmpty()) "ps -A -o PID,USER,RSS,ARGS | head -300" else "ps -A -o PID,USER,RSS,ARGS | grep -F -- $words"
            SysInfoQuery.SERVICES -> "service list"
            SysInfoQuery.GETPROP -> if (words.isEmpty()) "getprop" else "getprop $words"
            SysInfoQuery.SETTINGS -> {
                val parts = argument?.trim().orEmpty().split('/', ' ').filter { it.isNotEmpty() }
                when {
                    parts.isEmpty() -> throw BackendException(ErrorCode.INVALID_ARGUMENTS, "settings needs an argument like global/airplane_mode_on or secure")
                    parts.size == 1 && parts[0] in NAMESPACES -> "settings list ${parts[0]}"
                    parts.size == 1 -> "settings get global ${quote(parts[0])}"
                    else -> "settings get ${quote(parts[0])} ${quote(parts[1])}"
                }
            }
            SysInfoQuery.DUMPSYS -> if (words.isEmpty()) "dumpsys -l" else "dumpsys $words"
        }
        val r = shell.exec(command, timeoutMs = 30_000, maxOutputBytes = maxOf(maxChars * 4, 64 * 1024))
        if (r.timedOut) throw BackendException(ErrorCode.TIMEOUT, "$command timed out", retryable = true)
        val out = r.combined.trim().ifEmpty { "(no output, exit ${r.exitCode})" }
        return SysInfo(query, if (out.length > maxChars) out.take(maxChars) + "\n… (${out.length - maxChars} more characters)" else out)
    }

    override suspend fun deviceState(): DeviceState = deviceState(
        battery = text("dumpsys battery"),
        mem = text("cat /proc/meminfo"),
        df = text("df -k /data"),
        thermal = text("dumpsys thermalservice | grep -m1 'Thermal Status'"),
        network = text("dumpsys connectivity | grep -E 'Active default network|NetworkAgentInfo\\{' | head -8"),
    )

    private companion object {
        val NAMESPACES = setOf("global", "secure", "system")
    }
}

class ShellProcesses(private val shell: ShellBackend) : ProcessBackend {
    override val name = "shell_ps"

    override suspend fun isAvailable() = shell.isAvailable()

    private suspend fun all(): List<ProcessInfo> =
        SystemParsers.ps(requireOk(shell.exec("ps -A -o PID,USER,RSS,ARGS", maxOutputBytes = 4 * 1024 * 1024), "ps"))

    override suspend fun list(nameFilter: String?): List<ProcessInfo> =
        all().filter { nameFilter == null || it.command?.contains(nameFilter) == true || it.name.contains(nameFilter) }

    override suspend fun info(pid: Int): ProcessInfo? = all().firstOrNull { it.pid == pid }

    override suspend fun kill(pid: Int, signal: Int) {
        requireOk(shell.exec("kill -$signal $pid"), "kill $pid")
    }

    override suspend fun isRunning(name: String): Boolean =
        all().any { it.name == name || it.name.substringAfterLast('/') == name }
}

/**
 * Files through shell commands. [privileged] (a root shell) is optional: without it, requests for
 * privileged access fail with PERMISSION_DENIED instead of silently using less access.
 */
class ShellFiles(private val shell: ShellBackend, private val privileged: ShellBackend? = null) : FileBackend {
    override val name = "shell_fs"

    override suspend fun isAvailable() = shell.isAvailable()

    private fun pick(privilegedRequested: Boolean): ShellBackend =
        if (!privilegedRequested) shell
        else privileged ?: throw BackendException(ErrorCode.PERMISSION_DENIED, "Privileged file access needs root, which is not available.")

    override suspend fun read(path: String, maxBytes: Int, privileged: Boolean): String {
        val r = pick(privileged).exec("head -c $maxBytes -- ${quote(path)}", maxOutputBytes = maxBytes + 1024)
        if (!r.ok) throw BackendException(if ("No such file" in r.stderr) ErrorCode.NOT_FOUND else ErrorCode.COMMAND_FAILED, r.combined.trim().ifEmpty { "cannot read $path" })
        return r.stdout
    }

    override suspend fun write(path: String, content: String, append: Boolean, privileged: Boolean) {
        val r = pick(privileged).exec(ShellRequest("cat ${if (append) ">>" else ">"} ${quote(path)}", stdin = content, timeoutMs = 60_000))
        if (!r.ok) throw BackendException(ErrorCode.COMMAND_FAILED, r.combined.trim().ifEmpty { "cannot write $path" })
    }

    override suspend fun list(path: String, privileged: Boolean): List<FileEntry> {
        val dir = path.trimEnd('/').ifEmpty { "/" }
        val r = pick(privileged).exec("cd ${quote(dir)} && { stat -c '$STAT_FORMAT' -- .[!.]* ..?* * 2>/dev/null; true; }", maxOutputBytes = 2 * 1024 * 1024)
        if (!r.ok) throw BackendException(if ("No such file" in r.stderr) ErrorCode.NOT_FOUND else ErrorCode.COMMAND_FAILED, r.combined.trim().ifEmpty { "cannot list $path" })
        return SystemParsers.stat(r.stdout).map { it.copy(path = (if (dir == "/") "" else dir) + "/" + it.name) }.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
    }

    override suspend fun stat(path: String, privileged: Boolean): FileEntry? {
        val r = pick(privileged).exec("stat -c '$STAT_FORMAT' -- ${quote(path)}")
        return if (r.ok) SystemParsers.stat(r.stdout).firstOrNull() else null
    }

    override suspend fun mkdir(path: String, privileged: Boolean) {
        requireOk(pick(privileged).exec("mkdir -p -- ${quote(path)}"), "mkdir $path")
    }

    override suspend fun delete(path: String, recursive: Boolean, privileged: Boolean) {
        requireOk(pick(privileged).exec("rm ${if (recursive) "-rf" else "-f"} -- ${quote(path)}"), "delete $path")
    }

    override suspend fun move(from: String, to: String, privileged: Boolean) {
        val r = pick(privileged).exec("mv -- ${quote(from)} ${quote(to)}")
        if (!r.ok) throw BackendException(if ("No such file" in r.stderr) ErrorCode.NOT_FOUND else ErrorCode.COMMAND_FAILED, r.combined.trim().ifEmpty { "cannot move $from" })
    }

    private companion object {
        const val STAT_FORMAT = "%F|%s|%Y|%a|%n"
    }
}
