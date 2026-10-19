package app.umbra.core.workspace

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class GitProtectionTest {
    @TempDir lateinit var root: Path

    private fun command(cwd: String, args: List<String>): GitResult {
        val process = ProcessBuilder(listOf("git") + args).directory(Path.of(cwd).toFile()).redirectErrorStream(true).apply {
            environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
            environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            environment()["GIT_TERMINAL_PROMPT"] = "0"
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        return GitResult(code, output, if (code == 0) "" else output)
    }

    private fun git(folder: Path, vararg args: String): String {
        val result = command(folder.toString(), listOf("-c", "user.name=Test", "-c", "user.email=test@localhost", "-c", "commit.gpgSign=false") + args)
        check(result.code == 0) { result.stderr }
        return result.stdout.trim()
    }

    @Test fun `new folders get an initial repository and repeat checkpoints do not add empty history`(): Unit = runBlocking {
        val source = root.resolve("project with spaces").createDirectories()
        source.resolve("notes.txt").writeText("before")
        val protection = GitProtection { args, cwd -> command(cwd, args) }
        protection.initializeFolder(source.toString())
        assertEquals("before", git(source, "show", "HEAD:notes.txt"))
        val backup = root.resolve("recovery.git").toString()
        val first = protection.checkpoint(backup, source.toString())
        assertEquals(first, protection.checkpoint(backup, source.toString()))
        source.resolve("notes.txt").writeText("after")
        assertNotEquals(first, protection.checkpoint(backup, source.toString()))
        assertEquals(2, protection.history(backup, root.toString()).size)
    }

    @Test fun `existing staged and unstaged work survives with index and HEAD untouched and ignored data recoverable`(): Unit = runBlocking {
        val source = root.resolve("existing").createDirectories()
        source.resolve("tracked.txt").writeText("committed")
        git(source, "init", "-q")
        git(source, "add", ".")
        git(source, "commit", "-q", "-m", "user commit")
        source.resolve("tracked.txt").writeText("staged")
        git(source, "add", "tracked.txt")
        source.resolve("tracked.txt").writeText("unstaged")
        source.resolve(".gitignore").writeText(".env\n")
        source.resolve(".env").writeText("synthetic fixture")
        val bytes = ByteArray(256) { it.toByte() }
        Files.write(source.resolve("binary.dat"), bytes)
        val head = git(source, "rev-parse", "HEAD")
        val index = Files.readAllBytes(source.resolve(".git/index"))
        val protection = GitProtection { args, cwd -> command(cwd, args) }
        protection.initializeFolder(source.toString())
        val backup = root.resolve("private.git").toString()
        val checkpoint = protection.checkpoint(backup, source.toString())
        assertEquals(head, git(source, "rev-parse", "HEAD"))
        assertContentEquals(index, Files.readAllBytes(source.resolve(".git/index")))
        assertEquals("unstaged", source.resolve("tracked.txt").readText())
        Files.delete(source.resolve("tracked.txt"))
        val archive = root.resolve("recover.tar")
        protection.archive(backup, checkpoint, archive.toString(), root.toString())
        val recovered = root.resolve("recovered").createDirectories()
        val extraction = ProcessBuilder("tar", "-xf", archive.toString(), "-C", recovered.toString()).start()
        assertEquals(0, extraction.waitFor())
        assertEquals("unstaged", recovered.resolve("tracked.txt").readText())
        assertEquals("synthetic fixture", recovered.resolve(".env").readText())
        assertContentEquals(bytes, Files.readAllBytes(recovered.resolve("binary.dat")))
        assertFalse(Files.exists(source.resolve("tracked.txt")), "recovering a copy does not overwrite originals")
    }

    @Test fun `a single file checkpoint does not capture its siblings`(): Unit = runBlocking {
        val source = root.resolve("documents").createDirectories()
        source.resolve("selected.txt").writeText("selected original")
        source.resolve("other.txt").writeText("not selected")
        val backup = root.resolve("single.git").toString()
        val protection = GitProtection { args, cwd -> command(cwd, args) }
        protection.checkpoint(backup, source.toString(), "selected.txt")
        val names = git(root, "--git-dir=$backup", "ls-tree", "--name-only", "HEAD")
        assertEquals("selected.txt", names)
        assertTrue(protection.history(backup, root.toString()).isNotEmpty())
    }
}
