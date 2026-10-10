package app.umbra.core.control

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir

class SecurityEvidenceTest {
    @TempDir
    lateinit var dir: Path

    private fun store() = ControlStore(dir)

    private fun fill(s: ControlStore) {
        val a = s.journal("emergency")
        a.append("lockdown", buildJsonObject { put("by", "user") })
        a.append("lift", buildJsonObject { put("by", "user") })
        s.journal("credential-exposures").append("credential.exposed", buildJsonObject { put("name", "K"); put("to", "session s1") })
    }

    @Test
    fun everyJournalsChainIsCheckedAndATamperedOneShows() {
        val s = store()
        fill(s)
        val e = SecurityEvidence(s)
        assertTrue(e.check().all { it.intact })
        val digest = e.combinedDigest()
        val f = dir.resolve("journals/emergency.jsonl")
        Files.writeString(f, Files.readString(f).replaceFirst("\"user\"", "\"someone\""))
        val after = SecurityEvidence(store()).check()
        assertFalse(after.first { it.name == "emergency" }.intact)
        assertTrue(after.first { it.name == "credential-exposures" }.intact)
        assertTrue(digest != SecurityEvidence(store()).combinedDigest(), "the combined digest moves with any journal")
    }

    @Test
    fun anAnchorCatchesHistoryRewrittenOrRemovedEvenWhenTheChainIsRebuilt() {
        val s = store()
        fill(s)
        val e = SecurityEvidence(s)
        val heads = e.heads()
        e.anchored(EvidenceAnchor("a1", "node-1", e.combinedDigest(), heads, 1))
        // More history afterwards is fine: the anchored entries are still there.
        s.journal("emergency").append("lockdown", buildJsonObject { put("by", "user") })
        assertTrue(e.verify("a1", heads).ok)

        // Someone with full write access replaces the emergency journal with a consistent, rebuilt chain.
        Files.delete(dir.resolve("journals/emergency.jsonl"))
        val forged = store()
        forged.journal("emergency").append("lift", buildJsonObject { put("by", "intruder") })
        forged.journal("emergency").append("lift", buildJsonObject { put("by", "intruder") })
        val check = SecurityEvidence(forged)
        assertTrue(check.check().first { it.name == "emergency" }.intact, "locally the forged chain looks fine")
        val r = check.verify("a1", heads)
        assertEquals(listOf("emergency"), r.rewritten, "the independent anchor shows it")
        Files.delete(dir.resolve("journals/credential-exposures.jsonl"))
        assertEquals(listOf("credential-exposures"), SecurityEvidence(store()).verify("a1", heads).missing)
    }

    @Test
    fun incidentsMoveOnlyWhenTheUserMovesThemAndEveryChangeIsAVersion() {
        val e = SecurityEvidence(store())
        val i = e.record("Unknown git push", EvidenceLevel.L2, TrustBoundary.GIT, "a ref moved that nobody here published")
        assertEquals(IncidentState.DETECTED, i.state)
        var cur = i
        for (expected in listOf(IncidentState.TRIAGED, IncidentState.CONTAINED, IncidentState.REMEDIATING, IncidentState.TRUST_RESTORATION, IncidentState.RESOLVED)) {
            cur = e.advance(i.id, null)
            assertEquals(expected, cur.state)
        }
        assertEquals(6, cur.version)
        assertFailsWith<IllegalStateException> { e.advance(i.id, null) }
        assertEquals(IncidentState.TRIAGED, e.reopen(i.id, "it happened again").state)
        val fp = e.falsePositive(i.id, "the push was the user's, from another computer")
        assertTrue(fp.falsePositive && fp.state == IncidentState.RESOLVED)
        assertEquals(3, fp.notes.size)
    }

    @Test
    fun theExportOpensOnlyWithItsPassphraseAndShowsAnyChange() {
        val s = store()
        fill(s)
        val e = SecurityEvidence(s)
        e.record("Test", EvidenceLevel.L1, TrustBoundary.OTHER, null)
        assertFailsWith<IllegalArgumentException> { e.export("short".toCharArray()) }
        val sealed = e.export("correct horse battery".toCharArray())
        assertTrue(String(sealed).startsWith(Evidence.HEADER))
        assertFalse("credential.exposed" in String(sealed), "nothing readable without the passphrase")
        val plain = String(Evidence.open(sealed, "correct horse battery".toCharArray()))
        assertTrue("credential.exposed" in plain && "umbra-evidence-1" in plain && "\"incidents\"" in plain)
        assertFailsWith<Exception> { Evidence.open(sealed, "wrong passphrase!".toCharArray()) }
        val bad = String(sealed).lines().toMutableList().also { it[3] = it[3].replaceRange(10, 12, if (it[3].substring(10, 12) == "AA") "BB" else "AA") }.joinToString("\n").toByteArray()
        assertFailsWith<Exception> { Evidence.open(bad, "correct horse battery".toCharArray()) }
    }
}
