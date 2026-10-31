package app.umbra.agent.cards

import app.umbra.core.model.UmbraJson
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Pass the app's filesDir; answers stay local and bursts of checkbox changes become one atomic disk write. */
class CardStates(filesDir: File) : Closeable {
    private val file = File(filesDir, "cards/states.json")
    private val lock = Any()
    private val states = LinkedHashMap<String, CardState>(16, .75f, true)
    private val writer = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "card-states").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private var pending: ScheduledFuture<*>? = null
    private var closed = false

    /** A failed write leaves the previous file intact; the next change or flush retries the in-memory snapshot. */
    @Volatile var lastWriteError: IOException? = null
        private set

    init {
        // A damaged file costs the ticks it held, never the chat: cards then start unticked.
        if (file.isFile) runCatching { UmbraJson.decodeFromString(serializer, file.readText()) }.getOrNull()
            ?.entries?.toList()?.takeLast(MAX_ENTRIES)?.forEach { (key, state) -> states[key] = snapshot(state) }
    }

    /** Drawing a card reads its state; only a change writes, so scrolling a chat never touches the disk. */
    fun get(key: String): CardState = synchronized(lock) {
        check(!closed) { "CardStates is closed" }
        states[key]?.let(::snapshot) ?: CardState()
    }

    fun put(key: String, state: CardState) = synchronized(lock) {
        check(!closed) { "CardStates is closed" }
        states[key] = snapshot(state)
        while (states.size > MAX_ENTRIES) states.remove(states.keys.first())
        schedule()
    }

    private fun schedule() {
        pending?.cancel(false)
        pending = writer.schedule({ persist() }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun persist() {
        val copy = synchronized(lock) { LinkedHashMap(states) }
        try {
            val parent = requireNotNull(file.parentFile)
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create card state directory")
            val temporary = Files.createTempFile(parent.toPath(), ".states-", ".tmp")
            try {
                val bytes = UmbraJson.encodeToString(serializer, copy).toByteArray(Charsets.UTF_8)
                FileOutputStream(temporary.toFile()).use { out -> out.write(bytes); out.fd.sync() }
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                lastWriteError = null
            } finally { Files.deleteIfExists(temporary) }
        } catch (e: IOException) { lastWriteError = e }
    }

    /** Waiting is useful at orderly shutdown and in tests; serialization and disk I/O still run on the writer. */
    internal fun flush() {
        val future = synchronized(lock) {
            check(!closed) { "CardStates is closed" }
            pending?.cancel(false)
            writer.submit { persist() }
        }
        future.get()
        lastWriteError?.let { throw it }
    }

    override fun close() {
        val future = synchronized(lock) {
            if (closed) return
            closed = true
            pending?.cancel(false)
            writer.submit { persist() }
        }
        try { future.get(); lastWriteError?.let { throw it } }
        finally { writer.shutdown() }
    }

    private fun snapshot(state: CardState) = state.copy(checks = state.checks.toMap(), sent = state.sent.toMap())

    private companion object {
        const val MAX_ENTRIES = 2_000
        const val DEBOUNCE_MS = 250L
        val serializer = MapSerializer(String.serializer(), CardState.serializer())
    }
}
