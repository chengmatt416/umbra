package app.umbra.agent.capsule

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.ContextChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/**
 * A screen recording as context for a task: started from the Capsule's card, stopped by tapping the Capsule. Android
 * asks the person first (its screen-capture dialog). A model reads pictures and text, not video, so what the task
 * gets is a sheet of moments from the recording (the time in each corner) and a timeline of what was on screen,
 * read from the accessibility tree while it ran. The video itself stays on the phone only until it is turned into
 * that, then it is deleted.
 */
class ScreenRecorder(private val context: Context, private val container: AppContainer) {
    sealed interface State {
        data object Idle : State
        /** Android's dialog is asking the person. */
        data object Asking : State
        data class Recording(val startedAtMs: Long) : State
        /** Stopped; the sheet and the timeline are being made. */
        data object Finishing : State
    }

    private val mutable = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutable.asStateFlow()

    private val glances = ArrayList<Pair<Long, String>>()
    private var sampler: Job? = null

    /** Asks Android for the screen, then records. */
    fun request() {
        if (mutable.value != State.Idle) return
        mutable.value = State.Asking
        context.startActivity(Intent(context, ScreenRecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun stop() {
        if (mutable.value !is State.Recording) return
        mutable.value = State.Finishing
        context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP))
    }

    internal fun consented(code: Int, data: Intent) {
        ContextCompat.startForegroundService(context, Intent(context, ScreenRecordService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data))
    }

    internal fun refused() {
        if (mutable.value == State.Asking) mutable.value = State.Idle
    }

    internal fun started() {
        val start = System.currentTimeMillis()
        mutable.value = State.Recording(start)
        synchronized(glances) { glances.clear() }
        sampler?.cancel()
        sampler = container.scope.launch {
            var last: String? = null
            while (true) {
                val now = runCatching { container.capsule.screen.glance() }.getOrNull()
                if (now != null && now != last) synchronized(glances) { glances += (System.currentTimeMillis() - start) to now }
                last = now ?: last
                if (System.currentTimeMillis() - start >= MAX_MS) { stop(); break }
                delay(GLANCE_MS)
            }
        }
    }

    internal fun finished(file: File?, durationHint: Long) {
        sampler?.cancel()
        sampler = null
        container.scope.launch(Dispatchers.IO) {
            try {
                val chip = file?.takeIf { it.isFile && it.length() > 0 }?.let { chipFor(it, durationHint) }
                if (chip != null) {
                    container.ui.addContext(chip)
                    withContext(Dispatchers.Main) { container.capsule.showRecording() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "recording", e)
            } finally {
                file?.delete()
                mutable.value = State.Idle
            }
        }
    }

    private fun chipFor(video: File, durationHint: Long): ContextChip {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: durationHint
            val count = (duration / 3_000L).toInt().coerceIn(1, MAX_FRAMES)
            val times = (0 until count).map { i -> duration * (2 * i + 1) / (2 * count) }
            val frames = times.mapNotNull { t ->
                retriever.getScaledFrameAtTime(t * 1_000, MediaMetadataRetriever.OPTION_CLOSEST, CELL, CELL * 2)?.let { t to it }
            }
            val now = System.currentTimeMillis()
            val sheet = File(container.ui.snapshotDir, "recording-$now.jpg")
            if (frames.isNotEmpty()) sheet(frames).let { bmp -> FileOutputStream(sheet).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }; bmp.recycle() }
            frames.forEach { it.second.recycle() }
            val timeline = synchronized(glances) { glances.toList() }
            val text = buildString {
                append("A screen recording, ").append(clock(duration)).append(" long.")
                if (frames.isNotEmpty()) append(" The attached picture shows ").append(frames.size).append(" moments from it, the time in each corner.")
                if (timeline.isNotEmpty()) {
                    append("\nWhat was on screen while it ran:")
                    timeline.forEach { (t, line) -> append("\n").append(clock(t)).append("  ").append(line) }
                }
            }.take(MAX_TEXT)
            return ContextChip(
                id = "recording-$now",
                label = context.getString(R.string.r_rec_chip, clock(duration)),
                kind = KIND,
                imagePath = sheet.takeIf { it.isFile }?.path,
                text = text,
                capturedAtMs = now,
            )
        } finally {
            retriever.release()
        }
    }

