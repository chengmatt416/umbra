package app.umbra.platform.shell

import app.umbra.core.model.action.ErrorCode
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.BinaryShellResult
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellEvent
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.ShellResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A shell that runs each command as a child process: `sh -c <command>` for the app's own identity,
 * `su -c <command>` for root, `adb shell <command>` when a development machine drives a phone. The
 * only difference between those is the command [prefix]; everything else (timeouts, output limits,
 * stdin, binary output, streaming) is shared and therefore tested once, for real.
 */
class ProcessShell(
    override val identity: ShellIdentity,
    private val prefix: List<String>,
    override val name: String,
    /** Used instead of [prefix] for [execBytes]; `adb exec-out` is binary clean on old adb versions. */
    private val binaryPrefix: List<String> = prefix,
    /**
     * Applied to the composed command before it is appended to the prefix. `adb shell` joins its
     * arguments with spaces and lets the device's shell split them again, so a command that goes
     * through `su 0 sh -c` over adb has to be quoted once more.
     */
    private val wrap: (String) -> String = { it },
    private val available: suspend () -> Boolean = { true },
) : ShellBackend {
    init {
        require(prefix.isNotEmpty()) { "empty command prefix" }
    }

    override suspend fun isAvailable(): Boolean = available()

    override suspend fun exec(request: ShellRequest): ShellResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val run = runProcess(prefix, request)
        ShellResult(
            exitCode = run.exitCode,
            stdout = run.stdout.toString(Charsets.UTF_8),
            stderr = run.stderr.toString(Charsets.UTF_8).trimEnd(),
            truncated = run.truncated,
            timedOut = run.timedOut,
            durationMs = (System.nanoTime() - started) / 1_000_000,
            identity = identity,
        )
    }

    override suspend fun execBytes(request: ShellRequest): BinaryShellResult = withContext(Dispatchers.IO) {
        val run = runProcess(binaryPrefix, request.copy(maxOutputBytes = maxOf(request.maxOutputBytes, 32 * 1024 * 1024)))
        BinaryShellResult(run.exitCode, run.stdout, run.stderr.toString(Charsets.UTF_8).trimEnd(), run.timedOut)
    }

    override fun stream(request: ShellRequest): Flow<ShellEvent> = channelFlow {
        val process = try {
            start(prefix, request)
        } catch (e: IOException) {
            throw BackendException(ErrorCode.BACKEND_UNAVAILABLE, "cannot start $name: ${e.message}", cause = e)
        }
        feedStdin(process, request)
        val out = launch(Dispatchers.IO) { pump(process.inputStream) { ShellEvent.Out(it) } }
        val err = launch(Dispatchers.IO) { pump(process.errorStream) { ShellEvent.Err(it) } }
        val waiter = launch(Dispatchers.IO) {
            val code = process.waitFor()
            out.join()
            err.join()
            send(ShellEvent.Exit(code))
            close()
        }
        awaitClose {
            killTree(process)
            waiter.cancel()
        }
    }

    // ---- internals ----

    /**
     * Sends the lines of one of the process's pipes. Ending the process (the collector went away) closes the
     * pipes under a blocked read, which Android reports as an `InterruptedIOException`; that is the end of the
     * stream, not a failure, and left uncaught it would take down a helper process that outlives the stream.
     */
    private suspend fun ProducerScope<ShellEvent>.pump(pipe: java.io.InputStream, event: (String) -> ShellEvent) {
        try {
            val reader = pipe.bufferedReader()
            while (true) send(event(reader.readLine() ?: break))
        } catch (_: IOException) {
            // Closed by killTree or by the process's own end: nothing more to read.
        }
    }

    private class Run(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray, val truncated: Boolean, val timedOut: Boolean)

    private fun start(prefix: List<String>, request: ShellRequest): Process =
        ProcessBuilder(prefix + wrap(compose(request))).redirectErrorStream(false).start()

    /** cwd and environment are applied by the shell itself so the same code works over `su` and `adb`. */
    internal fun compose(request: ShellRequest): String = buildString {
        request.cwd?.let { append("cd ").append(quote(it)).append(" || exit 1; ") }
        for ((k, v) in request.env) {
            require(k.matches(ENV_NAME)) { "invalid environment variable name '$k'" }
            append("export ").append(k).append('=').append(quote(v)).append("; ")
        }
        append(request.command)
    }

    /**
     * Gives the command its input. A small string is written right away; a stream is copied on its own
     * thread so the command's timeout still applies when it stops reading (killing it ends the copy).
     */
    private fun feedStdin(process: Process, request: ShellRequest) {
        val source = request.stdinSource
        if (source != null) {
            Thread({ writeStdin(process) { out -> source().use { it.copyTo(out, 256 * 1024) } } }, "shell-stdin").apply { isDaemon = true }.start()
        } else {
            writeStdin(process) { out -> request.stdin?.let { out.write(it.toByteArray(Charsets.UTF_8)) } }
        }
    }

    private fun writeStdin(process: Process, write: (java.io.OutputStream) -> Unit) {
        val stdin = process.outputStream
        try {
            write(stdin)
        } catch (_: IOException) {
            // The command exited without reading its input; its exit code tells the story.
        } finally {
            try {
                stdin.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun runProcess(prefix: List<String>, request: ShellRequest): Run {
        val process = try {
            start(prefix, request)
        } catch (e: IOException) {
            throw BackendException(ErrorCode.BACKEND_UNAVAILABLE, "cannot start $name: ${e.message}", cause = e)
        }
        val out = Capture(request.maxOutputBytes)
        val err = Capture(request.maxOutputBytes)
        val outThread = pump(process.inputStream, out)
        val errThread = pump(process.errorStream, err)
        feedStdin(process, request)
        val finished = try {
            process.waitFor(request.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            killTree(process)
            throw CancellationException("interrupted while waiting for '${request.command.take(60)}'")
        }
        if (!finished) {
            killTree(process)
            process.waitFor(2, TimeUnit.SECONDS)
        }
        outThread.join(2_000)
        errThread.join(2_000)
        val code = if (finished) process.exitValue() else TIMEOUT_EXIT_CODE
        return Run(code, out.bytes(), err.bytes(), out.truncated || err.truncated, timedOut = !finished)
    }

    private class Capture(private val limit: Int) {
        private val buffer = ByteArrayOutputStream()

        @Volatile
        var truncated = false
            private set

        @Synchronized
        fun add(chunk: ByteArray, length: Int) {
            val room = limit - buffer.size()
            if (room <= 0) { truncated = true; return }
            if (length > room) { buffer.write(chunk, 0, room); truncated = true } else buffer.write(chunk, 0, length)
        }

        @Synchronized
        fun bytes(): ByteArray = buffer.toByteArray()
    }

    private fun pump(stream: InputStream, into: Capture): Thread = Thread {
        val chunk = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                into.add(chunk, n)
            }
        } catch (_: IOException) {
        }
    }.apply { isDaemon = true; start() }

    /**
     * Stops the shell and whatever it started (`sh -c "a; sleep 30"` leaves `sleep` behind otherwise).
     * `Process.descendants()` exists on a desktop JVM but not on Android, where the children are found
     * from the process table instead.
     */
    private fun killTree(process: Process) {
        val handles = ProcessTree.descendantsViaHandle(process)
        if (handles != null) {
            handles.forEach { it.destroyForcibly() }
        } else {
            ProcessTree.pidOf(process)?.let { ProcessTree.killDescendantsOf(it) }
        }
        process.destroyForcibly()
    }

    companion object {
        const val TIMEOUT_EXIT_CODE = 124
        private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** The shell of this process's own user. */
        fun app(): ProcessShell = ProcessShell(ShellIdentity.APP, listOf("sh", "-c"), "app_shell")

        /** `su -c`: root on a rooted device. Available only when `su` really hands out uid 0. */
        fun root(su: String = "su"): ProcessShell {
            lateinit var shell: ProcessShell
            shell = ProcessShell(ShellIdentity.ROOT, listOf(su, "-c"), "root_shell") {
                try {
                    // A root manager asks the user the first time: leave time to answer.
                    shell.exec(ShellRequest("id -u", timeoutMs = 30_000)).let { it.ok && it.stdout.trim() == "0" }
                } catch (_: BackendException) {
                    false
                }
            }
            return shell
        }

        /** Root dropped to the shell user (uid 2000), the identity virtual displays and input injection need (ADR 0004). */
        fun rootAsShell(su: String = "su"): ProcessShell {
            lateinit var shell: ProcessShell
            shell = ProcessShell(ShellIdentity.SHELL, listOf(su, "2000", "sh", "-c"), "root_as_shell") {
                try {
                    shell.exec(ShellRequest("id -u", timeoutMs = 10_000)).let { it.ok && it.stdout.trim() == "2000" }
                } catch (_: BackendException) {
                    false
                }
            }
            return shell
        }

        /** Single-quote [s] for a POSIX shell. */
        fun quote(s: String): String =
            if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "._-/:@+=,%" }) s else "'" + s.replace("'", "'\\''") + "'"
    }
}
