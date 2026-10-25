package app.umbra.agent.data

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*
import kotlinx.serialization.json.*

class DeliverableFilesTest {
    private fun receipt(bytes: ByteArray) = buildJsonObject {
        put("sizeBytes", bytes.size)
        put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }

    @Test fun sharingUsesAPrivateCopyOfTheExactPublishedBytes() {
        val root = Files.createTempDirectory("deliverable-")
        val source = root.resolve("report.txt")
        val bytes = "Published report · 中文".toByteArray()
        Files.write(source, bytes)
        val copy = DeliverableFiles.copyVerified(source, listOf(root), receipt(bytes), root.resolve("share").toFile(), 1000)
        Files.writeString(source, "Changed after copying")
        assertContentEquals(bytes, copy.readBytes())
        assertTrue(copy.toPath().startsWith(root.resolve("share")))
        assertNotEquals(source, copy.toPath())
    }

    @Test fun missingReceiptsTamperingOversizeAndEscapingPathsCannotBeShared() {
        val root = Files.createTempDirectory("deliverable-")
        val bytes = "original".toByteArray()
        val source = Files.write(root.resolve("report.txt"), bytes)
        val output = root.resolve("share").toFile()
        assertFailsWith<IllegalArgumentException> { DeliverableFiles.copyVerified(source, listOf(root), JsonObject(emptyMap()), output, 1000) }
        assertFailsWith<IllegalArgumentException> { DeliverableFiles.copyVerified(source, listOf(root), receipt(bytes), output, 2) }
        assertFailsWith<IllegalArgumentException> { DeliverableFiles.copyVerified(source, listOf(root.resolve("other")), receipt(bytes), output, 1000) }
        Files.writeString(source, "modified") // Same length: the content hash must still catch it.
        assertFailsWith<IllegalStateException> { DeliverableFiles.copyVerified(source, listOf(root), receipt(bytes), output, 1000) }
        assertTrue(output.listFiles().orEmpty().isEmpty(), "A rejected copy must not leave bytes available to send")
    }

    @Test fun aLinkOutsideTheOwnedWorkspaceIsRefused() {
        val root = Files.createTempDirectory("deliverable-")
        val outside = Files.writeString(Files.createTempFile("outside-", ".txt"), "private")
        val linked = Files.createSymbolicLink(root.resolve("linked.txt"), outside)
        assertFailsWith<IllegalArgumentException> {
            DeliverableFiles.copyVerified(linked, listOf(root), receipt("private".toByteArray()), root.resolve("share").toFile(), 1000)
        }
    }
}
