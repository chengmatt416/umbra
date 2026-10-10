package app.umbra.agent.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.umbra.agent.container
import app.umbra.core.safety.ApprovalLedgerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalHistoryScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    var entries by remember { mutableStateOf<List<ApprovalLedgerEntry>>(emptyList()) }
    LaunchedEffect(Unit) { entries = withContext(Dispatchers.IO) { container.ledger.read() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Approval history") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { container.ledger.clear(); entries = emptyList() }) { Text("Clear") } },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState("Nothing asked yet", "When a task needs your approval, the question and your answer are kept here.", Modifier.padding(padding))
        } else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { "${it.requestId}-${it.kind}-${it.timestampMs}" }) { e ->
                val (label, color) = when (e.kind) {
                    "approved" -> "Approved" to Semantic.ok
                    "auto_allowed" -> "Allowed automatically" to Semantic.ok
                    "denied" -> "Denied" to Semantic.danger
                    "auto_denied" -> "Blocked" to Semantic.danger
                    "timed_out" -> "No answer" to Semantic.warn
                    "cancelled" -> "Cancelled" to Semantic.idle
                    else -> "Asked" to Semantic.info
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                            Text(
                                "  ·  ${e.level.name.lowercase()} risk  ·  ${DateUtils.getRelativeTimeSpanString(e.timestampMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(e.summary, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        if (e.categories.isNotEmpty()) Text(e.categories.joinToString { it.title }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
