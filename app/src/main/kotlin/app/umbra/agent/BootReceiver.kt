package app.umbra.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After the phone starts and is unlocked, brings the app up so sessions with a resume contract can continue
 * (architecture spec §8.1). The work itself happens in [AppContainer.start]; receiving the broadcast is what starts
 * the app's process. Nothing runs before the first unlock: the sessions' files are in credential-encrypted storage.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Touching the container is enough: UmbraApp created it, and its start resumes what the contracts allow.
        val container = context.container
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) container.updater.onPackageReplaced()
    }
}
