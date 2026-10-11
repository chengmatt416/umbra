package app.umbra.core.control

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a brokered git credential may be used for (architecture spec §12.1: scoped capabilities, not raw secrets). */
@Serializable
enum class GitOperation(val label: String) {
    /** Read the remote: its refs (validation, read-back) and its commits (fetched into a checkout through the broker). */
    READ("Read"),

    /** Write the remote, only through the commit gate, for one promotion the user authorized. */
    PUBLISH("Publish"),
}

/**
 * A git credential the broker holds (architecture spec §12, AC-015): never its value, which stays encrypted under the
 * keystore and is given to no agent. It is bound to one exact https [url]: the broker uses it for that remote only,
 * whatever a checkout's own configuration says.
 */
@Serializable
data class GitCredential(
    val id: String,
    val label: String,
    /** The one remote it is for, normalized: `https://host/path` (no user, query, fragment, trailing `.git` or `/`). */
    val url: String,
    val username: String = "x-access-token",
    val operations: Set<GitOperation> = setOf(GitOperation.READ, GitOperation.PUBLISH),
    /** A PEM certificate to trust for a self-hosted server with its own CA; null: the system's trust store. */
    val caCertificate: String? = null,
    val createdAtMs: Long,
    val rotatedAtMs: Long? = null,
    val uses: Long = 0,
    val lastUsedAtMs: Long? = null,
    val lastUse: String? = null,
    /**
     * Set when the credential came back from a vault backup: restoring a secret does not restore the authority to
     * publish with it; the user grants that again (AC-018).
     */
    val publishSuspended: Boolean = false,
)

class GitCredentialException(message: String) : Exception(message)

/** The broker's account of its git credentials, and every use of one in a hash-chained journal. */
class GitCredentials(private val store: ControlStore) {
    private val docs = store.docs("git-credentials", GitCredential.serializer(), GitCredential::id)
    private val journal = store.journal("git-broker")

    val all: StateFlow<Map<String, GitCredential>> = docs.all
    val recent = journal.recent

    operator fun get(id: String): GitCredential? = docs[id]

    fun list(): List<GitCredential> = docs.list().sortedBy { it.createdAtMs }

    /** The credential bound to [remote] (a URL, compared normalized), or null: a remote name never matches. */
    fun forRemote(remote: String): GitCredential? {
        val u = runCatching { normalize(remote) }.getOrNull() ?: return null
        return docs.list().firstOrNull { it.url == u }
    }

    @Synchronized
    fun add(label: String, url: String, username: String, operations: Set<GitOperation>, caCertificate: String?, by: String = "user"): GitCredential {
        val u = normalize(url)
        if (docs.list().any { it.url == u }) throw GitCredentialException("a credential for $u is already held; rotate it instead")
        require(username.matches(Regex("[A-Za-z0-9._@+-]{1,100}"))) { "“$username” is not a user name git can send" }
        require(operations.isNotEmpty()) { "a credential with no operation is of no use" }
        val ca = caCertificate?.trim()?.takeIf { it.isNotEmpty() }
        require(ca == null || (ca.startsWith("-----BEGIN CERTIFICATE-----") && ca.endsWith("-----END CERTIFICATE-----"))) { "the CA certificate must be one PEM certificate" }
        val now = store.clock.nowMs()
        val c = docs.put(GitCredential("git-" + ControlStore.sha256("$u|$now").take(12), label.ifBlank { u.substringAfter("://") }, u, username, operations, ca, now))
        journal.append("git.credential_added", buildJsonObject { put("id", c.id); put("url", u); put("operations", operations.joinToString { it.name.lowercase() }); put("by", by) })
        return c
    }

    @Synchronized
    fun setOperations(id: String, operations: Set<GitOperation>, by: String = "user") {
        docs.update(id) { it.copy(operations = operations, publishSuspended = if (GitOperation.PUBLISH in operations) false else it.publishSuspended) }
            ?: throw GitCredentialException("no credential $id")
        journal.append("git.credential_scope", buildJsonObject { put("id", id); put("operations", operations.joinToString { it.name.lowercase() }); put("by", by) })
    }

    /** The user gives a restored credential its publishing authority back. */
    @Synchronized
    fun restorePublish(id: String, by: String = "user") {
        docs.update(id) { it.copy(publishSuspended = false) } ?: throw GitCredentialException("no credential $id")
        journal.append("git.publish_restored", buildJsonObject { put("id", id); put("by", by) })
    }

