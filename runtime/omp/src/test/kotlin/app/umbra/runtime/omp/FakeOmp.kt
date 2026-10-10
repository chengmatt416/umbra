package app.umbra.runtime.omp

import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Plays the omp side of the protocol in tests. */
class FakeOmp(val transport: ScriptedTransport) {
    suspend fun nextCommand(timeoutMs: Long = 3_000): JsonObject = withTimeout(timeoutMs) {
        WireJson.parseToJsonElement(transport.clientLines.receive()).jsonObject
    }

    fun send(json: String) = transport.serverSend(json)

    fun ready(v2: Boolean = true, maxFrameBytes: Int = 1_048_576) = send(
        """{"type":"ready","protocolVersion":1,"supportedProtocolVersions":${if (v2) "[1,2]" else "[1]"},""" +
            """"maxFrameBytes":$maxFrameBytes,"maxReassembledFrameBytes":67108864}""",
    )

    fun respond(id: String?, command: String, data: String? = null) = send(
        """{${id?.let { "\"id\":\"$it\"," } ?: ""}"type":"response","command":"$command","success":true${data?.let { ",\"data\":$it" } ?: ""}}""",
    )

    fun fail(id: String?, command: String, error: String, code: String? = null) = send(
        """{${id?.let { "\"id\":\"$it\"," } ?: ""}"type":"response","command":"$command","success":false,"error":"$error"${code?.let { ",\"code\":\"$it\"" } ?: ""}}""",
    )

    /** Answers the next command with success. Returns the command frame. */
    suspend fun ack(data: String? = null): JsonObject {
        val cmd = nextCommand()
        respond(cmd.str("id"), cmd.str("type")!!, data)
        return cmd
    }

    fun exit(code: Int = 0) = transport.serverExit(code)

    /** Splits a logical frame into protocol-v2 chunks of [chunkBytes] and sends them. */
    fun sendChunked(logicalJson: String, chunkBytes: Int = 40_000, chunkId: String = "rpc-1") {
        val bytes = logicalJson.toByteArray(Charsets.UTF_8)
        val count = (bytes.size + chunkBytes - 1) / chunkBytes
        for (i in 0 until count) {
            val part = bytes.copyOfRange(i * chunkBytes, minOf(bytes.size, (i + 1) * chunkBytes))
            send(
                """{"type":"rpc_chunk","chunkId":"$chunkId","index":$i,"count":$count,"byteLength":${bytes.size},"data":"${Base64.getEncoder().encodeToString(part)}"}""",
            )
        }
    }
}

/** A [CoroutineScope] too, so tests can `async { ... }` directly inside the block. */
class Harness(
    val scope: CoroutineScope,
    val transport: ScriptedTransport,
    val client: OmpClient,
    val fake: FakeOmp,
    val events: MutableList<OmpEvent>,
) : CoroutineScope by scope {
    /** Waits for the first event matching [predicate]. */
    suspend fun awaitEvent(timeoutMs: Long = 3_000, predicate: (OmpEvent) -> Boolean): OmpEvent {
        val found = withTimeoutOrNull(timeoutMs) {
            var hit: OmpEvent?
            do {
                hit = synchronized(events) { events.firstOrNull(predicate) }
                if (hit == null) delay(5)
            } while (hit == null)
            hit
        }
        return found ?: error("no matching event within ${timeoutMs}ms; saw ${eventsOf().map { it::class.simpleName }}")
    }

    suspend inline fun <reified T : OmpEvent> awaitEvent(timeoutMs: Long = 3_000): T = awaitEvent(timeoutMs) { it is T } as T

    fun eventsOf(): List<OmpEvent> = synchronized(events) { events.toList() }

    /** Runs the ready + negotiate handshake. */
    suspend fun handshake(v2: Boolean = true, maxFrameBytes: Int = 1_048_576): ReadyInfo = coroutineScope {
        val starting = async(Dispatchers.Default) { client.start() }
        fake.ready(v2, maxFrameBytes)
        if (v2) {
            val negotiate = fake.nextCommand()
            check(negotiate.str("type") == "negotiate_protocol") { "expected negotiate_protocol, got $negotiate" }
            fake.respond(negotiate.str("id"), "negotiate_protocol", """{"protocolVersion":2}""")
        }
        starting.await()
    }
}

/** Runs [block] against a fresh client wired to a scripted server; always cleans up. */
fun harness(config: OmpClientConfig = OmpClientConfig(readyTimeoutMs = 3_000, requestTimeoutMs = 3_000, promptAckTimeoutMs = 3_000), block: suspend Harness.() -> Unit): Unit =
    runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = ScriptedTransport()
        val client = OmpClient(transport, scope, config)
        val events = mutableListOf<OmpEvent>()
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            client.events.collect { synchronized(events) { events += it } }
        }
        try {
            Harness(scope, transport, client, FakeOmp(transport), events).block()
        } finally {
            scope.cancel()
        }
    }