    /** The frames side by side, three to a row, each with its time in a dark pill. */
    private fun sheet(frames: List<Pair<Long, Bitmap>>): Bitmap {
        val cols = minOf(3, frames.size)
        val rows = ceil(frames.size / cols.toFloat()).toInt()
        val cellW = frames.maxOf { it.second.width }
        val cellH = frames.maxOf { it.second.height }
        val gap = 8
        val out = Bitmap.createBitmap(cols * cellW + (cols + 1) * gap, rows * cellH + (rows + 1) * gap, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(24, 32, 28))
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 0, 0) }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = cellW / 11f; isFakeBoldText = true }
        frames.forEachIndexed { i, (t, bmp) ->
            val x = gap + (i % cols) * (cellW + gap).toFloat()
            val y = gap + (i / cols) * (cellH + gap).toFloat()
            canvas.drawBitmap(bmp, x, y, null)
            val text = clock(t)
            val w = label.measureText(text)
            canvas.drawRoundRect(RectF(x + 8, y + 8, x + 24 + w, y + 16 + label.textSize * 1.2f), 12f, 12f, pill)
            canvas.drawText(text, x + 16, y + 8 + label.textSize * 1.05f, label)
        }
        return out
    }

    companion object {
        private const val TAG = "ScreenRecorder"
        const val KIND = "recording"
        internal const val ACTION_STOP = "app.umbra.agent.RECORDING_STOP"
        internal const val EXTRA_CODE = "code"
        internal const val EXTRA_DATA = "data"
        /** A recording ends by itself after three minutes. */
        const val MAX_MS = 3 * 60_000L
        private const val GLANCE_MS = 2_500L
        private const val MAX_FRAMES = 6
        private const val CELL = 360
        private const val MAX_TEXT = 4_000

        fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1_000) % 60)
    }
}

/** See-through: shows Android's screen-capture dialog and hands the answer to the recorder. */
class ScreenRecordActivity : ComponentActivity() {
    private val ask = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) container.recorder.consented(result.resultCode, data)
        else container.recorder.refused()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { ask.launch(manager.createScreenCaptureIntent()) }.onFailure { container.recorder.refused(); finish() }
    }
}

/**
 * Records the screen while the person shows something (a foreground service of the media-projection kind, as Android
 * requires for screen capture). Scaled to at most 720 pixels wide: enough to read, small enough to handle on the phone.
 */
class ScreenRecordService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var file: File? = null
    private var startedAt = 0L
    private val ended = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ScreenRecorder.ACTION_STOP) { end(); return START_NOT_STICKY }
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent?.getIntExtra(ScreenRecorder.EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION") val data = intent?.getParcelableExtra<Intent>(ScreenRecorder.EXTRA_DATA)
        if (data == null) { end(); return START_NOT_STICKY }
        try {
            begin(code, data)
            container.recorder.started()
        } catch (e: Exception) {
            Log.w("ScreenRecordService", "start", e)
            end()
        }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val p = manager.getMediaProjection(code, data) ?: error("no projection")
        projection = p
        // Android 14+: the callback comes before the display; the projection may be ended from the status bar too.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { end() }
        }, Handler(mainLooper))
        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val scale = minOf(1f, MAX_WIDTH / bounds.width().toFloat())
        val w = (bounds.width() * scale).toInt() and 1.inv()
        val h = (bounds.height() * scale).toInt() and 1.inv()
        val dpi = (resources.displayMetrics.densityDpi * scale).toInt().coerceAtLeast(120)
        val out = File(cacheDir, "recordings").apply { mkdirs() }.let { File(it, "recording-${System.currentTimeMillis()}.mp4") }
        file = out
        val r = MediaRecorder(this).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(w, h)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(4_000_000)
            setOutputFile(out.path)
            prepare()
        }
        recorder = r
        display = p.createVirtualDisplay("umbra-recording", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        r.start()
        startedAt = System.currentTimeMillis()
    }

    private fun end() {
        if (!ended.compareAndSet(false, true)) return
        val ok = runCatching { recorder?.stop() }.isSuccess   // throws when no frame was recorded
        runCatching { recorder?.release() }
        runCatching { display?.release() }
        runCatching { projection?.stop() }
        recorder = null; display = null; projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        container.recorder.finished(file?.takeIf { ok }, if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L)
        if (!ok) file?.delete()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.r_rec_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_umbra)
            .setContentTitle(getString(R.string.r_rec_notification))
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        end()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "recording"
        const val NOTIFICATION = 4107
        const val MAX_WIDTH = 720f
    }
}
