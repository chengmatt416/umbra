package app.umbra.agent.publish

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir

class PublicationsTest {
    @TempDir lateinit var temporary: Path
    private fun root() = temporary.toRealPath().toFile()

    @Test fun reservationSurvivesRestartAndSuccessfulPublishReplacesIt() {
        val root = root()
        val artifact = root.resolve("report.html")
        val store = Publications(root)
        val reserved = store.reserve(artifact, "account") { "umbra-report-abc123" }
        assertEquals(reserved, store.publicationFor(artifact))
        assertNull(reserved.publishedAt)
        val restarted = Publications(root)
        assertEquals(reserved, restarted.reserve(artifact, "account") { error("Must reuse reserved name") })
        val published = reserved.copy(lastDeploymentId = "deployment", lastDeploymentUrl = "https://deployment.pages.dev",
            publishedAt = 1234, fileCount = 2, bytes = 512)
        restarted.save(published)
        assertEquals(published, Publications(root).publicationFor(root.resolve("./report.html")))
        val state = root.resolve("publish/publications.json")
        assertEquals(setOf(artifact.canonicalPath), Json.parseToJsonElement(state.readText()).jsonObject.keys)
        assertEquals(listOf("publications.json"), state.parentFile!!.listFiles()!!.map { it.name })
        assertFailsWith<IllegalArgumentException> { restarted.reserve(artifact, "other-account") { "unused" } }
        restarted.remove(published)
        assertNull(store.publicationFor(artifact))
    }

    @Test fun fullStoreRefusesNewProjectsButAllowsUpdatesAndTakedown() {
        val root = root()
        val store = Publications(root)
        val reserved = (0 until 200).map { i -> store.reserve(root.resolve("$i.html"), "account") { "umbra-site-$i" } }
        val before = root.resolve("publish/publications.json").readText()
        assertFailsWith<IllegalArgumentException> { store.reserve(root.resolve("201.html"), "account") { "unused" } }
        assertEquals(before, root.resolve("publish/publications.json").readText())
        store.save(reserved.first().copy(lastDeploymentId = "new", publishedAt = 12))
        assertEquals("new", store.publicationFor(root.resolve("0.html"))?.lastDeploymentId)
        store.remove(reserved.last())
        store.reserve(root.resolve("201.html"), "account") { "umbra-new-abc123" }
        assertEquals(200, Json.parseToJsonElement(root.resolve("publish/publications.json").readText()).jsonObject.size)
    }

    @Test fun corruptStateIsNotOverwrittenAndLinkedStateIsRefused() {
        val root = root()
        val file = root.resolve("publish/publications.json").apply { parentFile!!.mkdirs(); writeText("invalid") }
        val store = Publications(root)
        assertFailsWith<Exception> { store.reserve(root.resolve("report.html"), "account") { "unused" } }
        assertEquals("invalid", file.readText())
        assertTrue(file.delete())
        val target = root.resolve("other.json").apply { writeText("{}") }
        Files.createSymbolicLink(file.toPath(), target.toPath())
        assertFailsWith<IllegalArgumentException> { store.publicationFor(root.resolve("report.html")) }
        assertEquals("{}", target.readText())
        assertFalse(root.resolve("publish").listFiles()!!.any { it.name.endsWith(".tmp") })
    }
}
