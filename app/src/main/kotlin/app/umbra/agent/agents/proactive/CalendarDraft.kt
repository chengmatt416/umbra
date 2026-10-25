package app.umbra.agent.agents.proactive

import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
data class CalendarDraft(val id: String, val sessionId: String, val title: String, val startMs: Long, val endMs: Long,
    val zoneId: String, val location: String = "", val description: String = "", val createdAtMs: Long) {
    fun validate() {
        require(title.isNotBlank() && title.length <= 500 && location.length <= 1000 && description.length <= 10_000)
        require(startMs >= 0 && endMs > startMs && endMs - startMs <= 366 * 86_400_000L) { "Use a valid event interval of at most one year" }
        ZoneId.of(zoneId)
    }
    fun review() = "Title: $title\nStart: ${java.time.Instant.ofEpochMilli(startMs).atZone(ZoneId.of(zoneId))}\nEnd: ${java.time.Instant.ofEpochMilli(endMs).atZone(ZoneId.of(zoneId))}\nLocation: $location\n$description"
}
