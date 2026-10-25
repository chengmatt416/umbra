package app.umbra.agent.agents.proactive

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** Android's picker grant is the boundary; callers cannot supply individual file paths or other URIs. */
internal class DocumentContext(private val resolver: ContentResolver) {
    suspend fun search(tree: Uri, query: String, now: Long): List<ContextEvidence> = withTimeout(15_000) {
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) { "Choose a documents folder again" }
        val signal = CancellationSignal()
        val input = AtomicReference<InputStream?>()
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { signal.cancel(); runCatching { input.getAndSet(null)?.close() } }
        }
        try { withContext(Dispatchers.IO) {
            val root = DocumentsContract.getTreeDocumentId(tree)
            DocumentSearch.search(root, query, now, children = { parent, limit ->
                ensureActive()
                val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
                resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS), null, null, null, signal)?.use { cursor ->
                    buildList { while (size < limit && cursor.moveToNext()) {
                        ensureActive()
                        val id = cursor.getString(0) ?: continue
                        if (id.length > 4096) continue
                        val mime = cursor.getString(2).orEmpty().take(200)
                        add(ContextDocument(id, cursor.getString(1).orEmpty().take(200), mime, mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            if (cursor.isNull(3)) -1 else cursor.getLong(3), if (cursor.isNull(4)) 0 else cursor.getLong(4),
                            virtual = !cursor.isNull(5) && cursor.getInt(5) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0))
                    } }
                } ?: error("The selected documents folder is unavailable")
            }, read = read@ { document, limit ->
                ensureActive()
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, document.id)
                val descriptor = if (document.virtual) {
                    val type = DocumentText.virtualExportType(resolver.getStreamTypes(uri, "text/*")?.toList().orEmpty())
                    if (type == null) null else resolver.openTypedAssetFileDescriptor(uri, type, null, signal)
                } else requireNotNull(resolver.openAssetFileDescriptor(uri, "r", signal)) { "A selected document is unavailable" }
                if (descriptor == null) return@read ByteArray(0)
                descriptor.use { asset -> ensureActive(); asset.createInputStream().use { stream ->
                    input.set(stream)
                    try { ensureActive(); stream.readNBytes(limit) } finally { input.compareAndSet(stream, null) }
                } }
            }).map { it.copy(id = DocumentsContract.buildDocumentUriUsingTree(tree, it.id).toString()) }
        } } finally { cancellation.cancelAndJoin() }
    }
}
