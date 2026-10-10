package app.umbra.core.control

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir

/** The Repository Hub against real git: a bare repository as the remote. */
class RepositoryHubTest {
    @TempDir
    lateinit var dir: Path

    private val git = CommandGit { args ->
        withContext(Dispatchers.IO) {
            val p = ProcessBuilder(listOf("git") + args).apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }.start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            GitRun(p.waitFor(), out, err)
        }
    }

    private fun sh(cwd: File, vararg args: String) {
        val p = ProcessBuilder(listOf("git") + args).directory(cwd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { out }
    }

    private fun remote(): File {
        val bare = dir.resolve("origin.git").toFile()
        sh(dir.toFile(), "init", "-q", "--bare", "-b", "main", bare.path)
        val seed = dir.resolve("seed").toFile()
        sh(dir.toFile(), "clone", "-q", bare.path, seed.path)
        sh(seed, "config", "user.email", "t@example.com"); sh(seed, "config", "user.name", "T")
        sh(seed, "checkout", "-q", "-b", "main")
        File(seed, "a.txt").writeText("a"); sh(seed, "add", "a.txt"); sh(seed, "commit", "-q", "-m", "a"); sh(seed, "push", "-q", "origin", "main")
        return bare
    }

    @Test
    fun cloneRegistersAndPreflightSaysWhatIsTrue(): Unit = runBlocking {
        val bare = remote()
        val hub = RepositoryHub(ControlStore(dir.resolve("control")))
        val r = hub.clone(bare.path, dir.resolve("work").toString(), "proj", git)
        assertEquals(bare.path, r.origin)
        assertEquals(IntegrationPolicy.FAST_FORWARD_ONLY, r.policy, "the safe policy by default")

        val ok = hub.preflight(r.id, git).preflight!!
        assertTrue(ok.ok, "${ok.checks}")
        assertEquals("main", ok.branch)
        assertTrue(ok.checks.any { it.name == "Remote “origin”" && it.passed })

        File(r.location, "b.txt").writeText("dirty")
        val dirty = hub.preflight(r.id, git).preflight!!
        assertFalse(dirty.ok)
        assertTrue(dirty.checks.single { it.name == "Working tree" }.detail.startsWith("1 uncommitted"))

        // Registering the same checkout again is the same record; forgetting keeps the files.
        assertEquals(r.id, hub.register(r.location, git).id)
        hub.forget(r.id)
        assertTrue(File(r.location, "a.txt").exists())
    }

    @Test
    fun badUrlsNamesAndNonRepositoriesAreRefused(): Unit = runBlocking {
        val hub = RepositoryHub(ControlStore(dir.resolve("control")))
        assertFailsWith<RepositoryException> { hub.clone("--upload-pack=touch /tmp/x", dir.toString(), "x", git) }
        assertFailsWith<RepositoryException> { hub.clone("ext::sh -c touch% /tmp/pwned", dir.toString(), "x", git) }
        assertFailsWith<RepositoryException> { hub.clone(remote().path, dir.toString(), "../escape", git) }
        assertFailsWith<RepositoryException> { hub.register(dir.toString(), git) }
    }

    @Test
    fun theCommitGateFollowsTheIntegrationPolicy(): Unit = runBlocking {
        val bare = remote()
        val store = ControlStore(dir.resolve("control"))
        val hub = RepositoryHub(store)
        val r = hub.clone(bare.path, dir.resolve("work").toString(), "proj", git)
        val work = File(r.location)
        sh(work, "config", "user.email", "t@example.com"); sh(work, "config", "user.name", "T")
        // A diverging history: the next promotion would rewrite the remote.
        sh(work, "reset", "-q", "--hard", "HEAD")
        File(work, "c.txt").writeText("c"); sh(work, "add", "c.txt"); sh(work, "commit", "-q", "--amend", "-m", "rewritten")
        val gate = CommitGate(store, git, rewritePermitted = { hub.rewritePermitted(it) })
        val p = gate.propose(r.location)
        assertFalse(p.evidence!!.fastForward)
        val refused = gate.promote(p.id, authorizedBy = "user", allowRewrite = true)
        assertEquals(PromotionState.REFUSED, refused.state)
        assertTrue(refused.problems.any { "fast-forward only" in it }, "${refused.problems}")

        hub.setPolicy(r.id, IntegrationPolicy.REWRITE_WITH_PERMISSION)
        val again = gate.propose(r.location)
        assertEquals(PromotionState.COMMITTED, gate.promote(again.id, authorizedBy = "user", allowRewrite = true).state)
    }
}
