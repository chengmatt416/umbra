package app.umbra.core.safety

import app.umbra.core.safety.RiskCategory.ACCOUNT_SECURITY_CHANGE
import app.umbra.core.safety.RiskCategory.DESTRUCTIVE_SYSTEM
import app.umbra.core.safety.RiskCategory.DEVICE_SECURITY_COMPROMISE
import app.umbra.core.safety.RiskCategory.DYNAMIC_COMMAND
import app.umbra.core.safety.RiskCategory.FACTORY_RESET
import app.umbra.core.safety.RiskCategory.FINANCIAL_TRANSACTION
import app.umbra.core.safety.RiskCategory.IMPORTANT_DATA_DELETION
import app.umbra.core.safety.RiskCategory.INSTALLS_UNTRUSTED_APP
import app.umbra.core.safety.RiskCategory.IRREVERSIBLE_ROOT
import app.umbra.core.safety.RiskCategory.NETWORK_CHANGE
import app.umbra.core.safety.RiskCategory.PLACES_CALL
import app.umbra.core.safety.RiskCategory.PRIVILEGE_ESCALATION
import app.umbra.core.safety.RiskCategory.PUBLISHES_CONTENT
import app.umbra.core.safety.RiskCategory.SELF_PROTECTION
import app.umbra.core.safety.RiskCategory.SENDS_MESSAGE
import app.umbra.core.safety.RiskCategory.SENSITIVE_DATA_EXPOSURE
import app.umbra.core.safety.RiskCategory.SYSTEM_CONFIGURATION
import app.umbra.core.safety.RiskLevel.CRITICAL
import app.umbra.core.safety.RiskLevel.HIGH
import app.umbra.core.safety.RiskLevel.LOW
import app.umbra.core.safety.RiskLevel.MEDIUM

/**
 * Static risk analysis of a shell command line (spec §28). It is best effort: a command it does not
 * recognise is not flagged, but anything it cannot see through (command substitution, `eval`, pipes
 * into a shell) is flagged as [DYNAMIC_COMMAND] so the approval policy can decide conservatively.
 * It never executes or expands anything.
 */
class ShellRiskAnalyzer {
    fun analyze(command: String, asRoot: Boolean = false, ctx: ActionContext = ActionContext(), stdin: String? = null): List<RiskFinding> {
        val out = LinkedHashSet<RiskFinding>()
        Analysis(ctx, out).run(command, asRoot, 0, stdin)
        return out.toList()
    }

    private class Unwrapped(
        val command: String?,
        val args: List<String>,
        val root: Boolean,
        val scripts: List<String>,
        val dynamicArgs: Boolean,
    )

    private class Analysis(private val ctx: ActionContext, private val out: MutableSet<RiskFinding>) {
        private val paths = PathClassifier(ctx)

        private fun add(category: RiskCategory, level: RiskLevel, reason: String) {
            out += RiskFinding(category, level, reason)
        }

        fun run(command: String, asRoot: Boolean, depth: Int, stdin: String? = null) {
            if (depth > MAX_DEPTH) {
                add(DYNAMIC_COMMAND, MEDIUM, "command is nested too deeply to analyse")
                return
            }
            if (FORK_BOMB.containsMatchIn(command)) add(DESTRUCTIVE_SYSTEM, CRITICAL, "looks like a fork bomb")
            for (match in SUBSTITUTION.findAll(command)) {
                val inner = match.groups[1]?.value ?: match.groups[2]?.value
                if (!inner.isNullOrBlank()) run(inner, asRoot, depth + 1)
            }
            var previous: Unwrapped? = null
            for (segment in ShellLexer.parse(command)) {
                val unwrapped = unwrap(segment.words)
                if (!stdin.isNullOrBlank()) {
                    if (unwrapped.command in SHELLS || unwrapped.command == "su") {
                        run(stdin, asRoot || unwrapped.root, depth + 1)
                    } else if (unwrapped.command in setOf("python", "python3", "node", "bun", "perl", "ruby", "php", "lua")) {
                        add(DYNAMIC_COMMAND, HIGH, "supplies program input to an interpreter")
                    }
                }
                analyzeSegment(segment, unwrapped, previous, asRoot, depth)
                previous = unwrapped
            }
        }

        // ---- wrappers: sudo, su -c, env, nohup, sh -c, eval, xargs ... ----

        private fun unwrap(words: List<String>): Unwrapped {
            var i = 0
            var root = false
            var dynamicArgs = false
            val scripts = mutableListOf<String>()
            while (i < words.size) {
                val word = words[i]
                if (ASSIGNMENT.matches(word)) { i++; continue }
                val base = basename(word)
                when {
                    base == "sudo" || base == "doas" -> {
                        root = true
                        i = skipOptions(words, i + 1, SUDO_ARG_OPTS)
                    }
                    base == "su" -> {
                        var j = i + 1
                        while (j < words.size) {
                            val t = words[j]
                            if (t == "-c" || t == "--command") {
                                words.getOrNull(j + 1)?.let { scripts += it }
                                return Unwrapped(null, emptyList(), true, scripts, false)
                            }
                            j++
                        }
                        return Unwrapped("su", words.drop(i + 1), true, scripts, false)
                    }
                    base == "env" -> i = skipEnv(words, i + 1)
                    base in TRANSPARENT_WRAPPERS -> i = skipOptions(words, i + 1, emptySet())
                    base == "nice" || base == "ionice" -> i = skipOptions(words, i + 1, setOf("-n", "-c", "-p", "--adjustment"))
                    base == "timeout" -> {
                        i = skipOptions(words, i + 1, setOf("-s", "-k", "--signal", "--kill-after"))
                        i++ // the duration
                    }
                    base == "busybox" || base == "toybox" -> i++
                    base == "xargs" -> {
                        dynamicArgs = true
                        i = skipOptions(words, i + 1, XARGS_ARG_OPTS)
                    }
                    base == "eval" -> {
                        val rest = words.drop(i + 1)
                        if (rest.isNotEmpty()) scripts += rest.joinToString(" ")
                        return Unwrapped("eval", rest, root, scripts, false)
                    }
                    base in SHELLS -> {
                        var j = i + 1
                        while (j < words.size) {
                            val t = words[j]
                            if (!t.startsWith("-")) break
                            if (!t.startsWith("--") && t.contains('c')) {
                                words.getOrNull(j + 1)?.let { scripts += it }
                                break
                            }
                            j++
                        }
                        return Unwrapped(base, words.drop(i + 1), root, scripts, false)
                    }
                    else -> return Unwrapped(base, words.drop(i + 1), root, scripts, dynamicArgs)
                }
            }
            return Unwrapped(null, emptyList(), root, scripts, dynamicArgs)
        }

