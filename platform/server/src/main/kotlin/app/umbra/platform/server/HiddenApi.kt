package app.umbra.platform.server

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Process

/**
 * Framework access for a process started with `app_process`, which has no Application and therefore no
 * Context. Some system services refuse to talk to a caller without one, so the server borrows the
 * system context and presents itself as the shell package (`com.android.shell`), the identity the
 * system already associates with uid 2000 (the same approach scrcpy's server uses).
 */
internal object HiddenApi {
    const val SHELL_PACKAGE = "com.android.shell"

    private val systemContext: Context by lazy {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as Context
    }

    /** A context whose package and uid are the shell's, for services that check who is calling. */
    val context: Context by lazy { ShellContext(systemContext) }

    fun displayManager(): DisplayManager {
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(context)
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE

        override fun getOpPackageName(): String = SHELL_PACKAGE

        override fun getApplicationContext(): Context = this

        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
    }
}
