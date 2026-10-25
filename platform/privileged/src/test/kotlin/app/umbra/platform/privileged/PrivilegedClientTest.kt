package app.umbra.platform.privileged

import app.umbra.platform.api.BoundedLineReader
import app.umbra.platform.api.DispatchFence
import java.io.FilterInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class PrivilegedClientTest {
    private class Peer(val handle: (JsonObject, (JsonObject) -> Unit) -> Unit = { _, _ -> }) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val sockets = ConcurrentHashMap<Socket, (JsonObject) -> Unit>()
        val endpoint = PrivilegedClient.Endpoint(server.localPort, "fixture")
        val requests = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        init {
            Thread({
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    Thread({ serve(socket) }, "helper-fixture-peer").apply { isDaemon = true; start() }
                }
            }, "helper-fixture-accept").apply { isDaemon = true; start() }
        }
        private fun serve(socket: Socket) = socket.use {
            val send: (JsonObject) -> Unit = { obj -> synchronized(socket) {
                socket.getOutputStream().write((obj.toString() + "\n").toByteArray()); socket.getOutputStream().flush()
            } }
            sockets[socket] = send
            try {
                val reader = BoundedLineReader(socket.getInputStream())
                while (true) {
                    val request = Json.parseToJsonElement(reader.readLine(1024 * 1024) ?: break).jsonObject
                    requests += request
                    if (request.str("op") == Wire.HELLO) {
                        handle(request, send)
                        send(jsonOf("id" to request.str("id"), "ok" to true, "uid" to 2000, "version" to 1))
                    } else {
                        send(jsonOf("id" to request.str("id"), "ok" to true, "pid" to 123))
                        handle(request, send)
                    }
                }
            } catch (_: IOException) { } finally { sockets.remove(socket) }
        }
        fun push(obj: JsonObject) { sockets.values.forEach { it(obj) } }
        override fun close() { server.close(); sockets.keys.forEach { it.close() } }
    }

    @Test
    fun authorityIsRecheckedAfterTheHelperConnectionIsEstablished(): Unit = runBlocking {
        var epoch = 0
        Peer { request, _ -> if (request.str("op") == Wire.HELLO) epoch++ }.use { peer ->
            PrivilegedClient({ peer.endpoint }).use { client ->
                assertFailsWith<IllegalStateException> {
                    withContext(DispatchFence { check(epoch == 0) { "revoked" } }) { client.call(Wire.EXEC, jsonOf("command" to "fixture")) }
                }
                assertEquals(0, peer.requests.count { it.str("op") == Wire.EXEC })
                assertTrue(client.call(Wire.EXEC, jsonOf("command" to "fresh")).bool("ok") == true)
            }
        }
    }

    @Test
    fun anOldReaderCannotDisconnectItsReplacement(): Unit = runBlocking {
        val oldReader = CountDownLatch(1)
        val release = CountDownLatch(1)
        val created = AtomicInteger()
        val sockets = {
            if (created.getAndIncrement() == 0) object : Socket() {
                override fun getInputStream() = object : FilterInputStream(super.getInputStream()) {
                    override fun read(buffer: ByteArray, offset: Int, count: Int): Int = try {
                        super.read(buffer, offset, count).also { if (it < 0) { oldReader.countDown(); release.await(5, TimeUnit.SECONDS) } }
                    } catch (e: IOException) { oldReader.countDown(); release.await(5, TimeUnit.SECONDS); throw e }
                }
            } else Socket()
        }
        Peer().use { first -> Peer().use { second ->
            val endpoint = AtomicReference(first.endpoint)
            PrivilegedClient({ endpoint.get() }, socketFactory = sockets).use { client ->
                try {
                    val (_, oldEvents) = client.openProc("first fixture")
                    endpoint.set(second.endpoint)
                    val (id, events) = client.openProc("replacement fixture")
                    assertTrue(oldReader.await(3, TimeUnit.SECONDS), "Old reader must still be held during replacement registration")
                    release.countDown()
                    assertEquals(-1, withTimeout(3000) { oldEvents.receive() }.int("exit"))
                    assertNull(withTimeout(3000) { oldEvents.receiveCatching().getOrNull() })
                    assertTrue(client.call(Wire.EXEC, jsonOf("command" to "replacement still alive")).bool("ok") == true)
                    second.push(jsonOf("proc" to id, "out" to Wire.b64("new owner".toByteArray())))
                    val event = withTimeout(3000) { events.receive() }
                    assertNotNull(event.str("out"))
                    assertNull(event.int("exit"))
                    client.writeProc(id, "still writable".toByteArray())
                    assertEquals(1, second.requests.count { it.str("op") == Wire.PROC_WRITE })
                } finally { release.countDown() }
            }
        } }
    }

    @Test
    fun slowConsumersHaveABoundedQueueAndStopTheirProducer(): Unit = runBlocking {
        val killed = CompletableDeferred<Unit>()
        Peer { request, send ->
            if (request.str("op") == Wire.PROC_START) {
                repeat(100) { send(jsonOf("proc" to request.str("proc"), "out" to Wire.b64("fixture output".toByteArray()))) }
            }
            if (request.str("op") == Wire.PROC_KILL) killed.complete(Unit)
        }.use { peer ->
            PrivilegedClient({ peer.endpoint }).use { client ->
                val (_, events) = client.openProc("bounded fixture")
                withTimeout(3000) { killed.await() }
                val error = assertFailsWith<ServerException> { withTimeout(3000) { while (true) events.receive() } }
                assertEquals("overflow", error.code)
                assertTrue(client.call(Wire.EXEC, jsonOf("command" to "other work")).bool("ok") == true)
            }
        }
    }
}
