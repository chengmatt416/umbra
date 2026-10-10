package app.umbra.platform.shell

import app.umbra.core.model.action.ErrorCode
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.BinaryShellResult
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellEvent
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.ShellResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One shell identity that can be provided in several ways (the shell user via Shizuku, or via root's
 * `su 2000`). Each call goes to the first candidate that answers, so the app keeps working when
 * Shizuku dies and root takes over, or the other way round, without the callers noticing.
 */
class FirstAvailableShell(
    override val identity: ShellIdentity,
    private val candidates: List<ShellBackend>,
    private val cacheMs: Long = 5_000,
    private val now: () -> Long = System::currentTimeMillis,
) : ShellBackend {
    init {
        require(candidates.isNotEmpty()) { "no candidates" }
    }

    override val name: String get() = "first_available(${candidates.joinToString { it.name }})"

    @Volatile
    private var chosen: ShellBackend? = null

    @Volatile
    private var chosenAt = 0L

    /** One probe at a time: many callers arriving together must not each ask `su` (and so the root manager) the same question. */
    private val probing = Mutex()

    private fun cached(): ShellBackend? = chosen?.takeIf { now() - chosenAt < cacheMs }

    private suspend fun pick(): ShellBackend {
        cached()?.let { return it }
        return probing.withLock {
            cached()?.let { return@withLock it }
            for (c in candidates) {
                if (c.isAvailable()) {
                    chosen = c
                    chosenAt = now()
                    return@withLock c
                }
            }
            chosen = null
            throw BackendException(ErrorCode.BACKEND_UNAVAILABLE, "No ${identity.name.lowercase()} shell is available (${candidates.joinToString { it.name }}).")
        }
    }

    /** Forget the cached choice, e.g. after a call through it failed. */
    private fun invalidate() {
        chosen = null
    }

    override suspend fun isAvailable(): Boolean = try {
        pick()
        true
    } catch (_: BackendException) {
        false
    }

    override suspend fun exec(request: ShellRequest): ShellResult = try {
        pick().exec(request)
    } catch (e: BackendException) {
        invalidate()
        throw e
    }

    override suspend fun execBytes(request: ShellRequest): BinaryShellResult = try {
        pick().execBytes(request)
    } catch (e: BackendException) {
        invalidate()
        throw e
    }

    override fun stream(request: ShellRequest): Flow<ShellEvent> = flow { emitAll(pick().stream(request)) }
}
