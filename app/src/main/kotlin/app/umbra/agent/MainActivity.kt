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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Porcelain is a light glaze edge to edge: dark status and navigation icons over the page colour.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        requestedSession = intent?.getStringExtra(EXTRA_SESSION)
        setContent {
            PorcelainTheme {
                PorcelainApp(openSession = requestedSession, onOpened = { requestedSession = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedSession = intent.getStringExtra(EXTRA_SESSION)
    }

    companion object {
        const val EXTRA_SESSION = "session"
    }
}
