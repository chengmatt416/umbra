package app.umbra.agent.ui.nodes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.*

@Composable
fun RemoteScreenPicker() {
    val c = LocalContext.current.container
    val nodes by c.nodes.all.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    RefreshNodes()
    val available = nodes.values.filter { it.revocation == null && it.info?.screen?.available == true }
    val current = available.firstOrNull { it.id == selected }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (current != null) {
            PButton(stringResource(R.string.rn_choose_node), { selected = null }, style = ButtonStyle.Ghost)
            RemoteNodeViewer(current.id)
        } else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (available.isEmpty()) Callout(stringResource(R.string.rn_no_screens), Tone.Neutral)
            available.forEach { n -> PCard(onClick = { selected = n.id }) { Text(n.name, color = P.ink); Subtle(n.info?.screen?.detail.orEmpty()) } }
        }
    }
}
