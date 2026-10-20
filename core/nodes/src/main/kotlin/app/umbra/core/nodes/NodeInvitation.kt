package app.umbra.core.nodes

import java.net.URI
import java.util.Base64
import kotlinx.serialization.Serializable

/** The short-lived QR shown by setup. Its certificate pin binds every advertised LAN/VPN address. */
@Serializable
data class NodeInvitation(
    val version: Int = 1,
    val name: String,
    val fingerprint: String,
    val code: String,
    val expiresAtMs: Long,
    val addresses: List<NodeAddress>,
) {
    fun validate(nowMs: Long = System.currentTimeMillis()): NodeInvitation {
        require(version == 1) { "Unsupported node invitation version" }
        require(name.isNotBlank() && name.length <= 120) { "Invalid node name" }
        require(fingerprint.matches(Regex("[a-f0-9]{64}"))) { "Invalid node certificate fingerprint" }
        require(Pairing.normalize(code).matches(Regex("[0-9A-HJKMNP-TV-Z]{12}"))) { "Invalid pairing code" }
        require(expiresAtMs > nowMs && expiresAtMs <= nowMs + 11 * 60_000) { "Pairing code expired; refresh it on the computer" }
        require(addresses.isNotEmpty() && addresses.size <= 8) { "Invalid node addresses" }
        addresses.forEach { it.validate() }
        return this
    }

    fun uri(): String = "umbra-node://pair?data=" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(Rsp.json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8))

    companion object {
        fun parse(value: String, nowMs: Long = System.currentTimeMillis()): NodeInvitation {
            require(value.length <= 8192) { "Node invitation is too large" }
            val uri = URI(value.trim())
            require(uri.scheme == "umbra-node" && uri.host == "pair" && uri.fragment == null) { "This QR is not an Umbra node invitation" }
            val data = uri.rawQuery?.removePrefix("data=") ?: error("Missing node invitation")
            return Rsp.json.decodeFromString(serializer(), String(Base64.getUrlDecoder().decode(data), Charsets.UTF_8)).validate(nowMs)
        }
    }
}

@Serializable
data class NodeAddress(val host: String, val port: Int = Rsp.DEFAULT_PORT, val kind: String = "lan") {
    fun validate() {
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it in "/\\@?#" }) { "Invalid node host" }
        require(port in 1..65535) { "Invalid node port" }
    }
    fun label(): String = if (':' in host) "[$host]:$port" else "$host:$port"
}

@Serializable
data class NodeScreenInfo(val available: Boolean = false, val backend: String = "", val detail: String = "")