        private fun skipOptions(words: List<String>, start: Int, withArg: Set<String>): Int {
            var i = start
            while (i < words.size) {
                val w = words[i]
                if (w == "--") return i + 1
                if (!w.startsWith("-") || w == "-") return i
                i += if (w in withArg) 2 else 1
            }
            return i
        }

        private fun skipEnv(words: List<String>, start: Int): Int {
            var i = start
            while (i < words.size) {
                val w = words[i]
                if (ASSIGNMENT.matches(w) || w.startsWith("-")) {
                    i += if (w == "-u" || w == "-C" || w == "-S") 2 else 1
                } else {
                    return i
                }
            }
            return i
        }

        // ---- per-segment analysis ----

        private fun analyzeSegment(
            segment: ShellLexer.Segment,
            u: Unwrapped,
            previous: Unwrapped?,
            asRoot: Boolean,
            depth: Int,
        ) {
            val root = asRoot || u.root
            for (r in segment.redirects) redirect(r, root)
            for (script in u.scripts) run(script, root, depth + 1)
            val cmd = u.command

            if (segment.heredoc != null && (cmd in SHELLS || cmd == "su")) run(segment.heredoc, root, depth + 1)

            if (cmd == null) {
                if (segment.dynamic) add(DYNAMIC_COMMAND, MEDIUM, "the command to run is computed at runtime")
                return
            }
            if (cmd.startsWith("$")) {
                add(DYNAMIC_COMMAND, MEDIUM, "the command to run is the variable $cmd")
            }
            if (segment.dynamic && (u.scripts.isNotEmpty() || cmd == "eval")) {
                add(DYNAMIC_COMMAND, MEDIUM, "the script passed to '$cmd' is computed at runtime")
            }
            if (cmd in SHELLS && segment.pipedFromPrevious && u.scripts.isEmpty()) {
                val feeder = previous?.command
                if (feeder in DOWNLOADERS) {
                    add(DEVICE_SECURITY_COMPROMISE, HIGH, "executes code downloaded from the network ($feeder | $cmd)")
                } else {
                    add(DYNAMIC_COMMAND, MEDIUM, "pipes generated text into a shell")
                }
            }
            if ((segment.dynamic || u.dynamicArgs) && cmd in DYNAMIC_SENSITIVE) {
                add(DYNAMIC_COMMAND, MEDIUM, "arguments of '$cmd' are computed at runtime")
            }
            if (root && cmd == "su") add(PRIVILEGE_ESCALATION, MEDIUM, "opens an interactive root shell")

            dispatch(cmd, u.args, root, segment, previous)
        }

        private fun redirect(r: ShellLexer.Redirect, root: Boolean) {
            if (!r.operator.contains('>')) return
            writeTo(r.target, root, "redirects output to")
        }

        // ---- rules ----

        private fun dispatch(cmd: String, args: List<String>, root: Boolean, seg: ShellLexer.Segment, previous: Unwrapped?) {
            when {
                cmd in DELETERS -> deleters(cmd, args, root)
                cmd == "find" -> find(args, root)
                cmd == "dd" -> dd(args)
                cmd.startsWith("mkfs") || cmd in DISK_TOOLS -> add(DESTRUCTIVE_SYSTEM, CRITICAL, "'$cmd' can erase or reformat storage")
                cmd == "fastboot" -> fastboot(args)
                cmd in POWER_COMMANDS -> power(cmd, args)
                cmd == "init" -> if (args.firstOrNull() in setOf("0", "6")) add(DESTRUCTIVE_SYSTEM, HIGH, "init $args reboots or powers off the device")
                cmd == "svc" -> svc(args)
                cmd == "pm" -> packageManager(args)
                cmd == "cmd" -> cmdService(args)
                cmd == "am" -> activityManager(args)
                cmd == "settings" -> settings(args)
                cmd == "setprop" || cmd == "resetprop" -> setprop(args)
                cmd == "setenforce" || cmd in POLICY_TOOLS -> add(DEVICE_SECURITY_COMPROMISE, CRITICAL, "'$cmd' changes SELinux or root policy")
                cmd == "magisk" || cmd == "ksud" || cmd == "apd" -> magisk(cmd, args)
                cmd == "mount" || cmd == "umount" -> mount(cmd, args, root)
                cmd in PERMISSION_COMMANDS -> permissions(cmd, args, root)
                cmd in KILLERS -> killers(cmd, args, root)
                cmd in NETWORK_TOOLS -> network(cmd, args)
                cmd == "git" -> git(args)
                cmd == "gh" -> github(args)
                cmd in PUBLISHERS -> publishers(cmd, args)
                cmd in DOWNLOADERS || cmd == "http" || cmd == "https" || cmd == "xh" -> http(cmd, args)
                cmd in MESSAGING_COMMANDS -> add(SENDS_MESSAGE, HIGH, "'$cmd' sends a message on the user's behalf")
                cmd == "service" -> service(args)
                cmd == "content" -> content(args)
                cmd == "locksettings" -> add(ACCOUNT_SECURITY_CHANGE, HIGH, "changes the device lock credential")
                cmd == "sqlite3" -> sqlite(args, root)
                cmd == "passwd" || cmd == "chpasswd" -> add(ACCOUNT_SECURITY_CHANGE, HIGH, "changes a password")
                cmd == "ssh" || cmd == "scp" || cmd == "sftp" || cmd == "rsync" || cmd == "nc" || cmd == "ncat" || cmd == "socat" || cmd == "telnet" ->
                    remote(cmd, args)
                cmd == "adb" -> adb(args, root)
                cmd == "wm" -> {
                    // `wm size` alone is a query; only an explicit new value (not `reset`) changes anything.
                    val value = args.getOrNull(1)
                    if (args.firstOrNull() in setOf("size", "density") && value != null && value != "reset") {
                        add(SYSTEM_CONFIGURATION, MEDIUM, "changes display size or density")
                    }
                }
                cmd == "recovery" -> if (args.any { it.contains("wipe") }) add(FACTORY_RESET, CRITICAL, "wipes user data via recovery")
                cmd == "wipe" -> add(FACTORY_RESET, CRITICAL, "wipes user data")
                cmd == "chroot" || cmd == "unshare" || cmd == "nsenter" -> if (root) add(PRIVILEGE_ESCALATION, MEDIUM, "enters another root or namespace")
            }
            if (cmd in WRITERS) writers(cmd, args, root)
            if (cmd in READERS) readers(cmd, args, root)
            if (cmd == "sed" || cmd == "perl") {
                if (args.any { it == "-i" || it.startsWith("-i") }) operands(args).drop(1).forEach { writeTo(it, root, "edits in place") }
            }
        }

