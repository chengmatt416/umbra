package app.umbra.agent.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Violet = Color(0xFF6C4CF1)

private val LightColors = lightColorScheme(primary = Violet, secondary = Color(0xFF5B5F8F), tertiary = Color(0xFF0B7A75))
private val DarkColors = darkColorScheme(primary = Color(0xFFC8BFFF), secondary = Color(0xFFC3C5F5), tertiary = Color(0xFF7FD6CF))

/** Semantic colors that do not come from Material's roles: risk levels and run states. */
object Semantic {
    val ok = Color(0xFF2E9E5B)
    val warn = Color(0xFFE29A19)
    val danger = Color(0xFFD64545)
    val info = Color(0xFF3B82F6)
    val idle = Color(0xFF8A8F98)
}

@Composable
fun UmbraTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
