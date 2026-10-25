package app.umbra.agent.agents.proactive

import android.content.pm.ApplicationInfo
import kotlin.test.*

class ImmersiveAppsTest {
    @Test fun gameAndVideoAppsReduceInterruptionsWithoutRepeatedPackageQueries() {
        val lookups = mutableListOf<String>()
        val apps = ImmersiveApps { pkg ->
            lookups += pkg
            when (pkg) { "game" -> ApplicationInfo.CATEGORY_GAME; "video" -> ApplicationInfo.CATEGORY_VIDEO; else -> ApplicationInfo.CATEGORY_PRODUCTIVITY }
        }
        repeat(10) { assertTrue(apps.active("game", true)) }
        assertEquals(listOf("game"), lookups)
        assertTrue(apps.active("video", true))
        assertFalse(apps.active("editor", true))
        assertFalse(apps.active("game", false))
        assertTrue(apps.active("game", true))
        assertEquals(listOf("game", "video", "editor", "game"), lookups)
        val busy = InterruptionContext(immersiveOrTyping = apps.active("game", true))
        assertEquals(DeliveryTier.STANDARD_NOTIFY, AttentionRanker.tier(.8, .8, .8, InterruptionContext()))
        assertEquals(DeliveryTier.BRIEF_QUEUE, AttentionRanker.tier(.8, .8, .8, busy))
        assertEquals(DeliveryTier.URGENT_BREAKTHROUGH, AttentionRanker.tier(1.0, 1.0, 1.0, busy))
    }

    @Test fun missingForegroundOrUnavailableMetadataDoesNotKeepAnOldBusyState() {
        val apps = ImmersiveApps { pkg -> if (pkg == "video") ApplicationInfo.CATEGORY_VIDEO else error("Package unavailable") }
        assertTrue(apps.active("video", true))
        assertFalse(apps.active("missing", true))
        assertFalse(apps.active(null, true))
        assertTrue(apps.active("video", true))
        assertFalse(apps.active("video", false))
    }
}
