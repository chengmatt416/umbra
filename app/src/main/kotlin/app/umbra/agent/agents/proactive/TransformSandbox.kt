package app.umbra.agent.agents.proactive

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.webkit.*
import app.umbra.core.model.UmbraJson
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** JSON-only transformations: the renderer has no Java bridge, files, cookies, network or persistent storage. */
class TransformSandbox(private val context: Context) {
    private val mutex = Mutex()
    suspend fun run(source: String, input: JsonElement): JsonElement = mutex.withLock {
        require(source.length <= MAX_SOURCE && input.toString().length <= MAX_JSON)
        withContext(Dispatchers.Main) { withTimeout(15_000) {
            suspendCancellableCoroutine { continuation ->
                val nonce = UUID.randomUUID().toString()
                var bound = false
                var finished = false
                lateinit var connection: ServiceConnection
                fun unbind() { if (bound) { bound = false; runCatching { context.unbindService(connection) } } }
                fun fail() {
                    if (finished) return
                    finished = true; unbind()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("The isolated transform worker stopped or timed out"))
                }
                val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
                    val body = message.data
                    if (message.what == RESULT && body.getString("nonce") == nonce && !finished) {
                        finished = true; unbind()
                        try {
                            val raw = body.getString("result") ?: error(body.getString("error") ?: "Transform failed")
                            require(raw.length <= MAX_JSON)
                            if (continuation.isActive) continuation.resume(UmbraJson.parseToJsonElement(raw))
                        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                    }
                    true
                })
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (finished || binder == null) { fail(); return }
                        try { Messenger(binder).send(Message.obtain(null, RUN).apply {
                            replyTo = receiver
                            data = Bundle().apply { putString("nonce", nonce); putString("source", source); putString("input", input.toString()) }
                        }) } catch (_: Exception) { fail() }
                    }
                    override fun onServiceDisconnected(name: ComponentName?) = fail()
                    override fun onBindingDied(name: ComponentName?) = fail()
                    override fun onNullBinding(name: ComponentName?) = fail()
                }
                continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post {
                    finished = true; unbind()
                } }
                try {
                    bound = context.bindService(Intent(context, TransformSandboxService::class.java), connection, Context.BIND_AUTO_CREATE)
                    if (!bound) fail()
                } catch (_: Exception) { fail() }
            }
        } }
    }
    companion object {
        const val MAX_SOURCE = 32_000
        const val MAX_JSON = 64_000
        internal const val RUN = 901
        internal const val RESULT = 902
    }
}

