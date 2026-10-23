package app.umbra.agent.data

import app.umbra.platform.api.ShellResult

internal suspend fun signOutVerified(revoke: suspend () -> ShellResult, forget: suspend () -> Unit) {
    val result = revoke()
    check(result.ok && !result.truncated && result.stdout.lineSequence().any { it == "UMBRA_SIGNOUT_OK" }) {
        "Account revocation was not verified; local account retained."
    }
    forget()
}
