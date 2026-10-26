package app.umbra.core.orchestrator

import app.umbra.core.session.ProactiveTurnMode
import app.umbra.core.session.SessionFilter
import app.umbra.core.session.SessionKind
import app.umbra.core.session.SessionState
import app.umbra.core.session.SessionStore
import app.umbra.runtime.omp.HostToolDefinition
import app.umbra.runtime.omp.HostToolPayload
import app.umbra.runtime.omp.HostToolProgress
import app.umbra.runtime.omp.objectSchema
import app.umbra.tools.HostSideTool
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Bounded waits keep agents responsive to cancellation and let late reports reach their caller. */
class WaitTools(
    private val store: SessionStore,
    private val finalText: suspend (String) -> String,
    private val bus: SessionBus,
    private val exec: suspend (command: String, timeoutMs: Long) -> Int?,
) {
    constructor(store: SessionStore, supervisor: AgentSupervisor, bus: SessionBus, exec: suspend (String, Long) -> Int?) :
        this(store, supervisor::finalText, bus, exec)

    fun forSession(sessionId: String): List<HostSideTool> = listOf(
        HostSideTool(HostToolDefinition("wait_until", "Wait for a session's current turn, a Linux process to exit, or a read-only shell condition to succeed. You must choose timeout_s; on timeout you may wait again or move on.", objectSchema {
            enum("until", listOf("session_done", "process_exit", "condition"), "What to wait for.", required = true)
            string("target", "For session_done: a #handle, @agent or session id you may read.")
            integer("pid", "For process_exit: a positive Linux process id; choose pid or pattern.")
            string("pattern", "For process_exit: a pgrep -f pattern; choose pid or pattern.")
            string("command", "For condition: a read-only shell probe that exits 0 when ready (at most 2000 characters).")
            integer("interval_s", "Seconds between probes (default 5, range 2–60).")
            integer("timeout_s", "How long you are prepared to wait (5–3600 seconds).", required = true)
        })) { call, progress -> wait(sessionId, call.arguments, progress) },
    )

    private suspend fun wait(selfId: String, args: JsonObject, progress: HostToolProgress): HostToolPayload {
        val timeout = args.int("timeout_s")?.takeIf { it in 5..3600 }
            ?: return HostToolPayload.error("wait_until needs timeout_s: how long, in seconds, you are prepared to wait (5–3600)")
        val interval = if ("interval_s" in args) args.int("interval_s")?.takeIf { it in 2..60 }
            ?: return HostToolPayload.error("interval_s must be an integer from 2 to 60.") else 5
        val caller = store.get(selfId) ?: return HostToolPayload.error("This session no longer exists.")
        return when (args.str("until")) {
            "session_done" -> session(selfId, args.str("target") ?: return HostToolPayload.error("target is missing"), timeout, progress)
            "process_exit", "condition" -> {
                if (caller.turnMode == ProactiveTurnMode.READ_ONLY_DISCOVERY) return HostToolPayload.error("Read-only discovery may use wait_until only with until=session_done; Linux probes run commands.")
                probe(args, timeout, interval, progress)
            }
            else -> HostToolPayload.error("until must be session_done, process_exit or condition.")
        }
    }

    private suspend fun session(selfId: String, target: String, timeout: Int, progress: HostToolProgress): HostToolPayload {
        val s = bus.resolve(target) ?: return HostToolPayload.error("No session or agent is called '$target'.")
        val caller = store.get(selfId)
        val records = store.list(SessionFilter(limit = Int.MAX_VALUE)).associateBy { it.id }
        if (!mayRead(caller, s, records)) return HostToolPayload.error("This agent's private history was not shared with you. Use a structured handoff instead.")
        if (s.id == selfId) return HostToolPayload.error("A session cannot wait for its own turn to finish.")
        val own = s.spawnedBy == selfId || s.kind == SessionKind.CODING && caller?.kind == SessionKind.AGENT && caller.agentId != null && s.agentId == caller.agentId
        var consumed = false
        var pending = "${bus.address(s)} is still ${s.state.name.lowercase()}"
        if (own) bus.markConsumed(s.id)
        try {
            return await(timeout, { pending }, 1_000, progress) {
                val current = store.get(s.id) ?: return@await HostToolPayload.text("${bus.address(s)} was deleted.")
                pending = "${bus.address(current)} is still ${current.state.name.lowercase()}"
                when {
                    current.state.isTerminal -> {
                        val answer = finalText(current.id).ifBlank { current.lastError ?: "(no answer)" }.take(SessionBus.REPORT_LIMIT)
                        consumed = true
                        HostToolPayload.text("${bus.address(current)} finished (${current.state.name.lowercase()}): $answer")
                    }
                    current.state == SessionState.WAITING_APPROVAL -> HostToolPayload.text("${bus.address(current)} is waiting for approval; a person must act before its turn can finish.")
                    else -> null
                }
            }
        } finally {
            if (own && (!consumed || s.state.isTerminal)) bus.unmarkConsumed(s.id)
        }
    }

    private suspend fun probe(args: JsonObject, timeout: Int, interval: Int, progress: HostToolProgress): HostToolPayload {
        val process = args.str("until") == "process_exit"
        val command: String
        val pending: String
        val finished: String
        if (process) {
            if (("pid" in args) == ("pattern" in args)) return HostToolPayload.error("process_exit needs exactly one of pid or pattern.")
            if ("pid" in args) {
                val pid = args.int("pid")?.takeIf { it > 0 } ?: return HostToolPayload.error("pid must be a positive integer.")
                command = "test -d /proc/$pid"
                pending = "process $pid is still running"
                finished = "process $pid exited"
            } else {
                val pattern = args.str("pattern")?.takeIf { it.isNotBlank() } ?: return HostToolPayload.error("pattern is missing")
                if (pattern.length > 2000) return HostToolPayload.error("Commands and patterns must be at most 2000 characters.")
                // Ignore the probe's ancestors: their command lines may contain the pattern too.
                command = "exec pgrep -A -f -- " + "'" + pattern.replace("'", "'\\''") + "'"
                pending = "processes matching '$pattern' are still running"
                finished = "processes matching '$pattern' exited"
            }
        } else {
            command = args.str("command")?.takeIf { it.isNotBlank() } ?: return HostToolPayload.error("command is missing")
            pending = "condition is still pending: $command"
            finished = "condition met"
        }
        if (command.length > 2000) return HostToolPayload.error("Commands must be at most 2000 characters.")
        val started = TimeSource.Monotonic.markNow()
        return await(timeout, { pending }, interval * 1000L, progress) {
            val code = exec(command, minOf(timeout * 1000L, 5_000L)) ?: return@await HostToolPayload.error("Linux is not ready. Try again once the Linux environment is available.")
            when {
                process && code !in 0..1 -> HostToolPayload.error("Process probe failed (exit $code); cannot determine whether $pending.")
                process && code == 1 -> HostToolPayload.text(finished)
                !process && code == 0 -> HostToolPayload.text("$finished after ${started.elapsedNow().inWholeSeconds} s")
                else -> null
            }
        }
    }

    /** Progress has its own clock so a slow probe or long interval does not hide an active wait. */
    private suspend fun await(timeout: Int, pending: () -> String, intervalMs: Long, progress: HostToolProgress, check: suspend () -> HostToolPayload?): HostToolPayload {
        val started = TimeSource.Monotonic.markNow()
        val result = withTimeoutOrNull(timeout * 1000L) {
            coroutineScope {
                val updates = launch {
                    while (true) {
                        try {
                            progress.update(HostToolPayload.text("Waiting: ${pending()} (${started.elapsedNow().inWholeSeconds} s elapsed)."))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                        delay(5_000)
                    }
                }
                try {
                    var result = check()
                    while (result == null) {
                        delay(intervalMs)
                        result = check()
                    }
                    result
                } finally {
                    updates.cancel()
                }
            }
        }
        return result ?: HostToolPayload.text("Timed out after $timeout s: ${pending()}. You may wait again or move on.")
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
}
