package app.umbra.core.nodes

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PairingAndManifestTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun codesAreLongAndForgivingToType() {
        val code = Pairing.newCode()
        assertTrue(Regex("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}").matches(code), code)
        assertEquals(12, Pairing.normalize(code).length)
        assertEquals(Pairing.normalize("abcd-efgh-1jk0"), Pairing.normalize("ABCD EFGH IJKO"), "case, spaces and look-alikes do not matter")
    }

    @Test
    fun proofsBindTheCodeAndBothCertificates() {
        val node = "aa".repeat(32)
        val phone = "bb".repeat(32)
        val p = Pairing.phoneProof("K7QF-3MZA-9PXT", node, phone)
        assertTrue(Pairing.same(p, Pairing.phoneProof("k7qf3mza9pxt", node, phone)))
        assertNotEquals(p, Pairing.phoneProof("K7QF-3MZA-9PXV", node, phone), "another code")
        assertNotEquals(p, Pairing.phoneProof("K7QF-3MZA-9PXT", "cc".repeat(32), phone), "another node certificate (a machine in the middle)")
        assertNotEquals(p, Pairing.nodeProof("K7QF-3MZA-9PXT", node, phone), "the two directions differ")
    }

    @Test
    fun aManifestHoldsOnlyWhatIsInsideAndUnderTheCeiling() {
        val root = Files.createDirectories(dir.resolve("job"))
        Files.writeString(root.resolve("a.txt"), "aaaa")
        Files.createDirectories(root.resolve("src/sub"))
        Files.writeString(root.resolve("src/sub/b.txt"), "bb")
        Files.createSymbolicLink(root.resolve("src/inside"), root.resolve("a.txt"))
        val m = Manifests.build(root, listOf("a.txt", "src"), maxBytes = 100)
        assertEquals(listOf("a.txt", "src/inside", "src/sub/b.txt"), m.files.map { it.path }.sorted())
        assertEquals(10, m.totalBytes)
        assertTrue(m.secrets.isEmpty())
        assertEquals(DataManifest.RETENTION_DELETE, m.retention)

        assertFailsWith<ManifestException> { Manifests.build(root, listOf("a.txt", "src"), maxBytes = 5) }.also { assertTrue("ceiling" in it.message.orEmpty()) }
        assertFailsWith<ManifestException> { Manifests.build(root, listOf("/etc/passwd"), 100) }
        assertFailsWith<ManifestException> { Manifests.build(root, listOf("src/../../x"), 100) }
        Files.writeString(dir.resolve("secret.txt"), "s")
        Files.createSymbolicLink(root.resolve("leak"), dir.resolve("secret.txt"))
        assertFailsWith<ManifestException> { Manifests.build(root, listOf("leak"), 100) }.also { assertTrue("outside" in it.message.orEmpty()) }
        assertFalse(runCatching { Manifests.checkRelative("ok/./fine") }.isFailure)
    }
}
