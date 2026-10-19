package app.umbra.core.workspace

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GitResult(val code: Int, val stdout: String = "", val stderr: String = "")
data class GitCheckpoint(val id: String, val at: String, val message: String)

/** Private recovery history has its own index: snapshots never stage or commit the user's existing repository. */
class GitProtection(private val run: suspend (args: List<String>, cwd: String) -> GitResult) {
    private val mutex = Mutex()
    private val options = listOf("-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false", "-c", "core.createObject=rename", "-c", "commit.gpgSign=false", "-c", "user.name=Umbra", "-c", "user.email=umbra@localhost")

    private suspend fun git(cwd: String, args: List<String>, allowFailure: Boolean = false): GitResult {
        val result = run(options + args, cwd)
        check(allowFailure || result.code == 0) { "Git protection failed: ${result.stderr.ifBlank { result.stdout }.take(600)}" }
        return result
    }

    suspend fun initializeFolder(folder: String) = mutex.withLock {
        // Android shared storage reports a filesystem owner other than the app. Trust only this selected
        // directory for these commands; never change the user's global Git trust configuration.
        val trust = listOf("-c", "safe.directory=$folder")
        if (git(folder, trust + listOf("rev-parse", "--is-inside-work-tree"), true).code != 0) {
            git(folder, trust + listOf("init", "-q", "--", folder))
            git(folder, trust + listOf("config", "--local", "core.createObject", "rename"))
            git(folder, trust + listOf("add", "-A", "--", "."))
            git(folder, trust + listOf("commit", "-q", "--allow-empty", "-m", "Before Umbra session"))
        }
    }

    suspend fun checkpoint(repository: String, folder: String, file: String? = null): String = mutex.withLock {
        val prefix = listOf("--git-dir=$repository", "--work-tree=$folder")
        git(folder, listOf("init", "-q", "--bare", "--", repository))
        // Include ignored/untracked originals too. Only a selected file is staged for a single-file mount.
        git(folder, prefix + listOf("add", "--force", "-A", "--", file ?: "."))
        val head = git(folder, prefix + listOf("rev-parse", "--verify", "HEAD"), true)
        val unchanged = head.code == 0 && git(folder, prefix + listOf("diff", "--cached", "--quiet"), true).code == 0
        if (!unchanged) git(folder, prefix + listOf("commit", "-q", "--allow-empty", "-m", "Session safety checkpoint"))
        git(folder, prefix + listOf("rev-parse", "HEAD")).stdout.trim()
    }

    suspend fun history(repository: String, cwd: String): List<GitCheckpoint> = mutex.withLock {
        git(cwd, listOf("--git-dir=$repository", "log", "-20", "--format=%H%x09%cI%x09%s")).stdout.lineSequence().mapNotNull {
            val fields = it.split('\t', limit = 3)
            if (fields.size == 3) GitCheckpoint(fields[0], fields[1], fields[2]) else null
        }.toList()
    }

    /** Export to a new recovery directory; recovering never overwrites the live originals. */
    suspend fun archive(repository: String, checkpoint: String, destination: String, cwd: String) = mutex.withLock {
        require(checkpoint.matches(Regex("[0-9a-f]{40,64}"))) { "Invalid checkpoint" }
        git(cwd, listOf("--git-dir=$repository", "archive", "--format=tar", "--output=$destination", checkpoint))
        Unit
    }
}
