package app.umbra.platform.api

import app.umbra.core.model.action.ErrorCode

/**
 * Thrown by backends. The action pipeline turns it into a failed attempt and decides about retries
 * and fallbacks, so a backend reports what went wrong precisely and never retries on its own.
 */
class BackendException(
    val code: ErrorCode,
    message: String,
    /** True when trying again (possibly after re-observing) has a realistic chance of working. */
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Common to everything the pipeline can route an operation to. */
interface Backend {
    /** Short stable id used in attempts and the capability screen: `accessibility`, `root_shell`, ... */
    val name: String

    /** Cheap and free of side effects: is the backend connected and usable right now? */
    suspend fun isAvailable(): Boolean
}
