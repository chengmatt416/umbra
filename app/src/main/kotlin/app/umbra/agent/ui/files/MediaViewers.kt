package app.umbra.agent.ui.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import app.umbra.agent.R
import app.umbra.agent.ui.porcelain.*
import com.caverock.androidsvg.SVG
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun loadBitmap(file: File, svg: Boolean): Bitmap {
    if (svg) {
        if (file.length() > MAX_FILE_BYTES) throw FileTooLarge()
        val source = file.inputStream().use { it.readNBytes(MAX_FILE_BYTES + 1) }
        if (source.size > MAX_FILE_BYTES) throw FileTooLarge()
        val text = source.toString(Charsets.UTF_8)
        require(!text.contains("<!DOCTYPE", ignoreCase = true))
        val image = SVG.getFromString(text)
        val width = image.documentWidth.takeIf { it.isFinite() && it > 0 } ?: 1024f
        val height = image.documentHeight.takeIf { it.isFinite() && it > 0 } ?: 1024f
        val scale = minOf(1f, 4096f / maxOf(width, height))
        val bitmap = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        image.setDocumentWidth(bitmap.width.toFloat()); image.setDocumentHeight(bitmap.height.toFloat())
        image.renderToCanvas(AndroidCanvas(bitmap))
        return bitmap
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096 || (bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) > 12_000_000) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Image decode failed")
}

internal fun imageAttachment(bitmap: Bitmap): ByteArray {
    val ratio = minOf(1f, 1600f / maxOf(bitmap.width, bitmap.height))
    val resized = if (ratio < 1) Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true) else bitmap
    return try { ByteArrayOutputStream().also { resized.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
    finally { if (resized !== bitmap) resized.recycle() }
}

@Composable
internal fun ImageViewer(bitmap: Bitmap, onAsk: (AskSelection) -> Unit, onError: (Throwable) -> Unit) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var cropping by remember { mutableStateOf(false) }
    var region by remember(bitmap) { mutableStateOf<Rect?>(null) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val scope = rememberCoroutineScope()
    val where = stringResource(R.string.files_image_region)
    var working by remember { mutableStateOf(false) }
    val accent = P.accent
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PButton(stringResource(if (cropping) R.string.files_zoom else R.string.files_select_region), {
                cropping = !cropping; zoom = 1f; pan = Offset.Zero; region = null
            }, tiny = true, style = ButtonStyle.Ghost)
            PButton(stringResource(R.string.files_ask), {
                working = true
                scope.launch {
                    try {
                        val bytes = withContext(Dispatchers.IO) {
                            val r = region
                            if (r == null) imageAttachment(bitmap) else {
                                val cropped = Bitmap.createBitmap(bitmap, r.left.toInt(), r.top.toInt(), r.width.toInt().coerceAtLeast(1), r.height.toInt().coerceAtLeast(1))
                                try { imageAttachment(cropped) } finally { if (cropped !== bitmap) cropped.recycle() }
                            }
                        }
                        onAsk(AskSelection("", where, bytes))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { onError(e) }
                    finally { working = false }
                }
            }, tiny = true, enabled = !working && (!cropping || region != null))
        }
        if (cropping) Text(stringResource(R.string.files_drag_region), Modifier.padding(horizontal = 16.dp), color = P.sub)
        Canvas(Modifier.weight(1f).fillMaxWidth().clipToBounds()
            .pointerInput(bitmap, cropping) {
                fun toImage(point: Offset): Offset {
                    val fit = minOf(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
                    val origin = Offset((size.width - bitmap.width * fit) / 2, (size.height - bitmap.height * fit) / 2)
                    return Offset(((point.x - origin.x) / fit).coerceIn(0f, bitmap.width.toFloat()), ((point.y - origin.y) / fit).coerceIn(0f, bitmap.height.toFloat()))
                }
                if (cropping) {
                    var start = Offset.Zero
                    detectDragGestures(onDragStart = { start = toImage(it); region = null }, onDragEnd = { if ((region?.width ?: 0f) < 1 || (region?.height ?: 0f) < 1) region = null }) { change, _ ->
                        change.consume()
                        val end = toImage(change.position)
                        region = Rect(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y))
                    }
                } else detectTransformGestures { centroid, movement, factor, _ ->
                    val old = zoom
                    zoom = (zoom * factor).coerceIn(1f, 8f)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    pan = (pan + center - centroid) * (zoom / old) + centroid - center + movement
                    if (zoom == 1f) pan = Offset.Zero
                }
            }
            .pointerInput(cropping) { if (!cropping) detectTapGestures(onDoubleTap = { zoom = if (zoom > 1f) 1f else 2.5f; pan = Offset.Zero }) }
        ) {
            val fit = minOf(size.width / bitmap.width, size.height / bitmap.height)
            val width = bitmap.width * fit * zoom
            val height = bitmap.height * fit * zoom
            val origin = Offset((size.width - width) / 2, (size.height - height) / 2) + pan
            drawImage(image, dstOffset = IntOffset(origin.x.toInt(), origin.y.toInt()), dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)))
            region?.let { r -> drawRect(accent, origin + r.topLeft * fit, androidx.compose.ui.geometry.Size(r.width * fit, r.height * fit), style = Stroke(3.dp.toPx())) }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoViewer(file: File, onAsk: (AskSelection) -> Unit, onError: (Throwable) -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val error by rememberUpdatedState(onError)
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener { override fun onPlayerError(exception: PlaybackException) { error(exception) } })
            setMediaSource(ProgressiveMediaSource.Factory(FileDataSource.Factory()).createMediaSource(MediaItem.fromUri(Uri.fromFile(file))))
            prepare()
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    var capturing by remember { mutableStateOf(false) }
    val at = stringResource(R.string.files_at_time)
    Column(Modifier.fillMaxSize()) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.weight(1f).fillMaxWidth(), onRelease = { it.player = null })
        PButton(stringResource(R.string.files_use_moment), {
            val position = player.currentPosition.coerceAtLeast(0)
            player.pause(); capturing = true
            scope.launch {
                try {
                    val frame = withContext(Dispatchers.IO) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.path)
                            val bitmap = retriever.getScaledFrameAtTime(position * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 1600, 1600) ?: error(resources.getString(R.string.files_no_frame))
                            try { imageAttachment(bitmap) } finally { bitmap.recycle() }
                        } finally { retriever.release() }
                    }
                    val seconds = position / 1000
                    val timestamp = if (seconds >= 3600) String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
                    onAsk(AskSelection("", String.format(at, timestamp), frame))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error(e) }
                finally { capturing = false }
            }
        }, Modifier.padding(16.dp), enabled = !capturing)
    }
}