        private fun deleters(cmd: String, args: List<String>, root: Boolean) {
            val recursive = cmd != "rmdir" && (hasShort(args, 'r', 'R') || hasLong(args, "--recursive"))
            for (op in operands(args)) deletion(op, recursive, root)
        }

        private fun deletion(path: String, recursive: Boolean, root: Boolean) {
            val glob = paths.isGlob(path)
            when (paths.classify(path)) {
                PathClass.SELF_PROTECTED -> add(SELF_PROTECTION, CRITICAL, "would delete the agent's own data: $path")
                PathClass.SYSTEM, PathClass.DEVICE_NODE -> {
                    add(DESTRUCTIVE_SYSTEM, CRITICAL, "deletes system path $path")
                    if (root) add(IRREVERSIBLE_ROOT, CRITICAL, "irreversible root deletion of $path")
                }
                PathClass.SENSITIVE_FILE -> add(IMPORTANT_DATA_DELETION, HIGH, "deletes credentials or key material: $path")
                PathClass.APP_DATA -> add(IMPORTANT_DATA_DELETION, HIGH, "deletes another app's private data: $path")
                PathClass.USER_DATA -> add(IMPORTANT_DATA_DELETION, if (recursive || glob) HIGH else MEDIUM, "deletes user files: $path")
                PathClass.WORKSPACE_ROOT -> if (recursive) add(IMPORTANT_DATA_DELETION, HIGH, "deletes the whole workspace: $path")
                PathClass.WORKSPACE, PathClass.TEMP, PathClass.STAGING -> Unit
                PathClass.UNKNOWN ->
                    if (recursive || glob) add(IMPORTANT_DATA_DELETION, MEDIUM, "recursive or wildcard delete outside the workspace: $path")
            }
        }

        private fun find(args: List<String>, root: Boolean) {
            val deletes = args.contains("-delete") ||
                args.withIndex().any { (i, a) -> a == "-exec" && basename(args.getOrNull(i + 1) ?: "") in DELETERS }
            if (!deletes) return
            val starts = args.takeWhile { !it.startsWith("-") && it != "(" && it != "!" }
            for (s in starts.ifEmpty { listOf(".") }) deletion(s, recursive = true, root = root)
        }

        private fun dd(args: List<String>) {
            for (a in args) {
                if (!a.startsWith("of=")) continue
                val target = a.removePrefix("of=")
                when (paths.classify(target)) {
                    PathClass.DEVICE_NODE, PathClass.SYSTEM -> add(DESTRUCTIVE_SYSTEM, CRITICAL, "dd writes to $target")
                    PathClass.SELF_PROTECTED -> add(SELF_PROTECTION, CRITICAL, "dd writes to the agent's own data: $target")
                    else -> Unit
                }
            }
        }

        private fun fastboot(args: List<String>) {
            val sub = args.firstOrNull { !it.startsWith("-") }
            when {
                sub == null || sub in setOf("getvar", "devices", "help") -> Unit
                sub in setOf("erase", "format", "flashing", "oem") || args.contains("-w") ->
                    add(FACTORY_RESET, CRITICAL, "fastboot $sub can wipe or unlock the device")
                else -> add(DESTRUCTIVE_SYSTEM, CRITICAL, "fastboot $sub changes firmware or boot state")
            }
        }

        private fun power(cmd: String, args: List<String>) {
            val special = args.firstOrNull { !it.startsWith("-") }
            val detail = if (special != null) " ($special)" else ""
            add(DESTRUCTIVE_SYSTEM, HIGH, "'$cmd'$detail restarts or powers off the device and ends the session")
        }

        private fun svc(args: List<String>) {
            val target = args.firstOrNull()
            val action = args.getOrNull(1)
            when {
                target == "power" && action in setOf("reboot", "shutdown") -> add(DESTRUCTIVE_SYSTEM, HIGH, "svc power $action")
                target in setOf("wifi", "data", "bluetooth", "nfc") && action == "disable" -> add(NETWORK_CHANGE, MEDIUM, "svc $target disable can cut connectivity")
            }
        }

        private fun packageManager(args: List<String>) {
            val sub = args.firstOrNull { !it.startsWith("-") } ?: return
            val rest = args.drop(args.indexOf(sub) + 1)
            val ops = operands(rest)
            val target = ops.lastOrNull()?.substringBefore('/')
            when (sub) {
                "uninstall", "uninstall-system-updates", "delete" -> {
                    if (target != null) dangerToPackage(target, "uninstalls", deletesData = true)
                }
                "clear" -> if (target != null) dangerToPackage(target, "clears the data of", deletesData = true)
                "disable", "disable-user", "hide", "suspend" -> if (target != null) {
                    when {
                        target in ctx.protectedPackages -> add(SELF_PROTECTION, CRITICAL, "would disable the agent app itself")
                        isSystemPackage(target) -> add(DESTRUCTIVE_SYSTEM, HIGH, "disables system package $target")
                        else -> add(SYSTEM_CONFIGURATION, MEDIUM, "disables package $target")
                    }
                }
                "install", "install-create", "install-write", "install-commit", "install-existing" -> installApk(sub, args, ops)
                "grant", "revoke" -> {
                    val pkg = ops.getOrNull(0)
                    val perm = ops.getOrNull(1)
                    when {
                        pkg != null && pkg in ctx.trustedPackages -> Unit
                        perm != null && perm.substringAfterLast('.') in SENSITIVE_PERMISSIONS ->
                            add(DEVICE_SECURITY_COMPROMISE, HIGH, "$sub of sensitive permission ${perm.substringAfterLast('.')}")
                        else -> add(SYSTEM_CONFIGURATION, MEDIUM, "$sub of permission ${perm ?: "?"} for ${pkg ?: "?"}")
                    }
                }
            }
        }

