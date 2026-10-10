package app.umbra.runtime.omp

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class RpcFrameDecoderTest {
    private fun obj(json: String): JsonObject = WireJson.parseToJsonElement(json).jsonObject

    /** A logical frame of about [size] bytes. */
    private fun bigFrame(size: Int): String = """{"type":"response","command":"get_messages","success":true,"data":{"blob":"${"x".repeat(size)}"}}"""

    private fun chunks(logical: String, chunkBytes: Int, id: String = "rpc-1"): List<JsonObject> {
        val bytes = logical.toByteArray(Charsets.UTF_8)
        val count = (bytes.size + chunkBytes - 1) / chunkBytes
        return (0 until count).map { i ->
            val part = bytes.copyOfRange(i * chunkBytes, minOf(bytes.size, (i + 1) * chunkBytes))
            obj("""{"type":"rpc_chunk","chunkId":"$id","index":$i,"count":$count,"byteLength":${bytes.size},"data":"${Base64.getEncoder().encodeToString(part)}"}""")
        }
    }

    @Test
    fun ordinaryFramesPassThroughUnchanged() {
        val d = RpcFrameDecoder()
        val frame = obj("""{"type":"agent_start"}""")
        assertEquals(frame, d.push(frame))
    }

    @Test
    fun reassemblesAChunkedFrame() {
        val d = RpcFrameDecoder()
        val logical = bigFrame(1_300_000)
        val parts = chunks(logical, 256 * 1024)
        assertEquals(5, parts.size) // 1.3 MB / 256 KiB
        assertTrue(parts.all { (it["byteLength"] as kotlinx.serialization.json.JsonPrimitive).content.toLong() == logical.toByteArray().size.toLong() })
        for (p in parts.dropLast(1)) assertNull(d.push(p))
        val decoded = d.push(parts.last())!!
        assertEquals(logical.length, WireJson.encodeToString(JsonObject.serializer(), decoded).length)
        assertEquals("response", decoded.str("type"))
    }

    @Test
    fun aMultibyteCharacterSplitAcrossChunksSurvives() {
        val d = RpcFrameDecoder()
        val text = "日本語😀".repeat(100_000)
        val logical = """{"type":"x","t":"$text"}"""
        val parts = chunks(logical, 100_001) // odd size: splits inside multibyte sequences
        var out: JsonObject? = null
        for (p in parts) out = d.push(p) ?: out
        assertEquals(text, out!!.str("t"))
    }

    @Test
    fun rejectsInterleavedSequences() {
        val d = RpcFrameDecoder()
        val parts = chunks(bigFrame(1_300_000), 256 * 1024)
        d.push(parts[0])
        assertFailsWith<RpcProtocolException> { d.push(obj("""{"type":"agent_start"}""")) }
    }

    @Test
    fun rejectsASequenceThatDoesNotStartAtZero() {
        val parts = chunks(bigFrame(1_300_000), 256 * 1024)
        assertFailsWith<RpcProtocolException> { RpcFrameDecoder().push(parts[1]) }
    }

    @Test
    fun rejectsOutOfOrderAndMismatchedChunks() {
        val parts = chunks(bigFrame(1_300_000), 256 * 1024)
        RpcFrameDecoder().apply {
            push(parts[0])
            assertFailsWith<RpcProtocolException> { push(parts[2]) }
        }
        val other = chunks(bigFrame(1_300_000), 256 * 1024, id = "rpc-2")
        RpcFrameDecoder().apply {
            push(parts[0])
            assertFailsWith<RpcProtocolException> { push(other[1]) }
        }
    }

    @Test
    fun rejectsBadMetadataAndData() {
        val d = RpcFrameDecoder()
        assertFailsWith<RpcProtocolException> { d.push(obj("""{"type":"rpc_chunk","chunkId":"","index":0,"count":2,"byteLength":2000000,"data":"AAAA"}""")) }
        assertFailsWith<RpcProtocolException> { d.push(obj("""{"type":"rpc_chunk","chunkId":"a","index":0,"count":1,"byteLength":2000000,"data":"AAAA"}""")) }
        assertFailsWith<RpcProtocolException> { d.push(obj("""{"type":"rpc_chunk","chunkId":"a","index":0,"count":2,"byteLength":10,"data":"AAAA"}""")) }
        assertFailsWith<RpcProtocolException> { d.push(obj("""{"type":"rpc_chunk","chunkId":"a","index":0,"count":2,"byteLength":9999999999,"data":"AAAA"}""")) }
        assertFailsWith<RpcProtocolException> { d.push(obj("""{"type":"rpc_chunk","chunkId":"a","index":0,"count":2,"byteLength":2000000,"data":"!!!notbase64"}""")) }
        assertFailsWith<RpcProtocolException> { d.push(obj("""{"type":"rpc_chunk","chunkId":"a","index":0,"count":2,"byteLength":2000000}""")) }
    }

    @Test
    fun rejectsALengthThatDoesNotAddUp() {
        val parts = chunks(bigFrame(1_300_000), 256 * 1024)
        // Claim one byte more than was actually sent.
        val lying = parts.map { p -> JsonObject(p + ("byteLength" to kotlinx.serialization.json.JsonPrimitive((p["byteLength"] as kotlinx.serialization.json.JsonPrimitive).content.toLong() + 1))) }
        val d = RpcFrameDecoder()
        for (p in lying.dropLast(1)) d.push(p)
        assertFailsWith<RpcProtocolException> { d.push(lying.last()) }
    }

    @Test
    fun rejectsInvalidUtf8AndNonObjects() {
        val d = RpcFrameDecoder()
        val bad = ByteArray(1_100_000) { 0xFF.toByte() }
        val halves = listOf(bad.copyOfRange(0, 550_000), bad.copyOfRange(550_000, bad.size))
        halves.forEachIndexed { i, part ->
            val frame = obj("""{"type":"rpc_chunk","chunkId":"u","index":$i,"count":2,"byteLength":${bad.size},"data":"${Base64.getEncoder().encodeToString(part)}"}""")
            if (i == 0) d.push(frame) else assertFailsWith<RpcProtocolException> { d.push(frame) }
        }
        // A sequence of valid UTF-8 that is not a JSON object.
        val arr = "[${"1,".repeat(600_000)}1]"
        val parts = chunks(arr, 700_000, id = "arr")
        assertEquals(2, parts.size)
        val d2 = RpcFrameDecoder()
        d2.push(parts[0])
        assertFailsWith<RpcProtocolException> { d2.push(parts[1]) }
    }

    @Test
    fun resetAllowsRecoveryAfterAnError() {
        val d = RpcFrameDecoder()
        val parts = chunks(bigFrame(1_300_000), 256 * 1024)
        d.push(parts[0])
        assertFailsWith<RpcProtocolException> { d.push(obj("""{"type":"x"}""")) }
        d.reset()
        val frame = obj("""{"type":"agent_start"}""")
        assertEquals(frame, d.push(frame))
    }
}
