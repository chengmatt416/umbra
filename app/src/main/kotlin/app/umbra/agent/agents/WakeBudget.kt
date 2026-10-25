package app.umbra.agent.agents

/**
 * How often an agent may run its model in the background (heartbeats, timers, rules, messages): at most [MAX_PER_HOUR]
 * runs in any rolling hour and [MIN_GAP_MS] apart. Whatever a run arms for itself (a one-minute timer, a rule its own
 * output matches), the ten-minute heartbeat cannot turn into a loop. The user's own requests are never limited.
 */
object WakeBudget {
    const val MAX_PER_HOUR = 12
    const val MIN_GAP_MS = 60_000L
    const val MAX_DEFERRED = 40
    private const val HOUR_MS = 60 * 60_000L

    fun allows(recent: List<Long>, now: Long): Boolean {
        val lastHour = recent.filter { it > now - HOUR_MS && it <= now }
        return lastHour.size < MAX_PER_HOUR && (lastHour.maxOrNull()?.let { now - it >= MIN_GAP_MS } ?: true)
    }

    fun record(recent: List<Long>, now: Long): List<Long> = (recent.filter { it > now - HOUR_MS } + now).takeLast(MAX_PER_HOUR * 2)

    /** When the next background run may start. */
    fun nextAllowedMs(recent: List<Long>, now: Long): Long {
        val lastHour = recent.filter { it > now - HOUR_MS && it <= now }.sorted()
        val byGap = (lastHour.lastOrNull() ?: Long.MIN_VALUE / 2) + MIN_GAP_MS
        val byCount = if (lastHour.size < MAX_PER_HOUR) now else lastHour[lastHour.size - MAX_PER_HOUR] + HOUR_MS
        return maxOf(now, byGap, byCount)
    }
}