        private fun dangerToPackage(pkg: String, verb: String, deletesData: Boolean) {
            when {
                pkg in ctx.protectedPackages -> add(SELF_PROTECTION, CRITICAL, "would $verb the agent app itself")
                pkg in ctx.trustedPackages -> Unit
                else -> {
                    if (deletesData) add(IMPORTANT_DATA_DELETION, HIGH, "$verb $pkg and its data")
                    if (isSystemPackage(pkg)) add(DESTRUCTIVE_SYSTEM, HIGH, "$verb system package $pkg")
                }
            }
        }

        private fun installApk(sub: String, args: List<String>, ops: List<String>) {
            if (sub == "install-existing") return
            if (sub == "install-create" || sub == "install-commit") return // the APK is written by install-write
            val path = ops.lastOrNull()
            val viaStdin = args.contains("-S")
            when {
                path == null || viaStdin ->
                    add(INSTALLS_UNTRUSTED_APP, HIGH, "installs an APK streamed from an unknown source")
                path.startsWith("http://") || path.startsWith("https://") ->
                    add(INSTALLS_UNTRUSTED_APP, HIGH, "installs an APK from the network: $path")
                else -> when (paths.classify(path)) {
                    PathClass.WORKSPACE, PathClass.STAGING -> Unit
                    else -> add(INSTALLS_UNTRUSTED_APP, HIGH, "installs an APK from outside the workspace: $path")
                }
            }
        }

        private fun cmdService(args: List<String>) {
            when (args.firstOrNull()) {
                "package" -> packageManager(args.drop(1))
                "appops" -> if (args.getOrNull(1) == "set") add(SYSTEM_CONFIGURATION, MEDIUM, "changes an app operation permission")
                "overlay", "role", "notification" -> add(SYSTEM_CONFIGURATION, LOW, "changes ${args.first()} state")
                "connectivity" -> if (args.contains("airplane-mode")) add(NETWORK_CHANGE, MEDIUM, "toggles airplane mode")
                "wifi" -> if (args.getOrNull(1) in setOf("set-wifi-enabled", "forget-network")) add(NETWORK_CHANGE, MEDIUM, "changes Wi-Fi")
                "lock_settings" -> add(ACCOUNT_SECURITY_CHANGE, HIGH, "changes the device lock credential")
                "recovery" -> add(FACTORY_RESET, CRITICAL, "recovery command")
            }
        }

        private fun activityManager(args: List<String>) {
            val sub = args.firstOrNull { !it.startsWith("-") } ?: return
            val rest = args.drop(args.indexOf(sub) + 1)
            when (sub) {
                "force-stop", "kill", "stop-app" -> {
                    val pkg = operands(rest).lastOrNull()
                    if (pkg != null && pkg in ctx.protectedPackages) add(SELF_PROTECTION, CRITICAL, "would stop the agent app itself")
                }
                "crash" -> add(SYSTEM_CONFIGURATION, MEDIUM, "forces an app to crash")
                "broadcast" -> {
                    val action = optionValue(rest, "-a", "--action")?.substringAfterLast('.')?.uppercase()
                    when (action) {
                        "MASTER_CLEAR", "FACTORY_RESET" -> add(FACTORY_RESET, CRITICAL, "broadcasts a factory reset")
                        "REBOOT", "ACTION_SHUTDOWN", "REQUEST_SHUTDOWN", "SHUTDOWN" -> add(DESTRUCTIVE_SYSTEM, HIGH, "broadcasts a reboot or shutdown")
                    }
                }
                "start", "start-activity", "startservice", "start-service", "start-foreground-service" -> startIntent(rest)
            }
        }

        private fun startIntent(args: List<String>) {
            val action = optionValue(args, "-a", "--action")?.substringAfterLast('.')?.uppercase()
            val data = optionValue(args, "-d", "--data")
            val mime = optionValue(args, "-t", "--mime-type")
            when {
                action == "CALL" || (data?.startsWith("tel:") == true && action == "CALL") ->
                    add(PLACES_CALL, HIGH, "places a phone call${data?.let { " to ${it.removePrefix("tel:")}" } ?: ""}")
                action in setOf("SEND", "SENDTO", "SEND_MULTIPLE") || data?.startsWith("smsto:") == true ||
                    data?.startsWith("sms:") == true || data?.startsWith("mailto:") == true ->
                    add(SENDS_MESSAGE, MEDIUM, "opens a message or email compose screen")
                action == "DELETE" || action == "UNINSTALL_PACKAGE" ->
                    add(IMPORTANT_DATA_DELETION, HIGH, "opens an uninstall prompt${data?.let { " for $it" } ?: ""}")
                action == "INSTALL_PACKAGE" || mime == "application/vnd.android.package-archive" -> {
                    val verdict = data?.let { paths.classify(it.removePrefix("file://")) }
                    if (verdict != PathClass.WORKSPACE && verdict != PathClass.STAGING) {
                        add(INSTALLS_UNTRUSTED_APP, HIGH, "opens an APK install prompt for an unvetted file")
                    }
                }
            }
        }

