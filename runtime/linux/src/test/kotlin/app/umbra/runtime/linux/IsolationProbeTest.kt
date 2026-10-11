package app.umbra.runtime.linux

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsolationProbeTest {
    private val appNs = mapOf("mnt" to "mnt:[4026531841]", "pid" to "pid:[4026531836]", "net" to "net:[4026531840]", "user" to "user:[4026531837]")

    @Test
    fun aChrootAgentIsReportedAsTheAppsUserWithoutPrivilegesNeverAsASandbox() {
        val out = """
            told_uid=10221
            uid=10221
            gid=10221
            cap_eff=0000000000000000
            cap_bnd=0000000000000000
            no_new_privs=1
            seccomp=0
            tracer=0
            selinux=u:r:magisk:s0
            ns_mnt=mnt:[4026532900]
            ns_pid=pid:[4026531836]
            ns_net=net:[4026531840]
            ns_user=user:[4026531837]
            procs=9
            other_procs=0
            system_writable=no
        """.trimIndent()
        val r = IsolationProbe.report(out, appUid = 10221, appNamespaces = appNs, mode = LinuxMode.CHROOT)
        val by = r.facts.associateBy { it.name }
        assertFalse(by.getValue("Kernel user").good, "the app's own user is not isolation from the app")
        assertTrue(by.getValue("Capabilities").good && by.getValue("No new privileges").good && by.getValue("Other users' processes").good && by.getValue("System files").good)
        assertFalse(by.getValue("SELinux domain").good, "the root manager's domain does not confine it")
        assertFalse(by.getValue("Namespaces").good, "no private process table")
        assertEquals("own: mnt · shared with the app: net, pid, user", by.getValue("Namespaces").value)

        // A kernel without PID, user or IPC namespaces (the emulator's): absent on both sides is not "its own".
        val noPidNs = IsolationProbe.report(out.lines().filterNot { it.startsWith("ns_pid") || it.startsWith("ns_user") }.joinToString("\n"), 10221,
            mapOf("mnt" to "mnt:[1]", "net" to "net:[4026531840]", "pid" to "", "user" to "", "ipc" to ""), LinuxMode.CHROOT)
        val ns = noPidNs.facts.single { it.name == "Namespaces" }
        assertFalse(ns.good)
        assertTrue("no PID namespaces" in ns.meaning && "not in this kernel: pid, user, ipc" in ns.value, ns.toString())
        assertTrue(r.sameUserAsApp)
        assertTrue("not a separate Android sandbox" in r.summary)
        assertFalse(r.facts.any { "sandboxed" in it.meaning.lowercase() })
    }

    @Test
    fun underProotTheKernelsAccountWinsOverWhatTheProcessIsTold() {
        val out = "told_uid=0\nuid=10221\ncap_eff=0000000000000000\nno_new_privs=0\nseccomp=2\ntracer=4242\nselinux=u:r:untrusted_app:s0:c221,c256,c512,c768\nns_pid=pid:[4026531836]\nother_procs=0\nsystem_writable=yes"
        val r = IsolationProbe.report(out, 10221, appNs, LinuxMode.PROOT)
        val by = r.facts.associateBy { it.name }
        assertEquals("uid 10221 (it is told 0)", by.getValue("Kernel user").value)
        assertFalse(by.getValue("Traced").good)
        assertTrue(by.getValue("SELinux domain").good)
        assertTrue("no isolation from the app" in r.summary)
    }

    @Test
    fun theScriptRunsAndReportsThisProcess() {
        // On this machine (Linux or macOS) the script must at least run and print its keys without failing.
        val p = ProcessBuilder("bash", "-c", IsolationProbe.SCRIPT).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        val v = IsolationProbe.parse(out)
        assertTrue("told_uid" in v && "system_writable" in v, out)
        assertEquals(File("/proc/self/status").exists(), "uid" in v, "the kernel's account where there is a /proc")
    }
}
