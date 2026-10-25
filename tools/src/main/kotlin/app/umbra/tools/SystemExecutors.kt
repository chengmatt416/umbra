package app.umbra.tools

import app.umbra.core.model.action.ActionStatus
import app.umbra.core.model.action.DisplayAction
import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.action.FileAction
import app.umbra.core.model.action.LogAction
import app.umbra.core.model.action.LogFilter
import app.umbra.core.model.action.Operation
import app.umbra.core.model.action.PackageAction
import app.umbra.core.model.action.Privilege
import app.umbra.core.model.action.ProcessAction
import app.umbra.core.model.action.WindowAction
import app.umbra.core.model.observation.LogEvent
import app.umbra.core.model.observation.LogKind
import app.umbra.platform.api.AppLaunch
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.VirtualDisplaySpec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shell, packages, system info, logs, files and processes: operations that never touch a display (spec §10, §18). */
internal class SystemExecutor(private val env: PipelineEnv) {
    private val backends get() = env.backends

    suspend fun execute(c: OpContext): OpOutcome = when (val op = c.request.operation) {
        is Operation.Shell -> shell(c, op)
        is Operation.Packages -> packages(c, op)
        is Operation.SysInfo -> sysinfo(op)
        is Operation.Logs -> logs(c, op)
        is Operation.File -> file(c, op)
        is Operation.Process -> process(c, op)
        else -> OpOutcome.unsupported("${op.name} is not a system operation")
    }

    // ---- shell ----

    private suspend fun shell(c: OpContext, op: Operation.Shell): OpOutcome {
        val wantRoot = op.asRoot || c.request.requires == Privilege.ROOT
        val shell: ShellBackend = if (wantRoot) {
            backends.shell(ShellIdentity.ROOT)?.takeIf { it.isAvailable() }
                ?: return OpOutcome.fail(ErrorCode.PERMISSION_DENIED, "Root is not available on this device, so this command cannot run as root. Grant root to the app or run it without root.")
        } else {
            listOfNotNull(backends.shell(ShellIdentity.SHELL), backends.shell(ShellIdentity.APP)).available().firstOrNull()
                ?: return OpOutcome.unavailable("No shell is available.")
        }
        val result = try {
            c.checkDispatch()
            shell.exec(ShellRequest(op.command, op.cwd, op.env, op.stdin, c.request.timeoutMs, op.maxOutputBytes))
        } catch (e: BackendException) {
            return OpOutcome.fail(e.code, e.message ?: "shell failed", e.retryable)
        }
        val body = buildString {
            if (result.stdout.isNotEmpty()) append(result.stdout.trimEnd())
            if (result.stderr.isNotBlank()) { if (isNotEmpty()) append('\n'); append("[stderr] ").append(result.stderr.trimEnd()) }
            if (result.truncated) append("\n[output truncated]")
        }
        val ranAs = result.identity ?: shell.identity
        val who = when (ranAs) {
            ShellIdentity.ROOT -> "as root"
            ShellIdentity.SHELL -> "as shell"
            ShellIdentity.APP -> "as the app user (limited access: start the privileged helper or grant root to run pm/am/dumpsys)"
        }
        val data = buildJsonObject { put("exit_code", result.exitCode); put("duration_ms", result.durationMs); put("identity", ranAs.name.lowercase()) }
        return when {
            result.timedOut -> OpOutcome.fail(ErrorCode.TIMEOUT, "The command did not finish within ${c.request.timeoutMs}ms and was stopped.${if (body.isNotEmpty()) "\nOutput so far:\n$body" else ""}", retryable = true, status = ActionStatus.TIMEOUT, data = data)
            result.exitCode == 0 -> OpOutcome.ok("Exit 0 ($who, ${result.durationMs}ms)${if (body.isNotEmpty()) ":\n$body" else " with no output"}", data, shell.name)
            else -> OpOutcome.fail(ErrorCode.COMMAND_FAILED, "Exit ${result.exitCode} ($who, ${result.durationMs}ms)${if (body.isNotEmpty()) ":\n$body" else " with no output"}", data = data)
        }
    }

    // ---- packages ----