        private fun settings(args: List<String>) {
            val sub = args.firstOrNull() ?: return
            if (sub != "put" && sub != "delete") return
            val namespace = args.getOrNull(1) ?: return
            val key = args.getOrNull(2) ?: return
            when {
                key in SAFE_SETTINGS -> Unit
                key in SECURITY_SETTINGS || key.startsWith("lockscreen") || key.startsWith("lock_screen") ->
                    add(DEVICE_SECURITY_COMPROMISE, HIGH, "changes security-relevant setting $namespace/$key")
                key in NETWORK_SETTINGS -> add(NETWORK_CHANGE, MEDIUM, "changes network setting $namespace/$key")
                key.contains("proxy") || key.startsWith("private_dns") ->
                    add(DEVICE_SECURITY_COMPROMISE, HIGH, "changes traffic routing via $namespace/$key")
                else -> add(SYSTEM_CONFIGURATION, MEDIUM, "changes system setting $namespace/$key")
            }
        }

        private fun setprop(args: List<String>) {
            val key = args.firstOrNull { !it.startsWith("-") } ?: return
            when {
                key.startsWith("ro.") || key.startsWith("persist.") -> add(DEVICE_SECURITY_COMPROMISE, HIGH, "changes persistent system property $key")
                else -> add(SYSTEM_CONFIGURATION, MEDIUM, "changes system property $key")
            }
        }

        private fun magisk(cmd: String, args: List<String>) {
            val joined = args.joinToString(" ")
            if (joined.contains("module") || joined.contains("install") || joined.contains("--denylist") || joined.contains("sepolicy")) {
                add(DEVICE_SECURITY_COMPROMISE, HIGH, "$cmd changes root modules or policy")
            }
        }

        private fun mount(cmd: String, args: List<String>, root: Boolean) {
            val ops = operands(args)
            val remountRw = args.any { it.contains("remount") } && args.any { it.contains("rw") }
            val touchesSystem = ops.any { paths.classify(it) == PathClass.SYSTEM || it == "/" }
            when {
                touchesSystem && (remountRw || cmd == "umount") -> add(DESTRUCTIVE_SYSTEM, CRITICAL, "$cmd changes a system partition")
                args.contains("--bind") || args.contains("-o") && args.any { it.contains("bind") } ->
                    if (touchesSystem) add(DESTRUCTIVE_SYSTEM, CRITICAL, "bind-mounts over a system path")
                    else if (root) add(PRIVILEGE_ESCALATION, MEDIUM, "creates a bind mount")
                root -> add(PRIVILEGE_ESCALATION, MEDIUM, "$cmd as root")
            }
        }

        private fun permissions(cmd: String, args: List<String>, root: Boolean) {
            val recursive = hasShort(args, 'R') || hasLong(args, "--recursive")
            val ops = operands(args)
            val targets = if (cmd == "chmod" || cmd == "chown" || cmd == "chgrp") ops.drop(1) else ops
            for (t in targets) {
                when (paths.classify(t)) {
                    PathClass.SELF_PROTECTED -> add(SELF_PROTECTION, CRITICAL, "would change permissions on the agent's own data: $t")
                    PathClass.SYSTEM, PathClass.DEVICE_NODE ->
                        add(DESTRUCTIVE_SYSTEM, if (recursive) CRITICAL else HIGH, "$cmd on system path $t")
                    PathClass.APP_DATA, PathClass.USER_DATA ->
                        if (recursive) add(DEVICE_SECURITY_COMPROMISE, HIGH, "recursive $cmd on $t")
                    else -> Unit
                }
            }
            if (cmd == "chmod" && ops.firstOrNull() in setOf("777", "a+rwx", "o+w", "666") && targets.any { paths.classify(it) == PathClass.UNKNOWN }) {
                add(DEVICE_SECURITY_COMPROMISE, MEDIUM, "makes ${targets.joinToString()} world-writable")
            }
        }

        private fun killers(cmd: String, args: List<String>, root: Boolean) {
            val ops = operands(args)
            for (target in ops) {
                val name = target.substringAfterLast('/')
                when {
                    target == "1" || target == "-1" || target == "0" -> add(DESTRUCTIVE_SYSTEM, CRITICAL, "$cmd $target would kill init or every process")
                    name in CRITICAL_PROCESSES -> add(DESTRUCTIVE_SYSTEM, CRITICAL, "$cmd $name would crash or reboot the system")
                    name in ctx.protectedPackages || name == "umbra" -> add(SELF_PROTECTION, CRITICAL, "would kill the agent app itself")
                    root && target.toIntOrNull() != null -> add(PRIVILEGE_ESCALATION, LOW, "root kill of pid $target")
                }
            }
        }

        private fun network(cmd: String, args: List<String>) {
            val readOnly = args.any { it in setOf("-L", "-S", "-l", "-n", "show", "list", "status", "get") } &&
                args.none { it in setOf("-F", "-A", "-I", "-D", "-X", "add", "del", "delete", "flush", "set", "replace") }
            if (!readOnly && args.isNotEmpty()) add(NETWORK_CHANGE, MEDIUM, "'$cmd' changes network configuration")
        }

        private fun git(args: List<String>) {
            var i = 0
            while (i < args.size && args[i].startsWith("-")) i += if (args[i] in setOf("-C", "-c", "--git-dir", "--work-tree")) 2 else 1
            val sub = args.getOrNull(i) ?: return
            val rest = args.drop(i + 1)
            when (sub) {
                "push" -> {
                    add(PUBLISHES_CONTENT, HIGH, "pushes commits to a remote")
                    if (rest.any { it in setOf("--force", "-f", "--force-with-lease", "--delete", "-d", "--mirror") || it.startsWith(":") }) {
                        add(IMPORTANT_DATA_DELETION, HIGH, "rewrites or deletes remote history")
                    }
                }
                "reset" -> if (rest.contains("--hard")) add(IMPORTANT_DATA_DELETION, MEDIUM, "git reset --hard discards uncommitted work")
                "clean" -> if (rest.any { it.startsWith("-") && it.contains('f') }) add(IMPORTANT_DATA_DELETION, MEDIUM, "git clean deletes untracked files")
                "checkout", "restore" -> if (rest.contains("--force") || rest.contains("-f") || rest == listOf(".") || rest == listOf("--", ".")) {
                    add(IMPORTANT_DATA_DELETION, MEDIUM, "discards uncommitted changes")
                }
                "stash" -> if (rest.firstOrNull() in setOf("drop", "clear")) add(IMPORTANT_DATA_DELETION, MEDIUM, "drops stashed work")
                "branch" -> if (rest.any { it == "-D" || it == "--delete" && rest.contains("--force") }) add(IMPORTANT_DATA_DELETION, MEDIUM, "force-deletes a branch")
                "credential" -> add(ACCOUNT_SECURITY_CHANGE, MEDIUM, "touches stored git credentials")
            }
        }

