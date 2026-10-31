package app.umbra.agent.skills

import app.umbra.core.workspace.WorkspaceLayout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class InstalledSkillsTest {
    private suspend fun fixture(block: suspend (Path, InstalledSkills, MutableMap<String, ByteArray>) -> Unit) {
        val root = Files.createTempDirectory("installed-skills-").toRealPath()
        val bodies = mutableMapOf<String, ByteArray>()
        val hub = SkillHub(OkHttpClient.Builder().addInterceptor { chain ->
            val body = bodies[chain.request().url.toString()] ?: throw IOException("Download failed")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test").body(body.toResponseBody()).build()
        }.build())
        try { block(root, InstalledSkills(WorkspaceLayout(root), hub), bodies) }
        finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } } }
    }

    private fun source(bodies: MutableMap<String, ByteArray>, description: String = "Build Android apps", scripts: Boolean = false): Pair<SkillEntry, List<RemoteFile>> {
        val content = linkedMapOf("SKILL.md" to "---\nname: android\ndescription: $description\n---\n# Android\n".toByteArray())
        if (scripts) {
            content["scripts/plain.txt"] = "review me".toByteArray()
            content["helper"] = "#!/bin/sh\necho test\n".toByteArray()
        }
        val files = content.map { (path, bytes) ->
            val url = SkillHub.rawUrl("owner/repo", "main", "skills/android/$path")
            bodies[url] = bytes
            RemoteFile(path, url, bytes.size.toLong(), SkillHub.blobSha(bytes), if (path == "helper") "100755" else "100644")
        }
        return SkillEntry("owner/repo", "main", "skills/android", "android", description, files.first().sha, files.size, files.sumOf { it.size }) to files
    }

    @Test fun globalAndProjectInstallsScanProvenanceAndScriptsWithoutExecutableBits(): Unit = runBlocking {
        fixture { root, installed, bodies ->
            Files.createDirectories(root.resolve("projects/project"))
            val (entry, files) = source(bodies, scripts = true)
            val global = installed.install(entry, files, Target.Global)
            assertEquals("/workspace/.omp/skills/android", global.folder)
            assertEquals(listOf("helper", "scripts/plain.txt"), global.scripts)
            assertTrue(global.hasScripts)
            assertEquals(entry.sha, global.source?.sha)
            assertEquals("owner/repo", global.sourceRepo)
            assertFalse(Files.isExecutable(root.resolve(".omp/skills/android/helper")))
            val project = installed.install(entry, files, Target.Project("project"), name = "project-android")
            assertEquals("project-android", project.name)
            assertEquals("/workspace/projects/project/.omp/skills/project-android", project.folder)
            assertEquals(listOf("android"), installed.scan().map { it.name })
            val scanned = installed.scan("project")
            assertEquals(listOf("android", "project-android"), scanned.map { it.name })
            assertEquals(listOf(SkillScope.GLOBAL, SkillScope.PROJECT), scanned.map { it.scope })
            assertEquals("project", scanned.last().projectId)
            assertEquals(global.source, scanned.first().source)
            installed.remove("android", Target.Global)
            assertEquals(listOf("project-android"), installed.scan("project").map { it.name })
        }
    }

    @Test fun replacementRequiresConsentAndFailedDownloadsPreserveTheOldInstallation(): Unit = runBlocking {
        fixture { root, installed, bodies ->
            val (old, oldFiles) = source(bodies, "Old description")
            installed.install(old, oldFiles, Target.Global)
            val (fresh, files) = source(bodies, "New description", scripts = true)
            assertFailsWith<IllegalArgumentException> { installed.install(fresh, files, Target.Global) }
            val missing = files.last().url
            val saved = bodies.remove(missing)!!
            assertFailsWith<IOException> { installed.install(fresh, files, Target.Global, replace = true) }
            assertEquals("Old description", installed.scan().single().description)
            bodies[missing] = saved
            installed.install(fresh, files, Target.Global, replace = true)
            assertEquals("New description", installed.scan().single().description)
            assertEquals(listOf("android"), Files.newDirectoryStream(root.resolve(".omp/skills")).use { it.map { p -> p.fileName.toString() } })
        }
    }

    @Test fun scansUnmanagedFrontMatterAndRejectsSymlinkedDestinationsAndTraversal(): Unit = runBlocking {
        fixture { root, installed, bodies ->
            val folder = Files.createDirectories(root.resolve(".omp/skills/manual"))
            Files.writeString(folder.resolve("SKILL.md"), "---\nname: manual\ndescription: >\n  A manual skill\n  with folded text\n---\n")
            assertEquals("A manual skill with folded text", installed.scan().single().description)
            assertEquals(null, installed.scan().single().source)
            val (entry, files) = source(bodies)
            val outside = Files.createDirectories(root.resolve("outside"))
            Files.writeString(outside.resolve("keep"), "keep")
            Files.createSymbolicLink(root.resolve(".omp/skills/android"), outside)
            assertFailsWith<IllegalArgumentException> { installed.install(entry, files, Target.Global, replace = true) }
            assertFailsWith<IllegalArgumentException> { installed.remove("android", Target.Global) }
            assertFailsWith<IllegalArgumentException> { installed.remove("../outside", Target.Global) }
            assertFailsWith<IllegalArgumentException> { installed.install(entry, files, Target.Project("../outside")) }
            assertEquals("keep", Files.readString(outside.resolve("keep")))
            Files.delete(root.resolve(".omp/skills/android"))
            Files.createSymbolicLink(root.resolve(".omp/skills/manual/scripts"), outside)
            assertTrue(installed.scan().isEmpty())
        }
    }

    @Test fun forgedDownloadListsCannotEscapeOrExceedLimits(): Unit = runBlocking {
        fixture { _, installed, bodies ->
            val (entry, files) = source(bodies)
            for (bad in listOf(files.first().copy(path = "../SKILL.md"), files.first().copy(mode = "120000"),
                files.first().copy(size = SkillHub.MAX_INSTALL_BYTES + 1), files.first().copy(url = "https://example.com/SKILL.md"))) {
                assertFailsWith<IllegalArgumentException> { installed.install(entry, listOf(bad), Target.Global) }
            }
            assertFailsWith<IllegalArgumentException> { installed.install(entry.copy(sha = "changed"), files, Target.Global) }
            assertTrue(installed.scan().isEmpty())
        }
    }

    @Test fun anUnreadableOrSymlinkedProjectDoesNotHideGlobalSkills(): Unit = runBlocking {
        fixture { root, installed, bodies ->
            val (entry, files) = source(bodies)
            installed.install(entry, files, Target.Global)
            val project = Files.createDirectories(root.resolve("external"))
            Files.createDirectories(root.resolve("projects"))
            Files.createSymbolicLink(root.resolve("projects/linked"), project)
            assertEquals(listOf("android"), installed.scan("linked").map { it.name })
            assertEquals(listOf("android"), installed.scan("missing").map { it.name })
            assertFailsWith<IllegalArgumentException> { installed.install(entry, files, Target.Project("linked")) }
            assertFailsWith<IllegalArgumentException> { installed.scan("../escape") }
        }
    }
}
