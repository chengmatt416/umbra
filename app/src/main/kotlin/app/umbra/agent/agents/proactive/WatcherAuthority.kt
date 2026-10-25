package app.umbra.agent.agents.proactive

import app.umbra.core.model.UmbraJson
import kotlinx.serialization.json.*

/** Auto-disabling a one-shot trigger preserves its one run; a user revoke or new grant invalidates it. */
object WatcherAuthority {
    const val KEY = "watcher.authorization.v1"
    fun stamp(watcher: AgentWatcher): JsonObject {
        require(watcher.authorizedAtMs != null)
        return buildJsonObject {
            put("id", watcher.id)
            put("definition", SkillStore.hash(UmbraJson.encodeToString(AgentWatcher.serializer(), watcher.copy(enabled = true,
                lastCheckedMs = 0, lastValue = null, lastHash = null, lastMatched = false, error = null))))
        }
    }
    fun allows(watcher: AgentWatcher?, authority: JsonObject): Boolean = watcher != null && watcher.authorizedAtMs != null &&
        (watcher.enabled || watcher.triggerOnce) && stamp(watcher) == authority
}
