package app.umbra.tools

import app.umbra.core.model.Rect
import app.umbra.core.model.action.ActionStatus
import app.umbra.core.model.action.Environment
import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.action.ObserveLevel
import app.umbra.core.model.action.Operation
import app.umbra.core.model.action.Verification
import app.umbra.core.model.observation.DisplayInfo
import app.umbra.core.model.observation.ForegroundInfo
import app.umbra.core.model.observation.Marker
import app.umbra.core.model.observation.UiSnapshot
import app.umbra.core.model.observation.UiTree
import app.umbra.core.model.observation.UiTreeRenderer
import app.umbra.platform.api.CapturedImage
import app.umbra.platform.vision.ImageFormat
import app.umbra.platform.vision.ImageOps
import app.umbra.platform.vision.PixelImage
import app.umbra.platform.vision.SetOfMarks
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Observing and waiting: what the agent reads before it acts (spec §15 unified observation, §7 visual fallback). */
internal class ObservationExecutor(private val env: PipelineEnv) {
    private val backends get() = env.backends

    suspend fun execute(c: OpContext): OpOutcome = when (val op = c.request.operation) {
        is Operation.Observe -> observe(c, op)
        is Operation.Screenshot -> screenshot(c, op)
        is Operation.Find -> find(c, op)
        is Operation.WaitFor -> waitFor(c, op)
        is Operation.Verify -> verify(c, op)
        else -> OpOutcome.unsupported("${op.name} is not an observation")
    }

    // ---- observe ----

    private suspend fun foreground(c: OpContext): List<ForegroundInfo> {
        val fromApps = backends.apps?.takeIf { it.isAvailable() }?.let { a -> tryOrNull { a.foreground(c.displayId) } }
        if (!fromApps.isNullOrEmpty()) return fromApps
        return c.snapshot?.windows?.filter { it.isActive && (c.displayId == null || it.displayId == c.displayId) }
            ?.map { ForegroundInfo(it.displayId, it.packageName, null, it.title) }.orEmpty()
    }

    private suspend fun displays(): List<DisplayInfo> =
        backends.displays?.takeIf { it.isAvailable() }?.let { d -> tryOrNull { d.list() } }.orEmpty()

    /** The snapshot, re-taken a few times while the foreground app has not drawn its window yet (cold start, transitions). */
    private suspend fun settledSnapshot(c: OpContext, fg: List<ForegroundInfo>): UiSnapshot? {
        var snapshot = c.snapshot ?: return null
        val wanted = fg.mapNotNull { it.packageName }.filter { it != "com.android.systemui" }.toSet()
        repeat(4) {
            val present = snapshot.windows.any { w -> w.packageName in wanted && w.nodes.count { it.isVisible } > 3 && (c.displayId == null || w.displayId == c.displayId) }
            if (wanted.isEmpty() || present) return snapshot
            delay(600)
            snapshot = backends.ui(c.environment).available().firstNotNullOfOrNull { ui -> tryOrNull { ui.snapshot(app.umbra.platform.api.SnapshotOptions(displayId = c.displayId)) } } ?: return snapshot
        }
        return snapshot
    }

