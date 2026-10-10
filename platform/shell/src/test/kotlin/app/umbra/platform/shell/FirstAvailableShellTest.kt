package app.umbra.platform.shell

import app.umbra.platform.api.BackendException
import app.umbra.platform.api.BinaryShellResult
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellEvent
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.ShellResult
import app.umbra.platform.api.exec
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

class FirstAvailableShellTest {
    /** A shell that is up or down on demand and counts the questions it is asked. */
    private class Candidate(override val name: String, @Volatile var up: Boolean, private val answerMs: Long = 0) : ShellBackend {
        val probes = AtomicInteger()
        override val identity = ShellIdentity.ROOT

        override suspend fun isAvailable(): Boolean {
            probes.incrementAndGet()
            if (answerMs > 0) delay(answerMs)
            return up
        }

        override suspend fun exec(request: ShellRequest) = ShellResult(0, "$name:${request.command}")

        override suspend fun execBytes(request: ShellRequest) = BinaryShellResult(0, name.toByteArray())

        override fun stream(request: ShellRequest): Flow<ShellEvent> = emptyFlow()
    }

    @Test
    fun `the first candidate that answers runs the command`() = runBlocking {
        val su = Candidate("su", up = false)
        val helper = Candidate("helper", up = true)
        val shell = FirstAvailableShell(ShellIdentity.ROOT, listOf(su, helper))
        assertEquals("helper:id", shell.exec("id").stdout)
        assertTrue(shell.isAvailable())
    }

    @Test
    fun `an earlier candidate wins again as soon as it is back`() = runBlocking {
        var clock = 0L
        val su = Candidate("su", up = false)
        val helper = Candidate("helper", up = true)
        val shell = FirstAvailableShell(ShellIdentity.ROOT, listOf(su, helper), cacheMs = 1_000, now = { clock })
        assertEquals("helper:a", shell.exec("a").stdout)
        su.up = true
        assertEquals("helper:b", shell.exec("b").stdout, "the choice is kept while it is fresh")
        clock += 1_500
        assertEquals("su:c", shell.exec("c").stdout, "and revisited once it is stale")
    }

    @Test
    fun `no candidate means no shell`() = runBlocking {
        val shell = FirstAvailableShell(ShellIdentity.ROOT, listOf(Candidate("su", false), Candidate("helper", false)))
        assertFalse(shell.isAvailable())
        assertFailsWith<BackendException> { shell.exec("id") }
        Unit
    }

    @Test
    fun `callers arriving together ask the candidates once`() = runBlocking {
        // A root manager would otherwise show one prompt per caller.
        val su = Candidate("su", up = true, answerMs = 150)
        val shell = FirstAvailableShell(ShellIdentity.ROOT, listOf(su))
        val results = (1..12).map { n -> async(kotlinx.coroutines.Dispatchers.Default) { shell.exec("c$n").stdout } }.awaitAll()
        assertEquals((1..12).map { "su:c$it" }, results)
        assertEquals(1, su.probes.get(), "one question, one answer for everybody")
    }
}
