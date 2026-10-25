package app.umbra.agent.ui.nodes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun NodeQrCamera(onCode: (String) -> Unit, onError: (String) -> Unit) {
    val code by rememberUpdatedState(onCode)
    val error by rememberUpdatedState(onError)
    AndroidView(factory = { context -> CameraPreview(context, { code(it) }, { error(it) }) }, modifier = Modifier.fillMaxSize(), onRelease = { it.close() })
}

@SuppressLint("MissingPermission")
private class CameraPreview(context: Context, val onCode: (String) -> Unit, val onError: (String) -> Unit) : TextureView(context), AutoCloseable {
    private val thread = HandlerThread("node-qr-camera").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(context.mainLooper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var images: ImageReader? = null
    private var surface: Surface? = null
    private var opening = false
    private var lastDecoded: String? = null
    private var lastDecodeAtMs = 0L
    @Volatile private var closed = false
    private val decoder = NodeQrDecoder()
    init {
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { worker.post { if (!closed) open(texture) } }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { close(); return true }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }
    private fun open(texture: SurfaceTexture) {
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK } ?: manager.cameraIdList.first()
            val sizes = manager.getCameraCharacteristics(id)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.YUV_420_888)
            val size = sizes.filter { it.width <= 1280 && it.height <= 960 }.maxByOrNull { it.width * it.height } ?: sizes.minBy { it.width * it.height }
            texture.setDefaultBufferSize(size.width, size.height)
            surface = Surface(texture)
            images = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ source ->
                    if (closed) return@setOnImageAvailableListener
                    try {
                        source.acquireLatestImage()?.use { image ->
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastDecodeAtMs < 150) return@use
                            lastDecodeAtMs = now
                            val plane = image.planes[0]
                            val result = decoder.decode(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
                            if (result != null && result != lastDecoded) {
                                lastDecoded = result
                                main.post { if (!closed) onCode(result) }
                            }
                        }
                    } catch (e: Exception) { failed(e.message) }
                }, worker)
            }
            opening = true
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    opening = false
                    if (closed) { device.close(); thread.quitSafely(); return }
                    camera = device
                    try {
                        val targets = listOf(surface!!, images!!.surface)
                        @Suppress("DEPRECATION")
                        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                if (closed) { configured.close(); return }
                                session = configured
                                runCatching { configured.setRepeatingRequest(device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { targets.forEach(::addTarget); set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) }.build(), null, worker) }.onFailure { failed(it.message) }
                            }
                            override fun onConfigureFailed(configured: CameraCaptureSession) { failed("Camera preview unavailable; use manual pairing") }
                        }, worker)
                    } catch (e: Exception) { failed(e.message) }
                }
                override fun onDisconnected(device: CameraDevice) { opening = false; device.close(); if (closed) thread.quitSafely() else failed("Camera disconnected") }
                override fun onError(device: CameraDevice, error: Int) { opening = false; device.close(); if (closed) thread.quitSafely() else failed("Camera unavailable ($error); use manual pairing") }
            }, worker)
        } catch (e: Exception) { opening = false; failed(e.message) }
    }
    private fun failed(message: String?) { main.post { if (!closed) onError(message ?: "Camera unavailable; use manual pairing") } }
    override fun close() {
        if (closed) return
        closed = true
        // A decoded QR removes this view while the worker may still read its current image.
        // Release on that same worker so ImageReader cannot invalidate a buffer during decoding.
        worker.post {
            runCatching { session?.close() }; session = null
            runCatching { camera?.close() }; camera = null
            runCatching { images?.setOnImageAvailableListener(null, null); images?.close() }; images = null
            runCatching { surface?.release() }; surface = null
            if (!opening) thread.quitSafely()
        }
    }
}
