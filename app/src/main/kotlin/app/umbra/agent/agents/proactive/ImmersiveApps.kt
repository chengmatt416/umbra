package app.umbra.agent.agents.proactive

import android.content.pm.ApplicationInfo

/** Reuses the accessibility service's foreground signal without polling apps or retaining text. */
class ImmersiveApps(private val category: (String) -> Int?) {
    private var foreground: String? = null
    private var immersive = false

    @Synchronized fun active(packageName: String?, connected: Boolean): Boolean {
        if (!connected || packageName == null) {
            foreground = null
            immersive = false
        } else if (packageName != foreground) {
            foreground = packageName
            immersive = runCatching { category(packageName) }.getOrNull() in setOf(ApplicationInfo.CATEGORY_GAME, ApplicationInfo.CATEGORY_VIDEO)
        }
        return immersive
    }
}
