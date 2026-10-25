package app.umbra.agent.agents.proactive

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class WatcherAuthorityTest {
    private fun watcher() = AgentWatcher("w", "a", "Monitor", ProbeSpec.HttpOrCss("https://example.com"),
        ConditionSpec.Contains("match"), intervalMinutes = 5, onMatchInstruction = "Review", enabled = true, authorizedAtMs = 1)

    @Test fun oneShotDispatchKeepsItsGrantButRevocationAndDefinitionChangesInvalidateIt() {
        val w = watcher()
        val stamp = WatcherAuthority.stamp(w)
        assertTrue(WatcherAuthority.allows(w.copy(enabled = false, lastCheckedMs = 100, lastHash = "observation", lastMatched = true), stamp))
        for (changed in listOf(w.copy(enabled = false, authorizedAtMs = null), w.copy(authorizedAtMs = 2), w.copy(agentId = "other"),
            w.copy(probe = ProbeSpec.HttpOrCss("https://other.example.com")), w.copy(onMatchInstruction = "Changed")))
            assertFalse(WatcherAuthority.allows(changed, stamp))
        assertFalse(WatcherAuthority.allows(null, stamp))
        val repeating = w.copy(triggerOnce = false)
        assertFalse(WatcherAuthority.allows(repeating.copy(enabled = false), WatcherAuthority.stamp(repeating)))
    }

    @Test fun revocationDuringProbeDoesNotRestorePermissionOrCallAnyModel() = runBlocking {
        val store = ProactiveStore(Files.createTempDirectory("watcher-revoke-").toFile())
        val w = watcher().copy(condition = ConditionSpec.LayaVerify("Review evidence"))
        store.saveWatcher(w)
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<String>()
        var layaCalls = 0
        var wakes = 0
        val engine = WatcherEngine(store, { started.complete(Unit); result.await() }, { _, _ -> layaCalls++; true }, { _, _ -> wakes++ })
        val running = async { engine.tick("a", 300_000) }
        started.await()
        val revoked = w.copy(enabled = false, authorizedAtMs = null)
        store.saveWatcher(revoked)
        result.complete("match")
        running.await()
        assertEquals(revoked, store.watchers("a").single())
        assertEquals(0, layaCalls)
        assertEquals(0, wakes)
    }

    @Test fun revocationDuringLocalReviewPreventsDispatch() = runBlocking {
        val store = ProactiveStore(Files.createTempDirectory("watcher-review-").toFile())
        val w = watcher().copy(condition = ConditionSpec.LayaVerify("Review evidence"))
        store.saveWatcher(w)
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Boolean>()
        var wakes = 0
        val engine = WatcherEngine(store, { "match" }, { _, _ -> started.complete(Unit); result.await() }, { _, _ -> wakes++ })
        val running = async { engine.tick("a", 300_000) }
        started.await()
        val revoked = w.copy(enabled = false, authorizedAtMs = null)
        store.saveWatcher(revoked)
        result.complete(true)
        running.await()
        assertEquals(revoked, store.watchers("a").single())
        assertEquals(0, wakes)
    }

    @Test fun aFailedProbeCannotResurrectARemovedWatcher() = runBlocking {
        val store = ProactiveStore(Files.createTempDirectory("watcher-removed-").toFile())
        val w = watcher()
        store.saveWatcher(w)
        val engine = WatcherEngine(store, { store.removeWatcher("a", w.id); error("Probe failed") }, { _, _ -> false }, { _, _ -> fail("No dispatch expected") })
        engine.tick("a", 300_000)
        assertTrue(store.watchers("a").isEmpty())
    }
}
