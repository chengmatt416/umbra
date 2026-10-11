package app.umbra.core.control

/**
 * Git for the commit gate with a credential broker in front of the remote (architecture spec §12, AC-015). A remote
 * that is the URL of a [GitCredential] is read and written by root through [GitBrokerScript], with the token handed
 * over on stdin; the agent's git only does what needs no credential: reading its own checkout and writing the
 * candidate into a bundle. Any other remote goes to [local] unchanged, with whatever credentials the checkout has.
 *
 * [root] runs a command as root in the Linux environment with an environment and stdin; [agent] runs one as the
 * agent; [token] reads a credential's value from the vault; [unavailable] says why brokering is impossible here (no
 * root: root and the agent would be the same user), or null.
 */
class BrokeredGit(
    private val local: CommandGit,
    private val credentials: GitCredentials,
    private val token: (credentialId: String) -> String?,
    private val root: suspend (argv: List<String>, env: Map<String, String>, stdin: String?) -> GitRun,
    private val agent: suspend (argv: List<String>) -> GitRun,
    private val stageParent: String = "/root",
    private val agentTemp: String = "/tmp",
    private val unavailable: () -> String? = { null },
) : GitPort by local {

    override suspend fun lsRemote(repo: String, remote: String, ref: String): String? {
        val c = credentials.forRemote(remote) ?: return local.lsRemote(repo, remote, ref)
        val t = ready(c, null)
        val r = run(c, GitBrokerScript.lsRemote(c, ref), t)
        if (r.exit != 0) {
            credentials.used(c.id, GitOperation.READ, "ls-remote $ref", "failed (${r.exit})")
            throw IllegalStateException("the broker could not read ${c.url}: ${clean(r.stderr, t)}")
        }
        credentials.used(c.id, GitOperation.READ, "ls-remote $ref", "read")
        return r.stdout.lines().map { it.split('\t') }.firstOrNull { it.size == 2 && it[1].trim() == ref }?.get(0)?.trim()
    }

    override suspend fun pushWithLease(repo: String, remote: String, commit: String, ref: String, expectedOld: String?) {
        val c = credentials.forRemote(remote) ?: return local.pushWithLease(repo, remote, commit, ref, expectedOld)
        val t = ready(c, GitOperation.PUBLISH)
        val dir = agent(listOf("mktemp", "-d", "$agentTemp/umbra-candidate.XXXXXX")).let { if (it.exit != 0) throw IllegalStateException("no folder for the candidate: ${it.stderr.trim()}"); it.stdout.trim() }
        val bundle = "$dir/candidate.bundle"
        try {
            // The agent's git writes the candidate (and only what the remote lacks) into a bundle: data, not a request.
            check(local.ask(repo, "update-ref", BUNDLE_REF, commit).exit == 0) { "could not mark the candidate in $repo" }
            val base = expectedOld?.takeIf { local.resolve(repo, it) != null }?.let { listOf("^$it") }.orEmpty()
            val made = local.ask(repo, *(listOf("bundle", "create", "-q", bundle, BUNDLE_REF) + base).toTypedArray())
            check(made.exit == 0) { "could not bundle the candidate: ${made.stderr.trim().take(300)}" }
            val r = run(c, GitBrokerScript.push(c, ref, commit, expectedOld, bundle, BUNDLE_REF), t)
            val said = clean(r.stdout + "\n" + r.stderr, t)
            when {
                r.exit == 0 -> credentials.used(c.id, GitOperation.PUBLISH, "push $ref ${commit.take(12)}", "accepted")
                "stale info" in said || "[rejected]" in said || "\n!" in "\n" + r.stdout -> {
                    credentials.used(c.id, GitOperation.PUBLISH, "push $ref ${commit.take(12)}", "refused by the lease")
                    throw LeaseRejected(said.lines().firstOrNull { "!" in it || "rejected" in it }?.trim() ?: said.trim().take(300))
                }
                else -> {
                    credentials.used(c.id, GitOperation.PUBLISH, "push $ref ${commit.take(12)}", "failed (${r.exit})")
                    throw IllegalStateException("the brokered push failed (${r.exit}): ${clean(r.stderr, t).trim().take(300)}")
                }
            }
        } finally {
            runCatching { local.ask(repo, "update-ref", "-d", BUNDLE_REF) }
            runCatching { agent(listOf("rm", "-rf", "--", dir)) }
        }
    }

    /**
     * Brings the remote's [branch] into [repo] as `refs/remotes/umbra-broker/<branch>` without the checkout ever
     * holding the credential: root fetches it into a bundle, the agent's git reads the bundle. Returns its commit.
     */
    suspend fun fetchThroughBroker(repo: String, credentialId: String, branch: String): String {
        val c = credentials.authorize(credentialId, GitOperation.READ)
        val t = ready(c, GitOperation.READ)
        val ref = "refs/heads/$branch"
        val r = run(c, GitBrokerScript.fetch(c, ref), t)
        if (r.exit != 0) {
            credentials.used(c.id, GitOperation.READ, "fetch $ref", "failed (${r.exit})")
            throw IllegalStateException("the broker could not fetch $branch from ${c.url}: ${clean(r.stderr, t).trim().take(300)}")
        }
        val (folder, sha) = r.stdout.trim().lines().let { it.first().trim() to it.last().trim() }
        try {
            val got = local.ask(repo, "fetch", "-q", "--no-write-fetch-head", "$folder/remote.bundle", "+refs/umbra/remote:refs/remotes/umbra-broker/$branch")
            check(got.exit == 0) { "the checkout could not read the fetched commits: ${got.stderr.trim().take(300)}" }
        } finally {
            runCatching { root(GitBrokerScript.clean(c, folder), GitBrokerScript.environment(stageParent), null) }
        }
        credentials.used(c.id, GitOperation.READ, "fetch $ref", "read ${sha.take(12)}")
        return sha
    }

    private fun ready(c: GitCredential, operation: GitOperation?): String {
        unavailable()?.let { throw GitCredentialException(it) }
        if (operation != null) credentials.authorize(c.id, operation)
        return token(c.id)?.takeIf { it.isNotBlank() } ?: throw GitCredentialException("the token for “${c.label}” is not in the vault any more")
    }

    private suspend fun run(c: GitCredential, argv: List<String>, t: String) = root(argv, GitBrokerScript.environment(stageParent, c.caCertificate), t + "\n")

    /** Whatever git said, without the token in it, should it ever echo it. */
    private fun clean(text: String, t: String) = if (t.length >= 4) text.replace(t, "•••") else text

    companion object {
        const val BUNDLE_REF = "refs/umbra/candidate"
    }
}
