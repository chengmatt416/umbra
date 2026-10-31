package app.umbra.agent.ui.files

import android.content.ClipboardManager
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.umbra.agent.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** Add Ask to Compose's current selection menu, using Copy to obtain the exact highlighted text. */
@Composable
internal fun SelectionAsk(onAsk: (String) -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val askLabel = stringResource(R.string.files_ask)
    val ask by rememberUpdatedState(onAsk)
    val toolbar = LocalTextContextMenuToolbarProvider.current
    val dropdown = LocalTextContextMenuDropdownProvider.current
    fun augment(original: TextContextMenuProvider?) = original?.let {
        object : TextContextMenuProvider {
            override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
                original.showTextContextMenu(object : TextContextMenuDataProvider by dataProvider {
                    override fun data(): TextContextMenuData {
                        val data = dataProvider.data()
                        val copy = data.components.filterIsInstance<TextContextMenuItem>()
                            .firstOrNull { it.key == TextContextMenuKeys.CopyKey } ?: return data
                        val item = TextContextMenuItem(
                            key = "umbra-ask", label = askLabel,
                        ) {
                            selectedCopy(context, { copy.onClick(this) }, ask)
                            close()
                        }
                        return TextContextMenuData(data.components + item)
                    }
                })
            }
        }
    }
    val augmentedToolbar = remember(toolbar, context, askLabel) { augment(toolbar) }
    val augmentedDropdown = remember(dropdown, context, askLabel) { augment(dropdown) }
    CompositionLocalProvider(
        LocalTextContextMenuToolbarProvider provides augmentedToolbar,
        LocalTextContextMenuDropdownProvider provides augmentedDropdown,
        content = content,
    )
}

private fun selectedCopy(context: Context, copy: () -> Unit, onSelected: (String) -> Unit) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val previous = clipboard.primaryClip
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var finished = false
    lateinit var listener: ClipboardManager.OnPrimaryClipChangedListener
    fun finish(read: Boolean) {
        if (finished) return
        finished = true
        clipboard.removePrimaryClipChangedListener(listener)
        if (read) {
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
            text?.takeIf { it.isNotBlank() }?.let(onSelected)
        }
    }
    listener = ClipboardManager.OnPrimaryClipChangedListener { handler.post { finish(true) } }
    clipboard.addPrimaryClipChangedListener(listener)
    try { copy() } catch (e: Exception) { finish(false); throw e }
    handler.postDelayed({ finish(false) }, 1500)
}

internal class SelectionWebView(context: Context) : WebView(context) {
    var onAsk: (String) -> Unit = {}
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? = super.startActionMode(object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = callback.onCreateActionMode(mode, menu)
            if (created) menu.add(0, ASK_ITEM, 0, context.getString(R.string.files_ask))
            return created
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = callback.onPrepareActionMode(mode, menu)
        override fun onDestroyActionMode(mode: ActionMode) = callback.onDestroyActionMode(mode)
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != ASK_ITEM) return callback.onActionItemClicked(mode, item)
            if (settings.javaScriptEnabled) {
                evaluateJavascript("window.getSelection().toString()") { result ->
                    runCatching { Json.parseToJsonElement(result).jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() }?.let(onAsk)
                }
            } else {
                mode.menu.findItem(android.R.id.copy)?.let { copy -> selectedCopy(context, { callback.onActionItemClicked(mode, copy) }, onAsk) }
            }
            mode.finish()
            return true
        }
    }, type)

    companion object { private const val ASK_ITEM = 0x554d4252 }
}

/** Render only supplied content: neither scripts nor CSS can fetch local files or the network. */
@Suppress("SetJavaScriptEnabled")
@Composable
internal fun LocalHtml(html: String, javaScript: Boolean, modifier: Modifier, base: java.io.File? = null, onAsk: (String) -> Unit) {
    val ask by rememberUpdatedState(onAsk)
    key(html, javaScript, base) {
        AndroidView(modifier = modifier, factory = { context ->
            SelectionWebView(context).apply {
                settings.javaScriptEnabled = javaScript
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.domStorageEnabled = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (request.url.scheme == "data") null
                        else base?.let { folder -> localAsset(folder, request) } ?: WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
                }
                this.onAsk = { ask(it) }
                // With [base], the page's own files (its stylesheet, images, fonts, scripts when allowed) load from its
                // folder through the page's made-up origin, so the preview looks as it will once published; nothing
                // reaches the network either way.
                val own = if (base != null) " $PREVIEW_ORIGIN" else ""
                val csp = "default-src 'none'; img-src data:$own; style-src 'unsafe-inline'$own; font-src data:$own; media-src$own${if (own.isEmpty()) " 'none'" else ""}; " +
                    "script-src ${if (javaScript) "'unsafe-inline'$own" else "'none'"}; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'"
                loadDataWithBaseURL("$PREVIEW_ORIGIN/", "<meta http-equiv=\"Content-Security-Policy\" content=\"$csp\">$html", "text/html", "UTF-8", null)
            }
        }, onRelease = { it.stopLoading(); it.destroy() })
    }
}

private const val PREVIEW_ORIGIN = "https://umbra.invalid"

/**
 * A file the previewed page asks for by relative path, from the page's own folder or below: a GET on the preview's
 * origin, decoded, inside [folder] after resolving links, a regular file of at most 25 MiB. Anything else is refused
 * (null lets the caller answer with nothing).
 */
private fun localAsset(folder: java.io.File, request: WebResourceRequest): WebResourceResponse? {
    val url = request.url
    if (request.method != "GET" || url.scheme != "https" || url.host != "umbra.invalid" || url.port != -1) return null
    val path = url.path?.removePrefix("/")?.takeIf { it.isNotEmpty() } ?: return null
    if (path.split('/').any { it.isEmpty() || it == "." || it == ".." } || '\\' in path || '\u0000' in path) return null
    return runCatching {
        val root = folder.canonicalFile
        val file = java.io.File(root, path).canonicalFile
        if (!file.toPath().startsWith(root.toPath()) || !file.isFile || file.length() > 25L * 1024 * 1024) return null
        val ext = file.extension.lowercase()
        val mime = when (ext) {
            "css" -> "text/css"; "js", "mjs" -> "text/javascript"; "svg" -> "image/svg+xml"; "json" -> "application/json"; "wasm" -> "application/wasm"
            else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("xml")) "UTF-8" else null, java.io.FileInputStream(file))
    }.getOrNull()
}
