package app.umbra.agent.ui.current

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity

/**
 * Keeps a chat list on its newest message while the person is there: when content arrives and when the keyboard
 * opens, which shrinks the list without scrolling it (the message just sent and the thinking row then sat hidden
 * under the composer). Scrolling up by hand stops following until the list is back at its end.
 */
@Composable
fun rememberStickToBottom(list: LazyListState): State<Boolean> {
    val stick = remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        // Decided only when a scroll settles: a keyboard shrinking the list is not a scroll and must not end following.
        snapshotFlow { list.isScrollInProgress }.collect { scrolling -> if (!scrolling) stick.value = !list.canScrollForward }
    }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(keyboard) { if (stick.value) list.scrollToEnd(animate = false) }
    return stick
}

/** To the very end of the list, the bottom of a tall last item included (scrollToItem alone aligns its top). */
suspend fun LazyListState.scrollToEnd(animate: Boolean) {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    if (animate) animateScrollToItem(last) else scrollToItem(last)
    scrollBy(100_000f)
}
