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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.umbra.agent.AppContainer
import app.umbra.agent.container
import app.umbra.core.model.action.Environment
import app.umbra.core.model.observation.DisplayInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live view of the displays the agent works on, so the user can watch background work (spec §10, §25). */
@OptIn(ExperimentalMaterial3Api::class)
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
    LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Displays") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { item { Banner(it, tone = Semantic.danger) } }
            if (displays.isEmpty()) item { EmptyState("No displays", "Nothing to show yet.") }
            items(displays, key = { it.id }) { d ->
                val lease = container.leases.holder(d.id)
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (d.id == 0) "Phone screen" else d.name ?: "Display ${d.id}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${d.width}×${d.height} · ${if (d.isVirtual) "background" else "built in"}" + (lease?.let { " · in use by a task" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (d.isVirtual && lease == null && d.ownerPackage == "com.android.shell") {
                                OutlinedButton(onClick = { scope.launch { runCatching { container.displays.destroy(d.id) }; tick++ } }) { Text("Remove") }
                            }
                        }
                        DisplayPreview(container, d.id, tick)
                    }
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
        Image(it, contentDescription = "Display $displayId", modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 8.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
    }
}
