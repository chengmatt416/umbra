package app.umbra.platform.api

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** A fast authority check carried through backend selection, dispatcher changes and connection setup. */
class DispatchFence(val check: () -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DispatchFence> {
        suspend fun checkCurrent() { currentCoroutineContext()[Key]?.check?.invoke() }
    }
}