    @Synchronized
    fun rotated(id: String, by: String = "user") {
        docs.update(id) { it.copy(rotatedAtMs = store.clock.nowMs()) } ?: throw GitCredentialException("no credential $id")
        journal.append("git.credential_rotated", buildJsonObject { put("id", id); put("by", by) })
    }

    /** Umbra's copy is gone; the token stays valid at its issuer until it is revoked there. */
    @Synchronized
    fun revoked(id: String, by: String = "user") {
        val c = docs[id] ?: return
        docs.delete(id)
        journal.append("git.credential_revoked_locally", buildJsonObject { put("id", id); put("url", c.url); put("by", by) })
    }

    /** Throws unless [id] may be used for [operation] now. */
    fun authorize(id: String, operation: GitOperation): GitCredential {
        val c = docs[id] ?: throw GitCredentialException("no credential $id")
        if (operation !in c.operations) throw GitCredentialException("“${c.label}” is not allowed to ${operation.label.lowercase()} (${c.url})")
        if (operation == GitOperation.PUBLISH && c.publishSuspended) throw GitCredentialException("“${c.label}” came back from a backup: grant it publishing again before it is used to publish")
        return c
    }

    /** One brokered use: what, where, and how it ended. Never the value. */
    @Synchronized
    fun used(id: String, operation: GitOperation, what: String, outcome: String) {
        val now = store.clock.nowMs()
        docs.update(id) { it.copy(uses = it.uses + 1, lastUsedAtMs = now, lastUse = "$what: $outcome".take(160)) }
        journal.append("git.brokered", buildJsonObject { put("id", id); put("operation", operation.name.lowercase()); put("what", what.take(200)); put("outcome", outcome.take(200)) })
    }

    fun verifyJournal(): ChainCheck = journal.verify()

    companion object {
        /**
         * `https://host[:port]/path`, lowercased host, without user info, query, fragment, trailing `/` or `.git`.
         * Anything else is refused: the broker only speaks https, and a URL is the whole of a credential's reach.
         */
        fun normalize(url: String): String {
            val t = url.trim()
            val m = Regex("^https://([A-Za-z0-9.-]+)(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)$").matchEntire(t)
                ?: throw GitCredentialException("“$t” is not an https repository URL (https://host/owner/repo)")
            val path = m.groupValues[3].trimEnd('/').removeSuffix(".git").trimEnd('/')
            if (path.isEmpty() || path.split('/').any { it == ".." || it == "." }) throw GitCredentialException("“$t” names no repository")
            return "https://" + m.groupValues[1].lowercase() + m.groupValues[2] + path
        }
    }
}

/**
 * The commands the broker runs as root inside the Linux environment (with root only: under proot root is not separate
 * from the agent). Each is `bash -c SCRIPT umbra-broker OP USER URL …` with the token as the first line of stdin, so
 * it is never in a command line, a file or an environment the agent can read. Everything git does here happens in a
 * fresh repository in a root-only folder, with hooks off, no system or global configuration, other credential helpers
 * cleared, objects checked, no redirects and https only; a checkout's own configuration is never read.
 */
object GitBrokerScript {
    /** The environment the script runs with; [stageParent] is root's own folder in the Linux environment. */
    fun environment(stageParent: String = "/root", ca: String? = null): Map<String, String> = buildMap {
        put("HOME", stageParent)
        put("PATH", "/usr/bin:/bin")
        put("LANG", "C.UTF-8")
        put("GIT_CONFIG_NOSYSTEM", "1")
        put("GIT_CONFIG_GLOBAL", "/dev/null")
        put("GIT_TERMINAL_PROMPT", "0")
        put("GIT_ASKPASS", "/bin/false")
        put("UMBRA_STAGE_PARENT", stageParent)
        if (ca != null) put("UMBRA_CA", ca)
    }

    fun lsRemote(c: GitCredential, ref: String) = listOf("bash", "-c", SCRIPT, "umbra-broker", "ls-remote", c.username, c.url, checkRef(ref))

    /** Publishes [commit] (carried by [bundle], a file the agent's git wrote, holding [bundleRef]) with a lease. */
    fun push(c: GitCredential, ref: String, commit: String, expectedOld: String?, bundle: String, bundleRef: String) =
        listOf("bash", "-c", SCRIPT, "umbra-broker", "push", c.username, c.url, checkRef(ref), checkSha(commit), expectedOld?.let(::checkSha).orEmpty(), bundle, checkRef(bundleRef))

    /** Reads the remote's [ref] into a bundle in a fresh root-owned, world-readable folder; prints the folder and the commit. */
    fun fetch(c: GitCredential, ref: String) = listOf("bash", "-c", SCRIPT, "umbra-broker", "fetch", c.username, c.url, checkRef(ref))

