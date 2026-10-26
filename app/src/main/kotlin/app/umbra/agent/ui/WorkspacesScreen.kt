package app.umbra.agent.ui

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PConfirm
import app.umbra.agent.ui.porcelain.PEmpty
import app.umbra.agent.ui.porcelain.PIconButton
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PObject
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.sessions.sessionTone
import app.umbra.agent.ui.sessions.stateText
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.core.workspace.Workspace
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The projects the agent works in, so the user can see and take away what it produced (spec §22, §25). Deleting one
 * is refused while a task still uses it, and otherwise confirmed with what goes and what stays.
 */
@Composable
fun WorkspacesScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val container = LocalContext.current.container
    var workspaces by remember { mutableStateOf<List<Workspace>?>(null) }
    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var tick by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<Workspace?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(tick) {
        val list = container.workspaces.list()
        workspaces = list
        sizes = withContext(Dispatchers.IO) { list.associate { it.id to runCatching { kotlinx.coroutines.runBlocking { container.workspaces.usageBytes(it.id) } }.getOrDefault(0L) } }
    }
    PorcelainPage(stringResource(R.string.dest_workspaces), onBack) {
        val list = workspaces
        when {
            list == null -> Unit
            list.isEmpty() -> PEmpty(stringResource(R.string.ws_empty_title), stringResource(R.string.ws_empty_body), icon = PIcons.folder)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 6.dp, 18.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Eyebrow(stringResource(R.string.ws_eyebrow))
                    message?.let { Box(Modifier.padding(top = 10.dp)) { Callout(it, Tone.Neutral) } }
                }
                items(list, key = { it.id }) { ws ->
                    var menu by remember { mutableStateOf(false) }
                    val tasks = pluralStringResource(R.plurals.ws_tasks, ws.sessionIds.size, ws.sessionIds.size)
                    PObject(
                        ws.name,
                        stringResource(R.string.ws_summary, Formatter.formatShortFileSize(context, sizes[ws.id] ?: 0), tasks),
                        PIcons.folder,
                        onClick = { onOpen(ws.id) },
                        trailing = {
                            Box {
                                PIconButton(PIcons.more, stringResource(R.string.ws_actions), { menu = true }, size = 34.dp)
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.ws_share_zip)) }, onClick = { menu = false; scope.launch { shareZip(context, container.workspaces.resolve(ws.id, ""), ws.name) } })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = P.danger) }, onClick = { menu = false; deleting = ws })
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    deleting?.let { ws ->
        val linked = sessions.filter { it.id in ws.sessionIds || it.workspaceId == ws.id }
        val busy = linked.filter { !it.state.isTerminal }
        val size = Formatter.formatShortFileSize(context, sizes[ws.id] ?: 0)
        val deletedText = stringResource(R.string.ws_deleted, ws.name)
        PConfirm(
            title = stringResource(if (busy.isEmpty()) R.string.ws_delete_title else R.string.ws_delete_blocked_title, ws.name),
            body = if (busy.isEmpty()) stringResource(R.string.ws_delete_body, size) else stringResource(R.string.ws_delete_blocked_body),
            confirmLabel = stringResource(R.string.action_delete),
            confirmEnabled = busy.isEmpty(),
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                scope.launch {
                    message = try {
                        container.supervisor.deleteWorkspace(ws.id)
                        deletedText
                    } catch (e: Exception) {
                        resources.getString(R.string.ws_delete_failed, e.message ?: e::class.simpleName)
                    }
                    tick++
                }
            },
            extra = if (linked.isEmpty()) null else {
                {
                    Kicker(stringResource(R.string.ws_linked_tasks))
                    (if (busy.isEmpty()) linked else busy).take(6).forEach { s ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(s.title, Modifier.weight(1f), fontSize = 13.sp, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Tag(stateText(s), sessionTone(s))
                        }
                    }
                }
            },
        )
    }
}

/** Browses one workspace's files and shows text and images. */
@Composable
fun WorkspaceBrowserScreen(workspaceId: String, onBack: () -> Unit, onOpenFile: (String, String?) -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val container = context.container
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
    val title = (current ?: dir)?.let { f -> root?.let { r -> f.relativeToOrNull(r)?.path?.ifBlank { "/" } } } ?: ""
    PorcelainPage(
        title,
        onBack = {
            when {
                open != null -> open = null
                dir != null && dir != root -> dir = dir?.parentFile
                else -> onBack()
            }
        },
        actions = { if (current != null) PButton(stringResource(R.string.ws_share), { shareFile(context, current) }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.send) },
    ) {
        if (current != null) {
            FileViewer(current, Modifier)
        } else if (children.isEmpty()) {
            PEmpty(title.ifBlank { "/" }, stringResource(R.string.ws_folder_empty), icon = PIcons.folder)
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 6.dp, 18.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(children, key = { it.path }) { file ->
                PObject(
                    file.name,
                    if (file.isDirectory) null else Formatter.formatShortFileSize(context, file.length()),
                    if (file.isDirectory) PIcons.folder else PIcons.file,
                    onClick = { if (file.isDirectory) dir = file else onOpenFile(file.path, null) },
                )
            }
        }
    }
}

@Composable
private fun FileViewer(file: File, modifier: Modifier) {
    val isImage = file.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    if (isImage) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) { Screenshot(file.path, height = 480) }
        return
    }
    var text by remember(file) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    LaunchedEffect(file) {
        text = withContext(Dispatchers.IO) {
            val head = file.inputStream().use { it.readNBytes(MAX_VIEW_BYTES) }
            if (head.any { it == 0.toByte() }) resources.getString(R.string.ws_binary, Formatter.formatShortFileSize(context, file.length()))
            else String(head, Charsets.UTF_8) + if (file.length() > MAX_VIEW_BYTES) "\n" + resources.getString(R.string.ws_more_bytes, Formatter.formatShortFileSize(context, file.length() - MAX_VIEW_BYTES)) else ""
        }
    }
    SelectionContainer {
        Text(
            text ?: stringResource(R.string.ws_loading),
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = P.ink,
        )
    }
}

private const val MAX_VIEW_BYTES = 200 * 1024

private fun shareFile(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            context.getString(R.string.ws_share_file, file.name),
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
