package app.umbra.core.control

import app.umbra.core.model.Clock
import app.umbra.core.model.UmbraJson
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The control plane's durable state (architecture spec §1.2, §2, §8.1 "Durable Control State"): typed document
 * collections and append-only journals under one directory, separate from the runners that own processes.
 *
 * ```
 * <root>/<collection>/<id>.json     one document, replaced atomically (temp + fsync + rename)
 * <root>/journals/<name>.jsonl      append-only entries: seq, time, kind, data, prev digest, digest
 * ```
 *
 * Writes go to disk before the in-memory view changes, so what a screen shows has been persisted. Journals chain
 * each entry to the previous one by SHA-256; that detects accidental damage and naive edits, and is *not* proof
 * against someone who can rewrite the whole file (§13.2) — independent anchoring is what makes it evidence.
 */
class ControlStore(val root: Path, val clock: Clock = Clock.System, private val json: Json = UmbraJson) {
    private val collections = ConcurrentHashMap<String, Docs<*>>()
    private val journals = ConcurrentHashMap<String, Journal>()

    init {
        Files.createDirectories(root)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> docs(name: String, serializer: KSerializer<T>, idOf: (T) -> String): Docs<T> {
        require(SAFE.matches(name)) { "bad collection name: $name" }
        return collections.getOrPut(name) { Docs(root.resolve(name), serializer, idOf, json) } as Docs<T>
    }

    fun journal(name: String): Journal {
        require(SAFE.matches(name)) { "bad journal name: $name" }
        return journals.getOrPut(name) { Journal(root.resolve("journals").resolve("$name.jsonl"), clock, json) }
    }

    companion object {
        internal val SAFE = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}")

        internal fun atomicWrite(file: Path, bytes: ByteArray) {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".new")
            FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { ch ->
                ch.write(java.nio.ByteBuffer.wrap(bytes))
                ch.force(true)
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** One collection of documents of type [T], all loaded in memory (they are small) and observable. */
class Docs<T : Any> internal constructor(
    private val dir: Path,
    private val serializer: KSerializer<T>,
    private val idOf: (T) -> String,
    private val json: Json,
) {
    private val lock = Any()
    private val state: MutableStateFlow<Map<String, T>>

    init {
        Files.createDirectories(dir)
        val loaded = LinkedHashMap<String, T>()
        Files.list(dir).use { files ->
            files.filter { it.fileName.toString().endsWith(".json") }.sorted().forEach { f ->
                try {
                    val doc = json.decodeFromString(serializer, Files.readString(f))
                    loaded[idOf(doc)] = doc
                } catch (_: Exception) {
                    // A document that does not parse (older format, torn write) is skipped, never fatal.
                }
            }
        }
        state = MutableStateFlow(loaded)
    }

    val all: StateFlow<Map<String, T>> = state.asStateFlow()

    fun list(): List<T> = state.value.values.toList()

    operator fun get(id: String): T? = state.value[id]

    fun put(doc: T): T = synchronized(lock) {
        val id = idOf(doc)
        require(ControlStore.SAFE.matches(id)) { "bad document id: $id" }
        ControlStore.atomicWrite(dir.resolve("$id.json"), json.encodeToString(serializer, doc).toByteArray())
        state.value = state.value + (id to doc)
        doc
    }

    /** Read-modify-write under the collection lock; returns the new document, or null when there is none. */
    fun update(id: String, transform: (T) -> T): T? = synchronized(lock) {
        val current = state.value[id] ?: return null
        put(transform(current))
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        if (!ControlStore.SAFE.matches(id)) return false
        val existed = Files.deleteIfExists(dir.resolve("$id.json"))
        state.value = state.value - id
        existed
    }
}

@Serializable
data class JournalEntry(
    val seq: Long,
    val timeMs: Long,
    val kind: String,
    val data: JsonElement? = null,
    val prevDigest: String,
    val digest: String,
)

/** Result of checking a journal's hash chain. */
data class ChainCheck(val entries: Int, val intact: Boolean, val firstBadSeq: Long?, val headDigest: String)

/** An append-only journal whose entries are chained by SHA-256 (§2.1 OperationJournalEntry, §13.2). */
class Journal internal constructor(private val file: Path, private val clock: Clock, private val json: Json) {
    private val lock = Any()
    private var seq = 0L
    private var head = GENESIS
    private val tail = MutableStateFlow<List<JournalEntry>>(emptyList())

    init {
        Files.createDirectories(file.parent)
        if (Files.exists(file)) {
            val entries = readAll()
            entries.lastOrNull()?.let { seq = it.seq; head = it.digest }
            tail.value = entries.takeLast(TAIL)
        }
    }

    /** The most recent entries, newest last. */
    val recent: StateFlow<List<JournalEntry>> = tail.asStateFlow()

    fun append(kind: String, data: JsonElement? = null): JournalEntry = synchronized(lock) {
        val next = seq + 1
        val now = clock.nowMs()
        val body = "$next|$now|$kind|${data?.toString() ?: ""}|$head"
        val entry = JournalEntry(next, now, kind, data, head, ControlStore.sha256(body))
        val line = json.encodeToString(JournalEntry.serializer(), entry) + "\n"
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE).use { ch ->
            ch.write(java.nio.ByteBuffer.wrap(line.toByteArray()))
            ch.force(true)
        }
        seq = next
        head = entry.digest
        tail.value = (tail.value + entry).takeLast(TAIL)
        entry
    }

    fun headDigest(): String = synchronized(lock) { head }

    fun readAll(): List<JournalEntry> {
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file).mapNotNull { line ->
            if (line.isBlank()) null else try { json.decodeFromString(JournalEntry.serializer(), line) } catch (_: Exception) { null }
        }
    }

    /** Recomputes the chain from the start. */
    fun verify(): ChainCheck {
        var prev = GENESIS
        val entries = readAll()
        for (e in entries) {
            val body = "${e.seq}|${e.timeMs}|${e.kind}|${e.data?.toString() ?: ""}|$prev"
            if (e.prevDigest != prev || ControlStore.sha256(body) != e.digest) return ChainCheck(entries.size, false, e.seq, prev)
            prev = e.digest
        }
        return ChainCheck(entries.size, true, null, prev)
    }

    companion object {
        const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"
        private const val TAIL = 500
    }
}
