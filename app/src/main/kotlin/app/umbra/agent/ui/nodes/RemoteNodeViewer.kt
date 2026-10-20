package app.umbra.agent.ui.nodes

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.nodes.NodeConnections
import app.umbra.agent.ui.porcelain.*
import app.umbra.core.nodes.RfbClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.net.ssl.SSLSocket

internal class NodeScreenConnection(private val nodes: NodeConnections, private val id: String) : AutoCloseable {
    private val mutex = Mutex()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false
    private var client: RfbClient? = null
    suspend fun frame(): Bitmap = withContext(Dispatchers.IO) { mutex.withLock {
        check(!closed) { "Screen closed" }
        if (client == null) {
            val opened = nodes.openScreen(id)
            if (closed) { opened.close(); error("Screen closed") }
            socket = opened
            client = RfbClient(opened.inputStream, opened.outputStream)
        }
        val rfb = client!!
        val pixels = rfb.update()
        Bitmap.createBitmap(pixels, rfb.width, rfb.height, Bitmap.Config.ARGB_8888)
    } }
    suspend fun pointer(x: Int, y: Int, buttons: Int) = withContext(Dispatchers.IO) { mutex.withLock { client?.let { it.pointer(x.coerceIn(0, it.width - 1), y.coerceIn(0, it.height - 1), buttons) } } }
    suspend fun text(text: String) = withContext(Dispatchers.IO) { mutex.withLock { text.codePoints().forEach { c -> val key = if (c in 32..255) c else 0x01000000 or c; client?.key(key, true); client?.key(key, false) } } }
    suspend fun key(code: Int) = withContext(Dispatchers.IO) { mutex.withLock { client?.key(code, true); client?.key(code, false) } }
    override fun close() { closed = true; runCatching { socket?.close() } }
}

@Composable
internal fun NodeScreenPreview(nodeId: String) { RemoteNodeImage(nodeId, preview = true) }

@Composable
internal fun RemoteNodeViewer(nodeId: String, initiallyControlling: Boolean = false) { RemoteNodeImage(nodeId, preview = false, initiallyControlling) }

@Composable
private fun RemoteNodeImage(nodeId: String, preview: Boolean, initiallyControlling: Boolean = false) {
    val c = LocalContext.current.container
    val nodes by c.nodes.all.collectAsStateWithLifecycle()
    val node = nodes[nodeId]
    var active by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) { active = true; onStopOrDispose { active = false } }
    var retry by remember { mutableIntStateOf(0) }
    val connection = remember(nodeId, active, retry, node?.revocation) { NodeScreenConnection(c.nodeConnections, nodeId) }
    DisposableEffect(connection) { onDispose { connection.close() } }
    var bitmap by remember(nodeId) { mutableStateOf<Bitmap?>(null) }
    var error by remember(nodeId) { mutableStateOf<String?>(null) }
    var control by remember(nodeId) { mutableStateOf(initiallyControlling) }
    var trackpad by remember(nodeId) { mutableStateOf(false) }
    var keyboard by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    DisposableEffect(nodeId, active) {
        if (active && control) c.nodeConnections.userScreen(nodeId, true)
        onDispose { c.nodeConnections.userScreen(nodeId, false) }
    }
    LaunchedEffect(connection, active, node?.info?.screen?.available) {
        if (!active) return@LaunchedEffect
        error = null
        if (node?.info?.screen?.available != true || node.revocation != null) return@LaunchedEffect
        try { while (isActive) { bitmap = connection.frame(); delay(if (preview) 2_000 else 350) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { connection.close() }
    }
    fun act(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } } }
    Column(Modifier.fillMaxWidth().then(if (preview) Modifier else Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val image = bitmap
        if (!preview && control) Callout(stringResource(R.string.rn_you_control), Tone.Good)
        Box(Modifier.fillMaxWidth().then(if (preview) Modifier.height(180.dp) else if (image != null) Modifier.aspectRatio(image.width.toFloat() / image.height) else Modifier.height(300.dp)).clip(RoundedCornerShape(12.dp)).background(P.night), contentAlignment = Alignment.Center) {
            if (image != null && node?.revocation == null) {
                Image(image.asImageBitmap(), contentDescription = stringResource(R.string.rn_screen_preview), modifier = Modifier.fillMaxWidth().then(if (control && !preview) Modifier.pointerInput(nodeId, image.width, image.height, trackpad, connection) {
                    var px = image.width / 2f; var py = image.height / 2f
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!trackpad) { px = down.position.x * image.width / size.width; py = down.position.y * image.height / size.height }
                        val start = down.position
                        act { connection.pointer(px.toInt(), py.toInt(), if (trackpad) 0 else 1) }
                        var last = start
                        var moved = false
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if ((change.position - start).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (trackpad) { px += (change.position.x - last.x) * image.width / size.width; py += (change.position.y - last.y) * image.height / size.height }
                            else { px = change.position.x * image.width / size.width; py = change.position.y * image.height / size.height }
                            px = px.coerceIn(0f, image.width - 1f); py = py.coerceIn(0f, image.height - 1f)
                            val x = px.toInt(); val y = py.toInt()
                            act { connection.pointer(x, y, if (change.pressed && !trackpad) 1 else 0) }
                            last = change.position; change.consume()
                        } while (event.changes.any { it.pressed })
                        val x = px.toInt(); val y = py.toInt()
                        act { if (trackpad && !moved) connection.pointer(x, y, 1); connection.pointer(x, y, 0) }
                    }
                } else Modifier))
            } else Text(stringResource(if (node?.info?.screen?.available == true) R.string.rn_connecting_screen else R.string.rn_screen_off), Modifier.padding(18.dp), color = P.codeText, fontSize = 13.sp)
        }
        error?.let { Callout(it, Tone.Warn); PButton(stringResource(R.string.rn_retry), { retry++ }, style = ButtonStyle.Ghost, tiny = true) }
        if (!preview) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NodePill(stringResource(R.string.rn_keyboard), { keyboard = true }, Modifier.weight(1f), secondary = true, enabled = control && image != null)
                NodePill(stringResource(if (trackpad) R.string.rn_touch else R.string.rn_trackpad), { trackpad = !trackpad }, Modifier.weight(1f), secondary = true)
                NodePill(stringResource(if (control) R.string.rn_release else R.string.rn_takeover), { control = !control; c.nodeConnections.userScreen(nodeId, control); retry++ }, Modifier.weight(1f), enabled = node?.revocation == null)
            }
            Subtle(stringResource(R.string.rn_screen_tunnel))
        }
    }
    if (keyboard) AlertDialog(onDismissRequest = { keyboard = false }, title = { Text(stringResource(R.string.rn_keyboard)) }, text = {
        Column {
            OutlinedTextField(typed, { typed = it }, label = { Text(stringResource(R.string.rn_type)) })
            Row { PButton("↵", { act { connection.key(0xff0d) } }, tiny = true); PButton("⌫", { act { connection.key(0xff08) } }, tiny = true); PButton("Esc", { act { connection.key(0xff1b) } }, tiny = true) }
        }
    }, confirmButton = { PButton(stringResource(R.string.rn_send_keys), { val value = typed; typed = ""; act { connection.text(value) } }, enabled = typed.isNotEmpty()) }, dismissButton = { PButton(stringResource(R.string.action_cancel), { keyboard = false }, style = ButtonStyle.Ghost) })
}
