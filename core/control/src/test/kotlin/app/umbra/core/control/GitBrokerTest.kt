package app.umbra.core.control

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir

/**
 * The git credential broker against a real private server (git http-backend over HTTPS with Basic authentication).
 * Here "root" and "the agent" are the same user of this machine, so this checks what the broker does with git; that
 * the agent cannot read root's processes is the Linux environment's part, checked on the device.
 */
class GitBrokerTest {
    @TempDir
    lateinit var dir: Path

    private val token = "tok_" + java.util.UUID.randomUUID().toString().replace("-", "")
    private val agentHome by lazy { dir.resolve("agent-home").toFile().apply { mkdirs() } }
    private val stage by lazy { dir.resolve("root").toFile().apply { mkdirs() } }
    private val tmp by lazy { dir.resolve("tmp").toFile().apply { mkdirs() } }

    /** The agent's git: its own home and configuration, which it controls. */
    private fun agentEnv() = mapOf(
        "PATH" to System.getenv("PATH"), "HOME" to agentHome.path, "GIT_CONFIG_NOSYSTEM" to "1", "GIT_TERMINAL_PROMPT" to "0",
        "GIT_AUTHOR_NAME" to "Agent", "GIT_AUTHOR_EMAIL" to "agent@example.com", "GIT_COMMITTER_NAME" to "Agent", "GIT_COMMITTER_EMAIL" to "agent@example.com",
        "TMPDIR" to tmp.path,
    )

    private fun exec(argv: List<String>, env: Map<String, String>, stdin: String? = null, cwd: File? = null): GitRun {
        val pb = ProcessBuilder(argv).apply { cwd?.let { directory(it) } }
        pb.environment().apply { clear(); putAll(env) }
        val p = pb.start()
        p.outputStream.use { o -> stdin?.let { o.write(it.toByteArray()) } }
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        return GitRun(p.waitFor(), out, err)
    }

    private fun git(cwd: File, args: List<String>): String {
        val r = exec(listOf("git") + args, agentEnv(), cwd = cwd)
        check(r.exit == 0) { "git ${args.joinToString(" ")}: ${r.stderr}" }
        return r.stdout.trim()
    }

    private val agentGit = CommandGit { args -> withContext(Dispatchers.IO) { exec(listOf("git") + args, agentEnv()) } }

    private fun broker(store: ControlStore, credentials: GitCredentials, tokenOf: (String) -> String? = { token }) = BrokeredGit(
        local = agentGit, credentials = credentials, token = tokenOf,
        root = { argv, env, stdin -> withContext(Dispatchers.IO) { exec(argv, env + ("TMPDIR" to tmp.path), stdin) } },
        agent = { argv -> withContext(Dispatchers.IO) { exec(argv, agentEnv()) } },
        stageParent = stage.path, agentTemp = tmp.path,
    )

    private class Rig(val server: GitHttpsServer, val bare: File, val work: File, val url: String)

    private fun rig(block: suspend (Rig) -> Unit) {
        GitHttpsServer(dir.resolve("server").toFile(), "x-access-token", token).use { server ->
            val bare = server.repository("repo") { cwd, args -> git(cwd, args) }
            val url = server.url("repo")
            // The agent's checkout came from somewhere without the token (here: the files themselves).
            val work = dir.resolve("work").toFile()
            git(dir.toFile(), listOf("clone", "-q", bare.path, work.path))
            git(work, listOf("remote", "set-url", "origin", url))
            git(work, listOf("config", "http.sslCAInfo", File(dir.toFile(), "ca.pem").apply { writeText(server.caPem + "\n") }.path))
            // A hostile checkout: it would send the remote somewhere else, run a hook on push, and answer for credentials.
            val hooks = File(dir.toFile(), "hooks").apply { mkdirs() }
            File(hooks, "pre-push").apply { writeText("#!/bin/sh\ntouch ${File(dir.toFile(), "hook-ran").path}\n"); setExecutable(true) }
            git(work, listOf("config", "core.hooksPath", hooks.path))
            git(work, listOf("config", "credential.helper", "!f() { echo password=from-the-agent; }; f"))
            git(agentHome, listOf("config", "--global", "url.https://127.0.0.1:9/elsewhere.insteadOf", url))
            runBlocking { block(Rig(server, bare, work, url)) }
        }
    }

