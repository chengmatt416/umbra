package app.umbra.core.control

import app.umbra.core.model.Clock
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class CredentialVaultTest {
    @TempDir
    lateinit var dir: Path

    private var now = 5_000L
    private fun vault() = CredentialVault(ControlStore(dir, Clock { now }))

    @Test
    fun theAccountFollowsTheSecretStoreAndScopesDecideWhoGetsWhat() {
        val v = vault()
        v.reconcile(mapOf("ANTHROPIC_API_KEY" to ("Anthropic (Claude)" to "anthropic"), "OPENAI_API_KEY" to ("OpenAI" to "openai")))
        assertTrue(v.admits("ANTHROPIC_API_KEY", "openai"), "any session by default: a session may switch models")

        v.setScope("ANTHROPIC_API_KEY", CredentialScope.ITS_PROVIDER_ONLY)
        assertTrue(v.admits("ANTHROPIC_API_KEY", "anthropic"))
        assertFalse(v.admits("ANTHROPIC_API_KEY", "openai"))
        v.setScope("OPENAI_API_KEY", CredentialScope.NOT_FOR_AGENTS)
        assertFalse(v.admits("OPENAI_API_KEY", "openai"))
        assertTrue(v.admits("UNKNOWN", "x"), "what the vault does not hold is not its to refuse")

        // The scope survives a restart of the app.
        assertEquals(CredentialScope.ITS_PROVIDER_ONLY, vault()["ANTHROPIC_API_KEY"]!!.scope)

        v.reconcile(mapOf("ANTHROPIC_API_KEY" to ("Anthropic (Claude)" to "anthropic")))
        assertNull(v["OPENAI_API_KEY"], "a key no longer stored is forgotten")
        assertTrue(v.recentEvents.value.any { it.kind == "credential.gone" })
    }

    @Test
    fun everyExposureIsRecordedInAChainThatShowsTampering() {
        val v = vault()
        v.reconcile(mapOf("ANTHROPIC_API_KEY" to ("Anthropic" to "anthropic")))
        now = 9_000
        v.recordExposure(listOf("ANTHROPIC_API_KEY"), "session s1 · omp", "the agent's model calls")
        v.recordExposure(listOf("ANTHROPIC_API_KEY"), "session s2 · omp", "the agent's model calls")
        val r = v["ANTHROPIC_API_KEY"]!!
        assertEquals(2, r.exposures)
        assertEquals(9_000, r.lastExposedAtMs)
        assertEquals("session s2 · omp", r.lastExposedTo)
        assertTrue(v.verifyExposures().intact)
        assertFalse(v.recentExposures.value.any { "sk-" in it.data.toString() }, "names only, never values")

        // Editing an entry of the journal on disk breaks the chain.
        val file = dir.resolve("journals").resolve("credential-exposures.jsonl")
        Files.writeString(file, Files.readString(file).replaceFirst("session s1", "session s9"))
        assertFalse(vault().verifyExposures().intact)
    }

    @Test
    fun rotatingAndRevokingAreSeparateJournaledActs() {
        val v = vault()
        v.reconcile(mapOf("K" to ("Custom" to null)))
        now = 7_000
        v.rotated("K")
        assertEquals(7_000, v["K"]!!.rotatedAtMs)
        v.revoked("K")
        assertNull(v["K"])
        val kinds = v.recentEvents.value.map { it.kind }
        assertTrue("credential.rotated" in kinds && "credential.revoked_locally" in kinds, "$kinds")
    }
}