/** A separate host process keeps renderer failures and teardown away from the app's UI and agent runtime. */
class TransformSandboxService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    private var active: String? = null
    private var client: Messenger? = null
    private var timer: Runnable? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { request ->
        if (request.what != TransformSandbox.RUN || request.sendingUid != Process.myUid()) return@Handler true
        val nonce = request.data.getString("nonce").orEmpty()
        if (nonce.length !in 1..100 || request.replyTo == null) return@Handler true
        if (active != null) { reply(request.replyTo, nonce, null, "The transform worker is busy"); return@Handler true }
        val source = request.data.getString("source").orEmpty()
        val input = request.data.getString("input").orEmpty()
        if (source.length > TransformSandbox.MAX_SOURCE || input.length > TransformSandbox.MAX_JSON) {
            reply(request.replyTo, nonce, null, "Transform input exceeds its limit"); return@Handler true
        }
        active = nonce; client = request.replyTo
        execute(source, input, nonce)
        true
    })
    override fun onCreate() {
        super.onCreate()
        WebView.setWebContentsDebuggingEnabled(false)
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onUnbind(intent: Intent?): Boolean { finish(null, "Transform client disconnected"); return false }
    override fun onDestroy() { finish(null, "Transform worker stopped"); super.onDestroy() }

    @Suppress("SetJavaScriptEnabled")
    private fun execute(source: String, input: String, nonce: String) {
        try {
            val web = WebView(this)
            view = web
            web.settings.apply {
                javaScriptEnabled = true
                blockNetworkLoads = true
                blockNetworkImage = true
                loadsImagesAutomatically = false
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            CookieManager.getInstance().setAcceptCookie(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
            web.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?) = true
                override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            }
            web.webViewClient = object : WebViewClient() {
                private var evaluated = false
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                    if (request?.isForMainFrame == true && request.url.toString() == CONTENT_URL) return WebResourceResponse("text/html", "UTF-8", 200, "OK",
                        mapOf("Content-Security-Policy" to CONTENT_POLICY, "Cache-Control" to "no-store"), ByteArrayInputStream(CONTENT.toByteArray()))
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", mapOf("Content-Security-Policy" to "default-src 'none'"), ByteArrayInputStream(ByteArray(0)))
                }
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    if (active == nonce) finish(null, "The transform renderer stopped")
                    runCatching { view?.destroy() }
                    return true
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (evaluated || active != nonce || url != CONTENT_URL) return
                    evaluated = true
                    if (web.webViewRenderProcess == null) { finish(null, "An isolated multiprocess WebView is required"); return }
                    timer?.let(main::removeCallbacks)
                    timer = Runnable { if (active == nonce) finish(null, "Transform exceeded its three-second execution deadline") }.also { main.postDelayed(it, 3_000) }
                    val script = """
                        (() => {
                          const parse = JSON.parse, stringify = JSON.stringify;
                          try {
                            if (!document.querySelector('meta[http-equiv="Content-Security-Policy"]')) return stringify({error:'The transform content policy is missing'});
                            const transform = new Function('input', ${JsonPrimitive(source)});
                            const output = transform(parse(${JsonPrimitive(input)}));
                            if (output && typeof output.then === 'function') return stringify({error:'Asynchronous transforms are not supported'});
                            const json = stringify(output);
                            if (typeof json !== 'string' || json.length > ${TransformSandbox.MAX_JSON}) return stringify({error:'Transform output exceeds its limit or is not JSON'});
                            return stringify({result:json});
                          } catch (_) { return stringify({error:'Transform execution failed'}); }
                        })()
                    """.trimIndent()
                    web.evaluateJavascript(script) { encoded ->
                        if (active != nonce) return@evaluateJavascript
                        try {
                            require(encoded.length <= 4 * TransformSandbox.MAX_JSON + 4096)
                            val raw = (UmbraJson.parseToJsonElement(encoded) as? JsonPrimitive)?.content ?: error("Invalid transform reply")
                            val response = UmbraJson.parseToJsonElement(raw) as? JsonObject ?: error("Invalid transform result")
                            val output = (response["result"] as? JsonPrimitive)?.content
                            require(output == null || output.length <= TransformSandbox.MAX_JSON)
                            finish(output, (response["error"] as? JsonPrimitive)?.content?.take(200))
                        } catch (_: Exception) { finish(null, "Transform result exceeded its limit or was invalid") }
                    }
                }
            }
            timer = Runnable { if (active == nonce) finish(null, "The transform worker did not initialize in time") }.also { main.postDelayed(it, 8_000) }
            // CSP remains in force even if untrusted code removes the meta element from the DOM.
            web.loadUrl(CONTENT_URL)
        } catch (_: Exception) { finish(null, "The isolated transform runtime is unavailable") }
    }
    private fun finish(result: String?, error: String?) {
        val nonce = active ?: return
        active = null
        timer?.let(main::removeCallbacks); timer = null
        val replyTo = client; client = null
        val web = view; view = null
        runCatching { web?.webViewRenderProcess?.terminate() }
        runCatching { web?.destroy() }
        if (replyTo != null) reply(replyTo, nonce, result, error ?: if (result == null) "Transform failed" else null)
    }
    private fun reply(to: Messenger, nonce: String, result: String?, error: String?) {
        runCatching { to.send(Message.obtain(null, TransformSandbox.RESULT).apply {
            data = Bundle().apply { putString("nonce", nonce); result?.let { putString("result", it) }; error?.let { putString("error", it.take(200)) } }
        }) }
    }
    private companion object {
        const val CONTENT_URL = "https://umbra.invalid/json-transform"
        const val CONTENT_POLICY = "default-src 'none'; script-src 'unsafe-eval'; connect-src 'none'; img-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'"
        const val CONTENT = "<!doctype html><meta http-equiv=\"Content-Security-Policy\" content=\"$CONTENT_POLICY\"><title>JSON transform</title>"
    }
}
