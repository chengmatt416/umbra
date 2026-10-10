package app.umbra.core.session

import kotlinx.coroutines.flow.SharedFlow

sealed interface StoreChange {
    data class SessionChanged(val session: Session) : StoreChange
    data class EventAppended(val sessionId: String, val record: EventRecord) : StoreChange
    data class SessionDeleted(val sessionId: String) : StoreChange
    data object AllCleared : StoreChange
}

class SessionExistsException(id: String) : IllegalStateException("session already exists: $id")
class SessionNotFoundException(id: String) : NoSuchElementException("no such session: $id")
class InvalidSessionIdException(id: String) : IllegalArgumentException("invalid session id: '$id'")

/** Persistent sessions and their timelines (spec §24). All methods are safe to call concurrently. */
interface SessionStore {
    /** Emits every mutation; the UI observes this to stay current without polling. */
    val changes: SharedFlow<StoreChange>

    suspend fun create(session: Session): Session

    suspend fun get(id: String): Session?

    /** Atomic read-modify-write. Bumps `updatedAtMs`. Returns null if the session does not exist. */
    suspend fun update(id: String, transform: (Session) -> Session): Session?

    /** Most recently active first. */
    suspend fun list(filter: SessionFilter = SessionFilter()): List<Session>

    suspend fun delete(id: String): Boolean

    /** "Clear session data" (spec §32). */
    suspend fun deleteAll()

    /**
     * Creates a new session from [id] (same request, goal, plan, workspace; reset to CREATED).
     * With [withHistory] the timeline is copied too.
     */
    suspend fun duplicate(id: String, newId: String, title: String? = null, withHistory: Boolean = false): Session?

    /** Appends to the timeline and returns the record with its assigned sequence number. */
    suspend fun append(sessionId: String, event: SessionEvent): EventRecord

    /** Records after [afterSeq] in order, at most [limit]. */
    suspend fun events(sessionId: String, afterSeq: Long = 0, limit: Int = 500): List<EventRecord>

    /** The last [count] records in order. */
    suspend fun tail(sessionId: String, count: Int): List<EventRecord>

    suspend fun eventCount(sessionId: String): Long
}

object SessionIds {
    private val VALID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")

    fun isValid(id: String): Boolean = VALID.matches(id)

    fun require(id: String): String {
        if (!isValid(id)) throw InvalidSessionIdException(id)
        return id
    }

    /** Sortable by creation time, unique enough for one device. */
    fun generate(nowMs: Long, random: java.util.Random = java.util.Random()): String {
        val rand = (1..5).map { "abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)] }.joinToString("")
        return "s" + nowMs.toString(36) + "-" + rand
    }
}
