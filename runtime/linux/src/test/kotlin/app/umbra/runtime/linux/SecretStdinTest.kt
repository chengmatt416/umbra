package app.umbra.runtime.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SecretStdinTest {
    /** Runs the real wrapper under bash, as the guest does, with [rest] following the secrets on stdin. */
    private fun run(secrets: Map<String, String>, rest: String, script: String): String {
        val process = ProcessBuilder("bash", "-c", "umask 022; " + SecretStdin.PREAMBLE + "exec \"\$@\"", "umbra", "bash", "-c", script)
            .redirectErrorStream(true).start()
        process.outputStream.use { it.write((SecretStdin.payload(secrets) + rest).toByteArray()) }
        val out = process.inputStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), out)
        return out
    }

    @Test
    fun valuesOfAnyShapeArriveExactlyAndTheRestOfStdinIsLeftForTheCommand() {
        val tricky = "a'b\"c \$(touch /tmp/never) `x` \\ é 🔑"
        val out = run(mapOf("API_KEY" to "sk-123", "TRICKY" to tricky, "EMPTY" to ""), "rpc line 1\nrpc line 2\n",
            "printf '%s|%s|[%s]\\n' \"\$API_KEY\" \"\$TRICKY\" \"\$EMPTY\"; cat")
        assertEquals("sk-123|$tricky|[]\nrpc line 1\nrpc line 2\n", out)
        assertFalse(java.io.File("/tmp/never").exists())
    }

    @Test
    fun theWrapperLeavesNoHelperVariablesAndSecretsNeverAppearInTheCommandLine() {
        val out = run(mapOf("TOKEN" to "s3cret"), "", "env | grep -c __umbra_ || true")
        assertEquals("0\n", out)
        val command = "bash -c " + SecretStdin.PREAMBLE
        assertFalse("s3cret" in command)
    }

    @Test
    fun invalidOrReservedNamesAreRejected() {
        assertFailsWith<IllegalArgumentException> { SecretStdin.lines(mapOf("BAD NAME" to "x")) }
        assertFailsWith<IllegalArgumentException> { SecretStdin.lines(mapOf("__umbra_l" to "x")) }
    }
}
