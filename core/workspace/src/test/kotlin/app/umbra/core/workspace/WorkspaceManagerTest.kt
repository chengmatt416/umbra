package app.umbra.core.workspace

import app.umbra.core.model.Clock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class WorkspaceManagerTest {
    @TempDir
    lateinit var dir: Path

    private val time = AtomicLong(1_000_000)
    private val clock = Clock { time.get() }
    private val day = 24L * 60 * 60 * 1000

    private fun layout() = WorkspaceLayout(dir.resolve("ws"), guestRoot = "/workspace")
    private fun manager() = WorkspaceManager(layout(), clock)

    @Test
    fun createsProjectDirectoriesWithReadableUniqueIds(): Unit = runBlocking {
        val m = manager()
        val a = m.create("My Android App!")
        val b = m.create("My Android App!")
        assertEquals("my-android-app", a.id)
        assertEquals("my-android-app-2", b.id)
        assertEquals("My Android App!", a.name)
        assertTrue(layout().projectDir("my-android-app").exists())
        assertEquals(setOf("my-android-app", "my-android-app-2"), m.list().map { it.id }.toSet())
    }

    @Test
    fun weirdNamesStillProduceValidIds(): Unit = runBlocking {
        val m = manager()
        assertEquals("workspace", m.create("!!!").id)
        assertEquals("a-b", m.create("  a/b  ").id)
        assertTrue(m.create("x".repeat(200)).id.length <= 40)
    }

    @Test
    fun listIsOrderedByMostRecentlyUsed(): Unit = runBlocking {
        val m = manager()
        m.create("one"); time.addAndGet(10)
        m.create("two"); time.addAndGet(10)
        m.create("three"); time.addAndGet(10)
        m.touch("one")
        assertEquals(listOf("one", "three", "two"), m.list().map { it.id })
    }

    @Test
    fun registrySurvivesANewManagerInstance(): Unit = runBlocking {
        manager().create("keep", WorkspaceKind.REPOSITORY, origin = "https://github.com/a/b.git", permissions = WorkspacePermissions.READ_ONLY)
        val loaded = manager().get("keep")
        assertNotNull(loaded)
        assertEquals(WorkspaceKind.REPOSITORY, loaded.kind)
        assertEquals("https://github.com/a/b.git", loaded.origin)
        assertEquals(WorkspacePermissions.READ_ONLY, loaded.permissions)
    }

    @Test
    fun attachedSessionsBlockDeletionUnlessForced(): Unit = runBlocking {
        val m = manager()
        val ws = m.create("busy", sessionId = "s1")
        assertEquals(setOf("s1"), ws.sessionIds)
        assertFailsWith<WorkspaceInUseException> { m.delete("busy") }
        assertTrue(layout().projectDir("busy").exists())

        m.detachSession("busy", "s1")
        assertTrue(m.delete("busy"))
        assertFalse(layout().projectDir("busy").exists())
        assertNull(m.get("busy"))
        assertFalse(m.delete("busy"))

        m.create("forced", sessionId = "s2")
        assertTrue(m.delete("forced", force = true))
    }

    @Test
    fun onlyActiveSessionsBlockDeletingWhenIdle(): Unit = runBlocking {
        val m = manager()
        m.create("shared", sessionId = "done")
        m.attachSession("shared", "working")
        val refused = assertFailsWith<WorkspaceInUseException> { m.deleteIfIdle("shared") { it == "working" } }
        assertTrue("working" in refused.message!!)
        assertFalse("done" in refused.message!!)
        assertTrue(layout().projectDir("shared").exists())

        assertTrue(m.deleteIfIdle("shared") { false })
        assertFalse(layout().projectDir("shared").exists())
        assertNull(m.get("shared"))
        assertFalse(m.deleteIfIdle("shared") { false })
    }

    @Test
    fun resolveStaysInsideTheWorkspace(): Unit = runBlocking {
        val m = manager()
        m.create("app")
        val base = layout().projectDir("app")
        assertEquals(base.resolve("src/Main.kt"), m.resolve("app", "src/Main.kt"))
        assertEquals(base.resolve("b"), m.resolve("app", "a/../b"))
        assertEquals(base, m.resolve("app", ""))
        assertFailsWith<PathEscapeException> { m.resolve("app", "../other") }
        assertFailsWith<PathEscapeException> { m.resolve("app", "a/../../x") }
        assertFailsWith<WorkspaceNotFoundException> { m.resolve("missing", "x") }
    }

    @Test
    fun resolveRejectsSymlinksThatPointOutside(): Unit = runBlocking {
        val m = manager()
        m.create("app")
        val outside = dir.resolve("outside").also { it.createDirectories() }
        outside.resolve("secret.txt").writeText("s3cret")
        val link = layout().projectDir("app").resolve("link")
        Files.createSymbolicLink(link, outside)
        assertFailsWith<PathEscapeException> { m.resolve("app", "link/secret.txt") }
        assertFailsWith<PathEscapeException> { m.resolve("app", "link") }
        // A symlink that stays inside is fine.
        val inside = layout().projectDir("app").resolve("real").also { it.createDirectories() }
        Files.createSymbolicLink(layout().projectDir("app").resolve("alias"), inside)
        assertEquals(layout().projectDir("app").resolve("alias/x"), m.resolve("app", "alias/x"))
    }

    @Test
    fun guestPathsUseTheGuestRoot(): Unit = runBlocking {
        val m = manager()
        assertEquals("/workspace/projects/app", m.guestPath("app"))
        assertEquals("/workspace/projects/app/src/Main.kt", m.guestPath("app", "src/./Main.kt"))
        assertFailsWith<PathEscapeException> { m.guestPath("app", "../x") }
    }

    @Test
    fun hostAndGuestPathsTranslateBothWays() {
        val l = layout()
        val host = l.hostRoot.resolve("projects/app/src/Main.kt")
        assertEquals("/workspace/projects/app/src/Main.kt", l.toGuest(host))
        assertEquals(host, l.toHost("/workspace/projects/app/src/Main.kt"))
        assertEquals("/workspace", l.toGuest(l.hostRoot))
        assertEquals(l.hostRoot, l.toHost("/workspace"))
        assertNull(l.toGuest(dir.resolve("elsewhere")))
        assertNull(l.toHost("/etc/passwd"))
        assertNull(l.toHost("/workspace/../etc/passwd"))
        assertNull(l.toHost("relative/path"))
        assertEquals(l.hostRoot.resolve("a/c"), l.toHost("/workspace/a/b/../c"))
    }

    @Test
    fun sessionDirectoriesAreCreatedForFilesArtifactsAndLogs(): Unit = runBlocking {
        val m = manager()
        val dirs = m.sessionDirs("s1")
        assertTrue(dirs.files.exists() && dirs.artifacts.exists() && dirs.logs.exists())
        assertEquals(layout().sessionDir("s1"), dirs.root)
        assertFailsWith<IllegalArgumentException> { m.sessionDirs("../evil") }
    }

    @Test
    fun riskRootsListHostAndGuestSpellings(): Unit = runBlocking {
        val m = manager()
        m.create("mine", sessionId = "s1")
        m.create("theirs", sessionId = "s2")
        val roots = m.riskRoots("s1")
        val hostMine = layout().projectDir("mine").toAbsolutePath().normalize().toString()
        assertTrue(hostMine in roots && "/workspace/projects/mine" in roots, roots.toString())
        assertTrue("/workspace/sessions/s1" in roots)
        assertFalse(roots.any { it.endsWith("theirs") }, "another session's workspace is not trusted")
    }

    @Test
    fun usageCountsFileBytes(): Unit = runBlocking {
        val m = manager()
        m.create("sized")
        val base = layout().projectDir("sized")
        base.resolve("a.txt").writeText("12345")
        base.resolve("sub").createDirectories()
        base.resolve("sub/b.txt").writeText("1234567890")
        assertEquals(15, m.usageBytes("sized"))
        assertEquals(0, m.usageBytes("nothing"))
    }

    @Test
    fun cleanupRemovesOnlyStaleUnpinnedScratchWorkspaces(): Unit = runBlocking {
        val m = manager()
        m.create("old-scratch", WorkspaceKind.SCRATCH)
        m.create("pinned-scratch", WorkspaceKind.SCRATCH); m.setPinned("pinned-scratch", true)
        m.create("busy-scratch", WorkspaceKind.SCRATCH, sessionId = "live")
        m.create("old-project", WorkspaceKind.PROJECT)
        layout().projectDir("old-scratch").resolve("junk.bin").writeText("x".repeat(100))
        time.addAndGet(8 * day)
        m.create("fresh-scratch", WorkspaceKind.SCRATCH)

        val report = m.cleanup(CleanupPolicy(removeOrphanSessionDirs = false), liveSessionIds = setOf("live"))
        assertEquals(listOf("old-scratch"), report.deletedWorkspaces)
        assertEquals(100, report.freedBytes)
        assertEquals(setOf("pinned-scratch", "busy-scratch", "old-project", "fresh-scratch"), m.list().map { it.id }.toSet())
    }

    @Test
    fun cleanupRemovesOrphanSessionDirectoriesButKeepsLiveOnes(): Unit = runBlocking {
        val m = manager()
        m.sessionDirs("live"); m.sessionDirs("gone")
        layout().sessionDir("gone").resolve("files/x").writeText("abc")
        val report = m.cleanup(liveSessionIds = setOf("live"))
        assertEquals(listOf("gone"), report.deletedSessionDirs)
        assertTrue(layout().sessionDir("live").exists())
        assertFalse(layout().sessionDir("gone").exists())
    }

    @Test
    fun cleanupTrimsTheOldestArtifactsFirstWhenOverBudget(): Unit = runBlocking {
        val m = manager()
        val dirs = m.sessionDirs("s1")
        fun artifact(name: String, size: Int, ageDays: Long) {
            val f = dirs.artifacts.resolve(name)
            f.writeText("x".repeat(size))
            f.setLastModifiedTime(java.nio.file.attribute.FileTime.fromMillis(time.get() - ageDays * day))
        }
        artifact("oldest.apk", 400, 30)
        artifact("middle.apk", 400, 10)
        artifact("newest.apk", 400, 1)

        val report = m.cleanup(CleanupPolicy(maxArtifactBytesPerSession = 800), liveSessionIds = setOf("s1"))
        assertEquals(1, report.trimmedArtifactFiles)
        assertFalse(dirs.artifacts.resolve("oldest.apk").exists())
        assertTrue(dirs.artifacts.resolve("middle.apk").exists() && dirs.artifacts.resolve("newest.apk").exists())
    }

    @Test
    fun aDamagedRegistryIsRebuiltFromTheDirectoriesOnDisk(): Unit = runBlocking {
        val m = manager()
        m.create("alpha"); m.create("beta")
        layout().registryFile.writeText("{ not json")
        assertEquals(setOf("alpha", "beta"), manager().list().map { it.id }.toSet())
    }

    @Test
    fun manyProjectsCanBeCreatedConcurrently(): Unit = runBlocking {
        val m = manager()
        val created = (1..25).map { async(Dispatchers.Default) { m.create("proj") } }.awaitAll()
        assertEquals(25, created.map { it.id }.toSet().size)
        assertEquals(25, m.list().size)
        assertEquals(25, manager().list().size, "the registry on disk matches")
    }

    @Test
    fun renameAndPermissionsPersist(): Unit = runBlocking {
        val m = manager()
        m.create("p")
        m.rename("p", "  Shiny Name ")
        m.setPermissions("p", WorkspacePermissions(linuxWrite = false, androidWrite = true))
        val loaded = manager().get("p")!!
        assertEquals("Shiny Name", loaded.name)
        assertEquals(false, loaded.permissions.linuxWrite)
        assertEquals(true, loaded.permissions.androidWrite)
        assertFailsWith<WorkspaceNotFoundException> { m.rename("zzz", "x") }
    }
}
