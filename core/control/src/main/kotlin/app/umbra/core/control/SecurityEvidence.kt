package app.umbra.core.control

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Evidence levels (architecture spec §13.1). A level is about the evidence, not about how far the harm reaches. */
@Serializable
enum class EvidenceLevel(val label: String, val meaning: String) {
    L1("Observation", "A heuristic anomaly, low confidence: record it and watch"),
    L2("Credible risk", "Suspicious high-risk access or an identity anomaly: hold the affected sensitive operations, validate again"),
    L3("Verified violation", "A concrete policy, signature or capability violation: refuse it, isolate what it touched, trace the exposure"),
    L4("Critical trust", "Root or the control plane itself cannot be trusted: global lockdown, recover from an independent trusted environment"),
}

/** Where trust may be broken: decides which manual steps the incident points at. */
@Serializable
enum class TrustBoundary(val label: String) { CREDENTIAL("A credential"), GIT("Git / commit authority"), NODE("A remote node"), ROOT("Root / this phone's control plane"), OTHER("Something else") }

@Serializable
enum class IncidentState(val label: String) {
    DETECTED("Detected"), TRIAGED("Triaged"), CONTAINED("Contained"), REMEDIATING("Investigating · remediating"),
    TRUST_RESTORATION("Trust restoration gate"), RESOLVED("Resolved"),
}

@Serializable
data class IncidentNote(val atMs: Long, val by: String, val text: String)

/**
 * An incident the user recorded (architecture spec §13.1). Nothing happens by itself: its state moves when the user
 * moves it, every change is a new version, and resolving it resumes nothing.
 */
@Serializable
data class SecurityIncident(
    val id: String,
    val title: String,
    val level: EvidenceLevel,
    val boundary: TrustBoundary,
    val state: IncidentState = IncidentState.DETECTED,
    /** SecurityIncidentVersion: its own sequence, never mixed with any other (§2.2). */
    val version: Int = 1,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val notes: List<IncidentNote> = emptyList(),
    val falsePositive: Boolean = false,
)

/** One journal's chain as checked now. */
data class JournalCheck(val name: String, val entries: Int, val intact: Boolean, val firstBadSeq: Long?, val head: String, val lastTimeMs: Long?)

/** A journal's head at the moment it was anchored: the entry [seq] must still carry [digest]. */
@Serializable
data class AnchoredHead(val seq: Long, val digest: String)

/** A digest anchored on an independent node (§13.2): the combined digest and each journal's head. */
@Serializable
data class EvidenceAnchor(val id: String, val nodeId: String, val digest: String, val heads: Map<String, AnchoredHead>, val atMs: Long)

/** What comparing the journals with an anchor found, journal by journal. */
data class AnchorCheck(val anchorId: String, val intact: List<String>, val rewritten: List<String>, val missing: List<String>) {
    val ok get() = rewritten.isEmpty() && missing.isEmpty()
}

/**
 * Security & Evidence (architecture spec §13): the hash chains of every control journal, a combined digest, anchors of
 * that digest kept on an independent node and the check of the journals against them, incidents the user records,
 * and an encrypted evidence export. Containment stays with the user (Emergency Control, the vault, the nodes): this
 * records, checks and guides.
 */
class SecurityEvidence(private val store: ControlStore) {
    private val incidents = store.docs("incidents", SecurityIncident.serializer(), SecurityIncident::id)
    private val anchors = store.docs("evidence-anchors", EvidenceAnchor.serializer(), EvidenceAnchor::id)
    private val journal = store.journal("security")

    val allIncidents: StateFlow<Map<String, SecurityIncident>> = incidents.all
    val allAnchors: StateFlow<Map<String, EvidenceAnchor>> = anchors.all
    val recent = journal.recent

    fun check(): List<JournalCheck> = store.journalNames().map { name ->
        val j = store.journal(name)
        val c = j.verify()
        JournalCheck(name, c.entries, c.intact, c.firstBadSeq, c.headDigest, j.readAll().lastOrNull()?.timeMs)
    }

    /** One digest over every journal's length and head: what is anchored. */
    fun combinedDigest(checks: List<JournalCheck> = check()): String =
        ControlStore.sha256(checks.sortedBy { it.name }.joinToString("\n") { "${it.name}:${it.entries}:${it.head}" })

    /** The heads to anchor now: for each journal, its last entry's sequence and digest. */
    fun heads(): Map<String, AnchoredHead> = store.journalNames().mapNotNull { name ->
        store.journal(name).readAll().lastOrNull()?.let { name to AnchoredHead(it.seq, it.digest) }
    }.toMap()

    fun anchored(anchor: EvidenceAnchor) {
        anchors.put(anchor)
        journal.append("security.anchored", buildJsonObject { put("node", anchor.nodeId); put("digest", anchor.digest); put("journals", anchor.heads.size) })
    }

    /**
     * Compares the journals with [heads] (as an independent node kept them): each anchored entry must still be there
     * with the same digest. A rewritten entry or a shortened journal shows here even if the local chain is intact.
     */
    fun verify(anchorId: String, heads: Map<String, AnchoredHead>): AnchorCheck {
        val intact = ArrayList<String>()
        val rewritten = ArrayList<String>()
        val missing = ArrayList<String>()
        for ((name, head) in heads) {
            val entry = if (name in store.journalNames()) store.journal(name).readAll().firstOrNull { it.seq == head.seq } else null
            when {
                entry == null -> missing += name
                entry.digest != head.digest -> rewritten += name
                else -> intact += name
            }
        }
        val result = AnchorCheck(anchorId, intact.sorted(), rewritten.sorted(), missing.sorted())
        journal.append("security.anchor_checked", buildJsonObject { put("anchor", anchorId); put("ok", result.ok); put("rewritten", rewritten.joinToString()); put("missing", missing.joinToString()) })
        return result
    }

