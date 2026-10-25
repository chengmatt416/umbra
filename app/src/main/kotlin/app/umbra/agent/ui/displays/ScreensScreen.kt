package app.umbra.agent.ui.displays

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.capsule.RestingCompanion
import app.umbra.agent.container
import app.umbra.agent.data.GuestManager
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PField
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.core.control.LeaseState
import app.umbra.core.control.Principal
import app.umbra.core.model.action.Environment
import app.umbra.core.model.Point
import app.umbra.core.model.observation.DisplayInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screens (Power, Dev): the background screens the agent works on, live, and the Linux desktop. Each can be taken over
 * at once (the user's input wins, INV-005); on the desktop a touch is the mouse and the keyboard types into it. In
 * Simple mode none of this is a page: the screen shows in the task's conversation instead.
 */
@Composable
fun ScreensScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    PorcelainPage(stringResource(R.string.r_screens_title), onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Segmented(listOf(0 to stringResource(R.string.r_screens_background), 1 to stringResource(R.string.r_screens_linux), 2 to stringResource(R.string.rn_title)), tab, { tab = it }, Modifier.padding(top = 6.dp, bottom = 12.dp), fill = true)
            when (tab) { 0 -> BackgroundScreens(); 1 -> LinuxDesktop(); else -> app.umbra.agent.ui.nodes.RemoteScreenPicker() }
        }
    }
}

@Composable
private fun BackgroundScreens() {
    val container = LocalContext.current.container
    var displays by remember { mutableStateOf<List<DisplayInfo>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) { displays = runCatching { container.displays.list().filter { it.isVirtual } }.onFailure { error = it.message }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) { while (true) { delay(1_500); tick++ } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        error?.let { Callout(it, Tone.Danger) }
        if (displays.isEmpty()) Text(stringResource(R.string.r_screens_none), Modifier.padding(top = 24.dp), fontSize = 14.sp, color = P.sub)
        displays.forEach { d -> DisplayCard(container, d, tick) }
    }
}

@Composable
private fun DisplayCard(container: AppContainer, d: DisplayInfo, tick: Int) {
    val leases by container.inputLeases.all.collectAsStateWithLifecycle()
    val resource = "display:${d.id}"
    val lease = leases.values.firstOrNull { it.resourceId == resource && it.state == LeaseState.ACTIVE }
    val userHolds = lease?.owner == Principal.User
    val driven = container.leases.holder(d.id) != null
    val image = rememberCapture(container, Environment.ANDROID, d.id, tick)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(pc(0xFFF6F8F2)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RestingCompanion(size = 18.dp)
            Column(Modifier.weight(1f)) {
                Text(d.name ?: stringResource(R.string.disp_named, d.id), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = P.ink)
                Text(stringResource(if (driven) R.string.r_screens_driven else R.string.r_screens_idle), fontSize = 12.sp, color = P.sub)
            }
        }
        image?.let { Image(it, stringResource(R.string.disp_preview, d.id), Modifier.fillMaxWidth().aspectRatio(d.width.toFloat() / d.height.coerceAtLeast(1)).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit) }
        if (userHolds) PButton(stringResource(R.string.r_task_give_back), { container.returnControl(resource) }, Modifier.fillMaxWidth(), style = ButtonStyle.Alt)
        else PButton(stringResource(R.string.r_task_take_over), { container.takeOver(resource) }, Modifier.fillMaxWidth(), icon = PIcons.hand)
    }
}

/** The Linux desktop: a live picture; a tap clicks there, the keyboard types into the window in front. */
@Composable
private fun LinuxDesktop() {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val packs by container.guest.packStates.collectAsStateWithLifecycle()
    val guest by container.guest.state.collectAsStateWithLifecycle()
    val leases by container.inputLeases.all.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf<Boolean?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(guest) { running = runCatching { container.linuxGui.isAvailable() }.getOrDefault(false) }
    LaunchedEffect(running) { while (running == true) { delay(1_000); tick++ } }
    val image = if (running == true) rememberCapture(container, Environment.LINUX_GUI, null, tick) else null
    val userHolds = leases.values.any { it.resourceId == "gui:0" && it.state == LeaseState.ACTIVE && it.owner == Principal.User }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        error?.let { Callout(it, Tone.Danger) }
        when {
            running == null -> Unit
            running == false -> {
                Text(stringResource(R.string.r_screens_linux_off), Modifier.padding(top = 24.dp), fontSize = 14.sp, color = P.sub)
                val installed = packs["gui"] == GuestManager.PackState.Installed
                val installing = packs["gui"] is GuestManager.PackState.Installing
                PButton(
                    stringResource(if (installed) R.string.r_screens_linux_start else R.string.r_screens_linux_install),
                    {
                        if (!installed) container.guest.installPack("gui")
                        else scope.launch { running = runCatching { container.linuxGui.start() }.onFailure { error = it.message }.getOrDefault(false) }
                    },
                    Modifier.fillMaxWidth(), enabled = !installing,
                )
            }
            else -> {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(image?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (16f / 10f))
                        .clip(RoundedCornerShape(18.dp)).background(P.night)
                        .onSizeChanged { size = it }
                        .pointerInput(image) {
                            detectTapGestures { at ->
                                val img = image ?: return@detectTapGestures
                                if (size.width == 0) return@detectTapGestures
                                val p = Point((at.x / size.width * img.width).toInt(), (at.y / size.height * img.height).toInt())
                                if (!userHolds) container.takeOver("gui:0")
                                scope.launch { runCatching { container.userInput("gui:0") { container.linuxGui.tap(null, p) } }.onFailure { error = it.message } }
                                container.demonstrations.linuxDesktopInput("CLICK", p.x, p.y, img.width, img.height)
                            }
                        },
                ) {
                    image?.let { Image(it, stringResource(R.string.r_screens_linux), Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                }
                if (typing) {
                    PField(text, { text = it }, label = stringResource(R.string.r_screens_type), singleLine = true)
                    PButton(stringResource(R.string.cap_send), {
                        val t = text
                        text = ""
                        scope.launch { runCatching { container.userInput("gui:0") { container.linuxGui.typeText(null, t); container.linuxGui.key(null, "Return") } }.onFailure { error = it.message } }
                        image?.let { img ->
                            container.demonstrations.linuxDesktopInput("TYPE", 0, 0, img.width, img.height)
                            container.demonstrations.linuxDesktopInput("KEY", 0, 0, img.width, img.height, key = "Enter")
                        }
                    }, Modifier.fillMaxWidth(), enabled = text.isNotEmpty(), icon = PIcons.send)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton(stringResource(R.string.r_screens_keyboard), { if (!typing && !userHolds) container.takeOver("gui:0"); typing = !typing }, Modifier.weight(1f), style = ButtonStyle.Alt, icon = PIcons.terminal)
                    if (userHolds) PButton(stringResource(R.string.r_task_give_back), { container.returnControl("gui:0") }, Modifier.weight(1f), style = ButtonStyle.Alt)
                    else PButton(stringResource(R.string.r_task_take_over), { container.takeOver("gui:0") }, Modifier.weight(1f), icon = PIcons.hand)
                }
            }
        }
    }
}

/** The latest picture of a screen, refreshed as [tick] advances; the previous one stays while the next is taken. */
@Composable
internal fun rememberCapture(container: AppContainer, environment: Environment, displayId: Int?, tick: Int): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, displayId, tick) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val backend = container.backends.screenshots(environment).firstOrNull { runCatching { it.isAvailable() }.getOrDefault(false) }
                backend?.capture(displayId)?.let { img -> BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size)?.asImageBitmap() }
            }.getOrNull()
        } ?: value
    }
    return image
}
