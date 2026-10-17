package app.umbra.platform.server

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Real, invisible displays any app can be launched on (spec §9). Each owns an [ImageReader] surface,
 * which both keeps the display "connected" and lets the server take screenshots of it, since
 * `screencap` cannot address virtual displays. The display disappears with this process.
 */
internal class VirtualDisplays {
    class Entry(
        val id: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val display: VirtualDisplay,
        val reader: ImageReader,
    ) {
        @Volatile
        var lastFrame: Bitmap? = null
    }

    private val displays = ConcurrentHashMap<Int, Entry>()
    private val main = Handler(Looper.getMainLooper())

    fun list(): List<Entry> = displays.values.sortedBy { it.id }

    fun get(id: Int): Entry? = displays[id]

    /**
     * Must run on the main looper thread: the framework attaches its callbacks to the creating thread's looper.
     * The flags are the system-level ones only a privileged caller may pass, which the public constants do not list.
     */
    @SuppressLint("WrongConstant")
    fun create(name: String, width: Int, height: Int, dpi: Int): Entry {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val flags = FLAGS_BASE or FLAGS_API_34
        val display = try {
            HiddenApi.displayManager().createVirtualDisplay(name, width, height, dpi, reader.surface, flags)
        } catch (e: Throwable) {
            reader.close()
            throw e
        } ?: run {
            reader.close()
            throw IllegalStateException("the system refused to create the virtual display")
        }
        val entry = Entry(display.display.displayId, name, width, height, dpi, display, reader)
        // The agent types by injecting text: an app on this display gets no soft keyboard, so none pops up on the
        // phone's own screen either (by default it falls back there).
        runCatching { HiddenApi.setDisplayImePolicy(entry.id, DISPLAY_IME_POLICY_HIDE) }
        // When the display goes away with an app still on it, the app ends with it instead of landing on the phone's
        // own screen in front of the user (by default it moves there).
        runCatching { HiddenApi.setRemoveContentMode(entry.id, REMOVE_CONTENT_MODE_DESTROY) }
        // Keep the newest frame; the surface only produces one when something changes on screen.
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { entry.lastFrame = toBitmap(it, width, height) } }, main)
        displays[entry.id] = entry
        return entry
    }

    fun destroy(id: Int): Boolean {
        val entry = displays.remove(id) ?: return false
        entry.display.release()
        entry.reader.close()
        entry.lastFrame?.recycle()
        return true
    }

    fun destroyAll() {
        displays.keys.toList().forEach { destroy(it) }
    }

    /** The newest picture of the display, encoded. */
    fun screenshot(id: Int, jpeg: Boolean): Triple<ByteArray, Int, Int>? {
        val entry = displays[id] ?: return null
        // A frame may be waiting that the listener has not delivered yet.
        entry.reader.acquireLatestImage()?.use { entry.lastFrame = toBitmap(it, entry.width, entry.height) }
        val bitmap = entry.lastFrame ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 90, out)
        return Triple(out.toByteArray(), bitmap.width, bitmap.height)
    }

    private fun toBitmap(image: android.media.Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val wide = Bitmap.createBitmap(width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
        wide.copyPixelsFromBuffer(plane.buffer)
        if (wide.width == width) return wide
        return Bitmap.createBitmap(wide, 0, 0, width, height).also { wide.recycle() }
    }

    private companion object {
        // DisplayManager.VIRTUAL_DISPLAY_FLAG_*: PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH |
        // ROTATES_WITH_CONTENT | TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED
        const val FLAGS_BASE = (1 shl 0) or (1 shl 1) or (1 shl 3) or (1 shl 6) or (1 shl 7) or (1 shl 10) or (1 shl 11) or (1 shl 12)

        // OWN_FOCUS | DEVICE_DISPLAY_GROUP (API 34+, always present on Android 16): apps on the display keep input focus independently of the main screen.
        const val FLAGS_API_34 = (1 shl 14) or (1 shl 15)

        // WindowManager.DISPLAY_IME_POLICY_HIDE, WindowManager.REMOVE_CONTENT_MODE_DESTROY (both @hide).
        const val DISPLAY_IME_POLICY_HIDE = 2
        const val REMOVE_CONTENT_MODE_DESTROY = 2
    }
}