    @Test
    fun aBrokeredPushPublishesWithATokenTheCheckoutNeverHolds(): Unit = rig { r ->
        val store = ControlStore(dir.resolve("control"))
        val credentials = GitCredentials(store)
        val c = credentials.add("test forge", r.url + ".git/", "x-access-token", setOf(GitOperation.READ, GitOperation.PUBLISH), r.server.caPem)
        assertEquals(r.url, c.url, "stored normalized")

        // The agent's own push goes nowhere: its insteadOf sends it elsewhere, and it has no token anyway.
        File(r.work, "b.txt").writeText("from the agent\n")
        git(r.work, listOf("add", "b.txt")); git(r.work, listOf("commit", "-q", "-m", "agent work"))
        val candidate = git(r.work, listOf("rev-parse", "HEAD"))
        assertTrue(exec(listOf("git", "-C", r.work.path, "push", "origin", "HEAD:main"), agentEnv()).exit != 0)

        val gate = CommitGate(store, broker(store, credentials))
        val p = gate.propose(r.work.path, remote = r.url, branch = "main")
        assertEquals(PromotionState.VALIDATED, p.state, p.problems.joinToString())
        val done = gate.promote(p.id, authorizedBy = "user")
        assertEquals(PromotionState.COMMITTED, done.state, done.note)
        assertEquals(candidate, git(r.bare, listOf("rev-parse", "refs/heads/main")), "the server has the candidate")

        assertFalse(File(dir.toFile(), "hook-ran").exists(), "the checkout's hooks never ran")
        assertTrue(r.server.requests.any { "git-receive-pack" in it && it.endsWith("authorized") }, "${r.server.requests}")
        assertEquals(emptyList(), stage.listFiles()!!.toList(), "root's staging folder is gone")
        assertEquals(emptyList(), tmp.listFiles()!!.filter { it.name.startsWith("umbra-") }, "the candidate bundle is gone")
        assertEquals("", git(r.work, listOf("for-each-ref", BrokeredGit.BUNDLE_REF)), "the temporary ref is gone")
        val record = credentials[c.id]!!
        assertTrue(record.uses >= 3, "validation, the lease check, the push and the read-back are each a use")
        assertTrue(credentials.recent.value.any { it.kind == "git.brokered" && it.data.toString().contains("accepted") }, "${credentials.recent.value}")
        // The token is in no journal, record or file of the control store.
        assertTrue(dir.resolve("control").toFile().walk().filter { it.isFile }.none { token in it.readText() })
        assertTrue(credentials.verifyJournal().intact)
    }

    @Test
    fun aMovedRemoteIsNotOverwrittenAndAWrongTokenFailsWithoutShowingIt(): Unit = rig { r ->
        val store = ControlStore(dir.resolve("control"))
        val credentials = GitCredentials(store)
        credentials.add("forge", r.url, "x-access-token", setOf(GitOperation.READ, GitOperation.PUBLISH), r.server.caPem)
        val brokered = broker(store, credentials)
        File(r.work, "c.txt").writeText("c\n")
        git(r.work, listOf("add", "c.txt")); git(r.work, listOf("commit", "-q", "-m", "c"))
        val candidate = git(r.work, listOf("rev-parse", "HEAD"))
        val old = brokered.lsRemote(r.work.path, r.url, "refs/heads/main")!!

        // Someone else writes main first.
        val other = dir.resolve("other").toFile()
        git(dir.toFile(), listOf("clone", "-q", r.bare.path, other.path))
        File(other, "d.txt").writeText("d\n"); git(other, listOf("add", "d.txt")); git(other, listOf("commit", "-q", "-m", "d"))
        git(other, listOf("push", "-q", "origin", "HEAD:main"))
        val theirs = git(other, listOf("rev-parse", "HEAD"))

        assertFailsWith<LeaseRejected> { brokered.pushWithLease(r.work.path, r.url, candidate, "refs/heads/main", old) }
        assertEquals(theirs, git(r.bare, listOf("rev-parse", "refs/heads/main")), "their write stands")

        val wrong = broker(store, credentials) { "not-the-token-" + it }
        val e = assertFailsWith<IllegalStateException> { wrong.pushWithLease(r.work.path, r.url, candidate, "refs/heads/main", theirs) }
        assertFalse("not-the-token" in e.message.orEmpty(), e.message)
        assertTrue(r.server.requests.any { it.endsWith("wrong") })
    }

