package app.umbra.platform.privileged

/** A connection's resources. A reservation also fences creation that races with disconnect or cancellation. */
class OwnedResources<T : Any>(private val limit: Int, private val dispose: (T) -> Unit) : AutoCloseable {
    class Reservation<T : Any> internal constructor(val id: String) { internal var value: T? = null }
    private val entries = HashMap<String, Reservation<T>>()
    private var closed = false

    @Synchronized
    fun reserve(id: String): Reservation<T>? {
        if (closed || id.isBlank() || id.length > 128 || id in entries || entries.size >= limit) return null
        return Reservation<T>(id).also { entries[id] = it }
    }

    fun attach(reservation: Reservation<T>, value: T): Boolean {
        val accepted = synchronized(this) {
            if (closed || entries[reservation.id] !== reservation || reservation.value != null) false
            else { reservation.value = value; true }
        }
        if (!accepted) dispose(value)
        return accepted
    }

    @Synchronized
    operator fun get(id: String?): T? = entries[id]?.value

    @Synchronized
    fun release(reservation: Reservation<T>) {
        if (entries[reservation.id] === reservation) entries.remove(reservation.id)
    }

    fun cancel(id: String?) {
        val value = synchronized(this) { entries.remove(id)?.value }
        value?.let(dispose)
    }

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    override fun close() {
        val values = synchronized(this) {
            closed = true
            entries.values.mapNotNull { it.value }.also { entries.clear() }
        }
        values.forEach { runCatching { dispose(it) } }
    }
}