    private suspend fun packages(c: OpContext, op: Operation.Packages): OpOutcome {
        val pm = backends.packages?.takeIf { it.isAvailable() } ?: return OpOutcome.unavailable("Package management needs shell access (Shizuku or root).")
        c.checkDispatch()
        return try {
            when (op.action) {
                PackageAction.LIST -> {
                    val list = pm.list(op.filter, includeSystem = op.filter != null, userId = op.userId)
                    val shown = list.take(200)
                    OpOutcome.ok(
                        "${list.size} package(s)${op.filter?.let { " matching \"$it\"" } ?: " (user-installed)"}:\n" +
                            shown.joinToString("\n") { "${it.packageName}${it.versionName?.let { v -> " $v" } ?: ""}${if (!it.enabled) " [disabled]" else ""}${if (it.system) " [system]" else ""}" } +
                            if (list.size > shown.size) "\n… ${list.size - shown.size} more" else "",
                        buildJsonObject { put("count", list.size) }, pm.name,
                    )
                }
                PackageAction.INFO -> {
                    val pkg = op.packageName ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "info needs a package name.")
                    val d = pm.details(pkg, op.userId) ?: return OpOutcome.fail(ErrorCode.NOT_FOUND, "Package $pkg is not installed.")
                    OpOutcome.ok(
                        buildString {
                            append("$pkg ${d.summary.versionName ?: ""} (code ${d.summary.versionCode ?: "?"}), ${if (d.summary.enabled) "enabled" else "disabled"}${if (d.summary.system) ", system app" else ""}")
                            d.summary.debuggable?.let { append(if (it) ", debuggable" else "") }
                            d.launcherActivity?.let { append("\nLauncher activity: $it") }
                            if (d.grantedPermissions.isNotEmpty()) append("\nGranted: ${d.grantedPermissions.joinToString(", ") { it.removePrefix("android.permission.") }}")
                            val denied = d.requestedPermissions - d.grantedPermissions.toSet()
                            if (denied.isNotEmpty()) append("\nNot granted: ${denied.joinToString(", ") { it.removePrefix("android.permission.") }.take(500)}")
                        },
                        backend = pm.name,
                    )
                }
                PackageAction.INSTALL -> OpOutcome.ok(pm.install(env.config.pathMapper(op.apkPath ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "install needs apk_path.")), replace = true, userId = op.userId), backend = pm.name)
                PackageAction.UNINSTALL -> OpOutcome.ok(pm.uninstall(op.packageName ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "uninstall needs a package name."), userId = op.userId), backend = pm.name)
                PackageAction.CLEAR_DATA -> OpOutcome.ok(pm.clearData(op.packageName ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "clear_data needs a package name."), op.userId), backend = pm.name)
                PackageAction.ENABLE -> OpOutcome.ok(pm.setEnabled(op.packageName ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "enable needs a package name."), true, op.userId), backend = pm.name)
                PackageAction.DISABLE -> OpOutcome.ok(pm.setEnabled(op.packageName ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "disable needs a package name."), false, op.userId), backend = pm.name)
                PackageAction.GRANT -> OpOutcome.ok(pm.grant(op.packageName ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "grant needs a package name."), op.permission ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "grant needs a permission."), op.userId), backend = pm.name)
                PackageAction.REVOKE -> OpOutcome.ok(pm.revoke(op.packageName ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "revoke needs a package name."), op.permission ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "revoke needs a permission."), op.userId), backend = pm.name)
            }
        } catch (e: BackendException) {
            OpOutcome.fail(e.code, e.message ?: "package operation failed", e.retryable)
        }
    }

    // ---- sysinfo ----

    private suspend fun sysinfo(op: Operation.SysInfo): OpOutcome {
        val s = backends.sysinfo?.takeIf { it.isAvailable() } ?: return OpOutcome.unavailable("System queries need shell access (Shizuku or root).")
        return try {
            val r = s.query(op.query, op.argument)
            OpOutcome.ok(r.text, r.data, s.name)
        } catch (e: BackendException) {
            OpOutcome.fail(e.code, e.message ?: "query failed", e.retryable)
        }
    }

    // ---- logs ----

    private val watchCounter = AtomicInteger()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private fun renderLog(e: LogEvent): String =
        "${timeFormat.format(Instant.ofEpochMilli(e.timestampMs))} ${e.level}/${e.tag}(${e.pid}${e.packageName?.let { " $it" } ?: ""}): ${e.message.take(300)}${if (e.kind == LogKind.CRASH || e.kind == LogKind.ANR || e.kind == LogKind.NATIVE_CRASH) "  <== ${e.kind.name}" else ""}"

    private suspend fun logs(c: OpContext, op: Operation.Logs): OpOutcome {
        val monitor = env.logs
        return when (op.action) {
            LogAction.QUERY -> {
                val backend = backends.logs?.takeIf { it.isAvailable() }
                val lines = when {
                    // What the monitor saw has the apps' names on it, so it also answers for an app the log backend cannot place.
                    backend != null -> backend.read(op.filter, op.maxLines).ifEmpty { monitor?.query(op.filter, op.maxLines).orEmpty() }
                    monitor != null -> monitor.query(op.filter, op.maxLines)
                    else -> return OpOutcome.unavailable("Reading logs needs shell access (Shizuku or root) or the READ_LOGS permission.")
                }
                if (lines.isEmpty()) OpOutcome.ok("No log lines match.", backend = backend?.name ?: "log_monitor")
                else OpOutcome.ok("${lines.size} log line(s):\n" + lines.joinToString("\n", transform = ::renderLog), buildJsonObject { put("count", lines.size) }, backend?.name ?: "log_monitor")
            }
            LogAction.SUMMARY -> {
                val m = monitor ?: return OpOutcome.unavailable("The log monitor is not running.")
                val summary = m.summary(c.state.createdAtMs, op.filter.packageName)
                OpOutcome.ok(summary.text(), buildJsonObject { put("crashes", summary.crashes.size); put("errors", summary.errorCount); put("warnings", summary.warningCount); put("clean", summary.isClean) }, "log_monitor")
            }
            LogAction.WATCH -> {
                val m = monitor ?: return OpOutcome.unavailable("The log monitor is not running.")
                val name = op.watchName ?: "watch-${watchCounter.incrementAndGet()}"
                m.watch(name, op.filter)
                OpOutcome.ok("Watching the log as \"$name\" (${describe(op.filter)}). Matching lines and any crash or ANR will be reported to you as they happen.", buildJsonObject { put("watch", name) }, "log_monitor")
            }
            LogAction.STOP_WATCH -> {
                val m = monitor ?: return OpOutcome.unavailable("The log monitor is not running.")
                val names = op.watchName?.let { listOf(it) } ?: m.watchNames().toList()
                val stopped = names.count { m.stopWatch(it) }
                OpOutcome.ok(if (stopped == 0) "No matching watch was running." else "Stopped $stopped watch(es).", backend = "log_monitor")
            }
        }
    }

    private fun describe(f: LogFilter): String = buildList {
        f.packageName?.let { add("package $it") }
        f.tags.takeIf { it.isNotEmpty() }?.let { add("tags ${it.joinToString()}") }
        add("level ${f.minLevel}+")
        f.regex?.let { add("/$it/") }
    }.joinToString(", ")

    // ---- files ----

    private suspend fun file(c: OpContext, op: Operation.File): OpOutcome {
        val fs = backends.files?.takeIf { it.isAvailable() } ?: return OpOutcome.unavailable("File access is not available.")
        // The agent names files the way Linux sees them; the phone's file system knows them by their real path.
        val path = env.config.pathMapper(op.path)
        c.checkDispatch()
        return try {
            when (op.action) {
                FileAction.READ -> {
                    val text = fs.read(path, op.maxBytes, op.privileged)
                    OpOutcome.ok("${op.path} (${text.length} characters):\n$text", backend = fs.name)
                }
                FileAction.WRITE -> {
                    fs.write(path, op.content ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "write needs content."), append = false, privileged = op.privileged)
                    OpOutcome.ok("Wrote ${op.content!!.length} characters to ${op.path}", backend = fs.name)
                }
                FileAction.APPEND -> {
                    fs.write(path, op.content ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "append needs content."), append = true, privileged = op.privileged)
                    OpOutcome.ok("Appended ${op.content!!.length} characters to ${op.path}", backend = fs.name)
                }
                FileAction.LIST -> {
                    val entries = fs.list(path, op.privileged)
                    OpOutcome.ok(
                        "${entries.size} entr${if (entries.size == 1) "y" else "ies"} in ${op.path}:\n" +
                            entries.take(300).joinToString("\n") { "${if (it.isDirectory) "d" else "-"} ${it.mode ?: ""} ${it.sizeBytes} ${it.name}${if (it.isDirectory) "/" else ""}" } +
                            if (entries.size > 300) "\n… ${entries.size - 300} more" else "",
                        backend = fs.name,
                    )
                }
                FileAction.STAT -> {
                    val e = fs.stat(path, op.privileged) ?: return OpOutcome.fail(ErrorCode.NOT_FOUND, "${op.path} does not exist.")
                    OpOutcome.ok("${op.path}: ${if (e.isDirectory) "directory" else "file, ${e.sizeBytes} bytes"}, modified ${Instant.ofEpochMilli(e.modifiedMs)}${e.mode?.let { ", mode $it" } ?: ""}", backend = fs.name)
                }
                FileAction.MKDIR -> { fs.mkdir(path, op.privileged); OpOutcome.ok("Created directory ${op.path}", backend = fs.name) }
                FileAction.DELETE -> { fs.delete(path, recursive = true, privileged = op.privileged); OpOutcome.ok("Deleted ${op.path}", backend = fs.name) }
                FileAction.MOVE -> {
                    val dest = op.destination ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "move needs a destination.")
                    fs.move(path, env.config.pathMapper(dest), op.privileged)
                    OpOutcome.ok("Moved ${op.path} to $dest", backend = fs.name)
                }
            }
        } catch (e: BackendException) {
            OpOutcome.fail(e.code, e.message ?: "file operation failed", e.retryable)
        }
    }

    // ---- processes ----

    private suspend fun process(c: OpContext, op: Operation.Process): OpOutcome {
        val p = backends.processes?.takeIf { it.isAvailable() } ?: return OpOutcome.unavailable("Process access needs shell access (Shizuku or root).")
        return try {
            when (op.action) {
                ProcessAction.LIST -> {
                    val list = p.list(op.name_)
                    OpOutcome.ok("${list.size} process(es):\n" + list.take(100).joinToString("\n") { "${it.pid} ${it.user ?: ""} ${it.name}${it.memMb?.let { m -> " ${m}MB" } ?: ""}" }, buildJsonObject { put("count", list.size) }, p.name)
                }
                ProcessAction.INFO -> {
                    val pid = op.pid ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "info needs a pid.")
                    val info = p.info(pid) ?: return OpOutcome.fail(ErrorCode.NOT_FOUND, "No process $pid.")
                    OpOutcome.ok("${info.pid} ${info.name} user=${info.user ?: "?"} state=${info.state ?: "?"}${info.memMb?.let { " mem=${it}MB" } ?: ""}${info.command?.let { "\n$it" } ?: ""}", backend = p.name)
                }
                ProcessAction.KILL -> {
                    val pids = op.pid?.let { listOf(it) } ?: op.name_?.let { n -> p.list(n).map { it.pid } }.orEmpty()
                    if (pids.isEmpty()) return OpOutcome.fail(ErrorCode.NOT_FOUND, "No matching process to kill.")
                    pids.forEach { c.checkDispatch(); p.kill(it, op.signal ?: 15) }
                    OpOutcome.ok("Sent signal ${op.signal ?: 15} to ${pids.joinToString()}", backend = p.name)
                }
            }
        } catch (e: BackendException) {
            OpOutcome.fail(e.code, e.message ?: "process operation failed", e.retryable)
        }
    }
}

