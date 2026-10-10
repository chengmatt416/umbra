package app.umbra.runtime.linux

import app.umbra.platform.privileged.PrivilegedClient
import app.umbra.platform.privileged.ServerException
import app.umbra.runtime.omp.OmpTransport
import app.umbra.runtime.omp.ProcessTransport
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Starts a shell command as root and hands back its pipes. The agent's process in the Linux guest is
 * started this way: through `su` on a rooted phone, or through a root-owned privileged helper where `su`
 * is not available to the app (an emulator image, or Shizuku running as root).
 */
fun interface RootLauncher {
    suspend fun start(shellCommand: String, stderrLines: Int): OmpTransport
}

/** `su -c <command>`: the argv of a process that runs the command as root (`adb shell su 0 sh -c ...` for development tooling). */
class SuLauncher(private val rootCommand: (shellCommand: String) -> List<String>) : RootLauncher {
    override suspend fun start(shellCommand: String, stderrLines: Int): OmpTransport =
        ProcessTransport.start(rootCommand(shellCommand), stderrLines = stderrLines)
}

/** The command runs inside a helper that itself runs as root; the helper relays stdin and stdout. */
class HelperLauncher(private val client: PrivilegedClient) : RootLauncher {
    override suspend fun start(shellCommand: String, stderrLines: Int): OmpTransport = HelperProcessTransport.open(client, shellCommand, stderrLines)
}

/** Runs the command as the app itself (`sh -c`): how proot is started where there is no root (ADR 0012). */
object AppLauncher : RootLauncher {
    override suspend fun start(shellCommand: String, stderrLines: Int): OmpTransport =
        ProcessTransport.start(listOf("sh", "-c", shellCommand), stderrLines = stderrLines)
}

/** Uses [first] while it works and [second] otherwise, deciding per start. */
class FallbackLauncher(private val first: RootLauncher, private val firstWorks: suspend () -> Boolean, private val second: RootLauncher) : RootLauncher {
    override suspend fun start(shellCommand: String, stderrLines: Int): OmpTransport =
        if (firstWorks()) first.start(shellCommand, stderrLines) else second.start(shellCommand, stderrLines)
}

/** The pipes of a process that runs inside the privileged helper. */
internal class HelperProcessTransport private constructor(
    private val client: PrivilegedClient,
    private val id: String,
    private val events: Channel<JsonObject>,
    private val maxTail: Int,
) : OmpTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lines = Channel<String>(Channel.UNLIMITED)
    private val exit = CompletableDeferred<Int>()
    private val tail = ArrayDeque<String>()

    override val stderrTail: List<String> get() = synchronized(tail) { tail.toList() }

    init {
        scope.launch {
            val out = LineSplitter { lines.trySend(it) }
            val err = LineSplitter { line ->
                synchronized(tail) {
                    tail.addLast(line)
                    while (tail.size > maxTail) tail.removeFirst()
                }
            }
            for (event in events) {
                (event["out"] as? JsonPrimitive)?.content?.let { out.feed(Base64.getDecoder().decode(it)) }
                (event["err"] as? JsonPrimitive)?.content?.let { err.feed(Base64.getDecoder().decode(it)) }
                (event["exit"] as? JsonPrimitive)?.intOrNull?.let { code ->
                    out.flush()
                    err.flush()
                    exit.complete(code)
                    lines.close()
                    return@launch
                }
            }
            // The channel ended without an exit event: the helper went away.
            out.flush()
            exit.complete(-1)
            lines.close()
        }
    }

    override suspend fun readLine(): String? = lines.receiveCatching().getOrNull()

    override suspend fun writeLine(line: String) {
        try {
            client.writeProc(id, (line + "\n").toByteArray(Charsets.UTF_8))
        } catch (e: ServerException) {
            throw app.umbra.runtime.omp.OmpDisconnectedException("the agent process is not reading its input: ${e.message}")
        }
    }

    override suspend fun closeInput() {
        try {
            client.closeProcInput(id)
        } catch (_: ServerException) {
        }
    }

    override suspend fun awaitExit(timeoutMs: Long): Int? = withTimeoutOrNull(timeoutMs) { exit.await() }

    override suspend fun destroy() {
        client.killProc(id)
        exit.complete(-1)
        scope.cancel()
    }

    /** Cuts a byte stream into lines, keeping a partial line until the rest arrives. */
    private class LineSplitter(private val onLine: (String) -> Unit) {
        private val pending = ByteArrayOutputStream()

        fun feed(bytes: ByteArray) {
            for (b in bytes) {
                if (b == '\n'.code.toByte()) {
                    onLine(pending.toString(Charsets.UTF_8).trimEnd('\r'))
                    pending.reset()
                } else {
                    pending.write(b.toInt())
                }
            }
        }

        fun flush() {
            if (pending.size() > 0) onLine(pending.toString(Charsets.UTF_8))
            pending.reset()
        }
    }

    companion object {
        suspend fun open(client: PrivilegedClient, command: String, stderrLines: Int): HelperProcessTransport {
            val (id, channel) = client.openProc(command)
            return HelperProcessTransport(client, id, channel, stderrLines)
        }
    }
}
