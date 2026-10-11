package app.umbra.runtime.linux

/** One measured fact about the agent's process, and what it means. */
data class IsolationFact(val name: String, val value: String, val meaning: String, val good: Boolean)

/** What the agent's processes really are, measured from inside one of them (architecture spec INV-008). */
data class IsolationReport(val facts: List<IsolationFact>, val summary: String) {
    val sameUserAsApp: Boolean get() = facts.any { it.name == "Kernel user" && !it.good }
}

/**
 * Measures the isolation of the agent's commands from inside one of them, as the agent (INV-008: no fake sandbox).
 * The kernel's own account is used (`/proc/self/status`, `/proc/self/attr/current`, `/proc/self/ns`), not what the
 * process is told: under proot `id -u` may say 0 while the kernel says the app's user. The app compares the result
 * with its own process. Nothing here changes anything; it only reads.
 */
object IsolationProbe {
    val SCRIPT = """
        s=/proc/self/status
        echo "told_uid=$(id -u 2>/dev/null)"
        awk '/^Uid:/{print "uid="$2} /^Gid:/{print "gid="$2} /^CapEff:/{print "cap_eff="$2} /^CapBnd:/{print "cap_bnd="$2} /^NoNewPrivs:/{print "no_new_privs="$2} /^Seccomp:/{print "seccomp="$2} /^TracerPid:/{print "tracer="$2}' ${'$'}s
        echo "selinux=$(tr -d '\000' < /proc/self/attr/current 2>/dev/null)"
        for n in mnt pid net user uts ipc cgroup; do echo "ns_${'$'}n=$(readlink /proc/self/ns/${'$'}n 2>/dev/null)"; done
        me=$(awk '/^Uid:/{print $2}' ${'$'}s)
        all=0; other=0
        for p in /proc/[0-9]*; do u=$(stat -c %u "${'$'}p" 2>/dev/null) || continue; all=$((all+1)); [ "${'$'}u" = "${'$'}me" ] || other=$((other+1)); done
        echo "procs=${'$'}all"; echo "other_procs=${'$'}other"
        if [ -w / ] || [ -w /usr/bin ] || [ -w /etc ]; then echo "system_writable=yes"; else echo "system_writable=no"; fi
    """.trimIndent()

    fun parse(output: String): Map<String, String> =
        output.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()

    /**
     * Reads [output] of [SCRIPT] against the app's own process: [appUid] and [appNamespaces] (name → `/proc/self/ns`
     * link, from the app). [mode] is how the environment runs.
     */
    fun report(output: String, appUid: Int, appNamespaces: Map<String, String>, mode: LinuxMode): IsolationReport {
        val v = parse(output)
        val facts = ArrayList<IsolationFact>()
        val uid = v["uid"]?.toIntOrNull()
        if (uid != null) {
            val same = uid == appUid
            facts += IsolationFact("Kernel user", "uid $uid" + (v["told_uid"]?.takeIf { it != uid.toString() }?.let { " (it is told $it)" } ?: ""),
                if (same) "The app's own Android user: not a separate Android sandbox; it can signal the app and read what the app's user may" else "A user of its own, apart from the app's", !same)
        }
        v["cap_eff"]?.let { c ->
            val none = c.trimStart('0').isEmpty()
            facts += IsolationFact("Capabilities", if (none) "none" else "0x$c", if (none) "No kernel privileges, root's or any other" else "It holds kernel capabilities", none)
        }
        v["no_new_privs"]?.let { facts += IsolationFact("No new privileges", if (it == "1") "set" else "not set", if (it == "1") "Nothing it runs can gain privileges (setuid programs included)" else "A setuid program could raise its privileges", it == "1") }
        v["seccomp"]?.let { s ->
            val label = when (s) { "0" -> "off"; "1" -> "strict"; "2" -> "filter"; else -> s }
            facts += IsolationFact("System call filter", label, if (s == "2") "A seccomp filter limits its system calls (Android's app filter)" else "No system call filter", s == "2")
        }
        v["selinux"]?.takeIf { it.isNotBlank() }?.let { ctx ->
            val manager = listOf("magisk", ":su:", "ksu", "kernelsu").any { it in ctx.lowercase() }
            facts += IsolationFact("SELinux domain", ctx,
                when {
                    manager -> "The root manager's domain: SELinux does not confine it the way it confines an app"
                    "untrusted_app" in ctx -> "Android's app domain: confined like an app"
                    else -> "As the system labelled it"
                }, !manager)
        }
        val shared = appNamespaces.filter { (n, link) -> link.isNotEmpty() && v["ns_$n"] == link }.keys.sorted()
        val own = appNamespaces.filter { (n, link) -> link.isNotEmpty() && v["ns_$n"].orEmpty().isNotEmpty() && v["ns_$n"] != link }.keys.sorted()
        val absent = listOf("pid", "user", "ipc").filter { v["ns_$it"].isNullOrEmpty() && appNamespaces[it] == "" }
        if (shared.isNotEmpty() || own.isNotEmpty() || absent.isNotEmpty()) {
            val agentPid = v["ns_pid"].orEmpty()
            val appPid = appNamespaces["pid"].orEmpty()
            // Only what was measured: a namespace the kernel does not have is not a private one.
            val (pidMeaning, pidOwn) = when {
                agentPid.isEmpty() && appPid.isEmpty() -> "This kernel has no PID namespaces: process ids are the phone's, there is no private process table" to false
                agentPid.isNotEmpty() && agentPid == appPid -> "No private process table: process ids are the phone's" to false
                agentPid.isNotEmpty() && appPid.isNotEmpty() -> "It has a process table of its own" to true
                else -> "Whether it has a process table of its own could not be measured" to false
            }
            facts += IsolationFact("Namespaces",
                listOfNotNull(
                    if (own.isNotEmpty()) "own: ${own.joinToString()}" else "none of its own",
                    shared.takeIf { it.isNotEmpty() }?.let { "shared with the app: ${it.joinToString()}" },
                    absent.takeIf { it.isNotEmpty() }?.let { "not in this kernel: ${it.joinToString()}" },
                ).joinToString(" · "),
                pidMeaning, pidOwn)
        }
        val others = v["other_procs"]?.toIntOrNull()
        if (others != null) facts += IsolationFact("Other users' processes", if (others == 0) "hidden" else "$others visible",
            if (others == 0) "It cannot see root's or other users' processes (their environment, command lines, open files)" else "It can see other users' processes", others == 0)
        v["system_writable"]?.let { facts += IsolationFact("System files", if (it == "no") "read-only to it" else "writable", if (it == "no") "It cannot change the programs root runs in the environment" else "It can change system files of the environment", it == "no") }
        v["tracer"]?.toIntOrNull()?.takeIf { it != 0 }?.let { facts += IsolationFact("Traced", "by pid $it", "proot translates its paths by tracing it: that is not isolation", false) }
        val summary = when {
            facts.isEmpty() -> "The probe returned nothing: isolation unknown."
            mode == LinuxMode.PROOT -> "proot, without root: path translation only. The agent is the app's own user, with no isolation from the app."
            facts.firstOrNull { it.name == "Kernel user" }?.good == false -> "User-level isolation: the agent runs as the app's user without privileges, in a chroot whose system part it cannot change. It is not a separate Android sandbox."
            else -> "The agent runs as a separate user without privileges."
        }
        return IsolationReport(facts, summary)
    }
}
