package app.umbra.core.safety

import kotlinx.serialization.json.*

/** Reviews text locally using the current vault. Images require a separate visual privacy boundary. */
class ModelPayloadGuard(private val dlp: OutboundDlpGuard = OutboundDlpGuard()) {
    fun requireSafe(payload: JsonElement) {
        var characters = 0
        var nodes = 0
        fun visit(value: JsonElement, depth: Int) {
            require(depth <= 40 && ++nodes <= 100_000) { "Model content exceeds the local review bounds" }
            when (value) {
                is JsonPrimitive -> if (value.isString) {
                    val text = value.content
                    if (!text.startsWith("data:image/", ignoreCase = true)) {
                        characters += text.length
                        require(characters <= 2_000_000) { "Model text exceeds the local review bounds" }
                        dlp.requireSafe(text)
                    }
                }
                is JsonArray -> value.forEach { visit(it, depth + 1) }
                is JsonObject -> value.forEach { (key, child) ->
                    visit(JsonPrimitive(key), depth + 1)
                    if (child is JsonPrimitive && key.lowercase() in setOf("password", "passwd", "client_secret", "api_key", "access_token"))
                        dlp.requireSafe("$key: ${child.content}")
                    if (child is JsonPrimitive && key.lowercase() in setOf("otp", "verification_code", "one_time_code", "2fa_code"))
                        dlp.requireSafe("verification code: ${child.content}")
                    val image = key == "data" && (
                        value["type"] == JsonPrimitive("base64") && value["media_type"]?.jsonPrimitive?.contentOrNull?.startsWith("image/") == true ||
                        value["type"] == JsonPrimitive("image") && value["mimeType"]?.jsonPrimitive?.contentOrNull?.startsWith("image/") == true)
                    if (!image) visit(child, depth + 1)
                }
            }
        }
        visit(payload, 0)
    }
}
