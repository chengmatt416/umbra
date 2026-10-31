package app.umbra.agent.ui.files

import android.content.Intent
import android.graphics.Bitmap
import android.webkit.MimeTypeMap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.MarkdownText
import app.umbra.agent.ui.porcelain.*
import app.umbra.agent.ui.shell.PorcelainPage
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

internal data class ViewerFile(
    val access: FileAccess,
    val kind: FileKind,
    val digest: ByteArray,
    val text: String = "",
    val docx: DocxDocument? = null,
    val docxHtml: String = "",
    val xlsx: XlsxDocument? = null,
    val rows: List<List<String>> = emptyList(),
    val csvSheet: FileSheet? = null,
    val pptx: PptxDocument? = null,
    val bitmap: Bitmap? = null,
    val json: JsonElement? = null,
)

private suspend fun loadFile(container: AppContainer, path: String, session: String?): ViewerFile {
    val access = FileAccess.open(container, path, session)
    val file = access.file
    val kind = fileKind(file.extension, MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()))
    if (kind == FileKind.Video) return ViewerFile(access, kind, byteArrayOf())
    if (kind == FileKind.Image) return ViewerFile(access, kind, byteArrayOf(), bitmap = loadBitmap(file, false))
    val bytes = access.read()
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return when (kind) {
        FileKind.Docx -> DocxDocument(OfficeArchive(bytes)).let { ViewerFile(access, kind, digest, docx = it, docxHtml = it.html()) }
        FileKind.Xlsx -> ViewerFile(access, kind, digest, xlsx = XlsxDocument(OfficeArchive(bytes)))
        FileKind.Pptx -> ViewerFile(access, kind, digest, pptx = PptxDocument(OfficeArchive(bytes)))
        FileKind.Doc -> ViewerFile(access, kind, digest, text = LegacyDoc.text(bytes).orEmpty())
        FileKind.Xls -> ViewerFile(access, kind, digest)
        FileKind.Svg -> ViewerFile(access, kind, digest, text = utf8Text(bytes) ?: error(container.context.getString(R.string.files_unsupported)), bitmap = loadBitmap(file, true))
        else -> {
            val text = utf8Text(bytes) ?: return ViewerFile(access, FileKind.Unsupported, digest)
            val rows = if (kind == FileKind.Csv || kind == FileKind.Tsv) Csv.parse(text, if (kind == FileKind.Tsv) '\t' else ',') else emptyList()
            val csvSheet = if (kind == FileKind.Csv || kind == FileKind.Tsv) FileSheet("Sheet1", "", buildMap {
                rows.forEachIndexed { r, row -> row.forEachIndexed { c, value -> put(r to c, SheetCell(value)) } }
            }, rows.size.coerceAtLeast(1), (rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)) else null
            ViewerFile(access, kind, digest, text,
                rows = rows, csvSheet = csvSheet,
                json = if (kind == FileKind.Json) runCatching { JsonTree.codec.parseToJsonElement(text).also { JsonTree.flatten(it) } }.getOrNull() else null)
        }
    }
}

