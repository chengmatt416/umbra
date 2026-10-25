package app.umbra.agent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.umbra.agent.ui.porcelain.PorcelainTheme
import app.umbra.agent.ui.porcelain.Theme
import app.umbra.agent.ui.porcelain.ThemeRevealHost
import app.umbra.agent.ui.shell.PorcelainApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var requestedSession by mutableStateOf<String?>(null)
    private var requestedPage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Porcelain is a light glaze edge to edge: dark status and navigation icons over the page colour.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        requestedSession = intent?.getStringExtra(EXTRA_SESSION)
        requestedPage = intent?.getStringExtra(EXTRA_AGENT)?.let { "agent/$it" } ?: intent?.getStringExtra(EXTRA_PAGE)
        installUpdateIfAsked(intent)
        setContent {
            PorcelainTheme {
                // Light glazes take dark status and navigation icons; Liquid's dark glass takes light ones.
                val dark = Theme.dark
                androidx.compose.runtime.LaunchedEffect(dark) {
                    val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                }
                ThemeRevealHost {
                    PorcelainApp(openSession = requestedSession, openPage = requestedPage, onOpened = { requestedSession = null; requestedPage = null })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.ui.appVisible.value = true
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) { container.usageCounts.recordOpen() }
    }

    override fun onStop() {
        container.ui.appVisible.value = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedSession = intent.getStringExtra(EXTRA_SESSION)
        requestedPage = intent.getStringExtra(EXTRA_AGENT)?.let { "agent/$it" } ?: intent.getStringExtra(EXTRA_PAGE)
        installUpdateIfAsked(intent)
    }

    /** The "update ready" notification: with Umbra in front, the system's install dialog may open. */
    private fun installUpdateIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_INSTALL_UPDATE, false) == true) container.updater.installNow()
    }

    companion object {
        const val EXTRA_SESSION = "session"

        /** A page to open by its route ("inbox", "emergency"), for the Capsule's links. */
        const val EXTRA_PAGE = "page"

        /** An agent's id: opens its chat. */
        const val EXTRA_AGENT = "agent"

        /** Set by the updater's notification: install the downloaded update. */
        const val EXTRA_INSTALL_UPDATE = "install_update"
    }
}