/** Virtual displays and their leases (spec §9, §21). */
internal class DisplayExecutor(private val env: PipelineEnv, private val dispatch: suspend (app.umbra.core.model.action.ActionRequest, SessionToolState) -> ExecutionResult) {
    private val counter = AtomicInteger()

    suspend fun execute(c: OpContext, op: Operation.Display): OpOutcome {
        val displays = env.backends.displays?.takeIf { it.isAvailable() }
            ?: return OpOutcome.unavailable("Background displays need Shizuku or root (a shell-identity helper).")
        val session = c.request.sessionId ?: "-"
        c.checkDispatch()
        return try {
            when (op.action) {
                DisplayAction.LIST -> {
                    val list = displays.list()
                    OpOutcome.ok(
                        "${list.size} display(s):\n" + list.joinToString("\n") { d ->
                            "${d.id}${if (d.isDefault) " (default)" else ""} ${d.width}x${d.height} ${d.densityDpi}dpi${d.name?.let { " \"$it\"" } ?: ""}${if (d.isVirtual) " virtual" else ""}${env.leases.holder(d.id)?.let { " leased by $it" } ?: ""}"
                        },
                        buildJsonObject { put("count", list.size) }, displays.name,
                    )
                }
                DisplayAction.CREATE -> {
                    val spec = VirtualDisplaySpec(
                        name = "umbra-${session.take(8)}-${counter.incrementAndGet()}",
                        width = op.width ?: 1080, height = op.height ?: 2400, densityDpi = op.dpi ?: 420, ownerSessionId = c.request.sessionId,
                    )
                    val info = displays.create(spec)
                    c.checkDispatch()
                    env.leases.acquire(session, info.id)
                    c.checkDispatch()
                    c.state.activeDisplay = info.id
                    val launch = op.packageName?.let { pkg ->
                        dispatch(c.request.copy(id = c.request.id + ".launch", operation = Operation.LaunchApp(pkg, activity = op.activity, waitForForeground = false),
                            target = c.request.target.copy(display = info.id, app = pkg)), c.state).result
                    }
                    if (launch != null && !launch.isOk) return OpOutcome(launch.status, "Created display ${info.id}; app launch stopped: ${launch.summary}",
                        data = buildJsonObject { put("display", info.id) }, error = launch.error, backend = displays.name)
                    OpOutcome.ok(
                        "Created display ${info.id} (${info.width}x${info.height}, ${info.densityDpi}dpi) and leased it to this session." +
                            (if (launch != null) " Launched ${op.packageName} on it." else "") +
                            " Pass display=${info.id} to the android_* tools to work on it in the background.",
                        buildJsonObject { put("display", info.id) }, displays.name,
                    )
                }
                DisplayAction.DESTROY -> {
                    val id = op.displayId ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "destroy needs display_id.")
                    val holder = env.leases.holder(id)
                    if (holder != null && holder != session) return OpOutcome.fail(ErrorCode.LEASE_UNAVAILABLE, "Display $id belongs to session $holder.")
                    displays.destroy(id)
                    env.leases.forget(id)
                    // Nothing may keep pointing at a display that no longer exists.
                    if (c.state.activeDisplay == id) c.state.activeDisplay = 0
                    c.state.lastObservation.remove(id)
                    OpOutcome.ok("Destroyed display $id" + if (c.state.activeDisplay == 0) "; back on the main display" else "", backend = displays.name)
                }
                DisplayAction.LAUNCH -> {
                    OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "launch needs display_id and package.")
                }
                DisplayAction.LEASE -> {
                    val id = op.displayId ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "lease needs display_id.")
                    env.leases.acquire(session, id, env.config.leaseWaitMs)
                    c.checkDispatch()
                    OpOutcome.ok("Display $id is leased to this session (released automatically when it is idle or the session ends).")
                }
                DisplayAction.RELEASE -> {
                    val id = op.displayId ?: return OpOutcome.fail(ErrorCode.INVALID_ARGUMENTS, "release needs display_id.")
                    OpOutcome.ok(if (env.leases.release(session, id)) "Released display $id" else "This session did not hold display $id")
                }
            }
        } catch (e: LeaseUnavailableException) {
            OpOutcome.fail(ErrorCode.LEASE_UNAVAILABLE, e.message ?: "display is in use", retryable = true)
        } catch (e: BackendException) {
            OpOutcome.fail(e.code, e.message ?: "display operation failed", e.retryable)
        }
    }
}

