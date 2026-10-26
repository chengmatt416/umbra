package app.umbra.agent.ui.files

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.umbra.agent.R
import app.umbra.agent.ui.porcelain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlin.math.floor
import kotlin.math.ceil

@Composable
internal fun JsonViewer(root: JsonElement) {
    var collapsed by remember(root) { mutableStateOf(emptySet<String>()) }
    val rows by produceState(emptyList<JsonRow>(), root, collapsed) { value = withContext(Dispatchers.Default) { JsonTree.flatten(root, collapsed) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(rows, key = { it.path }) { row ->
            Row(Modifier.fillMaxWidth().padding(start = (row.depth.coerceAtMost(12) * 12).dp).then(if (row.branch) Modifier.clickable { collapsed = if (row.path in collapsed) collapsed - row.path else collapsed + row.path } else Modifier).padding(vertical = 6.dp)) {
                if (row.branch) Text(if (row.path in collapsed) "▸ " else "▾ ", color = P.sub)
                SelectionContainer { Text("${row.label}: ${row.value}", color = P.ink, fontFamily = P.mono) }
            }
        }
    }
}

@Composable
internal fun ParagraphEditor(paragraphs: List<String>, busy: Boolean, onSave: (() -> ByteArray) -> Unit, edit: (Int, String) -> ByteArray) {
    var selected by remember(paragraphs) { mutableStateOf<Int?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(paragraphs.size) { index ->
            Column(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = index }.border(1.dp, P.sub.copy(alpha = 0.2f)).padding(12.dp)) {
                Text(stringResource(R.string.files_paragraph, index + 1), color = P.sub)
                SelectionContainer { Text(paragraphs[index].ifEmpty { " " }, color = P.ink) }
            }
        }
    }
    selected?.let { index -> EditValueDialog(stringResource(R.string.files_paragraph, index + 1), paragraphs[index], busy, { selected = null }) { value ->
        onSave { edit(index, value) }; selected = null
    } }
}

@Composable
private fun EditValueDialog(title: String, initial: String, busy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = P.base, title = { Text(title, color = P.ink) }, text = {
        PField(text, { text = it }, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), minLines = 3)
    }, confirmButton = { PButton(stringResource(R.string.files_save), { onSave(text) }, enabled = !busy) }, dismissButton = {
        PButton(stringResource(R.string.files_cancel), onDismiss, style = ButtonStyle.Ghost)
    })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SheetsViewer(data: ViewerFile, editing: Boolean, busy: Boolean, onSave: (() -> ByteArray) -> Unit, onAsk: (AskSelection) -> Unit) {
    val sheets = data.xlsx?.sheets ?: listOfNotNull(data.csvSheet)
    var tab by remember(data) { mutableIntStateOf(0) }
    if (sheets.isEmpty()) { Text(stringResource(R.string.files_empty_sheet), Modifier.padding(16.dp), color = P.sub); return }
    val sheet = sheets[tab]
    var start by remember(sheet) { mutableStateOf(0 to 0) }
    var end by remember(sheet) { mutableStateOf(0 to 0) }
    var editingCell by remember(sheet) { mutableStateOf<Pair<Int, Int>?>(null) }
    val firstRow = minOf(start.first, end.first)
    val lastRow = maxOf(start.first, end.first)
    val firstColumn = minOf(start.second, end.second)
    val lastColumn = maxOf(start.second, end.second)
    val address = "${sheet.name}!${cellAddress(firstRow, firstColumn)}:${cellAddress(lastRow, lastColumn)}"
    val cell = sheet.cells[start]
    val scope = rememberCoroutineScope()
    var quoting by remember(sheet) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sheets.forEachIndexed { index, item -> PButton(item.name, { tab = index }, tiny = true, style = if (tab == index) ButtonStyle.Alt else ButtonStyle.Ghost) }
        }
        Text(address, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = P.sub)
        if (cell?.formula != null) SelectionContainer { Text(stringResource(R.string.files_formula, "=${cell.formula}"), Modifier.padding(horizontal = 16.dp), color = P.sub, fontFamily = P.mono) }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PButton(stringResource(R.string.files_ask), {
                quoting = true
                scope.launch {
                    try {
                        val quote = withContext(Dispatchers.Default) {
                            buildString {
                                outer@ for (r in firstRow..lastRow) {
                                    if (r > firstRow) append("\r\n")
                                    for (c in firstColumn..lastColumn) {
                                        if (c > firstColumn) append(',')
                                        append(Csv.write(listOf(listOf(sheet.value(r, c).take(4000)))))
                                        if (length >= 4000) { setLength(4000); break@outer }
                                    }
                                }
                            }
                        }
                        onAsk(AskSelection(quote, address))
                    } finally { quoting = false }
                }
            }, tiny = true, enabled = !quoting)
            if (editing) PButton(stringResource(R.string.files_edit_cell), { editingCell = start }, tiny = true, style = ButtonStyle.Ghost, enabled = !busy && cell?.formula == null)
        }
        Text(stringResource(R.string.files_range_hint), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = P.sub)
        val density = LocalDensity.current
        val columnWidth = with(density) { 144.dp.toPx() }
        var viewport by remember(sheet) { mutableFloatStateOf(0f) }
        var scroll by remember(sheet) { mutableFloatStateOf(0f) }
        val horizontal = rememberScrollableState { delta ->
            val before = scroll
            scroll = (scroll - delta).coerceIn(0f, (sheet.columnCount * columnWidth - viewport).coerceAtLeast(0f))
            before - scroll
        }
        val firstVisible = (scroll / columnWidth).toInt()
        val visibleCount = ceil(viewport / columnWidth).toInt() + 1
        val visibleColumns = firstVisible until minOf(sheet.columnCount, firstVisible + visibleCount)
        val offset = with(density) { (-(scroll % columnWidth)).toDp() }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewport = (it.width - with(density) { 48.dp.toPx() }).coerceAtLeast(0f) }.scrollable(horizontal, Orientation.Horizontal)) {
            stickyHeader {
                GridRow(sheet, 0, visibleColumns, offset, firstRow..lastRow, firstColumn..lastColumn, { r, c -> start = r to c; end = start }, { r, c -> end = r to c })
            }
            items((sheet.rowCount - 1).coerceAtLeast(0)) { index ->
                GridRow(sheet, index + 1, visibleColumns, offset, firstRow..lastRow, firstColumn..lastColumn, { r, c -> start = r to c; end = start }, { r, c -> end = r to c })
            }
        }
    }
    editingCell?.let { (row, column) -> EditValueDialog(cellAddress(row, column), sheet.value(row, column), busy, { editingCell = null }) { value ->
        val sheetIndex = tab
        onSave {
            if (data.xlsx != null) data.xlsx.edit(sheetIndex, row, column, value) else {
                val edited = data.rows.map { it.toMutableList() }.toMutableList()
                while (edited.size <= row) edited.add(mutableListOf())
                while (edited[row].size <= column) edited[row] += ""
                edited[row][column] = value
                Csv.write(edited, if (data.kind == FileKind.Tsv) '\t' else ',').toByteArray(Charsets.UTF_8)
            }
        }
        editingCell = null
    } }
}

