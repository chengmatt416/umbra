package app.umbra.runtime.linux.tmux

import app.umbra.runtime.omp.OmpTransport
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class TmuxCommandException(val command: String, val output: List<String>) : Exception("tmux refused '$command': ${output.joinToString(" ").take(300)}")

/** What a control client hears besides replies. */
sealed interface TmuxEvent {
    class Output(val paneId: String, val data: ByteArray) : TmuxEvent
    data class Notification(val name: String, val args: List<String>) : TmuxEvent

    /** The control client is gone (`%exit`, or its process ended): the server may or may not still run. */
    data class Closed(val reason: String?) : TmuxEvent
}

/**
 * One tmux control-mode client. Commands are written one per line and answered in order, so replies are matched
 * to callers first-in, first-out; notifications go to [events]. All input to panes goes through [command] on this
 * single ordered stream, which is what makes the terminal input dispatcher's ordering hold (§6.1, §7).
 */
class TmuxControl(private val transport: OmpTransport, scope: CoroutineScope) {
    private val writeLock = Mutex()
    private val pending = ArrayDeque<Pair<String, CompletableDeferred<Result<List<String>>>>>()
    private val mutableEvents = MutableSharedFlow<TmuxEvent>(extraBufferCapacity = 4096)
    private val open = MutableStateFlow(true)

    val events: SharedFlow<TmuxEvent> = mutableEvents.asSharedFlow()
    val isOpen: StateFlow<Boolean> = open.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { readLoop() }
    }

    private suspend fun readLoop() {
        var reply: MutableList<String>? = null
        var replyOurs = false
        var reason: String? = null
        try {
            while (true) {
                val line = transport.readLine() ?: break
                when (val parsed = TmuxProtocol.parse(line)) {
                    is TmuxLine.Begin -> { reply = ArrayList(); replyOurs = parsed.ours }
                    is TmuxLine.End -> {
                        val lines = reply ?: emptyList()
                        reply = null
                        if (replyOurs) {
                            val waiter = synchronized(pending) { pending.pollFirst() }
                            waiter?.second?.complete(if (parsed.error) Result.failure(TmuxCommandException(waiter.first, lines)) else Result.success(lines))
                        }
                    }
                    is TmuxLine.Text -> reply?.add(parsed.text)
                    is TmuxLine.Output -> mutableEvents.emit(TmuxEvent.Output(parsed.paneId, parsed.data))
                    is TmuxLine.Notification -> {
                        if (parsed.name == "exit") reason = parsed.args.joinToString(" ").ifBlank { "the client was detached" }
                        mutableEvents.emit(TmuxEvent.Notification(parsed.name, parsed.args))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reason = reason ?: e.message
        } finally {
            withContext(NonCancellable) {
                open.value = false
                synchronized(pending) {
                    pending.forEach { it.second.complete(Result.failure(IllegalStateException("the tmux control client ended"))) }
                    pending.clear()
                }
                mutableEvents.emit(TmuxEvent.Closed(reason))
            }
        }
    }

    /** Runs one tmux command and returns its output lines. */
    suspend fun command(cmd: String, timeoutMs: Long = 15_000): List<String> {
        check(open.value) { "the tmux control client has ended" }
        require('\n' !in cmd) { "one command per line" }
        val waiter = CompletableDeferred<Result<List<String>>>()
        // Enqueue and write as one step that cancellation cannot split: replies are matched in order, so a slot
        // without its command (or a command without its slot) would hand every later caller the wrong reply.
        withContext(NonCancellable) {
            writeLock.withLock {
                synchronized(pending) { pending.addLast(cmd to waiter) }
                try {
                    transport.writeLine(cmd)
                } catch (e: Exception) {
                    synchronized(pending) { pending.remove(cmd to waiter) }
                    throw e
                }
            }
        }
        val result = withTimeoutOrNull(timeoutMs) { waiter.await() } ?: throw IllegalStateException("tmux did not answer '$cmd' within ${timeoutMs}ms")
        return result.getOrThrow()
    }

    /** Detaches (the server and its panes keep running) and ends the client process. */
    suspend fun close() {
        try { transport.closeInput() } catch (_: Exception) { }
        if (transport.awaitExit(2_000) == null) transport.destroy()
    }
}

/** Where tmux runs: inside the Linux guest on a phone, or on this machine in tests. */
interface TmuxHost {
    /** Runs `tmux <args>` once and returns its standard output; throws when tmux fails. */
    suspend fun run(args: List<String>, timeoutMs: Long = 15_000): String

    /** Starts `tmux <args>` as a long-lived control-mode client. */
    suspend fun spawn(args: List<String>): OmpTransport

    /** The machine's boot id: with the server pid and start time it identifies a tmux server instance. */
    suspend fun bootId(): String
}
