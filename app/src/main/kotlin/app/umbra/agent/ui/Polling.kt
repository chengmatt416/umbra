package app.umbra.agent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Polls only while the screen is started, reading at once on return. The read can retain the current value on failure. */
@Composable
fun <T> rememberPolled(initial: T, vararg keys: Any?, periodMs: Long, read: suspend State<T>.() -> T): State<T> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentRead by rememberUpdatedState(read)
    return produceState(initial, lifecycle, periodMs, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = currentRead(this@produceState)
                delay(periodMs)
            }
        }
    }
}

/** Refreshes at once and periodically while started, so a composed screen does no polling in the background. */
@Composable
fun PollWhileStarted(vararg keys: Any?, periodMs: Long, block: suspend () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentBlock by rememberUpdatedState(block)
    LaunchedEffect(lifecycle, periodMs, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                currentBlock()
                delay(periodMs)
            }
        }
    }
}
