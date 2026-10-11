package app.umbra.core.control

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Which agent sessions a credential may reach (architecture spec §12.1: scoped, not handed to everything). */
@Serializable
enum class CredentialScope(val label: String) {
    /** Every session gets it, so a session can switch to its provider's models. */
    ANY_SESSION("Any session"),

    /** Only a session whose model belongs to this credential's provider. */
    ITS_PROVIDER_ONLY("Its provider only"),

    /** Held in the vault and given to no agent. */
    NOT_FOR_AGENTS("Not for agents"),
}

/**
 * What the vault knows about one credential: never its value. The value stays encrypted under a key that does not
 * leave the phone's keystore; this is its name, owner, scope and history.
 */
@Serializable
data class CredentialRecord(
    /** The name it is stored under, which is also the environment variable it is given as. */
    val name: String,
    val label: String,
    /** The model provider it belongs to, when it is a provider key. */
    val provider: String? = null,
    val createdAtMs: Long,
    val rotatedAtMs: Long? = null,
    val scope: CredentialScope = CredentialScope.ANY_SESSION,
    val exposures: Long = 0,
    val lastExposedAtMs: Long? = null,
    val lastExposedTo: String? = null,
)

/**
 * The credential vault's account (architecture spec §12): which credentials Umbra holds, which sessions they may
 * reach, and every time one actually left the vault (an exposure grant, kept in a hash-chained journal: what was
 * exposed cannot later be shown as not copied, so it is at least never hidden). Rotating and revoking are separate,
 * journaled acts; revoking deletes Umbra's copy only, the credential stays valid at its issuer until revoked there.
 */
class CredentialVault(private val store: ControlStore) {
    private val records = store.docs("credentials", CredentialRecord.serializer(), CredentialRecord::name)
    private val exposures = store.journal("credential-exposures")
    private val events = store.journal("credentials")

    val all: StateFlow<Map<String, CredentialRecord>> = records.all
    val recentExposures = exposures.recent
    val recentEvents = events.recent

    operator fun get(name: String): CredentialRecord? = records[name]

    /**
     * Brings the account in line with what the secret store holds ([present]: name → label and provider): new names
     * are registered, names no longer stored are forgotten (journaled as gone).
     */
    @Synchronized
    fun reconcile(present: Map<String, Pair<String, String?>>) {
        val now = store.clock.nowMs()
        for ((name, meta) in present) {
            val r = records[name]
            if (r == null) {
                records.put(CredentialRecord(name, meta.first, meta.second, now))
                events.append("credential.added", buildJsonObject { put("name", name); meta.second?.let { put("provider", it) } })
            } else if (r.label != meta.first || r.provider != meta.second) {
                records.put(r.copy(label = meta.first, provider = meta.second))
            }
        }
        for (r in records.list()) if (r.name !in present) {
            records.delete(r.name)
            events.append("credential.gone", buildJsonObject { put("name", r.name) })
        }
    }

    @Synchronized
    fun setScope(name: String, scope: CredentialScope, by: String = "user") {
        records.update(name) { it.copy(scope = scope) } ?: throw IllegalArgumentException("no credential $name")
        events.append("credential.scope", buildJsonObject { put("name", name); put("scope", scope.name.lowercase()); put("by", by) })
    }

    /** The value was replaced (the user rotated it at its issuer and entered the new one). */
    @Synchronized
    fun rotated(name: String, by: String = "user") {
        records.update(name) { it.copy(rotatedAtMs = store.clock.nowMs()) } ?: throw IllegalArgumentException("no credential $name")
        events.append("credential.rotated", buildJsonObject { put("name", name); put("by", by) })
    }

    /** Umbra's copy was deleted. It is still valid at its issuer until revoked there. */
    @Synchronized
    fun revoked(name: String, by: String = "user") {
        records.delete(name)
        events.append("credential.revoked_locally", buildJsonObject { put("name", name); put("by", by) })
    }

    /** A secret came back from a recovery backup, with the scope it had. */
    @Synchronized
    fun restored(name: String, label: String, provider: String?, scope: CredentialScope) {
        records.put(records[name]?.copy(scope = scope) ?: CredentialRecord(name, label, provider, store.clock.nowMs(), scope = scope))
        events.append("credential.restored", buildJsonObject { put("name", name); put("scope", scope.name.lowercase()) })
    }

    /** Everything a recovery backup carries about the stored secrets: label, provider and scope (values come from the store). */
    fun accountOf(name: String): Triple<String, String?, CredentialScope>? = records[name]?.let { Triple(it.label, it.provider, it.scope) }

    /** May [name] reach a session whose model belongs to [sessionProvider]? Unknown names are not the vault's to refuse. */
    fun admits(name: String, sessionProvider: String?): Boolean {
        val r = records[name] ?: return true
        return when (r.scope) {
            CredentialScope.ANY_SESSION -> true
            CredentialScope.ITS_PROVIDER_ONLY -> r.provider != null && r.provider == sessionProvider
            CredentialScope.NOT_FOR_AGENTS -> false
        }
    }

    /** [names] left the vault, to [to] (a session's omp, a terminal), for [purpose]: an exposure grant, journaled. */
    @Synchronized
    fun recordExposure(names: Collection<String>, to: String, purpose: String) {
        val now = store.clock.nowMs()
        for (name in names.sorted()) {
            exposures.append("credential.exposed", buildJsonObject { put("name", name); put("to", to); put("purpose", purpose) })
            records.update(name) { it.copy(exposures = it.exposures + 1, lastExposedAtMs = now, lastExposedTo = to) }
        }
    }

    /** Whether the exposure record is intact: every entry chained to the one before it. */
    fun verifyExposures(): ChainCheck = exposures.verify()
}
