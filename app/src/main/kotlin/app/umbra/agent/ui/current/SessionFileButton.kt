package app.umbra.agent.ui.current

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.MainActivity
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.SessionFile
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.ModalBottomSheet
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PIconButton
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PToggle
import app.umbra.core.workspace.GitCheckpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Shared by coding/agent chats and new sessions; a file's reference is inserted after its checkpoint succeeds. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SessionFileButton(
    sessionId: String?,
    onReference: (String) -> Unit,
    onError: (String) -> Unit,
    onPicture: (() -> Unit)? = null,
    compact: Boolean = true,
    ensureSession: suspend () -> String = { requireNotNull(sessionId) { "Start a session first" } },
) {
    val context = LocalContext.current
    val c = context.container
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val files by c.sessionFiles.items.collectAsStateWithLifecycle()
    val workspace by produceState<String?>(null, sessionId, files) { value = sessionId?.let { c.sessionStore.get(it)?.workspaceId } }
    val connected = files.filter { it.sessionId == sessionId || it.workspaceId == workspace }
    var open by remember { mutableStateOf(false) }
    var git by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFolder by rememberSaveable { mutableStateOf(false) }
    var historyFor by remember { mutableStateOf<SessionFile?>(null) }
    var history by remember { mutableStateOf<List<GitCheckpoint>>(emptyList()) }

    fun connect(uri: Uri, folder: Boolean) {
        busy = true
        scope.launch {
            try {
                val id = ensureSession()
                val item = if (folder) c.sessionFiles.mountFolder(id, uri, git) else c.sessionFiles.attach(id, uri, git)
                onReference(item.shortcut)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e.message ?: resources.getString(R.string.sf_failed)) }
            finally { busy = false }
        }
    }

    val access = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = pending
        pending = null
        if (uri != null && Environment.isExternalStorageManager()) connect(Uri.parse(uri), pendingFolder)
        else if (uri != null) onError(resources.getString(R.string.sf_access_denied))
    }
    fun choose(uri: Uri?, folder: Boolean) {
        if (uri == null) return
        try {
            if (c.sessionFiles.localPath(uri, folder) != null && !Environment.isExternalStorageManager()) {
                pending = uri.toString(); pendingFolder = folder
                access.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
            } else connect(uri, folder)
        } catch (e: Exception) { onError(e.message ?: resources.getString(R.string.sf_failed)) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { choose(it, false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { choose(it, true) }

    val title = stringResource(R.string.sf_title)
    if (compact) {
        if (busy) androidx.compose.foundation.layout.Box(Modifier.padding(10.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            app.umbra.agent.ui.porcelain.ThinkingOrb(stringResource(R.string.sf_busy), state = app.umbra.agent.ui.porcelain.ThinkingOrbState.CONNECTING)
        } else PIconButton(PIcons.clip, title, { open = true }, size = 40.dp, tint = P.sub)
    } else PButton(if (busy) stringResource(R.string.sf_busy) else title, { open = true }, enabled = !busy, style = ButtonStyle.Alt, icon = PIcons.clip)

    if (open) ModalBottomSheet(onDismissRequest = { if (!busy) open = false }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = P.base) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = P.ink, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.sf_note), color = P.sub, fontSize = 13.sp)
            PToggle(stringResource(R.string.sf_git), stringResource(R.string.sf_git_note), git, { git = it })
            if (onPicture != null) PButton(stringResource(R.string.sf_photo), { open = false; onPicture() }, Modifier.fillMaxWidth(), style = ButtonStyle.Alt, enabled = !busy, icon = PIcons.clip)
            PButton(stringResource(R.string.sf_attach), { open = false; filePicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !busy, icon = PIcons.file)
            PButton(stringResource(R.string.sf_mount), { open = false; folderPicker.launch(null) }, Modifier.fillMaxWidth(), enabled = !busy, icon = PIcons.folder)
            if (busy) Text(stringResource(R.string.sf_busy), color = P.sub, fontSize = 13.sp)
            connected.forEach { entry ->
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(entry.name, color = P.ink, fontWeight = FontWeight.Medium)
                    Text(stringResource(when { !entry.active -> R.string.sf_disconnected; entry.folder -> R.string.sf_folder; entry.direct -> R.string.sf_original; else -> R.string.sf_copy }), color = P.sub, fontSize = 12.sp)
                    Text(entry.shortcut, color = P.muted, fontSize = 11.sp, fontFamily = P.mono)
                    if (entry.active) PButton(stringResource(R.string.sf_reference), { onReference(entry.shortcut); open = false }, tiny = true, style = ButtonStyle.Ghost)
                    if (entry.git) PButton(stringResource(R.string.sf_history), { historyFor = entry }, tiny = true, style = ButtonStyle.Alt, icon = PIcons.history)
                    if (entry.active) PButton(stringResource(R.string.sf_disconnect), {
                        busy = true
                        scope.launch {
                            try { c.sessionFiles.disconnect(entry) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { onError(e.message ?: resources.getString(R.string.sf_failed)) }
                            finally { busy = false }
                        }
                    }, tiny = true, style = ButtonStyle.Ghost, enabled = !busy)
                }
            }
        }
    }
    LaunchedEffect(historyFor) {
        history = emptyList()
        historyFor?.let { entry ->
            try { history = c.sessionFiles.history(entry) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e.message ?: resources.getString(R.string.sf_failed)); historyFor = null }
        }
    }
    historyFor?.let { entry ->
        ModalBottomSheet(onDismissRequest = { if (!busy) historyFor = null }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = P.base) {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.sf_history), color = P.ink, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.sf_recover_note), color = P.sub, fontSize = 13.sp)
                history.forEach { checkpoint ->
                    PButton(stringResource(R.string.sf_recover, checkpoint.at), {
                        busy = true
                        scope.launch {
                            try {
                                val path = c.sessionFiles.recover(entry, checkpoint.id)
                                onReference(path)
                                historyFor = null; open = false
                                context.startActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAGE, "workspace/${path.substringAfterLast('/')}"))
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { onError(e.message ?: resources.getString(R.string.sf_failed)) }
                            finally { busy = false }
                        }
                    }, Modifier.fillMaxWidth(), enabled = !busy, style = ButtonStyle.Alt, icon = PIcons.history)
                }
            }
        }
    }
}
