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
import app.umbra.agent.ui.shell.PorcelainApp

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
        requestedPage = intent?.getStringExtra(EXTRA_PAGE)
        setContent {
            PorcelainTheme {
                PorcelainApp(openSession = requestedSession, openPage = requestedPage, onOpened = { requestedSession = null; requestedPage = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedSession = intent.getStringExtra(EXTRA_SESSION)
        requestedPage = intent.getStringExtra(EXTRA_PAGE)
    }

    companion object {
        const val EXTRA_SESSION = "session"

        /** A page to open by its route ("inbox", "emergency"), for the Capsule's links. */
        const val EXTRA_PAGE = "page"
    }
}