        private fun github(args: List<String>) {
            val group = args.firstOrNull() ?: return
            val action = args.getOrNull(1)
            when {
                group == "repo" && action in setOf("delete") -> add(IMPORTANT_DATA_DELETION, CRITICAL, "deletes a GitHub repository")
                group == "repo" && action in setOf("create", "edit", "fork", "archive", "rename") -> add(PUBLISHES_CONTENT, HIGH, "gh repo $action")
                group in setOf("pr", "issue", "release", "gist", "workflow", "label", "project") &&
                    action in setOf("create", "merge", "close", "comment", "edit", "delete", "upload", "run", "review", "reopen") ->
                    add(PUBLISHES_CONTENT, HIGH, "gh $group $action publishes to GitHub")
                group == "api" -> {
                    val method = optionValue(args, "-X", "--method")?.uppercase()
                    val writes = method in setOf("POST", "PUT", "PATCH", "DELETE") || args.any { it == "-f" || it == "-F" || it == "--field" || it == "--raw-field" }
                    if (writes) add(PUBLISHES_CONTENT, HIGH, "gh api with a write request")
                }
                group == "auth" && action in setOf("login", "refresh", "setup-git") -> add(ACCOUNT_SECURITY_CHANGE, MEDIUM, "changes GitHub credentials")
            }
        }

        private fun publishers(cmd: String, args: List<String>) {
            val sub = args.firstOrNull { !it.startsWith("-") } ?: return
            val publishing = when (cmd) {
                "npm", "yarn", "pnpm", "bun" -> sub in setOf("publish", "unpublish", "deprecate", "dist-tag", "access", "owner", "login", "adduser", "token")
                "cargo" -> sub in setOf("publish", "yank", "login", "owner")
                "docker", "podman" -> sub in setOf("push", "login")
                "twine" -> sub == "upload"
                "gem" -> sub in setOf("push", "yank", "signin")
                "helm" -> sub in setOf("push", "registry")
                "vsce", "ovsx" -> sub == "publish"
                else -> false
            }
            if (!publishing) return
            if (sub in setOf("login", "adduser", "signin", "token", "registry")) {
                add(ACCOUNT_SECURITY_CHANGE, MEDIUM, "$cmd $sub handles registry credentials")
            } else {
                add(PUBLISHES_CONTENT, HIGH, "$cmd $sub publishes publicly")
            }
        }

        private fun http(cmd: String, args: List<String>) {
            val urls = args.filter { it.contains("://") || it.startsWith("www.") }
            val method = (optionValue(args, "-X", "--request", "--method") ?: if (cmd == "http" || cmd == "https" || cmd == "xh") args.firstOrNull { it.uppercase() in HTTP_METHODS } else null)
                ?.uppercase()
            val sendsBody = args.any { it in BODY_FLAGS || BODY_FLAGS.any { f -> f.startsWith("--") && it.startsWith("$f=") } }
            val writes = method in setOf("POST", "PUT", "PATCH", "DELETE") || (method == null && sendsBody)
            for (u in urls) {
                val host = hostOf(u) ?: continue
                if (writes) {
                    when {
                        PAYMENT_HOSTS.any { host == it || host.endsWith(".$it") } -> add(FINANCIAL_TRANSACTION, HIGH, "write request to payment service $host")
                        MESSAGING_HOSTS.any { host == it || host.endsWith(".$it") } -> add(SENDS_MESSAGE, HIGH, "write request to messaging service $host")
                        PUBLISH_HOSTS.any { host == it || host.endsWith(".$it") } -> add(PUBLISHES_CONTENT, HIGH, "write request to publishing service $host")
                    }
                }
            }
            val uploadsLocalFile = args.any { it.startsWith("@") && it.length > 1 } || args.any { it == "-T" || it == "--upload-file" }
            if (uploadsLocalFile) {
                val targets = args.filter { it.startsWith("@") }.map { it.removePrefix("@") }
                if (targets.any { paths.classify(it) == PathClass.SENSITIVE_FILE }) {
                    add(SENSITIVE_DATA_EXPOSURE, HIGH, "uploads credentials or key material")
                }
            }
        }

        private fun service(args: List<String>) {
            if (args.firstOrNull() != "call") return
            val name = args.getOrNull(1) ?: return
            when {
                name == "isms" || name == "sms" || name.startsWith("isms") -> add(SENDS_MESSAGE, HIGH, "sends an SMS through the telephony service")
                name == "phone" || name == "telecom" -> add(PLACES_CALL, HIGH, "calls the telephony service directly")
                name == "power" -> add(DESTRUCTIVE_SYSTEM, HIGH, "calls the power service directly")
            }
        }

        private fun content(args: List<String>) {
            val sub = args.firstOrNull() ?: return
            val uri = optionValue(args, "--uri") ?: return
            val sensitive = SENSITIVE_PROVIDERS.any { uri.contains(it) }
            when (sub) {
                "delete" -> add(IMPORTANT_DATA_DELETION, if (sensitive) HIGH else MEDIUM, "deletes rows from $uri")
                "insert", "update" -> if (sensitive) add(IMPORTANT_DATA_DELETION, HIGH, "modifies personal data in $uri") else add(SYSTEM_CONFIGURATION, MEDIUM, "writes to $uri")
                "query", "read" -> if (sensitive) add(SENSITIVE_DATA_EXPOSURE, MEDIUM, "reads personal data from $uri")
                "call" -> add(SYSTEM_CONFIGURATION, MEDIUM, "calls a content provider method")
            }
        }

