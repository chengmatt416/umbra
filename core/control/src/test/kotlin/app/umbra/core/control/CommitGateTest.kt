package app.umbra.core.control

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir

/** The commit gate against real git: a bare repository stands in for the remote, clones for the people writing to it. */
class CommitGateTest {
    @TempDir
    lateinit var dir: Path

    private fun sh(cwd: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(cwd).redirectErrorStream(true)
            .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out.trim()
    }

    private val hostGit = CommandGit { args ->
        withContext(Dispatchers.IO) {
            val p = ProcessBuilder(listOf("git") + args).apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }.start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            GitRun(p.waitFor(), out, err)
        }
    }

    private lateinit var remote: File
    private lateinit var work: File

    private fun clone(name: String): File {
        val c = dir.resolve(name).toFile()
        sh(dir.toFile(), "clone", "-q", remote.path, c.path)
        sh(c, "config", "user.email", "t@example.com")
        sh(c, "config", "user.name", "Test")
        return c
    }

    private fun commit(repo: File, file: String, text: String): String {
        File(repo, file).writeText(text)
        sh(repo, "add", file)
        sh(repo, "commit", "-q", "-m", "change $file")
        return sh(repo, "rev-parse", "HEAD")
    }

    private fun setUp() {
        remote = dir.resolve("remote.git").toFile()
        sh(dir.toFile(), "init", "-q", "--bare", "-b", "main", remote.path)
        work = clone("work")
        sh(work, "checkout", "-q", "-b", "main")
        commit(work, "a.txt", "one")
        sh(work, "push", "-q", "origin", "main")
    }

    private fun remoteMain() = sh(remote, "rev-parse", "refs/heads/main")

    private fun gate(git: GitPort = hostGit) = CommitGate(ControlStore(dir.resolve("control")), git)

    @Test
    fun aFastForwardIsValidatedWithEvidenceThenWrittenAndReadBack(): Unit = runBlocking {
        setUp()
        val candidate = commit(work, "b.txt", "two")
        val g = gate()
        val p = g.propose(work.path)
        assertEquals(PromotionState.VALIDATED, p.state, "${p.problems}")
        val ev = assertNotNull(p.evidence)
        assertTrue(ev.fastForward)
        assertEquals(1, ev.commits.size)
        assertTrue("b.txt" in ev.diffStat, ev.diffStat)

        val done = g.promote(p.id, authorizedBy = "user")
        assertEquals(PromotionState.COMMITTED, done.state, done.note)
        assertEquals(candidate, done.observed)
        assertEquals(candidate, remoteMain())
        assertTrue(done.capability!!.used)
        assertTrue(g.verifyJournal().intact)
    }

    @Test
    fun aRemoteThatMovedAfterValidationIsNotOverwritten(): Unit = runBlocking {
        setUp()
        commit(work, "b.txt", "mine")
        val g = gate()
        val p = g.propose(work.path)
        val other = clone("other")
        val theirs = commit(other, "c.txt", "theirs")
        sh(other, "push", "-q", "origin", "main")

        val done = g.promote(p.id, authorizedBy = "user")
        assertEquals(PromotionState.REJECTED, done.state)
        assertTrue("moved since validation" in done.note.orEmpty(), done.note)
        assertEquals(theirs, remoteMain(), "their commit stays")
    }

    @Test
    fun rewritingTheRemotesHistoryNeedsItsOwnPermission(): Unit = runBlocking {
        setUp()
        val base = sh(work, "rev-parse", "HEAD")
        commit(work, "b.txt", "two")
        sh(work, "push", "-q", "origin", "main")
        val published = remoteMain()
        sh(work, "reset", "-q", "--hard", base)
        val rewrite = commit(work, "b.txt", "different")
        val g = gate()
        val p = g.propose(work.path)
        assertEquals(PromotionState.VALIDATED, p.state)
        assertFalse(p.evidence!!.fastForward)
        assertEquals(PromotionState.REFUSED, g.promote(p.id, authorizedBy = "user").state)
        assertEquals(published, remoteMain())

        val again = g.propose(work.path)
        assertEquals(PromotionState.COMMITTED, g.promote(again.id, authorizedBy = "user", allowRewrite = true).state)
        assertEquals(rewrite, remoteMain())
    }

    @Test
    fun aWriteWhoseReportIsLostIsSettledByReadingTheRemote(): Unit = runBlocking {
        setUp()
        // The write happens, but the answer never arrives (a dropped connection).
        val lostAnswer = object : GitPort by hostGit {
            override suspend fun pushWithLease(repo: String, remote: String, commit: String, ref: String, expectedOld: String?) {
                hostGit.pushWithLease(repo, remote, commit, ref, expectedOld)
                throw java.io.IOException("connection reset")
            }
        }
        val candidate = commit(work, "b.txt", "two")
        val g = gate(lostAnswer)
        val done = g.promote(g.propose(work.path).id, authorizedBy = "user")
        assertEquals(PromotionState.COMMITTED, done.state, done.note)
        assertEquals(candidate, remoteMain())
        assertTrue(g.recent.value.any { it.kind == "promotion.outcome_unknown" }, "the unknown outcome is on record")

        // The write never happens and the answer is lost too: the remote shows it did not happen.
        val neverSent = object : GitPort by hostGit {
            override suspend fun pushWithLease(repo: String, remote: String, commit: String, ref: String, expectedOld: String?) = throw java.io.IOException("timed out")
        }
        commit(work, "c.txt", "three")
        val g2 = gate(neverSent)
        val notWritten = g2.promote(g2.propose(work.path).id, authorizedBy = "user")
        assertEquals(PromotionState.REJECTED, notWritten.state)
        assertTrue("did not happen" in notWritten.note.orEmpty(), notWritten.note)
        assertEquals(candidate, remoteMain())
    }

    @Test
    fun aWriteTheAppDiedInIsSettledAtStartUp(): Unit = runBlocking {
        setUp()
        val candidate = commit(work, "b.txt", "two")
        val store = ControlStore(dir.resolve("control"))
        val g = CommitGate(store, hostGit)
        val p = g.propose(work.path)
        // The app wrote, then died before its receipt: the record still says it was writing.
        sh(work, "push", "-q", "origin", "main")
        store.docs("promotions", Promotion.serializer(), Promotion::id).put(p.copy(state = PromotionState.COMMITTING))

        val restarted = CommitGate(ControlStore(dir.resolve("control")), hostGit)
        restarted.recover()
        val settled = restarted[p.id]!!
        assertEquals(PromotionState.COMMITTED, settled.state, settled.note)
        assertEquals(candidate, settled.observed)
    }

    @Test
    fun nothingToPublishOrAnUnreadableRemoteIsRefusedAtValidation(): Unit = runBlocking {
        setUp()
        val g = gate()
        val same = g.propose(work.path)
        assertEquals(PromotionState.REFUSED, same.state)
        assertTrue(same.problems.any { "nothing to publish" in it }, "${same.problems}")
        val nowhere = g.propose(work.path, remote = "no-such-remote")
        assertEquals(PromotionState.REFUSED, nowhere.state)
        assertTrue(nowhere.problems.any { "could not be read" in it }, "${nowhere.problems}")
    }
}
