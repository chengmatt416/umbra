package app.umbra.agent.capsule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Base64
import app.umbra.agent.AgentService
import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.data.ContextChip
import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.Point
import app.umbra.core.model.Rect
import app.umbra.core.model.action.Environment
import app.umbra.core.model.observation.Lasso
import app.umbra.core.model.observation.UiSnapshot
import app.umbra.core.model.observation.UiTree
import app.umbra.core.orchestrator.AgentSupervisor
import app.umbra.platform.android.UmbraAccessibilityService
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.CapturedImage
import app.umbra.platform.api.SnapshotOptions
import app.umbra.runtime.omp.ContentBlock
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Why the screen could not be used, in words for the user. */
class ScreenContextException(message: String) : Exception(message)

/**
 * Snapshot selection and Live Context (architecture spec §3.1): a picture of the screen and its accessibility tree
 * taken together, a lasso turned into a cropped picture plus the text inside it, and — only when the user opted in —
 * what the app in front shows when a message is sent. Nothing is captured without the user asking for it.
 */
class ScreenContext(private val context: Context, private val container: AppContainer) {
    /** One screenshot and the tree read with it. [tree] is null when no accessibility service could read it. */
    class Capture(val image: Bitmap, val tree: UiSnapshot?, val screen: Rect, val atMs: Long)

    /** Takes the picture and reads the tree. Umbra's own windows are hidden first by the caller. */
    suspend fun capture(): Capture = withContext(Dispatchers.IO) {
        val tree = if (UmbraAccessibilityService.connected.value) runCatching { container.accessibility.snapshot(SnapshotOptions(displayId = 0)) }.getOrNull() else null
        val shot = screenshot()
        val image = BitmapFactory.decodeByteArray(shot.bytes, 0, shot.bytes.size)
            ?: throw ScreenContextException(context.getString(R.string.sc_unreadable, shot.bytes.size, shot.mime))
        Capture(image, tree, Rect(0, 0, image.width, image.height), System.currentTimeMillis())
    }

    private suspend fun screenshot(): CapturedImage {
        var last: Exception? = null
        for (backend in container.backends.screenshots(Environment.ANDROID)) {
            if (backend === container.accessibility && !UmbraAccessibilityService.connected.value) continue
            if (!runCatching { backend.isAvailable() }.getOrDefault(false)) continue
            try {
                return backend.capture(0)
            } catch (e: BackendException) {
                // An app that protects its screen is respected: no other way around it is tried.
                if (e.code == ErrorCode.PERMISSION_DENIED && "secure" in (e.message ?: "")) throw ScreenContextException(context.getString(R.string.sc_secure))
                last = e
            } catch (e: Exception) {
                last = e
            }
        }
        throw ScreenContextException(
            last?.let { context.getString(R.string.sc_failed, it.message.orEmpty()) }
                ?: context.getString(R.string.sc_needs_service),
        )
    }

    /** Turns the user's lasso on [capture] into a context chip: the cropped picture and what the tree shows inside it. */
    suspend fun select(capture: Capture, stroke: List<Point>): ContextChip = withContext(Dispatchers.Default) {
        val selection = Lasso.select(capture.tree ?: UiSnapshot(), stroke, capture.screen)
        val r = selection.region
        val crop = Bitmap.createBitmap(capture.image, r.left, r.top, r.width, r.height)
        val scale = MAX_SIDE.toFloat() / maxOf(crop.width, crop.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(crop, (crop.width * scale).toInt().coerceAtLeast(1), (crop.height * scale).toInt().coerceAtLeast(1), true) else crop
        val file = File(container.ui.snapshotDir, "selection-${capture.atMs}.jpg")
        FileOutputStream(file).use { sized.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val app = selection.apps.firstOrNull()?.let(::appName)
        val text = Lasso.describe(selection, capture.screen, ::appName, treeAvailable = capture.tree != null)
        ContextChip(
            id = "selection-${capture.atMs}",
            label = if (app != null) context.getString(R.string.sc_selection_app, app) else context.getString(R.string.sc_selection),
            kind = ContextChip.SNAPSHOT,
            imagePath = file.absolutePath,
            text = text,
            capturedAtMs = capture.atMs,
        )
    }

    /**
     * Live Context: what the app in front shows now, as text. Only called when the user turned Live Context on, and
     * only at the moment a message is sent.
     */
    suspend fun live(): String = withContext(Dispatchers.IO) {
        if (!UmbraAccessibilityService.connected.value) return@withContext "Live context is on, but Umbra's accessibility service is off, so the screen could not be read."
        val snapshot = runCatching { container.accessibility.snapshot(SnapshotOptions(displayId = 0)) }.getOrElse {
            return@withContext "Live context is on, but the screen could not be read: ${it.message}"
        }
        val front = frontApp(snapshot) ?: return@withContext "Live context is on; no app window was in front."
        val name = front.packageName?.let(::appName) ?: "an app"
        val nodes = Lasso.readable(front)
        "What $name showed when this was sent" + (front.title?.let { " (window “$it”)" } ?: "") + ":" +
            (if (nodes.isEmpty()) "\n(no text the accessibility tree could read)" else Lasso.elements(nodes, 3_000))
    }

    /** The app window under Umbra's own: not the status bar, not the keyboard, not Umbra. */
    private fun frontApp(snapshot: UiSnapshot): UiTree? {
        val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        val skip = setOf(context.packageName, "com.android.systemui", ime)
        val apps = snapshot.windows.filter { it.packageName !in skip && it.nodes.isNotEmpty() }
        return apps.firstOrNull { it.isActive } ?: apps.maxByOrNull { it.layer }
    }

    fun appName(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /**
     * Sends what a composer holds: [text], the attached context chips, and Live Context when the user opted in (only
     * [fromCapsule]). To
     * [sessionId] when given, otherwise as new work in [projectId]. Returns the session's id. The draft and the sent
     * chips are cleared only once the message went.
     */
    suspend fun send(text: String, sessionId: String?, projectId: String?, fromCapsule: Boolean = true): String {
        val chips = container.ui.draftContext.value
        val blocks = ArrayList<String>()
        chips.forEach { chip ->
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(chip.capturedAtMs))
            chip.text?.let { blocks += "[${chip.label}, captured $time" + (if (chip.imagePath != null) "; its picture is attached" else "") + "]\n$it" }
        }
        // Live Context belongs to the Capsule, which floats over other apps; inside Umbra there is nothing else in front.
        if (fromCapsule && container.settings.value.liveContext) blocks += "[Live context]\n" + live()
        val message = if (blocks.isEmpty()) text else text + "\n\n" + blocks.joinToString("\n\n")
        val images = withContext(Dispatchers.IO) {
            chips.mapNotNull { chip -> chip.imagePath?.let { File(it) }?.takeIf { it.isFile }?.let { ContentBlock.Image(Base64.encodeToString(it.readBytes(), Base64.NO_WRAP), "image/jpeg") } }
        }
        val id = if (sessionId != null) {
            container.supervisor.send(sessionId, message, images)
            sessionId
        } else {
            AgentService.start(context)
            container.supervisor.submit(message, title = AgentSupervisor.titleOf(text), projectId = projectId, images = images).id
        }
        container.ui.saveDraft("")
        container.ui.clearSent(chips)
        return id
    }

    private companion object {
        const val MAX_SIDE = 1600
    }
}
