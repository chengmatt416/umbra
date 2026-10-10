package app.umbra.agent

import android.app.Application
import android.system.Os
import android.util.Log
import app.umbra.platform.api.BackendException

class UmbraApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        hideFromTheLinuxEnvironment()
        keepRunningWhenABackendGoesAway()
        container = AppContainer(this)
        container.start()
    }

    /**
     * The agent works in the Linux environment as this app's own uid. Linux shows it every process of that uid in
     * `/proc`, and for a process it may inspect, `/proc/<pid>/root` is a door into that process's files: the app's
     * private folder, with the helpers' tokens and the model keys. A process that is not dumpable cannot be inspected
     * by others, so the files behind `/proc/<pid>` become root's and the door is shut. The app itself is not
     * affected (a process may always look at itself, and crash reports re-enable dumping when they need it).
     */
    private fun hideFromTheLinuxEnvironment() {
        try {
            Os.prctl(PR_SET_DUMPABLE, 0, 0, 0, 0)
        } catch (_: Exception) {
            // Not fatal: the approvals and the capability limits still apply. The probe in the on-device checks tells.
        }
    }

    /**
     * Root can be taken away in the root manager, the helper can be killed, a shell can stop answering, at any moment
     * and in the middle of anything. The code that uses them handles that where it can; this is the net under it for a
     * coroutine that ends with a [BackendException] nobody caught: it ends quietly (the screens look again at their
     * next refresh) instead of taking the whole app, and every task in it, down. Anything else still crashes as usual.
     */
    private fun keepRunningWhenABackendGoesAway() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val fromACoroutine = error.suppressed.any { it.javaClass.name.endsWith("DiagnosticCoroutineContextException") }
            val causes = generateSequence(error) { it.cause }
            if (fromACoroutine && causes.any { it is BackendException }) {
                Log.w("Umbra", "a coroutine ended because a backend went away: ${error.message}", error)
            } else {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    private companion object {
        const val PR_SET_DUMPABLE = 4
    }
}

val android.content.Context.container: AppContainer get() = (applicationContext as UmbraApp).container
