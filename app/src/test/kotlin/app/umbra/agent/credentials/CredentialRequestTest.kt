package app.umbra.agent.credentials

import app.umbra.platform.api.CredentialTarget
import kotlin.test.*

class CredentialRequestTest {
    private val token = "native-one-time-fixture-token-0123456789"
    private val request = CredentialRequest("request", "task", "Fixture", "Sign in", CredentialTarget("1:0", "example.app", 1, 0,
        "example.app:id/password", "EditText", true, "Password field", "form"), 1000, CredentialRequest.hash(token))
    @Test fun aLinkIsBoundToItsRequestAndExpiresWithoutGrantingInjection() {
        assertTrue(request.acceptsLink(token, 1000))
        assertFalse(request.acceptsLink("wrong-token", 1001))
        assertFalse(request.acceptsLink(token, 999))
        assertFalse(request.acceptsLink(token, 1000 + CredentialRequest.TTL_MS))
        assertFalse(request.copy(linkUsed = true).acceptsLink(token, 1001))
        assertFalse(request.copy(state = "injected").acceptsLink(token, 1001))
        val json = app.umbra.core.model.UmbraJson.encodeToString(CredentialRequest.serializer(), request)
        assertFalse(token in json)
        assertFalse("credentialValue" in json)
    }
}
