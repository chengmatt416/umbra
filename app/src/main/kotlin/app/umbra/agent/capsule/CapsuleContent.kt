package app.umbra.agent.capsule

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

internal fun <T> capsuleContent(signals: Flow<Long>, read: suspend () -> T): Flow<T> =
    signals.filter { it > 0 }.map { read() }.distinctUntilChanged()
