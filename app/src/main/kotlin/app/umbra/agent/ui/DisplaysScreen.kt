package app.umbra.agent.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.PEmpty
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.agent.container
import app.umbra.core.model.action.Environment
import app.umbra.core.model.observation.DisplayInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live view of the displays the agent works on, so the user can watch background work (spec §10, §25). */
@Composable
fun DisplaysScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var displays by remember { mutableStateOf<List<DisplayInfo>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) {
        displays = try { container.displays.list() } catch (e: Exception) { error = e.message; emptyList() }
    }
    PollWhileStarted(Unit, periodMs = 3_000) { tick++ }
    PorcelainPage(stringResource(R.string.dest_displays), onBack) {
        if (displays.isEmpty() && error == null) {
            PEmpty(stringResource(R.string.disp_empty_title), stringResource(R.string.disp_empty_body), icon = PIcons.monitor)
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 6.dp, 18.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Eyebrow(stringResource(R.string.disp_eyebrow)) }
            error?.let { item { Callout(it, Tone.Danger) } }
            items(displays, key = { it.id }) { d ->
                val lease = container.leases.holder(d.id)
                PCard(style = CardStyle.Soft) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (d.id == 0) stringResource(R.string.disp_phone) else d.name ?: stringResource(R.string.disp_named, d.id), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
                            Subtle(
                                "${d.width}×${d.height} · " + stringResource(if (d.isVirtual) R.string.disp_background else R.string.disp_builtin) +
                                    (lease?.let { " · " + stringResource(R.string.disp_in_use) } ?: ""),
                            )
                        }
                        if (d.isVirtual && lease == null && d.ownerPackage == "com.android.shell") {
                            PButton(stringResource(R.string.disp_remove), { scope.launch { runCatching { container.displays.destroy(d.id) }; tick++ } }, style = ButtonStyle.Alt, tiny = true)
                        }
                    }
                    DisplayPreview(container, d.id, tick)
                }
            }
        }
    }
}

@Composable
private fun DisplayPreview(container: AppContainer, displayId: Int, tick: Int) {
    val image by produceState<ImageBitmap?>(null, displayId, tick) {
        value = withContext(Dispatchers.IO) {
            try {
                val backend = container.backends.screenshots(Environment.ANDROID).firstOrNull { runCatching { it.isAvailable() }.getOrDefault(false) }
                backend?.capture(displayId)?.let { img -> BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size)?.asImageBitmap() }
            } catch (_: Exception) {
                null
            }
        } ?: value
    }
    image?.let {
        Image(it, contentDescription = stringResource(R.string.disp_preview, displayId), modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 10.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit)
    }
}
