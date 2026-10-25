package app.umbra.core.safety

import kotlin.test.*
import kotlinx.serialization.json.*

class ModelPayloadGuardTest {
    @Test fun currentVaultTextIsCheckedInsideNestedProviderAndToolData() {
        var secrets = emptyList<String>()
        val guard = ModelPayloadGuard(OutboundDlpGuard { secrets })
        val payload = buildJsonObject { put("messages", buildJsonArray { add(buildJsonObject { put("text", "opaque-current-vault-fixture") }) }) }
        guard.requireSafe(payload)
        secrets = listOf("opaque-current-vault-fixture")
        assertFailsWith<IllegalArgumentException> { guard.requireSafe(payload) }
        assertFailsWith<IllegalArgumentException> { guard.requireSafe(buildJsonObject { put(secrets.single(), true) }) }
        assertFailsWith<IllegalArgumentException> { guard.requireSafe(buildJsonObject { put("password", "synthetic-unknown-password") }) }
        assertFailsWith<IllegalArgumentException> { guard.requireSafe(buildJsonObject { put("otp", "123456") }) }
        guard.requireSafe(buildJsonObject { put("text", "Ordinary task result") })
    }
    @Test fun imagesAreNotMisrepresentedAsReviewedTextAndTextReviewIsBounded() {
        val guard = ModelPayloadGuard(OutboundDlpGuard { listOf("synthetic-image-marker") })
        guard.requireSafe(buildJsonObject { put("type", "base64"); put("media_type", "image/png"); put("data", "synthetic-image-marker") })
        assertFailsWith<IllegalArgumentException> { guard.requireSafe(buildJsonObject { put("type", "text"); put("data", "synthetic-image-marker") }) }
        assertFailsWith<IllegalArgumentException> { guard.requireSafe(JsonPrimitive("x".repeat(2_000_001))) }
        var deep: JsonElement = JsonPrimitive("value")
        repeat(45) { deep = JsonArray(listOf(deep)) }
        assertFailsWith<IllegalArgumentException> { guard.requireSafe(deep) }
    }
}
