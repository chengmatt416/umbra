package app.umbra.core.session

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class FileSessionStoreTest : SessionStoreContract() {
    @TempDir
    lateinit var dir: Path

    override fun newStore(): SessionStore = FileSessionStore(dir, clock)

    @Test
    fun dataSurvivesRestartAndSequenceNumbersContinue(): Unit = runBlocking {
        val first = FileSessionStore(dir, clock)
        first.create(session("s1").copy(goal = "g"))
        first.append("s1", SessionEvent.UserMessage("one"))
        first.append("s1", SessionEvent.UserMessage("two"))

        val second = FileSessionStore(dir, clock)
        assertEquals("g", second.get("s1")?.goal)
        assertEquals(2, second.eventCount("s1"))
        assertEquals(3L, second.append("s1", SessionEvent.UserMessage("three")).seq)
        assertEquals(listOf(1L, 2L, 3L), second.events("s1").map { it.seq })
    }

    @Test
    fun aTornLastLineFromACrashIsDroppedAndDoesNotCorruptTheNextAppend(): Unit = runBlocking {
        val first = FileSessionStore(dir, clock)
        first.create(session("s1"))
        first.append("s1", SessionEvent.UserMessage("one"))
        first.append("s1", SessionEvent.UserMessage("two"))
        // Simulate a crash in the middle of writing record 3.
        val events = dir.resolve("s1/events.jsonl")
        Files.write(events, "{\"seq\":3,\"ts\":99,\"event\":{\"type\":\"user_mess".toByteArray(), java.nio.file.StandardOpenOption.APPEND)

        val second = FileSessionStore(dir, clock)
        assertEquals(listOf(1L, 2L), second.events("s1").map { it.seq }, "torn record is invisible")
        val next = second.append("s1", SessionEvent.UserMessage("after crash"))
        assertEquals(3L, next.seq)
        val lines = events.readText().lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size, lines.toString())
        assertEquals(listOf(1L, 2L, 3L), second.events("s1").map { it.seq })
        assertEquals("after crash", (second.events("s1").last().event as SessionEvent.UserMessage).text)
    }

    @Test
    fun aFileThatIsOneTornLineIsEmptiedNotWedged(): Unit = runBlocking {
        FileSessionStore(dir, clock).create(session("s1"))
        dir.resolve("s1/events.jsonl").writeText("{\"seq\":1,\"ts\":5,\"event\":{\"ty")
        val store = FileSessionStore(dir, clock)
        assertEquals(1L, store.append("s1", SessionEvent.Notice("info", "fresh")).seq)
        assertEquals(1, store.events("s1").size)
    }

    @Test
    fun corruptedMetadataHidesOneSessionNotTheWholeList(): Unit = runBlocking {
        val store = FileSessionStore(dir, clock)
        store.create(session("good"))
        store.create(session("bad"))
        dir.resolve("bad/session.json").writeText("{ this is not json")
        assertEquals(listOf("good"), store.list().map { it.id })
        assertEquals(null, store.get("bad"))
    }

    @Test
    fun metadataWritesLeaveNoTemporaryFilesBehind(): Unit = runBlocking {
        val store = FileSessionStore(dir, clock)
        store.create(session("s1"))
        repeat(10) { i -> store.update("s1") { it.copy(failureCount = i) } }
        val names = dir.resolve("s1").listDirectoryEntries().map { it.fileName.toString() }.sorted()
        assertEquals(listOf("session.json"), names)
    }

    @Test
    fun theSessionDirectoryContainsOnlyOurFiles(): Unit = runBlocking {
        val store = FileSessionStore(dir, clock)
        store.create(session("s1"))
        store.append("s1", SessionEvent.Notice("info", "x"))
        val names = dir.resolve("s1").listDirectoryEntries().map { it.fileName.toString() }.toSet()
        assertEquals(setOf("session.json", "events.jsonl"), names)
        assertTrue(dir.resolve("s1/events.jsonl").readText().startsWith("{\"seq\":1,"), "seq must come first")
    }

    @Test
    fun deletingRemovesTheDirectory(): Unit = runBlocking {
        val store = FileSessionStore(dir, clock)
        store.create(session("s1"))
        store.append("s1", SessionEvent.Notice("info", "x"))
        assertTrue(dir.resolve("s1").exists())
        store.delete("s1")
        assertFalse(dir.resolve("s1").exists())
    }

    @Test
    fun pathTraversalIsImpossibleThroughIds(): Unit = runBlocking {
        val store = FileSessionStore(dir.resolve("sessions"), clock)
        val outside = dir.resolve("secret.txt").also { it.writeText("keep") }
        assertEquals(false, store.delete("../secret.txt"))
        assertEquals(null, store.get("../secret.txt"))
        assertTrue(store.events("../secret.txt").isEmpty())
        assertEquals("keep", outside.readText())
    }

    @Test
    fun listIgnoresStrayFilesInTheRoot(): Unit = runBlocking {
        val store = FileSessionStore(dir, clock)
        store.create(session("s1"))
        dir.resolve("notes.txt").writeText("hello")
        Files.createDirectory(dir.resolve("not valid id"))
        assertEquals(listOf("s1"), store.list().map { it.id })
        assertNotNull(store.get("s1"))
    }
}
