package app.umbra.agent.integration

import app.umbra.agent.AppContainer
import app.umbra.core.control.CommandGit
import app.umbra.core.control.GitRun

/** A git repository in the Linux environment's workspace, as it is now. */
data class Repository(val path: String, val branch: String?, val head: String?, val remotes: List<String>, val session: String?)

/**
 * Git as the commit gate uses it: run in the Linux environment as the agent's user (the workspace's owner), with no
 * terminal prompt (a push that needs a password fails instead of waiting), using the repository's own credentials.
 */
fun guestGit(c: AppContainer) = CommandGit { args ->
    val r = c.guest.runtime.exec(listOf("git") + args, env = mapOf("GIT_TERMINAL_PROMPT" to "0", "GIT_ASKPASS" to "/bin/false"), timeoutMs = 120_000, maxOutputBytes = 512 * 1024)
    GitRun(r.exitCode, r.stdout, r.stderr)
}

/** Finds the repositories under `/workspace`, each with its branch, HEAD, remotes and the session working in it. */
suspend fun findRepositories(c: AppContainer): List<Repository> {
    val found = c.guest.runtime.exec(listOf("sh", "-c", "find /workspace -maxdepth 5 -name .git -prune 2>/dev/null | sed 's#/\\.git$##' | sort"), timeoutMs = 30_000)
    if (found.exitCode != 0) return emptyList()
    val sessions = c.sessionStore.list()
    val layout = c.workspaceLayout
    return found.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { path ->
        suspend fun g(vararg a: String) = c.guest.runtime.exec(listOf("git", "-C", path) + a, timeoutMs = 20_000).takeIf { it.exitCode == 0 }?.stdout?.trim()
        val owner = sessions.firstOrNull { s ->
            val dir = s.workspaceId?.let { layout.guestProjectDir(it) } ?: "${layout.guestSessionDir(s.id)}/files"
            path == dir || path.startsWith("$dir/")
        }
        Repository(
            path = path,
            branch = g("symbolic-ref", "--quiet", "--short", "HEAD"),
            head = g("rev-parse", "--short", "HEAD"),
            remotes = g("remote")?.lines()?.filter { it.isNotBlank() }.orEmpty(),
            session = owner?.title,
        )
    }
}