        private fun sqlite(args: List<String>, root: Boolean) {
            val db = operands(args).firstOrNull() ?: return
            val sql = args.drop(1).joinToString(" ")
            val writes = SQL_WRITE.containsMatchIn(sql)
            when (paths.classify(db)) {
                PathClass.SELF_PROTECTED -> add(SELF_PROTECTION, CRITICAL, "would open the agent's own database")
                PathClass.SYSTEM, PathClass.SENSITIVE_FILE ->
                    add(if (writes) DEVICE_SECURITY_COMPROMISE else SENSITIVE_DATA_EXPOSURE, HIGH, "opens a system or credential database: $db")
                PathClass.APP_DATA -> add(if (writes) IMPORTANT_DATA_DELETION else SENSITIVE_DATA_EXPOSURE, if (writes) HIGH else MEDIUM, "opens another app's database: $db")
                else -> Unit
            }
        }

        private fun remote(cmd: String, args: List<String>) {
            add(SYSTEM_CONFIGURATION, MEDIUM, "'$cmd' talks to a remote machine")
            if (cmd == "scp" || cmd == "rsync" || cmd == "sftp") {
                if (operands(args).any { paths.classify(it) == PathClass.SENSITIVE_FILE }) {
                    add(SENSITIVE_DATA_EXPOSURE, HIGH, "copies credentials or key material to a remote machine")
                }
            }
        }

        private fun adb(args: List<String>, root: Boolean) {
            val i = args.indexOf("shell")
            if (i >= 0 && i + 1 < args.size) run(args.drop(i + 1).joinToString(" "), root, 1)
            if (args.firstOrNull { !it.startsWith("-") } in setOf("reboot", "sideload", "root", "disable-verity", "remount")) {
                add(DESTRUCTIVE_SYSTEM, HIGH, "adb ${args.first { !it.startsWith("-") }}")
            }
        }

        private fun writers(cmd: String, args: List<String>, root: Boolean) {
            val ops = operands(args)
            val destinations = when (cmd) {
                "cp", "mv", "install", "ln", "rsync", "unzip", "tar" -> ops.lastOrNull()?.let { listOf(it) } ?: emptyList()
                else -> ops
            }
            for (d in destinations) writeTo(d, root, "writes to")
            if (cmd == "mv") ops.dropLast(1).forEach { deletion(it, recursive = false, root = root) }
        }

        private fun writeTo(path: String, root: Boolean, verb: String) {
            when (paths.classify(path)) {
                PathClass.SELF_PROTECTED -> add(SELF_PROTECTION, CRITICAL, "$verb the agent's own data or safety files: $path")
                PathClass.SYSTEM, PathClass.DEVICE_NODE -> add(DESTRUCTIVE_SYSTEM, CRITICAL, "$verb system path $path")
                PathClass.SENSITIVE_FILE -> add(DEVICE_SECURITY_COMPROMISE, HIGH, "$verb credentials or key material: $path")
                PathClass.APP_DATA -> add(DEVICE_SECURITY_COMPROMISE, MEDIUM, "$verb another app's private data: $path")
                else -> Unit
            }
        }

        private fun readers(cmd: String, args: List<String>, root: Boolean) {
            for (op in operands(args)) {
                when (paths.classify(op)) {
                    PathClass.SENSITIVE_FILE -> add(SENSITIVE_DATA_EXPOSURE, HIGH, "reads credentials or key material: $op")
                    PathClass.APP_DATA -> add(SENSITIVE_DATA_EXPOSURE, MEDIUM, "reads another app's private data: $op")
                    else -> Unit
                }
            }
        }

        private fun isSystemPackage(pkg: String): Boolean =
            pkg == "android" || pkg.startsWith("com.android.") || pkg.startsWith("com.google.android.gms") ||
                pkg.startsWith("com.google.android.gsf") || pkg == "com.android.vending" || pkg.startsWith("com.qualcomm.") ||
                pkg.startsWith("com.sec.android.") || pkg.startsWith("com.samsung.android.") || pkg.startsWith("org.lineageos.")
    }

