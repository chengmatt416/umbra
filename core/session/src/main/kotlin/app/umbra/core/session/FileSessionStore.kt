package app.umbra.core.session

import app.umbra.core.model.Clock
import app.umbra.core.model.UmbraJson
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * One directory per session (ADR 0006):
 *
 * ```
 * <root>/<id>/session.json    metadata, replaced atomically (temp file + rename, fsynced)
 * <root>/<id>/events.jsonl    append-only timeline, one EventRecord per line, `seq` first
 * ```
 *
 * A crash can leave a torn last line in `events.jsonl`; it is dropped on first access so later
 * appends never glue onto it. Readers skip lines that do not parse.
 */
class FileSessionStore(
    private val root: Path,
    private val clock: Clock = Clock.System,
    private val json: Json = UmbraJson,
) : SessionStore {
    private val storeMutex = Mutex()
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()
    private val seqCounters = ConcurrentHashMap<String, AtomicLong>()
    private val _changes = MutableSharedFlow<StoreChange>(extraBufferCapacity = 512)
    override val changes: SharedFlow<StoreChange> = _changes.asSharedFlow()

    init {
        Files.createDirectories(root)
    }

    private fun dir(id: String): Path = root.resolve(SessionIds.require(id))
    private fun sessionFile(id: String): Path = dir(id).resolve(SESSION_FILE)
    private fun eventsFile(id: String): Path = dir(id).resolve(EVENTS_FILE)
    private fun mutexFor(id: String): Mutex = sessionMutexes.getOrPut(id) { Mutex() }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    override suspend fun create(session: Session): Session {
        SessionIds.require(session.id)
        io {
            storeMutex.withLock {
                try {
                    Files.createDirectory(dir(session.id))
                } catch (e: FileAlreadyExistsException) {
                    throw SessionExistsException(session.id)
                }
                writeSession(session)
            }
        }
        _changes.emit(StoreChange.SessionChanged(session))
        return session
    }

    override suspend fun get(id: String): Session? {
        if (!SessionIds.isValid(id)) return null
        return io { readSession(id) }
    }

    override suspend fun update(id: String, transform: (Session) -> Session): Session? {
        if (!SessionIds.isValid(id)) return null
        val updated = io {
            mutexFor(id).withLock {
                val current = readSession(id) ?: return@io null
                transform(current).copy(id = id, updatedAtMs = clock.nowMs()).also { writeSession(it) }
            }
        } ?: return null
        _changes.emit(StoreChange.SessionChanged(updated))
        return updated
    }

    override suspend fun list(filter: SessionFilter): List<Session> = io {
        val found = mutableListOf<Pair<Session, Long>>()
        Files.newDirectoryStream(root).use { entries ->
            for (entry in entries) {
                val id = entry.fileName.toString()
                if (!SessionIds.isValid(id) || !Files.isDirectory(entry)) continue
                val session = readSession(id) ?: continue
                found += session to maxOf(session.updatedAtMs, lastModifiedMs(eventsFile(id)))
            }
        }
        found.asSequence()
            .filter { (s, _) -> filter.states == null || s.state in filter.states }
            .filter { (s, _) -> filter.query.isNullOrBlank() || s.title.contains(filter.query, true) || s.request.contains(filter.query, true) }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(filter.limit)
            .toList()
    }

    override suspend fun delete(id: String): Boolean {
        if (!SessionIds.isValid(id)) return false
        val existed = io {
            storeMutex.withLock {
                mutexFor(id).withLock {
                    val d = dir(id)
                    if (!Files.exists(d)) {
                        false
                    } else {
                        deleteRecursively(d)
                        seqCounters.remove(id)
                        true
                    }
                }
            }
        }
        sessionMutexes.remove(id)
        if (existed) _changes.emit(StoreChange.SessionDeleted(id))
        return existed
    }

    override suspend fun deleteAll() {
        io {
            storeMutex.withLock {
                Files.newDirectoryStream(root).use { entries -> entries.forEach { deleteRecursively(it) } }
                seqCounters.clear()
                sessionMutexes.clear()
            }
        }
        _changes.emit(StoreChange.AllCleared)
    }

    override suspend fun duplicate(id: String, newId: String, title: String?, withHistory: Boolean): Session? {
        SessionIds.require(newId)
        if (!SessionIds.isValid(id)) return null
        val copy = io {
            storeMutex.withLock {
                mutexFor(id).withLock {
                    val source = readSession(id) ?: return@io null
                    try {
                        Files.createDirectory(dir(newId))
                    } catch (e: FileAlreadyExistsException) {
                        throw SessionExistsException(newId)
                    }
                    val duplicate = Duplication.derive(source, newId, title, clock.nowMs())
                    writeSession(duplicate)
                    if (withHistory && Files.exists(eventsFile(id))) {
                        repairTail(eventsFile(id))
                        Files.copy(eventsFile(id), eventsFile(newId))
                    }
                    duplicate
                }
            }
        } ?: return null
        _changes.emit(StoreChange.SessionChanged(copy))
        return copy
    }

    override suspend fun append(sessionId: String, event: SessionEvent): EventRecord {
        if (!SessionIds.isValid(sessionId)) throw SessionNotFoundException(sessionId)
        val record = io {
            mutexFor(sessionId).withLock {
                if (!Files.isDirectory(dir(sessionId))) throw SessionNotFoundException(sessionId)
                val seq = counter(sessionId).incrementAndGet()
                val record = EventRecord(seq = seq, ts = clock.nowMs(), event = event)
                val line = json.encodeToString(EventRecord.serializer(), record) + "\n"
                Files.write(
                    eventsFile(sessionId),
                    line.toByteArray(Charsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
                record
            }
        }
        _changes.emit(StoreChange.EventAppended(sessionId, record))
        return record
    }

    override suspend fun events(sessionId: String, afterSeq: Long, limit: Int): List<EventRecord> {
        if (!SessionIds.isValid(sessionId)) return emptyList()
        return io {
            val file = eventsFile(sessionId)
            if (!Files.exists(file)) return@io emptyList()
            val out = ArrayList<EventRecord>()
            Files.newBufferedReader(file).use { reader ->
                while (out.size < limit) {
                    val line = reader.readLine() ?: break
                    val seq = parseSeq(line) ?: continue
                    if (seq <= afterSeq) continue
                    decode(line)?.let { out += it }
                }
            }
            out
        }
    }

    override suspend fun tail(sessionId: String, count: Int): List<EventRecord> {
        if (!SessionIds.isValid(sessionId) || count <= 0) return emptyList()
        return io {
            val file = eventsFile(sessionId)
            if (!Files.exists(file)) return@io emptyList()
            val window = ArrayDeque<String>(count + 1)
            Files.newBufferedReader(file).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (parseSeq(line) == null) continue
                    window.addLast(line)
                    if (window.size > count) window.removeFirst()
                }
            }
            window.mapNotNull { decode(it) }
        }
    }

    override suspend fun eventCount(sessionId: String): Long {
        if (!SessionIds.isValid(sessionId) || !Files.isDirectory(dir(sessionId))) return 0
        return io { mutexFor(sessionId).withLock { counter(sessionId).get() } }
    }

    // ---- internals ----

    private fun counter(id: String): AtomicLong = seqCounters.getOrPut(id) { AtomicLong(loadLastSeq(id)) }

    private fun loadLastSeq(id: String): Long {
        val file = eventsFile(id)
        if (!Files.exists(file)) return 0
        repairTail(file)
        var last = 0L
        Files.newBufferedReader(file).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                parseSeq(line)?.let { last = maxOf(last, it) }
            }
        }
        return last
    }

    /** Drops a trailing partial line (a crash mid-append) so the next append starts on a clean line. */
    private fun repairTail(file: Path) {
        FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE).use { ch ->
            val size = ch.size()
            if (size == 0L) return
            val one = java.nio.ByteBuffer.allocate(1)
            ch.read(one, size - 1)
            if (one.get(0) == '\n'.code.toByte()) return
            var pos = size - 1
            val buf = java.nio.ByteBuffer.allocate(4096)
            var cut = 0L
            scan@ while (pos > 0) {
                val start = maxOf(0L, pos - buf.capacity())
                buf.clear()
                buf.limit((pos - start).toInt())
                ch.read(buf, start)
                for (i in (pos - start).toInt() - 1 downTo 0) {
                    if (buf.get(i) == '\n'.code.toByte()) {
                        cut = start + i + 1
                        break@scan
                    }
                }
                pos = start
            }
            ch.truncate(cut)
        }
    }

    private fun parseSeq(line: String): Long? {
        if (!line.startsWith(SEQ_PREFIX)) return null
        var i = SEQ_PREFIX.length
        var value = 0L
        var digits = 0
        while (i < line.length && line[i].isDigit()) {
            value = value * 10 + (line[i] - '0')
            i++
            digits++
        }
        return if (digits > 0 && i < line.length && line[i] == ',') value else null
    }

    private fun decode(line: String): EventRecord? =
        try {
            json.decodeFromString(EventRecord.serializer(), line)
        } catch (e: Exception) {
            null
        }

    private fun readSession(id: String): Session? {
        val file = sessionFile(id)
        if (!Files.exists(file)) return null
        return try {
            json.decodeFromString(Session.serializer(), Files.readString(file))
        } catch (e: Exception) {
            null
        }
    }

    private fun writeSession(session: Session) {
        val target = sessionFile(session.id)
        val tmp = dir(session.id).resolve("$SESSION_FILE.tmp")
        val bytes = json.encodeToString(Session.serializer(), session).toByteArray(Charsets.UTF_8)
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            ch.write(java.nio.ByteBuffer.wrap(bytes))
            ch.force(true)
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun lastModifiedMs(path: Path): Long =
        try {
            Files.getLastModifiedTime(path).toMillis()
        } catch (e: IOException) {
            0L
        }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private companion object {
        const val SESSION_FILE = "session.json"
        const val EVENTS_FILE = "events.jsonl"
        const val SEQ_PREFIX = "{\"seq\":"
    }
}
