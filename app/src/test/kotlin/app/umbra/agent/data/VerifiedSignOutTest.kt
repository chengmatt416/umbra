package app.umbra.agent.data

import app.umbra.platform.api.ShellResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class VerifiedSignOutTest {
    @Test
    fun failuresNeverRemoveTheLocalAccount(): Unit = runBlocking {
        var forgotten = 0
        for (result in listOf(ShellResult(1, "UMBRA_SIGNOUT_OK"), ShellResult(0, ""), ShellResult(0, "UMBRA_SIGNOUT_OK", timedOut = true), ShellResult(0, "UMBRA_SIGNOUT_OK", truncated = true))) {
            assertFailsWith<IllegalStateException> { signOutVerified({ result }, { forgotten++ }) }
        }
        assertFailsWith<IllegalStateException> { signOutVerified({ error("runtime failed") }, { forgotten++ }) }
        assertFailsWith<CancellationException> { signOutVerified({ throw CancellationException() }, { forgotten++ }) }
        assertEquals(0, forgotten)
    }

    @Test
    fun onlyVerifiedRevocationRemovesTheLocalAccount(): Unit = runBlocking {
        var forgotten = 0
        signOutVerified({ ShellResult(0, "UMBRA_SIGNOUT_OK\n") }, { forgotten++ })
        assertEquals(1, forgotten)
    }
}
