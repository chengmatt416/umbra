package app.umbra.tools

import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive

class ToolBridgeBoundaryTest {
    private fun server(limit: Int = 192) = ToolBridgeServer("fixture", { error("No tool should be dispatched") }, maxFrameBytes = limit,
        control = ControlHandler { _, _ -> JsonPrimitive("received") })

    @Test fun aRestartCanReuseItsPortWhileClosedClientsAreInTimeWait() {
        val first = server()
        try {
            val port = first.start()
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write("{\"auth\":\"fixture\",\"control\":\"fixture\"}\n".toByteArray())
                kotlin.test.assertNotNull(socket.getInputStream().bufferedReader().readLine())
                first.close()
                ToolBridgeServer("fixture", { error("No tool should be dispatched") }, port = port,
                    control = ControlHandler { _, _ -> JsonPrimitive("restarted") }).use { second ->
                    assertEquals(port, second.start())
                    BridgeClient("127.0.0.1", port, "fixture").use { client ->
                        assertEquals(JsonPrimitive("restarted"), client.control("fixture"))
                    }
                }
            }
        } finally { first.close() }
    }

    @Test
    fun unicodeIsBoundedInBytesBeforeDispatch() {
        server().use { bridge ->
            val port = bridge.start()
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 2_000
                val request = """{"id":"1","auth":"fixture","control":"fixture","args":{"text":"${"é".repeat(80)}"}}"""
                socket.getOutputStream().write((request + "\n").toByteArray())
                assertNull(socket.getInputStream().bufferedReader().readLine())
            }
            BridgeClient("127.0.0.1", port, "fixture").use { client ->
                assertEquals(JsonPrimitive("received"), client.control("fixture"), "An ordinary authenticated call remains usable")
            }
        }
    }

    @Test
    fun anOversizedFrameWithoutANewlineIsClosedImmediately() {
        server().use { bridge ->
            val port = bridge.start()
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write(ByteArray(512) { 65 })
                assertNull(socket.getInputStream().bufferedReader().readLine())
            }
        }
    }

    @Test
    fun shuttingDownTheBridgeClosesIdleConnections() {
        val bridge = server()
        val port = bridge.start()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2_000
            val reader = socket.getInputStream().bufferedReader()
            socket.getOutputStream().write("{\"auth\":\"fixture\",\"control\":\"fixture\"}\n".toByteArray())
            kotlin.test.assertNotNull(reader.readLine())
            bridge.close()
            assertNull(reader.readLine())
        }
    }
}
