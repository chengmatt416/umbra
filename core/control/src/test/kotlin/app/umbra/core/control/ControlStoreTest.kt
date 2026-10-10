package app.umbra.core.control

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir

class ControlStoreTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun documentsSurviveARestartAndBadFilesAreSkipped() {
        val store = ControlStore(dir)
        val projects = ProjectRegistry(store)
        val a = projects.create("Android Agent")
        projects.create("IdeaStone")
        projects.rename(a.id, "Android Agent v2")
        Files.writeString(dir.resolve("projects/broken.json"), "{not json")

        val again = ProjectRegistry(ControlStore(dir))
        assertEquals(listOf("Android Agent v2", "IdeaStone"), again.list().map { it.name })
        assertTrue(again.delete(a.id))
        assertNull(ProjectRegistry(ControlStore(dir))[a.id])
    }

    @Test
    fun theJournalChainsEntriesAndDetectsAnEditedLine() {
        val store = ControlStore(dir)
        val j = store.journal("security")
        repeat(5) { j.append("event", JsonPrimitive("n$it")) }
        assertEquals(5, j.verify().entries)
        assertTrue(j.verify().intact)
        assertEquals(j.headDigest(), ControlStore(dir).journal("security").headDigest(), "the head survives a restart")

        val file = dir.resolve("journals/security.jsonl")
        Files.writeString(file, Files.readString(file).replace("\"n2\"", "\"tampered\""))
        val check = ControlStore(dir).journal("security").verify()
        assertFalse(check.intact)
        assertEquals(3L, check.firstBadSeq)
    }
}
