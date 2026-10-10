package app.umbra.agent.ui

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.umbra.agent.container
import app.umbra.core.workspace.Workspace
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The projects the agent works in, so the user can see and take away what it produced (spec §22, §25). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspacesScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val container = LocalContext.current.container
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(tick) {
        workspaces = container.workspaces.list()
        sizes = withContext(Dispatchers.IO) { workspaces.associate { it.id to runCatching { kotlinx.coroutines.runBlocking { container.workspaces.usageBytes(it.id) } }.getOrDefault(0L) } }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workspaces") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (workspaces.isEmpty()) {
            EmptyState("No workspaces yet", "Each task gets a project folder. Its files appear here.", Modifier.padding(padding))
        } else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(workspaces, key = { it.id }) { ws ->
                var menu by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().clickable { onOpen(ws.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ws.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${Formatter.formatShortFileSize(context, sizes[ws.id] ?: 0)} · ${ws.sessionIds.size} task${if (ws.sessionIds.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.foundation.layout.Box {
                            TextButton(onClick = { menu = true }) { Text("⋯") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Share as zip") }, onClick = { menu = false; scope.launch { shareZip(context, container.workspaces.resolve(ws.id, ""), ws.name) } })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; scope.launch { runCatching { container.workspaces.delete(ws.id, force = true) }; tick++ } })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Browses one workspace's files and shows text and images. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceBrowserScreen(workspaceId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf<File?>(null) }
    var dir by remember { mutableStateOf<File?>(null) }
    var open by remember { mutableStateOf<File?>(null) }
    var children by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(workspaceId) {
        val path = container.workspaces.resolve(workspaceId, "").toFile()
        root = path
        dir = path
    }
    LaunchedEffect(dir) {
        children = dir?.let { d -> withContext(Dispatchers.IO) { d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).orEmpty() } }.orEmpty()
    }
    val current = open
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (current ?: dir)?.let { f -> root?.let { r -> f.relativeToOrNull(r)?.path?.ifBlank { "/" } } } ?: "",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            open != null -> open = null
                            dir != null && dir != root -> dir = dir?.parentFile
                            else -> onBack()
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (current != null) TextButton(onClick = { shareFile(context, current) }) { Text("Share") }
                },
            )
        },
    ) { padding ->
        if (current != null) {
            FileViewer(current, Modifier.padding(padding))
        } else LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (children.isEmpty()) item { EmptyState("Empty", "Nothing in this folder yet.") }
            items(children, key = { it.path }) { file ->
                Row(
                    Modifier.fillMaxWidth().clickable { if (file.isDirectory) dir = file else open = file }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (file.isDirectory) "📁" else "📄", Modifier.padding(end = 12.dp))
                    Text(file.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!file.isDirectory) Text(Formatter.formatShortFileSize(context, file.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FileViewer(file: File, modifier: Modifier) {
    val isImage = file.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    if (isImage) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { Screenshot(file.path, height = 480) }
        return
    }
    var text by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        text = withContext(Dispatchers.IO) {
            val head = file.inputStream().use { it.readNBytes(MAX_VIEW_BYTES) }
            if (head.any { it == 0.toByte() }) "(binary file, ${file.length()} bytes)" else String(head, Charsets.UTF_8) + if (file.length() > MAX_VIEW_BYTES) "\n… (${file.length() - MAX_VIEW_BYTES} more bytes)" else ""
        }
    }
    SelectionContainer {
        Text(
            text ?: "Loading…",
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

private const val MAX_VIEW_BYTES = 200 * 1024

private fun shareFile(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share ${file.name}",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private suspend fun shareZip(context: android.content.Context, dir: java.nio.file.Path, name: String) {
    val zip = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "share").apply { mkdirs() }.let { File(it, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".zip") }
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            val base = dir.toFile()
            base.walkTopDown().filter { it.isFile && !it.relativeTo(base).path.startsWith("build/") }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.relativeTo(base).path))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        out
    }
    shareFile(context, zip)
}
