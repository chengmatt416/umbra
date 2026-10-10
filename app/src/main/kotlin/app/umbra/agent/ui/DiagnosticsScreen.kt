package app.umbra.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.container
import app.umbra.agent.data.SelfTest
import app.umbra.agent.data.StepStatus
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val test = remember { SelfTest(context.container) }
    val results by test.results.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val running = results.any { it.status == StepStatus.RUNNING }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "Runs each capability for real on this phone. It opens Settings and presses Home, so don't use the phone while it runs.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !running, onClick = { scope.launch { test.run() } }) { Text("Run all") }
                    if (results.isNotEmpty()) OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Umbra diagnostics", test.report()))
                    }) { Text("Copy report") }
                }
            }
            items(results, key = { it.id }) { step ->
                val (mark, color) = when (step.status) {
                    StepStatus.PASSED -> "✓" to Semantic.ok
                    StepStatus.FAILED -> "✕" to Semantic.danger
                    StepStatus.SKIPPED -> "–" to Semantic.idle
                    StepStatus.RUNNING -> "" to Semantic.info
                    StepStatus.PENDING -> "·" to Semantic.idle
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        if (step.status == StepStatus.RUNNING) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
                        else Text(mark, Modifier.padding(end = 12.dp), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Column(Modifier.weight(1f)) {
                            Text(step.title, style = MaterialTheme.typography.titleSmall)
                            if (step.detail.isNotBlank()) Text(step.detail, style = MaterialTheme.typography.bodySmall, color = if (step.status == StepStatus.FAILED) Semantic.danger else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (step.durationMs > 0) Text(formatMs(step.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