@Composable
fun FileViewerScreen(path: String, sessionId: String? = null, onBack: () -> Unit, onAsk: suspend (file: String, selection: AskSelection, instruction: String) -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val container = context.container
    val scope = rememberCoroutineScope()
    var loaded by remember(path, sessionId) { mutableStateOf<ViewerFile?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var loading by remember(path) { mutableStateOf(true) }
    var revision by remember(path) { mutableIntStateOf(0) }
    var editing by remember(path) { mutableStateOf(false) }
    var source by remember(path) { mutableStateOf(false) }
    var javaScript by remember(path) { mutableStateOf(false) }
    var busy by remember(path) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var selection by remember(path) { mutableStateOf<AskSelection?>(null) }
    var draft by remember(path) { mutableStateOf(TextFieldValue()) }
    var discard by remember { mutableStateOf(false) }
    var leaveAfterDiscard by remember { mutableStateOf(false) }

    fun fail(e: Throwable) {
        error = when (e) {
            is FileTooLarge -> resources.getString(R.string.files_too_large)
            is FileNotAllowed -> resources.getString(R.string.files_not_allowed)
            is FileChanged -> resources.getString(R.string.files_changed)
            else -> resources.getString(R.string.files_failed, e.message ?: e.javaClass.simpleName)
        }
    }
    LaunchedEffect(path, sessionId, revision) {
        loading = true
        try {
            val next = withContext(Dispatchers.IO) { loadFile(container, path, sessionId) }
            loaded = next; draft = TextFieldValue(next.text)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { fail(e) }
        finally { loading = false }
    }
    val data = loaded
    val textEditable = data?.kind in setOf(FileKind.Text, FileKind.Markdown, FileKind.Json, FileKind.Html, FileKind.Svg)
    val editable = data?.access?.writable == true && (textEditable || data.kind in setOf(FileKind.Docx, FileKind.Pptx, FileKind.Csv, FileKind.Tsv, FileKind.Xlsx))
    val leave = {
        if (editing && textEditable && draft.text != data?.text) { leaveAfterDiscard = true; discard = true }
        else if (!busy) onBack()
    }
    fun save(makeBytes: () -> ByteArray) {
        val current = data ?: return
        if (busy || loading) return
        busy = true; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { current.access.save(current.digest, makeBytes()) }
                editing = false; revision++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
            finally { busy = false }
        }
    }

    PorcelainPage(java.io.File(path).name, leave, actions = {
        Box {
            PIconButton(PIcons.more, stringResource(R.string.files_actions), { menu = true })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.files_open_with)) }, onClick = {
                    menu = false
                    scope.launch {
                        try {
                            val access = data?.access ?: withContext(Dispatchers.IO) { FileAccess.open(container, path, sessionId) }
                            withContext(Dispatchers.IO) { access.check() }
                            val file = access.file
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), resources.getString(R.string.files_open_with)))
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { fail(e) }
                    }
                })
            }
        }
    }) {
        Column(Modifier.fillMaxSize()) {
            error?.let { Callout(it, Tone.Danger, Modifier.padding(12.dp)) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (data != null) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editable) PButton(stringResource(if (editing) R.string.files_done else R.string.files_edit), {
                        if (editing && textEditable && draft.text != data.text) { leaveAfterDiscard = false; discard = true }
                        else { editing = !editing; if (editing && textEditable) source = true }
                    }, style = ButtonStyle.Ghost, tiny = true, enabled = !busy && !loading)
                    if (editing && textEditable) PButton(stringResource(R.string.files_save), {
                        val text = draft.text
                        save {
                            if (data.kind == FileKind.Json) JsonTree.codec.parseToJsonElement(text)
                            text.toByteArray(Charsets.UTF_8)
                        }
                    }, tiny = true, enabled = !busy && !loading)
                    if (data.kind in setOf(FileKind.Markdown, FileKind.Json, FileKind.Html, FileKind.Svg)) PButton(stringResource(if (source) R.string.files_preview else R.string.files_source), { source = !source }, tiny = true, style = ButtonStyle.Ghost, enabled = !editing)
                    if (data.kind == FileKind.Html && !editing) PublishChips(data.access.file)
                    if (data.kind == FileKind.Html && !source) PButton(stringResource(if (javaScript) R.string.files_js_disable else R.string.files_js_allow), { javaScript = !javaScript }, tiny = true, style = ButtonStyle.Ghost)
                    if (data.kind !in setOf(FileKind.Image, FileKind.Video, FileKind.Xlsx, FileKind.Csv, FileKind.Tsv, FileKind.Pptx, FileKind.Unsupported, FileKind.Xls) && (data.kind != FileKind.Doc || data.text.isNotEmpty())) {
                        PButton(stringResource(R.string.files_ask), {
                            val range = draft.selection
                            val text = if (!range.collapsed) draft.text.substring(range.min, range.max) else if (data.kind == FileKind.Docx) data.docx?.paragraphs?.joinToString("\n").orEmpty() else draft.text
                            selection = AskSelection(text, resources.getString(R.string.files_selection))
                        }, tiny = true, style = ButtonStyle.Ghost)
                    }
                }
                if (data.kind == FileKind.Html) PublishStatus(data.access.file)
                SelectionAsk(onAsk = { selection = AskSelection(it, resources.getString(R.string.files_selection)) }) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            data.kind == FileKind.Unsupported -> ViewerNotice(R.string.files_unsupported)
                            data.kind == FileKind.Xls -> ViewerNotice(R.string.files_xls_unsupported)
                            data.kind == FileKind.Doc && data.text.isEmpty() -> ViewerNotice(R.string.files_doc_unsupported)
                            data.kind == FileKind.Video -> VideoViewer(data.access.file, { selection = it }, ::fail)
                            data.kind == FileKind.Image || (data.kind == FileKind.Svg && !source) -> data.bitmap?.let { ImageViewer(it, { selection = it }, ::fail) }
                            data.kind == FileKind.Docx -> if (editing) ParagraphEditor(data.docx!!.paragraphs, busy, ::save) { index, value -> data.docx.editParagraph(index, value) }
                                else LocalHtml(data.docxHtml, false, Modifier.fillMaxSize()) { selection = AskSelection(it, resources.getString(R.string.files_selection)) }
                            data.kind == FileKind.Pptx -> SlidesViewer(data.pptx!!, editing, busy, ::save) { selection = it }
                            data.kind in setOf(FileKind.Xlsx, FileKind.Csv, FileKind.Tsv) -> SheetsViewer(data, editing, busy, ::save) { selection = it }
                            data.kind == FileKind.Html && !source -> LocalHtml(data.text, javaScript, Modifier.fillMaxSize(), base = data.access.file.parentFile) { selection = AskSelection(it, resources.getString(R.string.files_selection)) }
                            data.kind == FileKind.Markdown && !source -> MarkdownText(data.text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), color = P.ink)
                            data.kind == FileKind.Json && !source && data.json != null -> JsonViewer(data.json)
                            else -> SourceText(draft, { draft = it }, editing, data.kind == FileKind.Json, busy)
                        }
                    }
                }
            }
        }
    }
    if (discard) PConfirm(title = stringResource(R.string.files_discard_title), body = stringResource(R.string.files_discard_body), confirmLabel = stringResource(R.string.files_discard), onDismiss = { discard = false }, onConfirm = {
        discard = false; editing = false; draft = TextFieldValue(data?.text.orEmpty())
        if (leaveAfterDiscard) onBack()
    })
    selection?.let { picked -> AskSheet(picked, onDismiss = { selection = null }) { instruction -> onAsk(path, picked, instruction) } }
}

