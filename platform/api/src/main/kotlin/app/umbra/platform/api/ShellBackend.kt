package app.umbra.platform.api

import kotlinx.coroutines.flow.Flow

/** Which identity a shell runs as, from least to most privileged. */
enum class ShellIdentity { APP, SHELL, ROOT }

data class ShellRequest(
    val command: String,
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val stdin: String? = null,
    val timeoutMs: Long = 30_000,
    val maxOutputBytes: Int = 64 * 1024,
    /**
     * Binary input, opened when the command starts and copied to its stdin until it ends; closed afterwards.
     * For feeding large archives to `tar` without a temporary file. Used instead of [stdin] when set.
     */
    val stdinSource: (() -> java.io.InputStream)? = null,
)

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String = "",
    /** True when output beyond the request's limit was dropped. */
    val truncated: Boolean = false,
    val timedOut: Boolean = false,
    val durationMs: Long = 0,
    /** Who really ran it, when the backend can fall back between identities. */
    val identity: ShellIdentity? = null,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    /** Both streams, stdout first, for places where the distinction does not matter. */
    val combined: String get() = if (stderr.isBlank()) stdout else if (stdout.isBlank()) stderr else "$stdout\n$stderr"
}

/** Result of a command whose standard output is binary (a PNG from `screencap -p`). */
class BinaryShellResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String = "",
    val timedOut: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

/** One event of a streamed command. */
sealed interface ShellEvent {
    data class Out(val line: String) : ShellEvent

    data class Err(val line: String) : ShellEvent

    /** Always the last event: the command ended. */
    data class Exit(val code: Int) : ShellEvent
}

/** Runs shell commands as one identity. Implementations: app process, Shizuku (shell), `su` (root), adb on a dev machine. */
interface ShellBackend : Backend {
    val identity: ShellIdentity

    suspend fun exec(request: ShellRequest): ShellResult

    /** Like [exec] but returns standard output as raw bytes, for commands such as `screencap -p`. */
    suspend fun execBytes(request: ShellRequest): BinaryShellResult

    /**
     * Runs a long-lived command and emits its output line by line (logcat, a build). Cancelling the
     * collector stops the process. The flow ends with [ShellEvent.Exit].
     */
    fun stream(request: ShellRequest): Flow<ShellEvent>
}

/** Convenience for callers that only have a command line. */
suspend fun ShellBackend.exec(command: String, timeoutMs: Long = 30_000, maxOutputBytes: Int = 64 * 1024): ShellResult =
    exec(ShellRequest(command = command, timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes))

/** Like [exec] but raises a [BackendException] when the command fails, for callers that parse output. */
suspend fun ShellBackend.execOrThrow(command: String, timeoutMs: Long = 30_000, maxOutputBytes: Int = 256 * 1024): String {
    val result = exec(command, timeoutMs, maxOutputBytes)
    if (result.timedOut) {
        throw BackendException(app.umbra.core.model.action.ErrorCode.TIMEOUT, "'$command' timed out after ${timeoutMs}ms", retryable = true)
    }
    if (!result.ok) {
        throw BackendException(
            app.umbra.core.model.action.ErrorCode.COMMAND_FAILED,
            "'${command.take(120)}' exited ${result.exitCode}: ${result.combined.take(300)}",
        )
    }
    return result.stdout
}
