package app.umbra.platform.shell

import app.umbra.platform.api.BackendException
import app.umbra.platform.api.BinaryShellResult
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellEvent
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.ShellResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * How an APK reaches `pm install`: by path when the shell user can read it, and on standard input
 * (`-S <size>`, as `adb install` does) when only the app can, which is the case for anything an agent
 * built in its workspace.
 */
class PackageInstallTest {
    /** A shell that records what it was asked and whose view of the file system is a switch. */
    private class RecordingShell(private val shellCanRead: Boolean, private val reply: String = "Success") : ShellBackend {
        val requests = mutableListOf<ShellRequest>()
        var received: ByteArray? = null
        override val name = "recording"
        override val identity = ShellIdentity.SHELL

        override suspend fun isAvailable() = true

        override suspend fun exec(request: ShellRequest): ShellResult {
            requests += request
            if (request.command.startsWith("test -r")) return ShellResult(if (shellCanRead) 0 else 1, "")
            request.stdinSource?.let { source -> received = source().use { it.readBytes() } }
            return ShellResult(0, "$reply\n")
        }

        override suspend fun execBytes(request: ShellRequest) = BinaryShellResult(0, ByteArray(0))

        override fun stream(request: ShellRequest): Flow<ShellEvent> = emptyFlow()
    }

    private fun apk(bytes: ByteArray = ByteArray(5_000) { (it * 7).toByte() }): File =
        File.createTempFile("umbra-install-", ".apk").apply { writeBytes(bytes); deleteOnExit() }

    @Test
    fun `a file the shell can read is installed by its path`() = runBlocking {
        val shell = RecordingShell(shellCanRead = true)
        val file = apk()
        assertEquals("Success", ShellPackages(shell).install(file.path))
        val install = shell.requests.last()
        assertTrue(install.command.startsWith("pm install -r --user 0 "), install.command)
        assertTrue(install.command.endsWith(file.path), install.command)
        assertEquals(null, install.stdinSource)
    }

    @Test
    fun `a file only the app can read is streamed to pm install`() = runBlocking {
        val shell = RecordingShell(shellCanRead = false)
        val content = ByteArray(70_000) { (it % 251).toByte() }
        val file = apk(content)
        assertEquals("Success", ShellPackages(shell).install(file.path))
        val install = shell.requests.last()
        assertEquals("pm install -r --user 0 -S ${content.size}", install.command)
        assertTrue(shell.received contentEquals content, "pm received exactly the bytes of the APK")
    }

    @Test
    fun `a path nobody can read is left to pm to report`() = runBlocking {
        val shell = RecordingShell(shellCanRead = false, reply = "Failure [INSTALL_FAILED_INVALID_URI]")
        val missing = File(System.getProperty("java.io.tmpdir"), "umbra-no-such-${System.nanoTime()}.apk")
        val failure = assertFailsWith<BackendException> { ShellPackages(shell).install(missing.path) }
        assertTrue("INSTALL_FAILED_INVALID_URI" in failure.message.orEmpty(), failure.message)
        assertTrue(shell.requests.last().command.contains(missing.path), "it asked pm about the path it was given")
        Unit
    }
}
