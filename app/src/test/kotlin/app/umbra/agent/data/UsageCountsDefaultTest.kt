package app.umbra.agent.data

import app.umbra.core.model.UmbraJson
import kotlin.test.*

class UsageCountsDefaultTest {
    private fun load(raw: String) = AppSettings.migrate(UmbraJson.decodeFromString(AppPrefs.serializer(), raw), raw)

    @Test fun newInstallationsShareCountsUntilSwitchedOff() {
        assertTrue(AppPrefs().shareUsageCounts)
        assertEquals(3, ProactiveDefaults().level)
    }

    @Test fun earlierInstallationsKeepTheChoiceTheyHad() {
        // Saved under "off by default": the switch was omitted when off, never touched or turned off alike.
        assertFalse(load("""{"prefsVersion":2}""").shareUsageCounts)
        assertTrue(load("""{"prefsVersion":2,"shareUsageCounts":true}""").shareUsageCounts)
        // From version 3 on a missing switch is the new default; an explicit off stays off.
        assertTrue(load("""{"prefsVersion":3}""").shareUsageCounts)
        assertFalse(load("""{"prefsVersion":3,"shareUsageCounts":false}""").shareUsageCounts)
        assertEquals(AppSettings.VERSION, load("""{"prefsVersion":2}""").prefsVersion)
    }
}
