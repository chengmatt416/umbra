package app.umbra.node

import app.umbra.core.nodes.*
import app.umbra.core.nodes.Rsp.str
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Uses the production Kotlin phone protocol against the real Go binary, with host-side approval over its private socket. */
class GoProtocolParityTest {
    @Test fun phonePairsUploadsRunsRejoinsExpiresAndRevokesAgainstGo(): Unit = runBlocking {
        val binary = System.getenv("UMBRA_GO_NODE")
        assumeTrue(binary != null, "Set UMBRA_GO_NODE to the built Go companion")
        val dir = Files.createTempDirectory(Path.of("/tmp"), "umbra-go-parity-")
        val legacy = NodeHome(dir.resolve("node").toFile()); legacy.ensureIdentity()
        val previousPin = legacy.certificate().fingerprint()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val process = ProcessBuilder(binary!!, "serve", "--home", dir.resolve("node").toString(), "--port", "0", "--pair").redirectErrorStream(true).redirectOutput(dir.resolve("runner.log").toFile()).start()
        try {
            val nodeHome = dir.resolve("node")
            withTimeout(20_000) { while (!Files.exists(nodeHome.resolve("control.sock"))) { check(process.isAlive) { Files.readString(dir.resolve("runner.log")) }; delay(50) } }
            val state = control(nodeHome)
            val invitation = Rsp.json.decodeFromJsonElement(NodeInvitation.serializer(), state["invitation"]!!)
            invitation.validate()
            assertEquals(previousPin, invitation.fingerprint, "Go migration must preserve the JVM node's pinned identity")
            assertEquals(invitation, NodeInvitation.parse(state.str("uri")!!))
            val phone = NodeHome(dir.resolve("phone").toFile()); phone.ensureIdentity()
            val port = invitation.addresses.first().port
            val context = tlsContext(phone.keyManagers(), PinnedTrust({ setOf(invitation.fingerprint) }))
            NodeChannel.connect("127.0.0.1", port, context, scope).use { ch ->
                assertFailsWith<NodeException> { ch.request("hello") }
                assertFailsWith<NodeException> { ch.request("pair", NodeChannel.params { put("proof", "wrong"); put("name", "Parity phone") }) }
                val paired = async(Dispatchers.IO) { ch.request("pair", NodeChannel.params { put("name", "Parity phone"); put("proof", Pairing.phoneProof(invitation.code, invitation.fingerprint, ch.localFingerprint!!)) }) }
                val pending = withTimeout(10_000) { var value: JsonObject? = null; while (value == null) { delay(50); value = control(nodeHome)["pending"] as? JsonObject }; value }
                assertFalse(paired.isCompleted, "phone must wait for the computer's approval")
                control(nodeHome, buildJsonObject { put("action", "pair-decision"); put("id", pending.str("id")); put("allow", true) })
                val reply = paired.await()
                assertEquals(Pairing.nodeProof(invitation.code, invitation.fingerprint, ch.localFingerprint!!), reply.str("proof"))
            }
            NodeChannel.connect("127.0.0.1", port, context, scope).use { ch ->
                val info = Rsp.json.decodeFromJsonElement(NodeInfo.serializer(), ch.request("hello")["info"]!!)
                assertTrue(info.cpus > 0 && info.epoch.isNotBlank())
                assertFalse(info.screen.available)
                val input = dir.resolve("input").toFile().apply { mkdirs() }
                input.resolve("input.txt").writeText("protocol parity\n")
                val manifest = Manifests.build(input.toPath(), listOf("input.txt"), 1024)
                ch.request("manifest", NodeChannel.params { put("job", "parity"); put("manifest", Rsp.json.encodeToJsonElement(DataManifest.serializer(), manifest)) })
                ch.request("put", NodeChannel.params { put("job", "parity"); put("path", "input.txt"); put("offset", 0); put("data", Base64.getEncoder().encodeToString(input.resolve("input.txt").readBytes())); put("last", true) })
                ch.request("run", NodeChannel.params { put("job", "parity"); put("argv", JsonArray(listOf("sh", "-c", "cat input.txt").map(::JsonPrimitive))); put("lease_ms", 10_000) })
                suspend fun jobs() = Rsp.json.decodeFromJsonElement(ListSerializer(JobStatus.serializer()), ch.request("jobs")["jobs"]!!)
                withTimeout(10_000) { while (jobs().single().state == JobState.RUNNING) delay(50) }
                assertEquals("protocol parity\n", jobs().single().outputTail)
                assertEquals(0, jobs().single().exit)
                ch.request("forget", NodeChannel.params { put("job", "parity") })
                assertFalse(Files.exists(nodeHome.resolve("jobs/parity")))
                ch.request("run", NodeChannel.params { put("job", "lease"); put("argv", JsonArray(listOf("sh", "-c", "sleep 30").map(::JsonPrimitive))); put("lease_ms", 100) })
                withTimeout(10_000) { while (jobs().single().state == JobState.RUNNING) delay(100) }
                assertEquals(JobState.LEASE_EXPIRED, jobs().single().state)
                val revocation = ch.request("revoke")
                assertTrue(revocation["jobs"] is JsonArray)
            }
            assertFailsWith<NodeException> { NodeChannel.connect("127.0.0.1", port, context, scope).use { it.request("hello") } }
            val config = Files.readString(nodeHome.resolve("paired.json"))
            assertEquals("[]", config)
        } finally {
            scope.cancel(); process.destroy(); if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            dir.toFile().deleteRecursively()
        }
    }

    private fun control(home: Path, action: JsonObject? = null): JsonObject {
        val body = action?.toString().orEmpty()
        val headers = if (action == null) "GET /state HTTP/1.0\r\n" else "POST /action HTTP/1.0\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n"
        SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
            socket.connect(UnixDomainSocketAddress.of(home.resolve("control.sock")))
            val request = ByteBuffer.wrap((headers + "Host: node\r\nConnection: close\r\n\r\n" + body).toByteArray())
            while (request.hasRemaining()) socket.write(request)
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(8192)
            while (socket.read(buffer) >= 0) { buffer.flip(); result.write(buffer.array(), 0, buffer.remaining()); buffer.clear() }
            val reply = result.toString(Charsets.UTF_8)
            assertTrue(reply.startsWith("HTTP/1.0 200"), reply.take(200))
            return Rsp.json.parseToJsonElement(reply.substringAfter("\r\n\r\n")).jsonObject
        }
    }
}