    private companion object {
        const val MAX_DEPTH = 5

        val ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")
        val FORK_BOMB = Regex(""":\s*\(\s*\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;?\s*:|\b(\w+)\s*\(\s*\)\s*\{\s*\1\s*\|\s*\1\s*&\s*\}""")
        val SUBSTITUTION = Regex("""\$\(([^()]*)\)|`([^`]*)`""")
        val SQL_WRITE = Regex("""\b(insert|update|delete|drop|alter|replace|create|vacuum|pragma\s+\w+\s*=)\b""", RegexOption.IGNORE_CASE)

        val SHELLS = setOf("sh", "bash", "zsh", "dash", "ash", "ksh", "mksh", "tcsh", "fish", "busybox-sh")
        val TRANSPARENT_WRAPPERS = setOf("nohup", "time", "command", "exec", "setsid", "stdbuf", "unbuffer", "builtin")
        val SUDO_ARG_OPTS = setOf("-u", "-g", "-h", "-p", "-C", "-D", "-R", "-T", "-r", "-t", "-U")
        val XARGS_ARG_OPTS = setOf("-n", "-I", "-L", "-P", "-d", "-E", "-s", "-a")

        val DELETERS = setOf("rm", "rmdir", "unlink", "shred", "srm", "trash")
        val DISK_TOOLS = setOf("mke2fs", "wipefs", "blkdiscard", "fdisk", "sfdisk", "parted", "sgdisk", "gdisk", "cfdisk", "flash_image", "erase_image", "nandwrite", "flash_erase", "newfs_msdos")
        val POWER_COMMANDS = setOf("reboot", "shutdown", "poweroff", "halt")
        val POLICY_TOOLS = setOf("supolicy", "sepolicy-inject", "magiskpolicy", "setsebool", "load_policy")
        val PERMISSION_COMMANDS = setOf("chmod", "chown", "chgrp", "chcon", "restorecon", "setfacl")
        val KILLERS = setOf("kill", "killall", "pkill", "skill", "killall5")
        val NETWORK_TOOLS = setOf("iptables", "ip6tables", "nft", "ip", "ifconfig", "route", "ndc", "tc", "resolvectl")
        val DOWNLOADERS = setOf("curl", "wget", "fetch", "aria2c")
        val PUBLISHERS = setOf("npm", "yarn", "pnpm", "bun", "cargo", "docker", "podman", "twine", "gem", "helm", "vsce", "ovsx")
        val MESSAGING_COMMANDS = setOf("sendmail", "mail", "mailx", "mutt", "msmtp", "swaks", "termux-sms-send", "termux-telephony-call", "signal-cli")
        val WRITERS = setOf("cp", "mv", "install", "ln", "rsync", "tee", "touch", "truncate", "mkdir", "mkfifo", "mknod", "unzip", "tar")
        val READERS = setOf("cat", "head", "tail", "less", "more", "grep", "egrep", "fgrep", "rg", "cp", "scp", "tar", "zip", "base64", "xxd", "od", "hexdump", "strings", "sha256sum", "md5sum")
        val DYNAMIC_SENSITIVE = setOf("rm", "dd", "mv", "chmod", "chown", "kill", "pkill", "killall", "pm", "am", "settings", "setprop", "mount", "umount", "cp", "tee", "find")
        val CRITICAL_PROCESSES = setOf(
            "system_server", "zygote", "zygote64", "surfaceflinger", "init", "installd", "vold", "netd", "logd",
            "servicemanager", "hwservicemanager", "keystore", "keystore2", "adbd", "lmkd", "healthd", "statsd",
        )
        val HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        val BODY_FLAGS = setOf("-d", "--data", "--data-raw", "--data-binary", "--data-urlencode", "-F", "--form", "--json", "-T", "--upload-file", "--post-data", "--post-file", "--body-data", "--body-file")

        val SENSITIVE_PERMISSIONS = setOf(
            "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALL_LOG", "WRITE_CALL_LOG", "CALL_PHONE",
            "RECORD_AUDIO", "CAMERA", "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "MANAGE_EXTERNAL_STORAGE",
            "WRITE_SECURE_SETTINGS", "READ_LOGS", "DUMP", "INSTALL_PACKAGES", "REQUEST_INSTALL_PACKAGES", "DELETE_PACKAGES",
            "SYSTEM_ALERT_WINDOW", "BIND_ACCESSIBILITY_SERVICE", "BIND_DEVICE_ADMIN", "READ_PHONE_STATE", "READ_PHONE_NUMBERS",
        )
        val SAFE_SETTINGS = setOf(
            "animator_duration_scale", "transition_animation_scale", "window_animation_scale", "stay_on_while_plugged_in",
            "screen_off_timeout", "accelerometer_rotation", "user_rotation", "show_touches", "pointer_location",
            "force_resizable_activities", "enable_freeform_support", "settings_enable_monitor_phantom_procs", "screen_brightness",
            "screen_brightness_mode", "font_scale", "show_ime_with_hard_keyboard", "long_press_timeout", "multi_press_timeout",
            "overlay_display_devices", "development_settings_enabled_for_agent",
        )
        val SECURITY_SETTINGS = setOf(
            "adb_enabled", "adb_wifi_enabled", "development_settings_enabled", "install_non_market_apps", "package_verifier_enable",
            "verifier_verify_adb_installs", "package_verifier_user_consent", "device_provisioned", "user_setup_complete",
            "enabled_accessibility_services", "accessibility_enabled", "enabled_notification_listeners", "enabled_input_methods",
            "default_input_method", "location_mode", "location_providers_allowed", "allow_mock_location", "mock_location",
            "usb_mass_storage_enabled", "safe_boot_disallowed", "play_protect_enabled",
        )
        val NETWORK_SETTINGS = setOf("airplane_mode_on", "wifi_on", "mobile_data", "data_roaming", "bluetooth_on", "wifi_sleep_policy", "captive_portal_mode")

        val MESSAGING_HOSTS = setOf(
            "api.telegram.org", "hooks.slack.com", "slack.com", "discord.com", "discordapp.com", "api.twilio.com", "api.sendgrid.com",
            "api.mailgun.net", "api.postmarkapp.com", "api.mailchimp.com", "graph.facebook.com", "graph.instagram.com", "api.line.me",
            "api.pushover.net", "ntfy.sh", "api.whatsapp.com", "graph.whatsapp.com",
        )
        val PAYMENT_HOSTS = setOf(
            "api.stripe.com", "api.paypal.com", "api-m.paypal.com", "api.coinbase.com", "api.binance.com", "api.kraken.com",
            "api.robinhood.com", "api.plaid.com", "api.venmo.com", "connect.squareup.com", "api.squareup.com", "api.wise.com",
        )
        val PUBLISH_HOSTS = setOf(
            "api.github.com", "uploads.github.com", "registry.npmjs.org", "upload.pypi.org", "api.medium.com", "api.twitter.com",
            "api.x.com", "api.linkedin.com", "api.bsky.app", "bsky.social", "api.mastodon.social", "api.wordpress.com", "api.tumblr.com",
        )
        val SENSITIVE_PROVIDERS = listOf("content://sms", "content://mms", "content://contacts", "content://com.android.contacts", "content://call_log", "content://com.android.calendar", "content://media", "content://telephony")

        fun basename(word: String): String = word.substringAfterLast('/')

        fun operands(args: List<String>): List<String> {
            val out = mutableListOf<String>()
            var endOptions = false
            for (a in args) {
                if (!endOptions && a == "--") { endOptions = true; continue }
                if (!endOptions && a.startsWith("-") && a.length > 1) continue
                out += a
            }
            return out
        }

        fun hasShort(args: List<String>, vararg chars: Char): Boolean =
            args.any { it.startsWith("-") && !it.startsWith("--") && chars.any { c -> it.contains(c) } }

        fun hasLong(args: List<String>, vararg names: String): Boolean = args.any { it in names }

        fun optionValue(args: List<String>, vararg names: String): String? {
            for ((i, a) in args.withIndex()) {
                if (a in names) return args.getOrNull(i + 1)
                for (n in names) if (n.startsWith("--") && a.startsWith("$n=")) return a.substringAfter('=')
            }
            return null
        }

        fun hostOf(url: String): String? {
            val noScheme = url.substringAfter("://", url)
            val host = noScheme.substringBefore('/').substringAfter('@').substringBefore(':').lowercase()
            return host.takeIf { it.isNotBlank() }
        }
    }
}
