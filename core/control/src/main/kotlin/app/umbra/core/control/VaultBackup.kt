package app.umbra.core.control

import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One stored secret in a backup: its value and the vault's account of it. */
@Serializable
data class BackedUpSecret(val name: String, val value: String, val label: String, val provider: String? = null, val scope: CredentialScope = CredentialScope.ANY_SESSION)

/** One git credential in a backup, with its token. Publishing authority is deliberately not part of it. */
@Serializable
data class BackedUpGitCredential(val label: String, val url: String, val username: String, val operations: Set<GitOperation>, val caCertificate: String? = null, val token: String)

@Serializable
data class VaultBackupBody(
    val format: String = VaultBackup.FORMAT,
    val createdAtMs: Long,
    val secrets: List<BackedUpSecret> = emptyList(),
    val git: List<BackedUpGitCredential> = emptyList(),
)

/** What a restore did, item by item. */
data class RestoreReport(val restored: List<String>, val kept: List<String>, val gitRestored: List<String>, val gitKept: List<String>)

/**
 * The vault's recovery backup (architecture spec §12.3, AC-018). Recovery needs material kept apart from the phone and
 * from the file: a random recovery code (125 bits), shown once and never stored, from which the key is derived. The
 * file is AES-256-GCM, so a changed file does not open. Restoring brings secrets back, never authority: git
 * credentials return unable to publish until the user grants it again, and no promotion, capability, lease or scope
 * beyond the record of who may receive a model key is in a backup at all. A value already present is kept.
 */
object VaultBackup {
    const val HEADER = "UMBRA-VAULT-1"
    const val FORMAT = "umbra-vault-1"
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** A new recovery code: 25 symbols of Crockford base32 in five groups, 125 random bits. */
    fun newRecoveryCode(random: SecureRandom = SecureRandom()): String =
        (0 until 25).map { ALPHABET[random.nextInt(32)] }.chunked(5).joinToString("-") { it.joinToString("") }

    /** The code as typed (any case, spaces or dashes, O for 0, I or L for 1), or an error for anything else. */
    fun normalizeCode(typed: String): String {
        val s = typed.uppercase().filterNot { it == '-' || it.isWhitespace() }.map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")
        require(s.length == 25 && s.all { it in ALPHABET }) { "a recovery code is 25 letters and digits" }
        return s.chunked(5).joinToString("-")
    }

    fun seal(body: VaultBackupBody, code: String): ByteArray =
        PassphraseSeal.seal(HEADER, app.umbra.core.model.UmbraJson.encodeToString(VaultBackupBody.serializer(), body).toByteArray(), normalizeCode(code).toCharArray())

    /** Throws when the code is wrong or the file was changed; nothing is restored from a file that does not open. */
    fun open(sealed: ByteArray, code: String): VaultBackupBody {
        val body = app.umbra.core.model.UmbraJson.decodeFromString(VaultBackupBody.serializer(), String(PassphraseSeal.open(HEADER, sealed, normalizeCode(code).toCharArray())))
        require(body.format == FORMAT) { "a vault backup of an unknown format (${body.format})" }
        return body
    }

    /**
     * Restores [body]: a secret whose name is not stored is written with [putSecret] and its scope recorded in
     * [vault]; a git credential whose URL has none is added to [git] with publishing suspended and its token written
     * with [putGitToken]. Anything already present is kept as it is.
     */
    fun restore(
        body: VaultBackupBody,
        storedNames: Set<String>,
        putSecret: (name: String, value: String) -> Unit,
        vault: CredentialVault,
        git: GitCredentials,
        putGitToken: (credentialId: String, token: String) -> Unit,
        journal: Journal,
    ): RestoreReport {
        val restored = ArrayList<String>()
        val kept = ArrayList<String>()
        for (s in body.secrets) {
            if (s.name in storedNames) { kept += s.name; continue }
            // Record and value together, under the vault's lock (its reconcile cannot run in between): the key is never
            // stored without the limit on who may receive it.
            synchronized(vault) {
                vault.restored(s.name, s.label, s.provider, s.scope)
                putSecret(s.name, s.value)
            }
            restored += s.name
        }
        val gitRestored = ArrayList<String>()
        val gitKept = ArrayList<String>()
        for (g in body.git) {
            if (git.forRemote(g.url) != null) { gitKept += g.url; continue }
            val c = git.add(g.label, g.url, g.username, g.operations, g.caCertificate, by = "restore", restored = true)
            putGitToken(c.id, g.token)
            gitRestored += c.url
        }
        journal.append("vault.restored", buildJsonObject {
            put("secrets", restored.joinToString()); put("kept", kept.joinToString())
            put("git", gitRestored.joinToString()); put("git_kept", gitKept.joinToString()); put("publishing", "suspended")
        })
        return RestoreReport(restored, kept, gitRestored, gitKept)
    }
}
