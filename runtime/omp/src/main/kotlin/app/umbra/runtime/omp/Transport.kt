package app.umbra.runtime.omp

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/** Newline-delimited JSON over a pair of pipes. Real runs use [ProcessTransport]; tests script a server. */
interface OmpTransport {
    /** Next stdout line, or null at EOF. */
    suspend fun readLine(): String?

    suspend fun writeLine(line: String)

    /** Close stdin: omp treats it as "the host is gone" and shuts down. stdout stays readable. */
    suspend fun closeInput()

    /** Exit code once the process ended, or null if it is still running after [timeoutMs]. */
    suspend fun awaitExit(timeoutMs: Long): Int?

    /** Kills the process and everything it started. Suspends because the process may live in another environment (the Linux guest). */
    suspend fun destroy()

    /** The last lines omp wrote to stderr, for diagnostics. */
    val stderrTail: List<String>

    /** Operating-system id of the omp process in the environment it runs in, when that can be told. */
    suspend fun processId(): Long? = null
}

/** Launches a child process and speaks the protocol over its stdio. */
class ProcessTransport private constructor(
    private val process: Process,
    stderrLines: Int,
) : OmpTransport {
    /** Operating-system process id of the launched command, or -1 where the platform cannot tell. */
    val pid: Long = try { process.pid() } catch (_: Throwable) { -1 }

    private val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8), 64 * 1024)
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8), 64 * 1024)
    private val tail = ArrayDeque<String>()
    private val maxTail = stderrLines

    init {
        // stderr must be drained or a chatty child blocks on a full pipe.
        Thread({
            try {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).forEachLine { line ->
                    synchronized(tail) {
                        tail.addLast(line)
                        while (tail.size > maxTail) tail.removeFirst()
                    }
                }
            } catch (_: IOException) {
            }
        }, "omp-stderr").apply { isDaemon = true }.start()
    }

    override val stderrTail: List<String> get() = synchronized(tail) { tail.toList() }

    override suspend fun processId(): Long? = pid.takeIf { it > 0 }

    override suspend fun readLine(): String? = withContext(Dispatchers.IO) {
        try {
            reader.readLine()
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun writeLine(line: String) {
        withContext(Dispatchers.IO) {
            try {
                writer.write(line)
                writer.write("\n")
                writer.flush()
            } catch (e: IOException) {
                throw OmpDisconnectedException("omp is not reading its input: ${e.message}")
            }
        }
    }

    override suspend fun closeInput() {
        withContext(Dispatchers.IO) {
            try {
                writer.close()
            } catch (_: IOException) {
            }
        }
    }

    override suspend fun awaitExit(timeoutMs: Long): Int? = withContext(Dispatchers.IO) {
        try {
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.exitValue() else null
        } catch (e: InterruptedException) {
            throw CancellationException("interrupted while waiting for omp to exit")
        }
    }

    override suspend fun destroy() {
        process.destroy()
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }

    companion object {
        fun start(
            command: List<String>,
            environment: Map<String, String> = emptyMap(),
            workingDirectory: File? = null,
            stderrLines: Int = 200,
            inheritEnvironment: Boolean = true,
        ): ProcessTransport {
            val builder = ProcessBuilder(command)
            if (!inheritEnvironment) builder.environment().clear()
            builder.environment().putAll(environment)
            workingDirectory?.let { builder.directory(it) }
            builder.redirectError(ProcessBuilder.Redirect.PIPE)
            return ProcessTransport(builder.start(), stderrLines)
        }
    }
}

/**
 * Scripted peer for protocol tests: the test plays omp by calling [serverSend] and reading what the
 * client wrote from [clientLines].
 */
class ScriptedTransport : OmpTransport {
    private val toClient = Channel<String>(Channel.UNLIMITED)
    private val fromClient = Channel<String>(Channel.UNLIMITED)
    private val written = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    var inputClosed = false
        private set

    @Volatile
    var exitCode: Int? = null
        private set

    override val stderrTail: List<String> = emptyList()

    /** Everything the client has written so far. */
    val sent: List<String> get() = synchronized(written) { written.toList() }

    /** Lines the client wrote, as a channel for tests that wait on them. */
    val clientLines: Channel<String> get() = fromClient

    fun serverSend(line: String) {
        toClient.trySend(line)
    }

    /** Simulates omp exiting: the client sees EOF. */
    fun serverExit(code: Int = 0) {
        exitCode = code
        toClient.close()
    }

    override suspend fun readLine(): String? = toClient.receiveCatching().getOrNull()

    override suspend fun writeLine(line: String) {
        if (inputClosed) throw OmpDisconnectedException("input is closed")
        written += line
        fromClient.trySend(line)
    }

    override suspend fun closeInput() {
        inputClosed = true
        fromClient.close()
        // A real omp exits when stdin closes.
        serverExit(exitCode ?: 0)
    }

    override suspend fun awaitExit(timeoutMs: Long): Int? = exitCode

    override suspend fun destroy() {
        serverExit(exitCode ?: 137)
    }
}

private val ERROR_LINE = Regex("(?i)error|denied|EACCES|ENOENT|cannot|failed|panic|not found")

/** The lines of a process's error output that say why it failed, or its last lines when none do. */
fun summarizeStderr(lines: List<String>, max: Int = 4): String =
    lines.filter { it.length < 400 && ERROR_LINE.containsMatchIn(it) }.take(max).ifEmpty { lines.takeLast(max + 2) }.joinToString(" | ") { it.trim() }
