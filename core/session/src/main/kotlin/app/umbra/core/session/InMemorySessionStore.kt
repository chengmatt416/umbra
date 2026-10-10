package app.umbra.core.session

import app.umbra.core.model.Clock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reference implementation and test double; also what the UI previews run against. */
class InMemorySessionStore(private val clock: Clock = Clock.System) : SessionStore {
    private val mutex = Mutex()
    private val sessions = LinkedHashMap<String, Session>()
    private val timelines = HashMap<String, MutableList<EventRecord>>()
    private val _changes = MutableSharedFlow<StoreChange>(extraBufferCapacity = 512)
    override val changes: SharedFlow<StoreChange> = _changes.asSharedFlow()

    override suspend fun create(session: Session): Session {
        SessionIds.require(session.id)
        mutex.withLock {
            if (session.id in sessions) throw SessionExistsException(session.id)
            sessions[session.id] = session
            timelines[session.id] = mutableListOf()
        }
        _changes.emit(StoreChange.SessionChanged(session))
        return session
    }

    override suspend fun get(id: String): Session? = mutex.withLock { sessions[id] }

    override suspend fun update(id: String, transform: (Session) -> Session): Session? {
        val updated = mutex.withLock {
            val current = sessions[id] ?: return null
            transform(current).copy(id = id, updatedAtMs = clock.nowMs()).also { sessions[id] = it }
        }
        _changes.emit(StoreChange.SessionChanged(updated))
        return updated
    }

    override suspend fun list(filter: SessionFilter): List<Session> = mutex.withLock {
        sessions.values
            .asSequence()
            .filter { filter.states == null || it.state in filter.states }
            .filter { s -> filter.query.isNullOrBlank() || s.title.contains(filter.query, true) || s.request.contains(filter.query, true) }
            .sortedByDescending { s -> maxOf(s.updatedAtMs, timelines[s.id]?.lastOrNull()?.ts ?: 0L) }
            .take(filter.limit)
            .toList()
    }

    override suspend fun delete(id: String): Boolean {
        val existed = mutex.withLock {
            timelines.remove(id)
            sessions.remove(id) != null
        }
        if (existed) _changes.emit(StoreChange.SessionDeleted(id))
        return existed
    }

    override suspend fun deleteAll() {
        mutex.withLock {
            sessions.clear()
            timelines.clear()
        }
        _changes.emit(StoreChange.AllCleared)
    }

    override suspend fun duplicate(id: String, newId: String, title: String?, withHistory: Boolean): Session? {
        SessionIds.require(newId)
        val copy = mutex.withLock {
            val source = sessions[id] ?: return null
            if (newId in sessions) throw SessionExistsException(newId)
            val now = clock.nowMs()
            val duplicate = Duplication.derive(source, newId, title, now)
            sessions[newId] = duplicate
            timelines[newId] = if (withHistory) timelines[id].orEmpty().toMutableList() else mutableListOf()
            duplicate
        }
        _changes.emit(StoreChange.SessionChanged(copy))
        return copy
    }

    override suspend fun append(sessionId: String, event: SessionEvent): EventRecord {
        val record = mutex.withLock {
            if (sessionId !in sessions) throw SessionNotFoundException(sessionId)
            val list = timelines.getOrPut(sessionId) { mutableListOf() }
            EventRecord(seq = (list.lastOrNull()?.seq ?: 0L) + 1, ts = clock.nowMs(), event = event).also { list += it }
        }
        _changes.emit(StoreChange.EventAppended(sessionId, record))
        return record
    }

    override suspend fun events(sessionId: String, afterSeq: Long, limit: Int): List<EventRecord> = mutex.withLock {
        timelines[sessionId].orEmpty().filter { it.seq > afterSeq }.take(limit)
    }

    override suspend fun tail(sessionId: String, count: Int): List<EventRecord> = mutex.withLock {
        timelines[sessionId].orEmpty().takeLast(count)
    }

    override suspend fun eventCount(sessionId: String): Long = mutex.withLock { timelines[sessionId]?.lastOrNull()?.seq ?: 0L }
}

/** What a duplicated session keeps and what it must re-earn. */
internal object Duplication {
    fun derive(source: Session, newId: String, title: String?, now: Long): Session = source.copy(
        id = newId,
        title = title ?: (source.title + " (copy)"),
        state = SessionState.CREATED,
        createdAtMs = now,
        updatedAtMs = now,
        // The copy starts fresh: no live resources, no OMP conversation, no remembered approvals.
        ompSessionFile = null,
        activeApps = emptyList(),
        displays = emptyList(),
        terminals = emptyList(),
        processes = emptyList(),
        subagents = emptyList(),
        parentSessionId = source.id,
        failureCount = 0,
        lastError = null,
        attachments = emptyMap(),
    )
}