@Composable
private fun GridRow(sheet: FileSheet, row: Int, columns: IntRange, offset: androidx.compose.ui.unit.Dp, selectedRows: IntRange, selectedColumns: IntRange, onStart: (Int, Int) -> Unit, onEnd: (Int, Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(P.base)) {
        Text((row + 1).toString(), Modifier.width(48.dp).height(48.dp).padding(8.dp), color = P.sub, fontFamily = P.mono)
        Box(Modifier.weight(1f).height(48.dp).clipToBounds()) {
            Row(Modifier.offset(x = offset).width((columns.count() * 144).dp)) {
                for (column in columns) {
                    val selected = row in selectedRows && column in selectedColumns
                    Box(Modifier.width(144.dp).height(48.dp).background(if (selected) P.accent.copy(alpha = 0.15f) else P.base)
                        .border(0.5.dp, P.sub.copy(alpha = 0.25f))
                        .clickable { onStart(row, column) }
                        .pointerInput(sheet, row, column) {
                            val width = 144.dp.toPx()
                            val height = 48.dp.toPx()
                            detectDragGesturesAfterLongPress(onDragStart = { onStart(row, column) }) { change, _ ->
                                change.consume()
                                onEnd((row + floor(change.position.y / height).toInt()).coerceIn(0, sheet.rowCount - 1), (column + floor(change.position.x / width).toInt()).coerceIn(0, sheet.columnCount - 1))
                            }
                        }.padding(8.dp)) {
                        Text(sheet.value(row, column), color = P.ink, maxLines = 1, fontFamily = P.mono, fontWeight = if (row == 0) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SlidesViewer(document: PptxDocument, editing: Boolean, busy: Boolean, onSave: (() -> ByteArray) -> Unit, onAsk: (AskSelection) -> Unit) {
    var slide by remember(document) { mutableIntStateOf(0) }
    var shape by remember(document) { mutableStateOf<Int?>(null) }
    val slides = document.slides
    if (slides.isEmpty()) { Text(stringResource(R.string.files_no_slides), Modifier.padding(16.dp), color = P.sub); return }
    val current = slides[slide]
    val resources = androidx.compose.ui.platform.LocalResources.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            slides.indices.forEach { i -> PButton(stringResource(R.string.files_slide, i + 1), { slide = i }, tiny = true, style = if (slide == i) ButtonStyle.Alt else ButtonStyle.Ghost) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                PButton(stringResource(R.string.files_ask_slide), { onAsk(AskSelection(current.shapes.joinToString("\n") { it.text }, resources.getString(R.string.files_slide, slide + 1))) }, tiny = true)
            }
            items(current.shapes, key = { it.index }) { item ->
                PCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionContainer { Text(item.text, color = P.ink, fontWeight = if (item.index == 0) FontWeight.SemiBold else FontWeight.Normal) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PButton(stringResource(R.string.files_ask), { onAsk(AskSelection(item.text, resources.getString(R.string.files_slide_shape, slide + 1, item.index + 1))) }, tiny = true, style = ButtonStyle.Ghost)
                            if (editing) PButton(stringResource(R.string.files_edit), { shape = item.index }, tiny = true, style = ButtonStyle.Ghost, enabled = !busy)
                        }
                    }
                }
            }
            items(current.images) { source ->
                val bitmap by produceState<android.graphics.Bitmap?>(null, source) { value = withContext(Dispatchers.IO) {
                    val bytes = Base64.decode(source.substringAfter(','), Base64.DEFAULT)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                } }
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.files_slide_image), Modifier.fillMaxWidth().heightIn(max = 300.dp)) }
            }
        }
    }
    shape?.let { index -> EditValueDialog(stringResource(R.string.files_slide_shape, slide + 1, index + 1), current.shapes[index].text, busy, { shape = null }) { text ->
        val slideIndex = slide
        onSave { document.edit(slideIndex, index, text) }; shape = null
    } }
}
