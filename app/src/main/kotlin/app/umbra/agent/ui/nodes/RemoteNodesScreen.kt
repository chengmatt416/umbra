package app.umbra.agent.ui.nodes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.PollWhileStarted
import app.umbra.agent.ui.porcelain.*
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.core.control.NodeTrust
import app.umbra.core.control.PairedNode
import app.umbra.core.nodes.JobState
import app.umbra.core.nodes.shortFingerprint
import kotlinx.coroutines.launch

internal const val NODE_INSTALL = "curl -fsSL https://umbra-agent.pages.dev/node | sh"

@Composable
fun NodesScreen(onBack: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var viewing by rememberSaveable { mutableStateOf(false) }
    var takeControl by rememberSaveable { mutableStateOf(false) }
    fun back() { when { viewing -> viewing = false; adding -> adding = false; advanced -> advanced = false; selected != null -> selected = null; else -> onBack() } }
    BackHandler(selected != null || adding || advanced || viewing) { back() }
    val c = LocalContext.current.container
    val nodes by c.nodes.all.collectAsStateWithLifecycle()
    val node = selected?.let(nodes::get)
    when {
        adding -> NodePairingScreen(onBack = { adding = false }, onPaired = { selected = it; adding = false })
        viewing && node != null -> PorcelainPage(node.name, { viewing = false }) { RemoteNodeViewer(node.id, initiallyControlling = takeControl) }
        else -> PorcelainPage(node?.name ?: stringResource(R.string.rn_title), ::back) {
            if (advanced) NodeAdvancedControls()
            else if (node != null) NodeDetail(node, onScreen = { takeControl = it; viewing = true }, onAdvanced = { advanced = true }, onRemoved = { selected = null })
            else NodeList(onOpen = { selected = it }, onAdd = { adding = true }, onAdvanced = { advanced = true })
        }
    }
}