    @Test
    fun fetchingThroughTheBrokerGivesTheCheckoutTheRemotesCommits(): Unit = rig { r ->
        val store = ControlStore(dir.resolve("control"))
        val credentials = GitCredentials(store)
        val c = credentials.add("forge", r.url, "x-access-token", setOf(GitOperation.READ), r.server.caPem)
        val other = dir.resolve("other").toFile()
        git(dir.toFile(), listOf("clone", "-q", r.bare.path, other.path))
        File(other, "e.txt").writeText("e\n"); git(other, listOf("add", "e.txt")); git(other, listOf("commit", "-q", "-m", "e"))
        git(other, listOf("push", "-q", "origin", "HEAD:main"))
        val head = git(other, listOf("rev-parse", "HEAD"))

        val sha = broker(store, credentials).fetchThroughBroker(r.work.path, c.id, "main")
        assertEquals(head, sha)
        assertEquals(head, git(r.work, listOf("rev-parse", "refs/remotes/umbra-broker/main")))
        assertTrue(tmp.listFiles()!!.none { it.name.startsWith("umbra-broker-out.") }, "the fetch folder is gone")
        // Read-only: it cannot publish, and nothing is sent.
        assertFailsWith<WriteNotSent> { broker(store, credentials).pushWithLease(r.work.path, r.url, head, "refs/heads/main", head) }
    }

    @Test
    fun aCredentialReachesOneUrlAndOnlyItsOperations() {
        assertEquals("https://github.com/o/r", GitCredentials.normalize("https://GitHub.com/o/r.git/"))
        for (bad in listOf("http://github.com/o/r", "https://user:pw@github.com/o/r", "https://github.com/o/r?x=1", "ssh://github.com/o/r", "https://github.com/", "https://github.com/o/../r")) {
            assertFailsWith<GitCredentialException>(bad) { GitCredentials.normalize(bad) }
        }
        val credentials = GitCredentials(ControlStore(dir.resolve("control")))
        val c = credentials.add("forge", "https://github.com/o/r", "x-access-token", setOf(GitOperation.READ, GitOperation.PUBLISH), null)
        assertEquals(c.id, credentials.forRemote("https://github.com/o/r.git")?.id)
        assertNull(credentials.forRemote("origin"), "a remote name never matches")
        assertNull(credentials.forRemote("https://github.com/o/other"))
        assertFailsWith<GitCredentialException> { credentials.add("again", "https://github.com/o/r", "x", setOf(GitOperation.READ), null) }
        credentials.setOperations(c.id, setOf(GitOperation.READ))
        assertFailsWith<GitCredentialException> { credentials.authorize(c.id, GitOperation.PUBLISH) }
        assertEquals(c.id, credentials.authorize(c.id, GitOperation.READ).id)

        // Without root (proot) root is the agent's own user: the broker refuses before anything runs.
        val noRoot = BrokeredGit(agentGit, credentials, { token }, root = { _, _, _ -> error("nothing may run") }, agent = { error("nothing may run") },
            unavailable = { "Brokered git needs root" })
        val e = assertFailsWith<GitCredentialException> { runBlocking { noRoot.lsRemote("/nowhere", "https://github.com/o/r", "refs/heads/main") } }
        assertTrue("needs root" in e.message.orEmpty())
    }
}
