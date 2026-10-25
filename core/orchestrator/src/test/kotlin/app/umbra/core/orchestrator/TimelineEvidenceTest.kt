package app.umbra.core.orchestrator

import app.umbra.core.session.FileSessionStore
import app.umbra.core.session.Session
import app.umbra.core.session.SessionEvent
import app.umbra.runtime.omp.ContentBlock
import app.umbra.runtime.omp.HostToolCall
import app.umbra.runtime.omp.HostToolPayload
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir

class TimelineEvidenceTest {
    @TempDir lateinit var root: Path

    @Test fun recordedScreenshotReceiptsBindTheSavedBytesRatherThanAPathAlone(): Unit = runBlocking {
        val store = FileSessionStore(root.resolve("sessions"))
        store.create(Session("task", "Task", createdAtMs = 1, request = "Observe the screen"))
        val bytes = byteArrayOf(0x12, 0x34, 0x56)
        var saved: ByteArray? = null
        val recorder = TimelineRecorder("task", store, setOf("android_observe"), ArtifactSink { _, name, content -> saved = content; name })
        recorder.hostToolResult(HostToolCall("call", "call", "android_observe", JsonObject(emptyMap())),
            HostToolPayload(listOf(ContentBlock.Image(Base64.getEncoder().encodeToString(bytes), "image/png"))), 1)
        val observation = store.tail("task", 10).mapNotNull { it.event as? SessionEvent.Observation }.single()
        assertContentEquals(bytes, saved)
        assertEquals(3L, observation.screenshotSizeBytes)
        assertEquals("image/png", observation.screenshotMimeType)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, observation.screenshotSha256)
        assertTrue(observation.screenshotPath!!.endsWith(".png"))
    }

    @Test fun aFailedSaveDoesNotProduceAVisualProofReceipt(): Unit = runBlocking {
        val store = FileSessionStore(root.resolve("sessions"))
        store.create(Session("task", "Task", createdAtMs = 1, request = "Observe the screen"))
        val recorder = TimelineRecorder("task", store, emptySet())
        recorder.hostToolResult(HostToolCall("call", "call", "android_observe", JsonObject(emptyMap())),
            HostToolPayload(listOf(ContentBlock.Image("Eg==", "image/jpeg"))), 1)
        val observation = store.tail("task", 10).mapNotNull { it.event as? SessionEvent.Observation }.single()
        assertNull(observation.screenshotPath)
        assertNull(observation.screenshotSha256)
        assertNull(observation.screenshotSizeBytes)
    }
}
