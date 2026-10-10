package app.umbra.runtime.linux

import app.umbra.platform.api.exec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/** The Debian guest on the real Android 16 emulator: install, mount, run as the app user, unmount. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RootChrootRuntimeLiveTest {
    private var runtime: RootChrootRuntime? = null

    private fun guest(): RootChrootRuntime = runtime ?: runBlocking { LiveGuest.installed() }.also { runtime = it }

    @AfterAll
    fun unmount() {
        runtime?.let { runBlocking { it.unmount() } }
    }

    @Test
    @Order(1)
    fun theArchiveInstallsAndIsRecognised(): Unit = runBlocking {
        val guest = guest()
        guest.unmount()
        assertEquals(RuntimeState.INSTALLED, guest.status())
    }

    @Test
    @Order(2)
    fun mountingIsRealAndIdempotent(): Unit = runBlocking {
        val guest = guest()
        guest.mount()
        guest.mount()
        assertEquals(RuntimeState.MOUNTED, guest.status())
        val mounts = LiveGuest.root().exec("cat /proc/mounts").stdout
        for (point in listOf("/proc", "/dev", "/tmp", "/workspace", "/home/agent")) {
            assertTrue(mounts.lines().any { it.split(' ').getOrNull(1) == guest.layout.rootfs + point }, "$point is mounted: $mounts")
        }
    }

    @Test
    @Order(3)
    fun theToolchainRunsInsideTheGuest(): Unit = runBlocking {
        val guest = guest()
        guest.mount()
        val versions = guest.versions()
        assertTrue(versions != null, "versions() must work on a mounted guest")
        assertEquals("1.3.14", versions!!["bun"])
        assertEquals("18.6.1", versions["omp"])
        assertTrue(versions["debian"]!!.startsWith("13"), "Debian 13: $versions")
        assertTrue(versions["git"]!!.startsWith("2."), "git: $versions")
    }

    @Test
    @Order(4)
    fun commandsRunAsTheAppUserWithoutPrivileges(): Unit = runBlocking {
        val guest = guest()
        guest.mount()
        val out = guest.exec(listOf("bash", "-c", "id -u; id -g; id -G; grep -E '^(CapEff|CapBnd|NoNewPrivs):' /proc/self/status; whoami; echo \$HOME; umask; pwd"))
        assertTrue(out.ok, out.combined)
        val lines = out.stdout.lines()
        assertEquals(guest.layout.uid.toString(), lines[0], "runs as the app uid: $lines")
        assertEquals(guest.layout.gid.toString(), lines[1])
        assertEquals("${guest.layout.gid} 3003", lines[2], "its own group plus the inet group, nothing else: $lines")
        assertTrue(lines.any { it.matches(Regex("CapEff:\\s+0+")) }, "no effective capabilities: $lines")
        assertTrue(lines.any { it.matches(Regex("CapBnd:\\s+0+")) }, "empty bounding set: $lines")
        assertTrue(lines.any { it.matches(Regex("NoNewPrivs:\\s+1")) }, "no_new_privs is set: $lines")
        assertEquals("agent", lines[lines.indexOfFirst { it.startsWith("NoNewPrivs") } + 1])
        assertEquals("/home/agent", lines[lines.indexOfFirst { it.startsWith("NoNewPrivs") } + 2])
        assertEquals("0022", lines[lines.indexOfFirst { it.startsWith("NoNewPrivs") } + 3], "files made in the guest are not writable by everyone: $lines")
        assertEquals("/workspace", lines.last { it.isNotBlank() })
    }

    @Test
    @Order(5)
    fun filesTheGuestWritesBelongToTheAppAndAreVisibleOutside(): Unit = runBlocking {
        val guest = guest()
        guest.mount()
        val marker = "from-guest-${System.nanoTime()}"
        val out = guest.exec(listOf("bash", "-c", "echo $marker > /workspace/probe.txt && stat -c '%u:%g' /workspace/probe.txt"))
        assertEquals("${guest.layout.uid}:${guest.layout.gid}", out.stdout.trim(), out.combined)
        val root = LiveGuest.root()
        val outside = root.exec("cat ${guest.layout.workspaces}/probe.txt").stdout
        assertEquals(marker, outside.trim(), "the app's workspace folder holds what the guest wrote")
        guest.exec(listOf("rm", "-f", "/workspace/probe.txt"))
    }

    @Test
    @Order(6)
    fun theGuestReachesTheInternet(): Unit = runBlocking {
        val guest = guest()
        guest.mount()
        val out = guest.exec(listOf("bash", "-c", "curl -sS -m 25 -o /dev/null -w '%{http_code}' https://deb.debian.org/debian/dists/trixie/Release"), timeoutMs = 60_000)
        assertEquals("200", out.stdout.trim(), "DNS, TLS certificates and routing work from the guest: ${out.combined}")
    }

    @Test
    @Order(7)
    fun aRootCommandInsideTheGuestCanInstallPacks(): Unit = runBlocking {
        val guest = guest()
        guest.mount()
        val out = guest.exec(listOf("bash", "-c", "id -u; umask; umbra-pack list"), asRoot = true)
        assertEquals("0", out.stdout.lines().first(), out.combined)
        assertEquals("0022", out.stdout.lines()[1], "root's files in the guest are not writable by everyone either: ${out.stdout}")
        assertTrue(out.stdout.contains("dev"), "the pack helper lists the packs: ${out.stdout}")
    }

    @Test
    @Order(7)
    fun filesWrittenFromOutsideBelongToTheAppUserTogetherWithTheirFolders(): Unit = runBlocking {
        val guest = guest()
        guest.mount()
        guest.exec(listOf("rm", "-rf", "/home/agent/.umbra-probe"), asRoot = true)
        guest.writeFile("/home/agent/.umbra-probe/nested/deeper/file.txt", "secret-ish", mode = "600")
        val owners = guest.exec(listOf("stat", "-c", "%u %a", "/home/agent/.umbra-probe", "/home/agent/.umbra-probe/nested", "/home/agent/.umbra-probe/nested/deeper", "/home/agent/.umbra-probe/nested/deeper/file.txt"))
        val uid = guest.layout.uid
        assertEquals(listOf("$uid 755", "$uid 755", "$uid 755", "$uid 600"), owners.stdout.lines().filter { it.isNotBlank() }, owners.combined)
        // The agent can work next to the file, and read it back.
        val agent = guest.exec(listOf("bash", "-c", "touch /home/agent/.umbra-probe/nested/deeper/other && cat /home/agent/.umbra-probe/nested/deeper/file.txt"))
        assertEquals("secret-ish", agent.stdout.trim(), agent.combined)
        guest.exec(listOf("rm", "-rf", "/home/agent/.umbra-probe"), asRoot = true)
    }

    @Test
    @Order(7)
    fun theAgentCannotChangeTheSystemPartOfTheGuest(): Unit = runBlocking {
        val guest = guest()
        guest.mount()
        // Root enters the guest by running programs from it, so these must belong to root and be closed to everyone else.
        val paths = listOf("/", "/usr", "/usr/bin", "/usr/bin/setpriv", "/usr/bin/env", "/bin/bash", "/etc", "/etc/passwd", "/opt", "/opt/umbra", "/opt/umbra/bin", "/usr/local/bin", "/lib", "/var", "/opt/bun/install/global/node_modules/@oh-my-pi/pi-coding-agent/dist/cli.js")
        val facts = guest.exec(listOf("stat", "-L", "-c", "%a %u %n") + paths, asRoot = true)
        for (line in facts.stdout.lines().filter { it.isNotBlank() }) {
            val (mode, owner, path) = line.split(' ', limit = 3)
            assertEquals("0", owner, "$path belongs to root")
            assertEquals(0, mode.toInt(8) and 0b000_010_010, "$path is not writable by group or others (mode $mode)")
        }
        // And the agent cannot add to it, move it, or replace what runs as root.
        val attempts = listOf(
            "touch /agent-was-here", "mkdir /agent-dir", "ln -s /bin /agent-link", "touch /usr/bin/agent-was-here", "touch /etc/agent-was-here",
            "echo x >> /usr/bin/setpriv", "echo x >> /opt/umbra/bin/umbra-pack", "touch /opt/agent-was-here",
        )
        for (a in attempts) {
            val out = guest.exec(listOf("bash", "-c", "$a 2>&1; echo \"exit=\$?\""))
            assertFalse(out.stdout.contains("exit=0"), "the agent must not be able to run '$a': ${out.stdout}")
        }
        // Other users' processes are invisible, so their command lines (the helpers' tokens are on theirs) cannot be read.
        val seen = guest.exec(listOf("bash", "-c", "ps -A -o user= | sort -u"))
        val users = seen.stdout.lines().filter { it.isNotBlank() }
        assertEquals(listOf("agent"), users, "the agent sees only its own user's processes: ${seen.combined}")
        assertTrue(guest.exec(listOf("bash", "-c", "cat /proc/1/cmdline")).stdout.isBlank(), "init's command line is not readable")
        // Nothing outside the places that are the agent's can be written by everyone.
        val open = guest.exec(listOf("bash", "-c", "find / -xdev \\( -path /tmp -o -path /var/tmp -o -path /home/agent -o -path /workspace -o -path /dev -o -path /proc -o -path /sys \\) -prune -o ! -type l -perm -0002 ! \\( -type d -perm -1000 \\) -print | head -20"), asRoot = true)
        assertEquals("", open.stdout.trim(), "world-writable entries in the system part of the guest")
    }

    @Test
    @Order(7)
    fun theAppsOwnFilesAreSyncedAsRootOnlyWhenTheyChanged(): Unit = runBlocking {
        val guest = guest()
        guest.mount()
        guest.exec(listOf("rm", "-rf", "/opt/umbra/sync-probe", "/opt/umbra/.sync-probe-version"), asRoot = true)
        val first = GuestFileSet(listOf(
            GuestFile("/opt/umbra/sync-probe/bin/tool", "#!/bin/sh\necho first\n", "755"),
            GuestFile("/opt/umbra/sync-probe/list.apt", "curl\n"),
        ))
        val marker = "/opt/umbra/.sync-probe-version"
        assertTrue(guest.syncFiles(first, marker), "an empty environment gets the files")
        val facts = guest.exec(listOf("stat", "-c", "%u:%g %a", "/opt/umbra/sync-probe/bin/tool", "/opt/umbra/sync-probe/list.apt", marker))
        assertEquals(listOf("0:0 755", "0:0 644", "0:0 644"), facts.stdout.lines().filter { it.isNotBlank() }, facts.combined)
        // The agent can run the script but cannot change it.
        assertEquals("first", guest.exec(listOf("/opt/umbra/sync-probe/bin/tool")).stdout.trim())
        val tamper = guest.exec(listOf("bash", "-c", "echo hacked >> /opt/umbra/sync-probe/bin/tool"))
        assertFalse(tamper.ok, "the app user must not be able to change the file")

        assertFalse(guest.syncFiles(first, marker), "the same files are not written again")
        guest.exec(listOf("bash", "-c", "echo local-change >> /opt/umbra/sync-probe/list.apt"), asRoot = true)
        assertFalse(guest.syncFiles(first, marker), "a matching version means nothing is touched")

        val second = GuestFileSet(listOf(
            GuestFile("/opt/umbra/sync-probe/bin/tool", "#!/bin/sh\necho second\n", "755"),
            GuestFile("/opt/umbra/sync-probe/list.apt", "curl\njq\n"),
        ))
        assertTrue(guest.syncFiles(second, marker), "a newer version replaces everything")
        assertEquals("second", guest.exec(listOf("/opt/umbra/sync-probe/bin/tool")).stdout.trim())
        assertEquals("curl\njq", guest.readFile("/opt/umbra/sync-probe/list.apt")?.trim())
        assertEquals(second.version, guest.readFile(marker)?.trim())
        guest.exec(listOf("rm", "-rf", "/opt/umbra/sync-probe", marker), asRoot = true)
    }

    @Test
    @Order(8)
    fun unmountingLeavesNothingMountedAndKeepsTheFiles(): Unit = runBlocking {
        val guest = guest()
        guest.mount()
        guest.unmount()
        assertEquals(RuntimeState.INSTALLED, guest.status())
        val mounts = LiveGuest.root().exec("cat /proc/mounts").stdout
        assertFalse(mounts.lines().any { it.contains(guest.layout.rootfs) }, "no mount may survive: ${mounts.lines().filter { it.contains(guest.layout.rootfs) }}")
        // And it mounts again from the unmounted state.
        guest.mount()
        assertEquals(RuntimeState.MOUNTED, guest.status())
        guest.unmount()
    }
}
