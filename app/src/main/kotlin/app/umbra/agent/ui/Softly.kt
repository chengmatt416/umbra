package app.umbra.agent.ui

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Runs a refresh that only reads the state of the device. Root can be taken away, the helper can die, a service can
 * restart, at any moment and in the middle of one of these; that is an expected event and the screen should show the
 * new state at its next refresh, not take the app down with an uncaught exception in a composition coroutine.
 */
suspend inline fun softly(what: String = "refresh", block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Umbra", "$what failed, will look again: ${e.message}")
    }
}
