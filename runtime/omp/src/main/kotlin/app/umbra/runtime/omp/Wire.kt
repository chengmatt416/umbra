package app.umbra.runtime.omp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** The protocol is documented in `docs/rpc.md` of oh-my-pi; ADR 0001 explains the topology. */
internal val WireJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
}

// ---- JsonObject accessors (frames are open records: unknown keys must pass through) ----

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.strList(key: String): List<String> = arr(key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
internal fun JsonElement?.isNull(): Boolean = this == null || this is JsonNull

class RpcProtocolException(message: String) : RuntimeException(message)

/**
 * Reassembles protocol-v2 `rpc_chunk` sequences into the logical frame they carry. Mirrors the
 * validation rules of the server-side decoder: one sequence at a time, strictly ordered, bounded by
 * the advertised reassembly limit, strict UTF-8.
 */
class RpcFrameDecoder(
    private val maxFrameBytes: Int = 1024 * 1024,
    private val maxReassembledBytes: Long = 64L * 1024 * 1024,
) {
    private class Pending(val chunkId: String, val count: Int, val byteLength: Long) {
        val bytes = ByteArrayOutputStream()
        var nextIndex = 0
        var received = 0L
    }

    private var pending: Pending? = null

    /** The decoded frame, or null when more chunks are needed. */
    fun push(frame: JsonObject): JsonObject? {
        if (frame.str("type") != "rpc_chunk") {
            if (pending != null) throw RpcProtocolException("rpc chunk sequence interrupted")
            return frame
        }
        val chunkId = frame.str("chunkId")
        val index = frame.long("index")
        val count = frame.long("count")
        val byteLength = frame.long("byteLength")
        if (chunkId.isNullOrEmpty() || chunkId.length > 128 || index == null || count == null || byteLength == null ||
            index < 0 || count < 2 || index >= count || byteLength < maxFrameBytes || byteLength > maxReassembledBytes
        ) {
            throw RpcProtocolException("invalid rpc chunk metadata")
        }
        val data = frame.str("data") ?: throw RpcProtocolException("rpc chunk has no data")
        val bytes = try {
            Base64.getDecoder().decode(data)
        } catch (e: IllegalArgumentException) {
            throw RpcProtocolException("invalid rpc chunk data")
        }

        var p = pending
        if (p == null) {
            if (index != 0L) throw RpcProtocolException("rpc chunk sequence must start at index 0")
            p = Pending(chunkId, count.toInt(), byteLength).also { pending = it }
        }
        if (p.chunkId != chunkId || p.count.toLong() != count || p.byteLength != byteLength || p.nextIndex.toLong() != index) {
            throw RpcProtocolException("rpc chunk sequence mismatch")
        }
        p.bytes.write(bytes)
        p.received += bytes.size
        p.nextIndex++
        if (p.received > p.byteLength) throw RpcProtocolException("rpc chunk sequence exceeds declared length")
        if (p.nextIndex < p.count) return null
        if (p.received != p.byteLength) throw RpcProtocolException("rpc chunk sequence length mismatch")

        pending = null
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(p.bytes.toByteArray()))
                .toString()
        } catch (e: CharacterCodingException) {
            throw RpcProtocolException("rpc frame is not valid UTF-8")
        }
        return WireJson.parseToJsonElement(text) as? JsonObject ?: throw RpcProtocolException("rpc frame must be an object")
    }

    /** Forget a half-received sequence (after a protocol error). */
    fun reset() {
        pending = null
    }
}

/** First frame after startup. v1 servers omit the v2 fields. */
data class ReadyInfo(
    val protocolVersion: Int,
    val supportedProtocolVersions: List<Int>,
    val maxFrameBytes: Int,
    val maxReassembledFrameBytes: Long,
) {
    val supportsV2: Boolean get() = 2 in supportedProtocolVersions

    companion object {
        fun from(frame: JsonObject) = ReadyInfo(
            protocolVersion = frame.int("protocolVersion") ?: 1,
            supportedProtocolVersions = frame.arr("supportedProtocolVersions")?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: listOf(1),
            maxFrameBytes = frame.int("maxFrameBytes") ?: (1024 * 1024),
            maxReassembledFrameBytes = frame.long("maxReassembledFrameBytes") ?: (64L * 1024 * 1024),
        )
    }
}

/** `{type:"response", ...}`: the reply to one command. */
data class RpcResponse(
    val id: String?,
    val command: String,
    val success: Boolean,
    val data: JsonElement?,
    val error: String?,
    val code: String?,
) {
    companion object {
        fun from(frame: JsonObject) = RpcResponse(
            id = frame.str("id"),
            command = frame.str("command") ?: "unknown",
            success = frame.bool("success") ?: false,
            data = frame["data"]?.takeUnless { it is JsonNull },
            error = frame.str("error"),
            code = frame.str("code"),
        )
    }
}

class OmpCommandException(
    val command: String,
    override val message: String,
    val code: String? = null,
) : RuntimeException("omp command '$command' failed: $message")

class OmpDisconnectedException(message: String) : RuntimeException(message)
class OmpTimeoutException(message: String) : RuntimeException(message)
class OmpFrameTooLargeException(val bytes: Int, val limit: Int) :
    RuntimeException("frame of $bytes bytes exceeds the $limit byte transport limit")