/** Linux desktop operations that have no Android counterpart (spec §13). */
internal class GuiExecutor(private val env: PipelineEnv) {
    suspend fun execute(c: OpContext): OpOutcome {
        val gui = env.backends.linuxGui?.takeIf { it.isAvailable() }
            ?: return OpOutcome.unavailable("There is no Linux desktop to use: the Linux environment must be running with the Desktop apps add-on installed (Set up). Use the shell for command-line work.")
        c.checkDispatch()
        return try {
            when (val op = c.request.operation) {
                is Operation.Window -> when (op.action) {
                    WindowAction.LIST -> {
                        val windows = gui.windows(c.displayId)
                        OpOutcome.ok(
                            "${windows.size} window(s):\n" + windows.joinToString("\n") { w -> "[${w.id}] ${w.appName ?: "?"} \"${w.title ?: ""}\"${if (w.isFocused) " (focused)" else ""} ${w.bounds.width}x${w.bounds.height}" },
                            buildJsonObject { put("count", windows.size) }, gui.name,
                        )
                    }
                    else -> {
                        val ok = gui.windowAction(op.action, op.id, op.title, op.rect)
                        if (ok) OpOutcome.ok("${op.action.name.lowercase().replaceFirstChar { it.uppercase() }} window ${op.id ?: op.title ?: ""}".trim(), backend = gui.name)
                        else OpOutcome.fail(ErrorCode.NOT_FOUND, "No window matched (${op.id ?: op.title}) or the window manager refused. List the windows first.", retryable = true)
                    }
                }
                is Operation.LaunchCommand -> {
                    val pid = gui.launch(op.command, op.args, op.cwd, op.env)
                    OpOutcome.ok("Started ${op.command}${pid?.let { " (pid $it)" } ?: ""} on the desktop. Call gui_observe or window list to see it.", backend = gui.name)
                }
                else -> OpOutcome.unsupported("${c.request.operation.name} is not a desktop operation")
            }
        } catch (e: BackendException) {
            OpOutcome.fail(e.code, e.message ?: "desktop operation failed", e.retryable)
        }
    }
}