    /** Removes a folder [fetch] made, once the agent's git has read the bundle in it. */
    fun clean(c: GitCredential, folder: String) = listOf("bash", "-c", SCRIPT, "umbra-broker", "clean", c.username, c.url, folder)

    private fun checkRef(ref: String): String {
        require(ref.matches(Regex("refs/[A-Za-z0-9._/-]+")) && ".." !in ref) { "“$ref” is not a ref the broker writes" }
        return ref
    }

    private fun checkSha(sha: String): String {
        require(sha.matches(Regex("[0-9a-f]{40}([0-9a-f]{24})?"))) { "“$sha” is not a full commit id" }
        return sha
    }

    const val SCRIPT = """set -eu
umask 077
UMBRA_TOKEN=""
token() {
  IFS= read -r UMBRA_TOKEN || true
  [ -n "${'$'}UMBRA_TOKEN" ] || { echo "umbra-broker: no token" >&2; exit 64; }
  export UMBRA_TOKEN
}
UMBRA_USER=${'$'}2
export UMBRA_USER
op=${'$'}1; url=${'$'}3
stage=${'$'}(mktemp -d "${'$'}{UMBRA_STAGE_PARENT:-/root}/umbra-broker.XXXXXX")
trap 'rm -rf "${'$'}stage"' EXIT
extra=()
if [ -n "${'$'}{UMBRA_CA:-}" ]; then printf '%s\n' "${'$'}UMBRA_CA" > "${'$'}stage/ca.pem"; extra=(-c "http.sslCAInfo=${'$'}stage/ca.pem"); fi
git init -q --bare "${'$'}stage/repo"
g() {
  git -C "${'$'}stage/repo" -c core.hooksPath=/dev/null -c core.fsmonitor=false \
    -c transfer.fsckObjects=true -c fetch.fsckObjects=true -c receive.fsckObjects=true \
    -c credential.helper= -c 'credential.helper=!f() { test "${'$'}1" = get || exit 0; echo "username=${'$'}UMBRA_USER"; echo "password=${'$'}UMBRA_TOKEN"; }; f' \
    -c credential.interactive=false -c http.followRedirects=false \
    -c protocol.allow=never -c protocol.https.allow=always -c protocol.file.allow=always \
    ${'$'}{extra[@]+"${'$'}{extra[@]}"} "${'$'}@"
}
case "${'$'}op" in
  ls-remote)
    token
    g ls-remote -- "${'$'}url" "${'$'}4" ;;
  push)
    token
    ref=${'$'}4; commit=${'$'}5; expected=${'$'}6; bundle=${'$'}7; bref=${'$'}8
    { [ -f "${'$'}bundle" ] && [ ! -L "${'$'}bundle" ]; } || { echo "umbra-broker: the candidate bundle is missing" >&2; exit 65; }
    cp -- "${'$'}bundle" "${'$'}stage/candidate.bundle"
    if [ -n "${'$'}expected" ]; then g fetch -q -- "${'$'}url" "+${'$'}ref:refs/umbra/remote" || { echo "umbra-broker: could not read ${'$'}ref from the remote" >&2; exit 66; }; fi
    g fetch -q -- "${'$'}stage/candidate.bundle" "+${'$'}bref:refs/umbra/candidate"
    got=${'$'}(g rev-parse --verify -q refs/umbra/candidate)
    [ "${'$'}got" = "${'$'}commit" ] || { echo "umbra-broker: the bundle does not hold the validated commit" >&2; exit 67; }
    g push --porcelain "--force-with-lease=${'$'}ref:${'$'}expected" -- "${'$'}url" "${'$'}commit:${'$'}ref" ;;
  fetch)
    token
    ref=${'$'}4
    out=${'$'}(mktemp -d "${'$'}{TMPDIR:-/tmp}/umbra-broker-out.XXXXXX")
    g fetch -q -- "${'$'}url" "+${'$'}ref:refs/umbra/remote"
    g bundle create -q "${'$'}out/remote.bundle" refs/umbra/remote
    chmod 0755 "${'$'}out"; chmod 0644 "${'$'}out/remote.bundle"
    echo "${'$'}out"; g rev-parse refs/umbra/remote ;;
  clean)
    case "${'$'}4" in "${'$'}{TMPDIR:-/tmp}"/umbra-broker-out.*) rm -rf -- "${'$'}4" ;; *) echo "umbra-broker: not a broker folder" >&2; exit 64 ;; esac ;;
  *) echo "umbra-broker: unknown operation" >&2; exit 64 ;;
esac
"""
}
