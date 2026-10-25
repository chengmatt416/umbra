package app.umbra.agent.credentials

import java.net.URI

/** HTTPS carries the opaque panel nonce only in the fragment, which is not sent to the web host. */
object CredentialLinks {
    const val HOST = "umbra-agent.pages.dev"
    private val tokenPattern = Regex("[A-Za-z0-9_-]{32,100}")

    fun panel(token: String, verified: Boolean): String {
        require(tokenPattern.matches(token))
        return if (verified) "https://$HOST/credential-handoff#$token" else "umbra://credential-handoff/$token"
    }

    fun token(link: String): String? {
        if (link.length > 512) return null
        val uri = try { URI(link) } catch (_: Exception) { return null }
        if (uri.rawUserInfo != null || uri.rawQuery != null) return null
        val token = when {
            uri.scheme.equals("umbra", true) && uri.rawAuthority.equals("credential-handoff", true) && uri.rawFragment == null ->
                uri.rawPath?.removePrefix("/")
            uri.scheme.equals("https", true) && uri.host.equals(HOST, true) && uri.port in setOf(-1, 443) &&
                uri.rawPath in setOf("/credential-handoff", "/credential-handoff/") -> uri.rawFragment
            else -> null
        }
        return token?.takeIf(tokenPattern::matches)
    }
}
