package app.umbra.platform.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.umbra.core.model.action.ErrorCode
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.ClipboardBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A window that exists for a few milliseconds so the app has focus, which is the only time Android
 * (10+) lets it read the clipboard. It reports what it read and closes itself.
 */
class ClipboardReaderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDimAmount(0f)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        ClipboardBackendState.pending?.complete(text)
        finish()
    }
}

internal object ClipboardBackendState {
    @Volatile
    var pending: CompletableDeferred<String?>? = null
}

/** Reads the clipboard through a short-lived focused window, writes it directly (writing needs no focus). */
class AndroidClipboardBackend(private val context: Context) : ClipboardBackend {
    override val name = "clipboard"

    private val lock = Mutex()

    override suspend fun isAvailable() = true

    override suspend fun get(): String? = lock.withLock {
        val result = CompletableDeferred<String?>()
        ClipboardBackendState.pending = result
        try {
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(context, ClipboardReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
            withTimeoutOrNull(3_000) { result.await() }
                ?: throw BackendException(ErrorCode.TIMEOUT, "The clipboard window did not get focus (the screen may be off or locked).", retryable = true)
        } finally {
            ClipboardBackendState.pending = null
        }
    }

    override suspend fun set(text: String) {
        withContext(Dispatchers.Main) {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("umbra", text))
        }
    }
}
