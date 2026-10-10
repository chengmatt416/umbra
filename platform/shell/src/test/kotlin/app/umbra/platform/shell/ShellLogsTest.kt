package app.umbra.platform.shell

import app.umbra.core.model.action.LogFilter
import app.umbra.platform.api.BinaryShellResult
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellEvent
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.ShellResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * Reading the log for one app. logcat names the writer by uid, and an app that crashed has no process any more,
 * so the package has to be turned into its uid; the lines below are what Android 16 prints.
 */
class ShellLogsTest {
    private class Shell(private val installed: Map<String, Int>, private val log: String) : ShellBackend {
        val commands = mutableListOf<String>()
        override val name = "scripted"
        override val identity = ShellIdentity.SHELL

        override suspend fun isAvailable() = true

        override suspend fun exec(request: ShellRequest): ShellResult {
            commands += request.command
            val c = request.command
            return when {
                c.startsWith("pm list packages -U ") -> {
                    val wanted = c.removePrefix("pm list packages -U ").trim('\'', ' ')
                    ShellResult(0, installed.filterKeys { it.contains(wanted) }.entries.joinToString("") { "package:${it.key} uid:${it.value}\n" })
                }
                c.startsWith("logcat -d ") -> ShellResult(0, log)
                else -> ShellResult(1, "", "unexpected: $c")
            }
        }

        override suspend fun execBytes(request: ShellRequest) = BinaryShellResult(0, ByteArray(0))

        override fun stream(request: ShellRequest): Flow<ShellEvent> = emptyFlow()
    }

    private val log = """
         1791235897.155  1000 31477 31565 I ActivityManager: Start proc 13353:app.fixture/u0a224 for top-activity {app.fixture/app.fixture.Main}
         1791235909.085 10224 13353 13353 E AndroidRuntime: FATAL EXCEPTION: main
         1791235909.085 10224 13353 13353 E AndroidRuntime: Process: app.fixture, PID: 13353
         1791235909.085 10224 13353 13353 E AndroidRuntime: java.lang.NullPointerException: Attempt to invoke virtual method on a null object reference
         1791235909.086 10224 13353 13353 E AndroidRuntime: 	at app.fixture.Main.signIn(Main.java:87)
         1791235909.200 10199  4000  4000 E SomeoneElse: an unrelated error
         1791235909.300 10224 13353 13353 D Main: not an error
    """.trimIndent()

    @Test
    fun `an app's lines are found by its uid even though its process is gone`() = runBlocking {
        val shell = Shell(mapOf("app.fixture" to 10224, "app.other" to 10199), log)
        val lines = ShellLogs(shell).read(LogFilter(packageName = "app.fixture", minLevel = "E"), 50)
        assertEquals(4, lines.size, lines.joinToString("\n"))
        assertTrue(lines.all { it.uid == 10224 })
        assertTrue(lines.any { it.message.contains("NullPointerException") })
        assertTrue(lines.any { it.message.contains("signIn") }, "the stack trace comes with it")
        assertFalse(lines.any { it.tag == "SomeoneElse" })
        assertFalse(shell.commands.last { it.startsWith("logcat") }.contains("--pid"), "no pid: the process is gone")
    }

    @Test
    fun `a package that is not installed has no lines instead of an error`() = runBlocking {
        val shell = Shell(mapOf("app.other" to 10199), log)
        assertEquals(emptyList(), ShellLogs(shell).read(LogFilter(packageName = "app.fixture"), 50))
    }

    @Test
    fun `a filter that already names a uid is not looked up`() = runBlocking {
        val shell = Shell(emptyMap(), log)
        val lines = ShellLogs(shell).read(LogFilter(packageName = "app.fixture", uid = 10199), 50)
        assertTrue(shell.commands.none { it.startsWith("pm list") })
        assertEquals(0, lines.size, "the uid wins; the package name only labels the request")
    }

    @Test
    fun `without a package nothing is looked up`() = runBlocking {
        val shell = Shell(emptyMap(), log)
        val lines = ShellLogs(shell).read(LogFilter(minLevel = "E"), 50)
        assertTrue(shell.commands.none { it.startsWith("pm list") })
        assertEquals(5, lines.size)
    }
}
