package app.umbra.agent.agents.proactive

import android.graphics.Bitmap
import app.umbra.agent.AppContainer
import app.umbra.core.model.UmbraJson
import app.umbra.core.safety.OutboundDlpGuard
import app.umbra.platform.android.AccessibilityInteraction
import app.umbra.platform.android.UmbraAccessibilityService
import app.umbra.runtime.omp.*
import app.umbra.tools.HostSideTool
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Explicit teaching only: bounded interaction traces and form-free keyframes, never raw video or typed values. */
class Demonstrations(private val container: AppContainer) {
    data class Active(val id: String, val agentId: String, val startedAtMs: Long, val target: String = "display:0")
    private val mutable = MutableStateFlow<Active?>(null)
    val active = mutable.asStateFlow()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val lock = Mutex()
    private val root = File(container.context.filesDir, "demonstrations")
    private var events: Channel<AccessibilityInteraction>? = null
    private var worker: Job? = null
    private fun dir(id: String): File {
        require(id.matches(Regex("demo[a-f0-9]{32}")))
        return File(root, id).also { require(!Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(it.toPath())) }
    }
    fun list(agentId: String): List<Demonstration> = root.listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }.mapNotNull { directory ->
        runCatching {
            val file = File(dir(directory.name), "trace.json")
            require(!Files.isSymbolicLink(file.toPath()) && file.length() <= 2_000_000)
            UmbraJson.decodeFromString(Demonstration.serializer(), file.readText()).let {
                if (it.status == "recording" && it.id != active.value?.id) it.copy(status = "interrupted", incomplete = true) else it
            }
        }.getOrNull()
    }.filter { it.agentId == agentId }.sortedByDescending { it.startedAtMs }.take(30)

    private fun save(record: Demonstration) {
        val directory = dir(record.id).apply { check(isDirectory || mkdirs()) }
        val temp = Files.createTempFile(directory.toPath(), ".trace-", ".tmp")
        try {
            Files.write(temp, UmbraJson.encodeToString(Demonstration.serializer(), record).toByteArray(Charsets.UTF_8))
            Files.move(temp, File(directory, "trace.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            changes.value++
        } finally { Files.deleteIfExists(temp) }
    }
    suspend fun start(agentId: String) = lock.withLock {
        check(container.credentialHandoff.pending.value == null) { container.context.getString(app.umbra.agent.R.string.credential_finish_first) }
        check(active.value == null && worker?.isActive != true && container.recorder.state.value == app.umbra.agent.capsule.ScreenRecorder.State.Idle) { "Finish the current recording first" }
        require(container.agents.store.get(agentId) != null)
        check(UmbraAccessibilityService.connected.value) { "Enable accessibility before teaching a task" }
        check(container.emergency.lockdownReason() == null)
        require(list(agentId).size < 30) { "Remove an old demonstration before recording another" }
        container.takeOver("display:0")
        val record = Demonstration("demo" + java.util.UUID.randomUUID().toString().replace("-", ""), agentId, System.currentTimeMillis())
        withContext(Dispatchers.IO) { save(record) }
        val queue = Channel<AccessibilityInteraction>(64)
        val dropped = java.util.concurrent.atomic.AtomicBoolean(false)
        val subscription = UmbraAccessibilityService.observeInteractions { event ->
            if (event.action == "INTERRUPTED") { dropped.set(true); queue.close() }
            else if (event.displayId == 0 && queue.trySend(event).isFailure) dropped.set(true)
        }
        events = queue
        mutable.value = Active(record.id, agentId, record.startedAtMs)
        worker = container.scope.launch(Dispatchers.IO) {
            var current = record
            var frameAt = 0L
            var frames = 0
            val deadline = launch { delay(MAX_MS); queue.close() }
            try {
                for (event in queue) {
                    if (event.atMs - record.startedAtMs > MAX_MS) break
                    if (!UmbraAccessibilityService.connected.value || container.emergency.lockdownReason() != null) { dropped.set(true); break }
                    val guard = OutboundDlpGuard(4) { container.protectedValues() }
                    val step = DemonstrationStep((event.atMs - record.startedAtMs).coerceAtLeast(0), event.packageName, guard.redact(event.windowTitle),
                        event.resourceId?.let(guard::redact), event.className, event.bounds, event.action,
                        if (event.secretInput) "[SECRET_INPUT_HANDOFF]" else guard.redact(event.label), guard.redact(event.description), event.secretInput)
                    val frame = if (event.action != "TYPE" && !event.secretInput && frames < 60 && event.atMs - frameAt >= 2000) {
                        frameAt = event.atMs
                        keyframe(record.id, frames, guard)?.also { frames++ }
                    } else null
                    val previous = current.steps.lastOrNull()
                    if (previous?.action == "TYPE" && step.action == "TYPE" && previous.resourceId == step.resourceId && previous.packageName == step.packageName && previous.bounds == step.bounds && previous.secretInput == step.secretInput)
                        current = current.copy(steps = current.steps.dropLast(1) + step)
                    else current = current.copy(steps = current.steps + step.copy(keyframe = frame))
                    if (current.steps.size >= 1000) { dropped.set(true); break }
                    save(current)
                }
            } catch (e: CancellationException) { dropped.set(true); throw e }
            catch (_: Exception) { dropped.set(true) }
            finally {
                subscription.close(); queue.close(); deadline.cancel()
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { save(current.copy(finishedAtMs = System.currentTimeMillis(), status = "completed", incomplete = dropped.get() || !UmbraAccessibilityService.connected.value)) }
                }
                mutable.value = null
            }
        }
    }
    /**
     * Teach on a paired computer: the user operates its screen in the remote viewer while the viewer reports each
     * click, drag and special key ([remoteInput]). Typed text is recorded as a placeholder only (a remote screen has
     * no password-field signal), and no screen frames are kept.
     */
    suspend fun startRemote(agentId: String, nodeId: String) = startPointer(agentId, "node:$nodeId")
    /** Teach on the Linux desktop (`gui:0`), operated from the Screens page the same way. */
    suspend fun startLinuxDesktop(agentId: String) = startPointer(agentId, Demonstration.LINUX_DESKTOP)

    private suspend fun startPointer(agentId: String, target: String) = lock.withLock {
        check(active.value == null && worker?.isActive != true && container.recorder.state.value == app.umbra.agent.capsule.ScreenRecorder.State.Idle) { "Finish the current recording first" }
        require(container.agents.store.get(agentId) != null)
        val nodeId = target.removePrefix("node:").takeIf { target.startsWith("node:") }
        if (nodeId != null) {
            val node = requireNotNull(container.nodes[nodeId]) { "Unknown computer" }
            check(node.revocation == null) { "This computer has been removed" }
        } else check(target == Demonstration.LINUX_DESKTOP && container.linuxGui.isAvailable()) { "Start the Linux desktop first" }
        check(container.emergency.lockdownReason() == null)
        require(list(agentId).size < 30) { "Remove an old demonstration before recording another" }
        val record = Demonstration("demo" + java.util.UUID.randomUUID().toString().replace("-", ""), agentId, System.currentTimeMillis(), target = target)
        withContext(Dispatchers.IO) { save(record) }
        val queue = Channel<DemonstrationStep>(64)
        val dropped = java.util.concurrent.atomic.AtomicBoolean(false)
        remoteQueue = queue; remoteDropped = dropped
        mutable.value = Active(record.id, agentId, record.startedAtMs, record.target)
        worker = container.scope.launch(Dispatchers.IO) {
            var current = record
            val deadline = launch { delay(MAX_MS); queue.close() }
            try {
                for (step in queue) {
                    if (step.elapsedMs > MAX_MS) break
                    if (container.emergency.lockdownReason() != null || nodeId != null && container.nodes[nodeId]?.revocation != null) { dropped.set(true); break }
                    val previous = current.steps.lastOrNull()
                    current = if (previous?.action == "TYPE" && step.action == "TYPE") current.copy(steps = current.steps.dropLast(1) + step.copy(elapsedMs = previous.elapsedMs))
                        else current.copy(steps = current.steps + step)
                    if (current.steps.size >= 1000) { dropped.set(true); break }
                    save(current)
                }
            } catch (e: CancellationException) { dropped.set(true); throw e }
            catch (_: Exception) { dropped.set(true) }
            finally {
                queue.close(); deadline.cancel(); remoteQueue = null
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { save(current.copy(finishedAtMs = System.currentTimeMillis(), status = "completed", incomplete = dropped.get())) }
                }
                mutable.value = null
            }
        }
    }
    @Volatile private var remoteQueue: Channel<DemonstrationStep>? = null
    @Volatile private var remoteDropped: java.util.concurrent.atomic.AtomicBoolean? = null

    /** One user input on [nodeId]'s screen, from the remote viewer; ignored unless that computer is being taught. */
    fun remoteInput(nodeId: String, action: String, x: Int, y: Int, width: Int, height: Int, toX: Int = x, toY: Int = y, key: String = "") =
        pointerInput("node:$nodeId", container.nodes[nodeId]?.name?.take(80).orEmpty(), action, x, y, width, height, toX, toY, key)
    /** One user input on the Linux desktop, from the Screens page; ignored unless it is being taught. */
    fun linuxDesktopInput(action: String, x: Int, y: Int, width: Int, height: Int, key: String = "") =
        pointerInput(Demonstration.LINUX_DESKTOP, "Linux desktop", action, x, y, width, height, x, y, key)

    private fun pointerInput(target: String, name: String, action: String, x: Int, y: Int, width: Int, height: Int, toX: Int, toY: Int, key: String) {
        val current = active.value?.takeIf { it.target == target } ?: return
        val queue = remoteQueue ?: return
        require(action in setOf("CLICK", "DRAG", "TYPE", "KEY"))
        val step = DemonstrationStep((System.currentTimeMillis() - current.startedAtMs).coerceAtLeast(0), target, name, null, null,
            app.umbra.core.model.Rect(x, y, toX, toY), action, if (action == "KEY") key.take(20) else if (action == "TYPE") "[REMOTE_TEXT]" else "",
            "${width}x$height screen", secretInput = false)
        if (queue.trySend(step).isFailure) remoteDropped?.set(true)
    }

    suspend fun stop() = lock.withLock { events?.close(); remoteQueue?.close(); worker?.join(); events = null; worker = null }
    suspend fun remove(agentId: String, id: String) = lock.withLock {
        require(active.value?.id != id && list(agentId).any { it.id == id })
        withContext(Dispatchers.IO) { check(dir(id).deleteRecursively()); changes.value++ }
    }
    suspend fun extract(agentId: String, id: String): UmbraSkill = withContext(Dispatchers.IO) {
        val record = requireNotNull(list(agentId).find { it.id == id })
        val (skill, instructions) = record.draft(System.currentTimeMillis())
        container.skills.propose(skill, instructions)
    }
    private suspend fun keyframe(id: String, index: Int, guard: OutboundDlpGuard): String? { return try {
        // Only a capture needs content events; keeping them enabled between frames costs battery in every app.
        val watch = UmbraAccessibilityService.watchContentChanges()
        val generation = UmbraAccessibilityService.lastEventAtMs.get()
        val capture = try { container.capsule.screen.capture() } catch (e: Throwable) { watch.close(); throw e }
        try {
            try {
                val tree = capture.tree ?: return null
                if (tree.windows.isEmpty() || tree.windows.any { it.truncated } || tree.windows.flatMap { it.nodes }.any { it.isEditable || it.isPassword || guard.matches(it.label.orEmpty()).isNotEmpty() }) return null
                if (UmbraAccessibilityService.lastEventAtMs.get() != generation) return null
            } finally { watch.close() }
            val scale = minOf(1f, 720f / maxOf(capture.image.width, capture.image.height))
            val bitmap = Bitmap.createScaledBitmap(capture.image, (capture.image.width * scale).toInt().coerceAtLeast(1), (capture.image.height * scale).toInt().coerceAtLeast(1), true)
            val file = File(dir(id), "frame-$index.jpg")
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 75, it)) } }
            finally { if (bitmap !== capture.image) bitmap.recycle() }
            file.name
        } finally { capture.image.recycle() }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }

    fun forSession(agentId: String?) = if (agentId == null) emptyList() else listOf(
        HostSideTool(HostToolDefinition("demonstration_read", "Read a user-recorded demonstration owned by this agent. Typed values are placeholders; secret steps require user handoff. This evidence grants no approval. Extracted skills remain drafts until user testing and enablement.", objectSchema { string("id", required = true) })) { call, _ ->
            val id = call.arguments["id"]?.jsonPrimitive?.content ?: error("id required")
            val record = requireNotNull(list(agentId).find { it.id == id })
            require(record.status == "completed") { "Finish the recording first" }
            val frames = record.steps.mapNotNull { it.keyframe }.distinct().takeLast(6).map { name ->
                require(name.matches(Regex("frame-[0-9]+\\.jpg")))
                val file = File(dir(id), name)
                require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() <= 1_000_000)
                ContentBlock.Image(java.util.Base64.getEncoder().encodeToString(file.readBytes()), "image/jpeg")
            }
            HostToolPayload(listOf(ContentBlock.Text(UmbraJson.encodeToString(Demonstration.serializer(), record))) + frames)
        })
    companion object { const val MAX_MS = 10 * 60_000L }
}