@Composable
private fun ViewerNotice(id: Int) { Text(stringResource(id), Modifier.padding(20.dp), color = P.sub) }

@Composable
private fun SourceText(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, editing: Boolean, json: Boolean, busy: Boolean) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (json) Text(stringResource(R.string.files_json_hint), Modifier.padding(bottom = 8.dp), color = P.sub)
        if (editing) OutlinedTextField(value, onChange, Modifier.fillMaxSize(), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = P.mono, color = P.ink), readOnly = busy)
        else SelectionContainer { Text(value.text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()), fontFamily = P.mono, color = P.ink) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskSheet(selection: AskSelection, onDismiss: () -> Unit, onSend: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var instruction by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.files_send_failed)
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = P.base) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.files_ask), color = P.ink)
            Text(selection.where, color = P.sub)
            if (selection.image != null) {
                val preview by produceState<Bitmap?>(null, selection) { value = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeByteArray(selection.image, 0, selection.image.size) } }
                preview?.let { androidx.compose.foundation.Image(it.asImageBitmap(), stringResource(R.string.files_selected_image), Modifier.fillMaxWidth().heightIn(max = 200.dp)) }
            }
            if (selection.quote.isNotEmpty()) SelectionContainer { Text(selection.quote.take(4000), Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), color = P.sub) }
            PField(instruction, { instruction = it }, label = stringResource(R.string.files_instruction), minLines = 3, enabled = !busy)
            error?.let { Callout(it, Tone.Danger) }
            PButton(stringResource(R.string.files_send), {
                busy = true
                scope.launch {
                    try { onSend(instruction); onDismiss() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = "$failed: ${e.message.orEmpty()}" }
                    finally { busy = false }
                }
            }, enabled = !busy && instruction.isNotBlank())
        }
    }
}