    // ---- incidents ----

    fun record(title: String, level: EvidenceLevel, boundary: TrustBoundary, note: String?, by: String = "user"): SecurityIncident {
        val now = store.clock.nowMs()
        val i = SecurityIncident("inc-" + ControlStore.sha256("$title|$now|${journal.headDigest()}").take(12), title, level, boundary, createdAtMs = now, updatedAtMs = now,
            notes = listOfNotNull(note?.takeIf { it.isNotBlank() }?.let { IncidentNote(now, by, it) }))
        incidents.put(i)
        journal.append("security.incident", buildJsonObject { put("id", i.id); put("level", level.name); put("boundary", boundary.name.lowercase()); put("state", i.state.name.lowercase()); put("version", 1) })
        return i
    }

    /** Moves [id] to its next state. Resolved is the end; it resumes nothing. */
    fun advance(id: String, note: String?, by: String = "user"): SecurityIncident {
        val i = incidents[id] ?: throw IllegalArgumentException("no incident $id")
        check(i.state != IncidentState.RESOLVED) { "the incident is resolved; reopen it first" }
        return change(i.copy(state = IncidentState.entries[i.state.ordinal + 1]), note, by)
    }

    fun reopen(id: String, note: String?, by: String = "user"): SecurityIncident {
        val i = incidents[id] ?: throw IllegalArgumentException("no incident $id")
        return change(i.copy(state = IncidentState.TRIAGED, falsePositive = false), note, by)
    }

    /** False-positive review: closed as such, with the reason; nothing it led to is undone by itself. */
    fun falsePositive(id: String, note: String, by: String = "user"): SecurityIncident {
        val i = incidents[id] ?: throw IllegalArgumentException("no incident $id")
        return change(i.copy(state = IncidentState.RESOLVED, falsePositive = true), note, by)
    }

    fun note(id: String, text: String, by: String = "user"): SecurityIncident {
        val i = incidents[id] ?: throw IllegalArgumentException("no incident $id")
        return change(i, text, by)
    }

    private fun change(next: SecurityIncident, note: String?, by: String): SecurityIncident {
        val now = store.clock.nowMs()
        val saved = incidents.put(next.copy(version = next.version + 1, updatedAtMs = now, notes = next.notes + listOfNotNull(note?.takeIf { it.isNotBlank() }?.let { IncidentNote(now, by, it) })))
        journal.append("security.incident", buildJsonObject {
            put("id", saved.id); put("state", saved.state.name.lowercase()); put("version", saved.version); put("false_positive", saved.falsePositive); put("by", by)
        })
        return saved
    }

    // ---- export ----

    /**
     * The evidence, encrypted (§13.2): every journal (with its chain check), the incidents and the anchors, as JSON,
     * under AES-256-GCM with a key derived from [passphrase] (PBKDF2, 210 000 rounds). Secret values are never in any
     * journal to begin with. `umbra-node evidence-decrypt` reads it back.
     */
    fun export(passphrase: CharArray): ByteArray {
        require(passphrase.size >= 10) { "use a passphrase of at least 10 characters" }
        val checks = check()
        val body = buildJsonObject {
            put("format", "umbra-evidence-1")
            put("exported_at", store.clock.nowMs())
            put("combined_digest", combinedDigest(checks))
            put("journals", kotlinx.serialization.json.JsonObject(store.journalNames().associateWith { name ->
                val c = checks.first { it.name == name }
                buildJsonObject {
                    put("entries", c.entries); put("intact", c.intact); put("head", c.head)
                    put("lines", kotlinx.serialization.json.JsonArray(store.journal(name).readAll().map { e -> app.umbra.core.model.UmbraJson.encodeToJsonElement(JournalEntry.serializer(), e) }))
                }
            }))
            put("incidents", app.umbra.core.model.UmbraJson.encodeToJsonElement(ListSerializer(SecurityIncident.serializer()), incidents.list()))
            put("anchors", app.umbra.core.model.UmbraJson.encodeToJsonElement(ListSerializer(EvidenceAnchor.serializer()), anchors.list()))
        }
        val sealed = Evidence.seal(body.toString().toByteArray(), passphrase)
        journal.append("security.exported", buildJsonObject { put("bytes", sealed.size); put("journals", checks.size) })
        return sealed
    }
}

/** The evidence file format: a header line, then salt, nonce and AES-256-GCM ciphertext, base64. */
object Evidence {
    const val HEADER = "UMBRA-EVIDENCE-1"
    private const val ROUNDS = 210_000

    private fun key(passphrase: CharArray, salt: ByteArray) =
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase, salt, ROUNDS, 256)).encoded, "AES")

    fun seal(plain: ByteArray, passphrase: CharArray): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv)); updateAAD(HEADER.toByteArray()) }
        val b64 = Base64.getEncoder()
        return "$HEADER\n${b64.encodeToString(salt)}\n${b64.encodeToString(iv)}\n${b64.encodeToString(c.doFinal(plain))}\n".toByteArray()
    }

    /** Throws when the passphrase is wrong or the file was changed (GCM authenticates it). */
    fun open(sealed: ByteArray, passphrase: CharArray): ByteArray {
        val lines = String(sealed).trim().lines()
        require(lines.size == 4 && lines[0] == HEADER) { "not an Umbra evidence file" }
        val b64 = Base64.getDecoder()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(passphrase, b64.decode(lines[1])), GCMParameterSpec(128, b64.decode(lines[2]))); updateAAD(HEADER.toByteArray()) }
        return c.doFinal(b64.decode(lines[3]))
    }
}