    private suspend fun observe(c: OpContext, op: Operation.Observe): OpOutcome {
        // The Linux desktop has no foreground app and no Android displays; describing the phone's would only mislead.
        val linux = c.environment == app.umbra.core.model.action.Environment.LINUX_GUI
        val fg = if (linux) emptyList() else foreground(c)
        val snapshot = settledSnapshot(c, fg)
        val displays = if (linux) emptyList() else displays()
        val level = op.level
        val trees = snapshot?.windows
            ?.filter { level == ObserveLevel.FULL || c.displayId == null || it.displayId == c.displayId }
            ?.sortedWith(compareByDescending<UiTree> { it.isActive }.thenByDescending { it.layer })
            .orEmpty()

        val text = StringBuilder()
        if (linux) text.append("Linux desktop\n")
        for (f in fg) {
            text.append("Display ${f.displayId}: ").append(f.activity ?: f.packageName ?: "unknown")
            f.windowTitle?.takeIf { it.isNotBlank() }?.let { text.append(" \"").append(it).append('"') }
            text.append('\n')
        }
        if (fg.isEmpty() && !linux) text.append("Foreground app: unknown\n")
        if (displays.size > 1) {
            text.append("Displays: ").append(
                displays.joinToString("; ") { d ->
                    "${d.id}${if (d.isDefault) " (default)" else ""} ${d.width}x${d.height}${d.name?.let { " \"$it\"" } ?: ""}${env.leases.holder(d.id)?.let { " leased by $it" } ?: ""}"
                },
            ).append('\n')
        }
        val interactive = trees.sumOf { it.interactive().size }
        if (snapshot == null) {
            text.append(
                if (linux) "The desktop could not be read: ${c.snapshotError ?: "no desktop session"}. Check that the Desktop apps add-on is installed, or use a screenshot.\n"
                else "The screen tree could not be read: ${c.snapshotError ?: "no UI backend is connected"}. Enable the accessibility service (or Shizuku/root for UiAutomation), or use a screenshot.\n",
            )
        } else {
            text.append("$interactive interactive element(s) on ${trees.size} window(s).\n")
        }

        var unchanged = false
        if (level != ObserveLevel.BRIEF && snapshot != null) {
            val key = c.displayId ?: 0
            val sig = uiSignature(UiSnapshot(trees), null).first
            val previous = c.state.lastObservation.put(key, sig)
            unchanged = previous == sig
            if (unchanged) {
                text.append("The screen is unchanged since your last observation; element ids from it are still valid.\n")
            } else {
                var budget = op.maxNodes.coerceIn(10, 1_000)
                for (t in trees) {
                    if (budget <= 0) break
                    c.state.memory.remember(t)
                    val rendered = UiTreeRenderer.render(t, UiTreeRenderer.Options(maxNodes = budget, onlyInteractive = op.onlyInteractive))
                    text.append(rendered).append('\n')
                    budget -= rendered.count { it == '\n' }.coerceAtLeast(1)
                }
            }
        }
        if (level == ObserveLevel.FULL) {
            backends.sysinfo?.takeIf { it.isAvailable() }?.let { s ->
                tryOrNull { s.deviceState() }?.let { d ->
                    text.append("Device: battery ${d.batteryPct ?: "?"}%${if (d.charging == true) " charging" else ""}, thermal ${d.thermalStatus ?: "?"}, memory free ${d.memAvailMb ?: "?"} MB, network ${d.networkType ?: "?"}\n")
                }
            }
        }

        val images = if (op.includeScreenshot) listOfNotNull(captureImage(c, annotate = false, maxEdge = env.config.maxScreenshotEdge)?.image) else emptyList()
        val data = buildJsonObject {
            put("interactive", interactive)
            put("unchanged", unchanged)
            put("foreground", buildJsonArray { fg.forEach { f -> add(buildJsonObject { put("display", f.displayId); f.packageName?.let { put("package", it) }; f.activity?.let { put("activity", it) } }) } })
            put("windows", buildJsonArray { trees.forEach { t -> add(buildJsonObject { put("id", t.windowId); put("display", t.displayId); t.packageName?.let { put("package", it) }; put("nodes", t.nodes.size); put("active", t.isActive) }) } })
        }
        return OpOutcome.ok(text.toString().trimEnd(), data, backend = c.primaryUi?.name, images = images)
    }

    // ---- find ----

    private fun find(c: OpContext, op: Operation.Find): OpOutcome {
        val snapshot = c.snapshot ?: return OpOutcome.fail(
            ErrorCode.BACKEND_UNAVAILABLE,
            "The screen could not be read (${c.snapshotError ?: "no UI backend is connected"}), so it cannot be searched. Enable the accessibility service, or use a screenshot.",
            retryable = c.snapshotError != null,
        )
        val trees = snapshot.windows.filter { c.displayId == null || it.displayId == c.displayId }
        val matches = trees.flatMap { t -> t.find(op.query).filter { it.isVisible }.map { it to t } }
        trees.forEach { c.state.memory.remember(it) }
        if (matches.isEmpty()) {
            val e = env.targeting.notFoundError(op.query, snapshot, c.displayId)
            return OpOutcome.fail(ErrorCode.NOT_FOUND, e.message, retryable = true)
        }
        val shown = matches.take(op.limit.coerceIn(1, 100))
        val text = "${matches.size} match(es) for ${env.targeting.describeQuery(op.query)}:\n" + shown.joinToString("\n") { (n, _) ->
            "  [${n.id}] ${env.targeting.describe(n)} (${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom})" +
                buildList {
                    if (n.isClickable) add("click"); if (n.isEditable) add("edit"); if (n.isScrollable) add("scroll")
                    if (!n.isEnabled) add("disabled"); if (n.has(app.umbra.core.model.observation.NodeFlag.CHECKED)) add("checked")
                }.let { if (it.isEmpty()) "" else " {${it.joinToString(",")}}" }
        } + if (matches.size > shown.size) "\n  … ${matches.size - shown.size} more" else ""
        return OpOutcome.ok(text, buildJsonObject { put("count", matches.size) }, backend = c.primaryUi?.name)
    }

    // ---- screenshots ----

    class Shot(val image: ImagePayload, val markers: List<Marker>, val scale: Float, val width: Int, val height: Int, val shownWidth: Int, val shownHeight: Int)

    private suspend fun capture(environment: Environment, displayId: Int?): CapturedImage? {
        var failure: Exception? = null
        for (s in backends.screenshots(environment).available()) {
            try {
                return s.capture(displayId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        if (failure != null) throw failure
        return null
    }

    /** Capture, optionally annotate, downscale and encode within the frame budget. Null when no screenshot is possible. */
    suspend fun captureImage(c: OpContext, annotate: Boolean, maxEdge: Int, region: Rect? = null, grid: Boolean = false): Shot? {
        val codec = env.codec ?: return null
        val shot = capture(c.environment, c.displayId) ?: return null
        var full = codec.decode(shot.bytes)
        val offsetX = region?.left?.coerceIn(0, full.width - 1) ?: 0
        val offsetY = region?.top?.coerceIn(0, full.height - 1) ?: 0
        if (region != null) full = full.crop(region)

        val tree = c.snapshot?.activeWindow(c.displayId)
        val markers = if (annotate && tree != null) {
            SetOfMarks.markersFor(tree, shot.width, shot.height).mapNotNull { m ->
                val b = m.bounds.let { Rect(it.left - offsetX, it.top - offsetY, it.right - offsetX, it.bottom - offsetY) }
                b.intersection(Rect(0, 0, full.width, full.height))?.takeIf { it.width >= 6 && it.height >= 6 }?.let { m.copy(bounds = it) }
            }
        } else {
            emptyList()
        }

        var edge = maxEdge.coerceIn(256, env.config.maxScreenshotEdge)
        var quality = env.config.jpegQuality
        while (true) {
            val small = ImageOps.downscale(full, edge)
            val scale = small.width.toFloat() / full.width
            var out: PixelImage = small
            if (annotate && markers.isNotEmpty()) out = SetOfMarks.draw(out, markers, scale)
            if (grid || (annotate && markers.isEmpty())) out = SetOfMarks.drawGrid(out, scale, env.config.gridStepPx, full.width, full.height)
            val bytes = codec.encode(out, ImageFormat.JPEG, quality)
            if (bytes.size <= env.config.maxImageBytes || (edge <= 400 && quality <= 40)) {
                if (annotate && markers.isNotEmpty()) c.state.memory.rememberMarks(c.displayId ?: 0, MarkSet(markers.map { m -> m.copy(bounds = Rect(m.bounds.left + offsetX, m.bounds.top + offsetY, m.bounds.right + offsetX, m.bounds.bottom + offsetY)) }, scale))
                if (annotate && tree != null) c.state.memory.remember(tree)
                return Shot(ImagePayload(bytes, ImageFormat.JPEG.mime, "display ${c.displayId ?: 0}"), markers, scale, full.width, full.height, out.width, out.height)
            }
            // Over budget: first lower the quality, then the size.
            if (quality > 50) quality -= 15 else edge = (edge * 0.8f).roundToInt()
        }
    }

    private suspend fun screenshot(c: OpContext, op: Operation.Screenshot): OpOutcome {
        if (env.codec == null) return OpOutcome.unsupported("Screenshots are not available: no image codec is configured.")
        val shot = try {
            captureImage(c, op.annotate, op.maxEdge, op.region, op.grid)
        } catch (e: app.umbra.platform.api.BackendException) {
            return OpOutcome.fail(e.code, "Could not take a screenshot: ${e.message}", e.retryable)
        } ?: return OpOutcome.unavailable("No screenshot backend is available. Screenshots need the accessibility service, Shizuku or root.")

        val text = buildString {
            append("Screenshot of display ${c.displayId ?: 0} (${shot.width}x${shot.height}")
            if (shot.shownWidth != shot.width) append(", shown at ${shot.shownWidth}x${shot.shownHeight}, scale ${"%.3f".format(shot.scale)}")
            append(").")
            if (op.annotate) {
                if (shot.markers.isEmpty()) {
                    append(
                        if (c.snapshot == null) "\nThe screen tree could not be read (${c.snapshotError ?: "no UI backend"}), so nothing could be marked; a coordinate grid is drawn instead."
                        else "\nThe screen exposes no interactive elements to mark; a coordinate grid is drawn instead.",
                    )
                    append(" Read x/y off the grid (display pixels) and tap with x and y.")
                } else {
                    append("\nMarks: ").append(shot.markers.joinToString(" | ") { m -> "${m.index} ${m.label?.let { "\"$it\" " } ?: ""}[${m.nodeId}]" })
                    append("\nTap a mark with mark=N (or use the element id).")
                }
            } else if (op.grid) {
                append("\nCoordinate grid drawn every ${env.config.gridStepPx} display pixels; labels are display coordinates.")
            }
        }
        val data = buildJsonObject {
            put("width", shot.width); put("height", shot.height); put("scale", shot.scale.toDouble())
            put("markers", JsonArray(shot.markers.map { m -> buildJsonObject { put("index", m.index); m.nodeId?.let { put("node", it) }; m.label?.let { put("label", it) } } }))
        }
        return OpOutcome.ok(text, data, backend = "screenshot", images = listOf(shot.image))
    }

    // ---- waiting and verifying ----

    private suspend fun waitFor(c: OpContext, op: Operation.WaitFor): OpOutcome {
        val baseline = env.verifier.baseline(op.expect, c.environment, c.displayId)
        val ctx = VerifyContext(c.environment, c.displayId, baseline, c.startedAtMs)
        val spec = Verification(op.expect, timeoutMs = c.request.timeoutMs, pollMs = 200, required = true)
        val r = env.verifier.verify(spec, ctx)
        return if (r.passed) OpOutcome.ok("Condition met after ${r.durationMs}ms: ${r.checks.joinToString("; ") { it.evidence }}", verificationData(r.checks.map { it.evidence }))
        else OpOutcome.fail(ErrorCode.TIMEOUT, "Timed out after ${c.request.timeoutMs}ms waiting: ${r.checks.filter { !it.passed }.joinToString("; ") { it.evidence }}", retryable = true, status = ActionStatus.TIMEOUT)
    }

    private suspend fun verify(c: OpContext, op: Operation.Verify): OpOutcome {
        val baseline = env.verifier.baseline(op.expect, c.environment, c.displayId)
        val ctx = VerifyContext(c.environment, c.displayId, baseline, c.startedAtMs)
        val r = env.verifier.verify(Verification(op.expect, timeoutMs = c.request.verify.timeoutMs.coerceAtMost(c.request.timeoutMs), pollMs = 200, required = true), ctx)
        val text = r.checks.joinToString("\n") { (if (it.passed) "PASS " else "FAIL ") + it.expectation + ": " + it.evidence }
        return if (r.passed) OpOutcome.ok("Verified:\n$text", verificationData(r.checks.map { it.evidence }))
        else OpOutcome.fail(ErrorCode.VERIFICATION_FAILED, "Not verified:\n$text", retryable = true)
    }

    private fun verificationData(evidence: List<String>): JsonObject = buildJsonObject { put("evidence", JsonArray(evidence.map(::JsonPrimitive))) }
}
