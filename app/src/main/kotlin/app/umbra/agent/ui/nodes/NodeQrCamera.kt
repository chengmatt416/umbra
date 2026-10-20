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
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

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
    @Volatile private var closed = false
    private val decoder = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
    init {
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { open(texture) }
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
                    source.acquireLatestImage()?.use { image ->
                        if (closed) return@use
                        val plane = image.planes[0]
                        val data = ByteArray(image.width * image.height)
                        for (row in 0 until image.height) {
                            val start = row * plane.rowStride
                            for (col in 0 until image.width) data[row * image.width + col] = plane.buffer.get(start + col * plane.pixelStride)
                        }
                        var luma: LuminanceSource = PlanarYUVLuminanceSource(data, image.width, image.height, 0, 0, image.width, image.height, false)
                        repeat(4) {
                            val result = runCatching { decoder.decodeWithState(BinaryBitmap(HybridBinarizer(luma))) }.getOrNull()
                            decoder.reset()
                            if (result != null) { main.post { if (!closed) onCode(result.text) }; return@use }
                            if (luma.isRotateSupported) luma = luma.rotateCounterClockwise()
                        }
                    }
                }, worker)
            }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closed) { device.close(); return }
                    camera = device
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
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); failed("Camera disconnected") }
                override fun onError(device: CameraDevice, error: Int) { device.close(); failed("Camera unavailable ($error); use manual pairing") }
            }, worker)
        } catch (e: Exception) { failed(e.message) }
    }
    private fun failed(message: String?) { main.post { if (!closed) onError(message ?: "Camera unavailable; use manual pairing") } }
    override fun close() {
        closed = true
        session?.close(); camera?.close(); images?.close(); surface?.release()
        thread.quitSafely()
    }
}
