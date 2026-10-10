package app.umbra.core.control

/** The result of one git command. */
data class GitRun(val exit: Int, val stdout: String, val stderr: String)

/**
 * [GitPort] over the `git` command line, wherever [run] runs it (the Linux environment on the phone, the host in
 * tests). [run] takes git's arguments, without `git` itself.
 */
class CommandGit(private val run: suspend (args: List<String>) -> GitRun) : GitPort {
    private suspend fun git(repo: String, vararg args: String) = run(listOf("-C", repo) + args)

    private suspend fun ok(repo: String, vararg args: String): String {
        val r = git(repo, *args)
        if (r.exit != 0) throw IllegalStateException("git ${args.first()} failed (${r.exit}): ${r.stderr.trim().take(300)}")
        return r.stdout
    }

    override suspend fun resolve(repo: String, rev: String): String? =
        git(repo, "rev-parse", "--verify", "--quiet", "$rev^{commit}").takeIf { it.exit == 0 }?.stdout?.trim()?.ifEmpty { null }

    override suspend fun currentBranch(repo: String): String? =
        git(repo, "symbolic-ref", "--quiet", "--short", "HEAD").takeIf { it.exit == 0 }?.stdout?.trim()?.ifEmpty { null }

    override suspend fun lsRemote(repo: String, remote: String, ref: String): String? {
        val out = ok(repo, "ls-remote", "--", remote, ref)
        return out.lines().map { it.split('\t') }.firstOrNull { it.size == 2 && it[1].trim() == ref }?.get(0)?.trim()
    }

    override suspend fun isAncestor(repo: String, ancestor: String, descendant: String): Boolean {
        val r = git(repo, "merge-base", "--is-ancestor", ancestor, descendant)
        return when (r.exit) {
            0 -> true
            1 -> false
            else -> throw IllegalStateException("git merge-base failed (${r.exit}): ${r.stderr.trim().take(300)}")
        }
    }

    override suspend fun log(repo: String, range: String, max: Int): List<String> =
        ok(repo, "log", "--oneline", "--no-decorate", "-n", max.toString(), range).lines().filter { it.isNotBlank() }

    override suspend fun diffStat(repo: String, from: String?, to: String): String =
        (if (from != null) ok(repo, "diff", "--stat", from, to) else ok(repo, "show", "--stat", "--format=", to)).trim()

    override suspend fun tree(repo: String, commit: String): String = ok(repo, "rev-parse", "$commit^{tree}").trim()

    override suspend fun pushWithLease(repo: String, remote: String, commit: String, ref: String, expectedOld: String?) {
        val r = git(repo, "push", "--porcelain", "--force-with-lease=$ref:${expectedOld.orEmpty()}", remote, "$commit:$ref")
        if (r.exit == 0) return
        val said = (r.stdout + "\n" + r.stderr)
        if ("stale info" in said || "[rejected]" in said || "! " in r.stdout) throw LeaseRejected(said.lines().firstOrNull { "!" in it || "rejected" in it }?.trim() ?: said.trim().take(300))
        throw IllegalStateException("git push failed (${r.exit}): ${r.stderr.trim().take(300)}")
    }
}
