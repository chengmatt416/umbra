package app.umbra.agent.ui.nodes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.*
import app.umbra.core.nodes.NodeInvitation
import app.umbra.core.nodes.Pairing
import app.umbra.core.nodes.Rsp
import app.umbra.core.nodes.shortFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NodePairingScreen(onBack: () -> Unit, onPaired: (String) -> Unit) {
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val ink = Color(0xffeef3ec)
    val sub = Color(0xff93a595)
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedTextColor = ink, unfocusedTextColor = ink, focusedLabelColor = ink, unfocusedLabelColor = sub, focusedBorderColor = Color(0xff9dbba1), unfocusedBorderColor = sub)
    DisposableEffect(view) {
        val window = (context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val status = controller?.isAppearanceLightStatusBars
        val nav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose { status?.let { controller.isAppearanceLightStatusBars = it }; nav?.let { controller.isAppearanceLightNavigationBars = it } }
    }
    val scope = rememberCoroutineScope()
    var permission by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    var active by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) { active = true; onStopOrDispose { active = false } }
    var invitation by remember { mutableStateOf<NodeInvitation?>(null) }
    var manual by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(Rsp.DEFAULT_PORT.toString()) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun scanned(value: String) {
        runCatching { NodeInvitation.parse(value) }.onSuccess { invitation = it; error = null }.onFailure { error = it.message }
    }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color(0xff0e1511)).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 56.dp), verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!manual) {
                Box(Modifier.size(260.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xff0e1511)).border(3.dp, Color(0xff9dbba1), RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
                    when {
                        invitation != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) { PIcon(PIcons.check, tint = Color(0xff9dbba1), size = 48.dp); Text(stringResource(R.string.rn_found, invitation!!.name), color = Color(0xffeef3ec), modifier = Modifier.padding(18.dp)) }
                        permission && active -> NodeQrCamera(onCode = ::scanned, onError = { error = it })
                        else -> NodePill(stringResource(R.string.rn_camera), { request.launch(Manifest.permission.CAMERA) }, Modifier.padding(20.dp), dark = true)
                    }
                }
                Text(stringResource(R.string.rn_aim), color = ink, fontSize = 17.sp)
            }
            error?.let { Callout(it, Tone.Danger) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xff121815)).padding(18.dp)) {
                val invite = invitation
                if (invite != null) {
                    Text(stringResource(R.string.rn_found, invite.name), fontSize = 16.sp, color = ink)
                    Text(shortFingerprint(invite.fingerprint), Modifier.padding(top = 6.dp), fontSize = 12.sp, color = sub)
                    Text(invite.addresses.joinToString(" · ") { it.label() }, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = sub)
                } else if (manual) {
                    OutlinedTextField(pasted, { pasted = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rn_invitation)) }, maxLines = 3, colors = fieldColors)
                    NodePill(stringResource(R.string.rn_read_invitation), { scanned(pasted) }, Modifier.padding(top = 8.dp), secondary = true, dark = true, enabled = pasted.isNotBlank())
                    OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.np_address)) }, singleLine = true, colors = fieldColors)
                    OutlinedTextField(port, { port = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.np_port)) }, singleLine = true, colors = fieldColors)
                    OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.rn_code)) }, singleLine = true, colors = fieldColors)
                }
                Text(stringResource(R.string.rn_confirm_names, "${Build.MANUFACTURER} ${Build.MODEL}"), Modifier.padding(top = 10.dp), color = sub, fontSize = 13.sp)
                if (busy) Callout(stringResource(R.string.rn_awaiting), Tone.Neutral, Modifier.padding(top = 12.dp))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NodePill(stringResource(if (manual || invite != null) R.string.rn_scan_again else R.string.rn_manual), { manual = if (invitation != null) false else !manual; invitation = null; error = null }, Modifier.weight(1f), secondary = true, enabled = !busy, dark = true)
                    NodePill(stringResource(R.string.np_pair), {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val name = "${Build.MANUFACTURER} ${Build.MODEL}"
                                val node = if (invite != null) context.container.nodeConnections.pair(invite, name)
                                else context.container.nodeConnections.pair(host, port.toInt(), code, name)
                                runCatching { context.container.nodeConnections.preflight(node.id) }
                                onPaired(node.id)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message }
                            finally { busy = false }
                        }
                    }, Modifier.weight(1f), enabled = !busy && (invite != null || manual && host.isNotBlank() && port.toIntOrNull() in 1..65535 && Pairing.normalize(code).length == 12), dark = true)
                }
            }
            Text(stringResource(R.string.rn_pair_limits), fontSize = 12.sp, color = sub)
        }
        Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { PIconButton(PIcons.chevronLeft, stringResource(R.string.action_back), onBack, tint = ink) }
    }
}