@Composable
internal fun RefreshNodes() {
    val c = LocalContext.current.container
    val nodes by c.nodes.all.collectAsStateWithLifecycle()
    PollWhileStarted(nodes.keys, periodMs = 15_000) {
        kotlinx.coroutines.coroutineScope {
            nodes.values.filter { it.revocation == null }.map { n -> launch { runCatching { c.nodeConnections.preflight(n.id); c.nodeConnections.reconcile(n.id) } } }.forEach { it.join() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeList(onOpen: (String) -> Unit, onAdd: () -> Unit, onAdvanced: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val nodes by c.nodes.all.collectAsStateWithLifecycle()
    val states by c.nodeConnections.states.collectAsStateWithLifecycle()
    val jobs by c.nodes.allJobs.collectAsStateWithLifecycle()
    val lanLabel = stringResource(R.string.rn_lan)
    RefreshNodes()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (nodes.isEmpty()) PCard(padding = PaddingValues(18.dp)) {
            Text(stringResource(R.string.rn_empty), fontSize = 17.sp, color = P.ink)
            Subtle(stringResource(R.string.rn_empty_body), Modifier.padding(top = 6.dp))
        }
        nodes.values.sortedBy { it.pairedAtMs }.forEach { n ->
            val state = states[n.id]
            PCard(padding = PaddingValues(14.dp), onClick = { onOpen(n.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NodeDesignIcon(if (n.info?.screen?.available == true) "42-4091-imgIconCpu" else "42-4091-imgIconServer")
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(n.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.ink)
                        Subtle(listOfNotNull(n.info?.os, n.info?.arch, state?.latencyMs?.let { "$it ms" }).joinToString(" · ").ifBlank { "${n.host}:${n.port}" })
                        Text(when { n.revocation != null -> stringResource(R.string.nodes_revocation_pending).takeIf { n.revocation?.pending == true } ?: stringResource(R.string.nodes_revoked_ack); state?.online == true -> stringResource(R.string.rn_online); else -> stringResource(R.string.rn_offline) + (n.lastSeenMs?.let { " · ${DateUtils.getRelativeTimeSpanString(it)}" } ?: "") }, fontSize = 12.sp, color = if (state?.online == true) P.good else P.sub)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Tag(stringResource(if (n.info?.screen?.available == true) R.string.rn_vnc else R.string.rn_jobs_only), if (n.info?.screen?.available == true) Tone.Good else Tone.Neutral)
                            Tag(n.addresses.firstOrNull()?.kind?.let { kind -> when (kind) { "tailscale" -> "Tailscale"; "vpn" -> "VPN"; else -> lanLabel } } ?: lanLabel, Tone.Neutral)
                            val count = jobs.values.count { it.nodeId == n.id && it.state == JobState.RUNNING }
                            if (count > 0) Tag(stringResource(R.string.rn_jobs_count, count), Tone.Neutral)
                        }
                    }
                    NodeDesignIcon("42-4091-imgIconRight")
                }
            }
        }
        NodePill(stringResource(R.string.rn_add), onAdd, asset = "42-4091-imgIconQr")
        Text(stringResource(R.string.rn_install_hint), fontSize = 12.sp, color = P.sub)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(P.night).clickable { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Umbra Node", NODE_INSTALL)) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(NODE_INSTALL, Modifier.weight(1f), color = P.codeText, fontFamily = P.mono, fontSize = 12.sp)
            NodeDesignIcon("42-4091-imgIconCopy")
        }
        PButton(stringResource(R.string.rn_setup_guide), { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://umbra-agent.pages.dev/#remote-node"))) }, style = ButtonStyle.Ghost)
        Disclosure(stringResource(R.string.rn_advanced)) { PButton(stringResource(R.string.rn_jobs_policy), onAdvanced, style = ButtonStyle.Alt) }
        Gap(24.dp)
    }
}

@Composable
private fun NodeDetail(n: PairedNode, onScreen: (Boolean) -> Unit, onAdvanced: () -> Unit, onRemoved: () -> Unit) {
    val c = LocalContext.current.container
    val state by c.nodeConnections.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var revoke by remember { mutableStateOf(false) }
    RefreshNodes()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        error?.let { Callout(it, Tone.Danger) }
        PCard(padding = PaddingValues(12.dp)) {
            NodeScreenPreview(n.id)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NodePill(stringResource(R.string.rn_view), { onScreen(false) }, Modifier.weight(1f), secondary = true, enabled = n.revocation == null && n.info?.screen?.available == true)
                NodePill(stringResource(R.string.rn_takeover), { onScreen(true) }, Modifier.weight(1f), enabled = n.revocation == null && n.info?.screen?.available == true)
            }
        }
        Kicker(stringResource(R.string.rn_connection))
        PCard(padding = PaddingValues(14.dp)) {
            NodeRow("42-4275-imgIconWifi", "${n.host}:${n.port}", (n.addresses + n.info?.addresses.orEmpty()).distinct().joinToString(" · ") { "${it.kind}: ${it.label()}" }, state[n.id]?.latencyMs?.let { "$it ms" })
            Gap(18.dp)
            NodeRow("42-4275-imgIconMonitor", stringResource(R.string.rn_vnc), n.info?.screen?.detail ?: stringResource(R.string.rn_jobs_only), stringResource(if (n.info?.screen?.available == true) R.string.rn_enabled else R.string.rn_disabled))
            Gap(14.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NodeDesignIcon("42-4275-imgIconEye")
                Column(Modifier.weight(1f)) { Text(stringResource(R.string.rn_agent_screen), fontSize = 15.sp, color = P.ink); Subtle(stringResource(R.string.rn_agent_screen_body)) }
                androidx.compose.material3.Switch(n.allowAgentScreen, { c.nodes.allowAgentScreen(n.id, it) })
            }
        }
        Kicker(stringResource(R.string.rn_trust))
        PCard(padding = PaddingValues(14.dp)) {
            NodeRow("42-4275-imgIconShield", stringResource(R.string.rn_trust), stringResource(R.string.rn_trust_body))
            Gap(10.dp)
            Segmented(listOf(NodeTrust.LIMITED to stringResource(R.string.rn_limited), NodeTrust.OWNED to stringResource(R.string.rn_owned)), n.trust, { c.nodes.setTrust(n.id, it) }, fill = true)
            Subtle(shortFingerprint(n.fingerprint), Modifier.padding(top = 8.dp))
        }
        NodePill(stringResource(R.string.rn_jobs_policy), onAdvanced, secondary = true)
        if (n.revocation == null) NodePill(stringResource(R.string.nodes_revoke), { revoke = true }, secondary = true)
        else { Callout(stringResource(R.string.np_revoke_body), Tone.Warn); PButton(stringResource(R.string.nodes_unpair), { c.nodes.unpair(n.id); onRemoved() }, style = ButtonStyle.Ghost) }
        Gap(24.dp)
    }
    if (revoke) PConfirm(stringResource(R.string.nodes_revoke), stringResource(R.string.np_revoke_body), stringResource(R.string.np_revoke), onDismiss = { revoke = false }, onConfirm = { revoke = false; scope.launch { runCatching { c.nodeConnections.revoke(n.id, "Revoked by user") }.onFailure { error = it.message } } })
}

@Composable
private fun NodeRow(asset: String, title: String, subtitle: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NodeDesignIcon(asset)
        Column(Modifier.weight(1f)) { Text(title, fontSize = 15.sp, color = P.ink); if (subtitle.isNotBlank()) Subtle(subtitle) }
        trailing?.let { Text(it, fontSize = 12.sp, color = P.sub) }
    }
}

@Composable
internal fun NodePill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, secondary: Boolean = false, enabled: Boolean = true, asset: String? = null, dark: Boolean = false) {
    val background = if (dark) androidx.compose.ui.graphics.Color(if (secondary || !enabled) 0xff2a342e else 0xff9dbba1) else if (secondary || !enabled) P.base2 else P.accent2
    val foreground = if (dark) androidx.compose.ui.graphics.Color(if (secondary || !enabled) 0xffeef3ec else 0xff26372f) else if (secondary || !enabled) P.ink else androidx.compose.ui.graphics.Color.White
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(background).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        asset?.let { NodeDesignIcon(it); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 15.sp, color = foreground, fontWeight = FontWeight.Medium)
    }
}
