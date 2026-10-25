package app.umbra.agent.credentials

import kotlin.test.*

class CredentialLinksTest {
    private val token = "A".repeat(43)
    @Test fun verifiedLinksKeepTheNonceOutOfTheRequestPathAndQuery() {
        val link = CredentialLinks.panel(token, verified = true)
        assertEquals("https://umbra-agent.pages.dev/credential-handoff#$token", link)
        val uri = java.net.URI(link)
        assertFalse(token in uri.rawPath)
        assertNull(uri.rawQuery)
        assertEquals(token, CredentialLinks.token(link))
        assertEquals(token, CredentialLinks.token("https://umbra-agent.pages.dev/credential-handoff/#$token"))
        assertEquals(token, CredentialLinks.token(CredentialLinks.panel(token, verified = false)))
    }
    @Test fun confusedOriginsEncodedTokensAndUnexpectedFieldsAreRejected() {
        val links = listOf("http://umbra-agent.pages.dev/credential-handoff#$token",
            "https://umbra-agent.pages.dev.evil.example/credential-handoff#$token",
            "https://evil.example@umbra-agent.pages.dev/credential-handoff#$token",
            "https://umbra-agent.pages.dev:444/credential-handoff#$token",
            "https://umbra-agent.pages.dev/credential-handoff/extra#$token",
            "https://umbra-agent.pages.dev/credential-handoff?token=$token",
            "https://umbra-agent.pages.dev/credential-handoff?other=1#$token",
            "https://umbra-agent.pages.dev/credential-handoff#%41${token.drop(1)}",
            "https://umbra-agent.pages.dev/credential-handoff#token=$token",
            "umbra://credential-handoff/$token?extra=1", "umbra://credential-handoff/$token#extra",
            "umbra://credential-handoff/$token/extra", "umbra://other/$token", "not a link", "x".repeat(513))
        links.forEach { assertNull(CredentialLinks.token(it), it) }
        assertFailsWith<IllegalArgumentException> { CredentialLinks.panel("short", true) }
    }
}
