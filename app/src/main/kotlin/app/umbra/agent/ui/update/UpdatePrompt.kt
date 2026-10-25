package app.umbra.agent.ui.update

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.update.AppUpdater
import app.umbra.agent.update.UpdateManifest
import app.umbra.agent.ui.porcelain.AlertDialog
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PBar
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.porcelain.pcHue

/** Asked once per start of the app; a version answered "later" is not asked about again. */
private var askedThisRun = false

/**
 * When the app opens it looks once, in the background, for a newer version; if there is one it asks. "Update" does the
 * rest by itself (download, verify, install, reopen) while a thin strip at the top shows where it is.
 */
@Composable
fun UpdatePrompt() {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val updater = container.updater
    val state by updater.state.collectAsStateWithLifecycle()
    var offer by remember { mutableStateOf<UpdateManifest?>(null) }
    var following by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (askedThisRun) return@LaunchedEffect
        askedThisRun = true
        val found = updater.offered() ?: return@LaunchedEffect
        if (found.versionCode != container.settings.value.skippedUpdateVersion) offer = found
    }
    offer?.let { u ->
        AlertDialog(
            onDismissRequest = { offer = null },
            title = { Text(stringResource(R.string.r_upd_title, u.version), fontFamily = P.serif, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, letterSpacing = (-0.2).sp, fontSize = 22.sp, color = P.ink) },
            text = { Text(stringResource(R.string.r_upd_body, Formatter.formatShortFileSize(context, u.bytes)), fontSize = 14.5.sp, lineHeight = 22.sp, color = P.sub) },
            confirmButton = { PButton(stringResource(R.string.r_upd_now), { offer = null; following = true; updater.updateNow() }) },
            dismissButton = {
                PButton(stringResource(R.string.r_upd_later), {
                    container.settings.update { it.copy(skippedUpdateVersion = u.versionCode) }
                    offer = null
                }, style = ButtonStyle.Ghost)
            },
        )
    }
    if (!following) return
    val line = when (val s = state) {
        is AppUpdater.State.Checking -> null
        is AppUpdater.State.Downloading -> stringResource(R.string.r_upd_downloading, s.update.version) to s.bytes.toFloat() / s.update.bytes.coerceAtLeast(1)
        is AppUpdater.State.Ready -> stringResource(R.string.r_upd_waiting, s.update.version) to 1f
        is AppUpdater.State.Installing -> stringResource(R.string.r_upd_installing, s.update.version) to 1f
        is AppUpdater.State.Failed -> stringResource(R.string.r_upd_failed, s.message) to 0f
        else -> null
    } ?: return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier.statusBarsPadding().padding(top = 44.dp, start = 16.dp, end = 16.dp).fillMaxWidth()
                .shadow(8.dp, shape, ambientColor = pcHue(0x2222352D), spotColor = pcHue(0x3322352D)).clip(shape).background(pc(0xFFF6F8F2))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row { Text(line.first, fontSize = 13.5.sp, color = if (state is AppUpdater.State.Failed) P.danger else P.ink) }
            if (state !is AppUpdater.State.Failed) PBar(line.second, Modifier.fillMaxWidth())
        }
    }
}
