package app.umbra.platform.privileged

import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.observation.DisplayInfo
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.BinaryShellResult
import app.umbra.platform.api.CapturedImage
import app.umbra.platform.api.DisplayBackend
import app.umbra.platform.api.ScreenshotBackend
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellEvent
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.ShellRequest
import app.umbra.platform.api.ShellResult
import app.umbra.platform.api.VirtualDisplaySpec
import app.umbra.platform.shell.ProcessShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** Size of one piece of streamed input; 128 KB of input is a 170 KB message. */
private const val INPUT_CHUNK = 128 * 1024

private suspend fun <T> guard(what: String, block: suspend () -> T): T = try {
    block()
} catch (e: ServerException) {
    throw when (e.code) {
        "timeout" -> BackendException(ErrorCode.TIMEOUT, "$what: ${e.message}", retryable = true)
        "unavailable", "disconnected", "unauthorized" -> BackendException(ErrorCode.BACKEND_UNAVAILABLE, "$what: ${e.message}", retryable = true)
        "not_found" -> BackendException(ErrorCode.NOT_FOUND, "$what: ${e.message}")
        "unsupported" -> BackendException(ErrorCode.UNSUPPORTED, "$what: ${e.message}")
        "permission_denied" -> BackendException(ErrorCode.PERMISSION_DENIED, "$what: ${e.message}")
        else -> BackendException(ErrorCode.COMMAND_FAILED, "$what: ${e.message}")
    }
}

private fun ShellRequest.toJson(binary: Boolean): JsonObject = jsonOf(
    "command" to command,
    "cwd" to cwd,
    "env" to env.takeIf { it.isNotEmpty() },
    "stdin" to stdin?.let { Wire.b64(it.toByteArray(Charsets.UTF_8)) },
    "timeoutMs" to timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()),
    "maxOutputBytes" to maxOutputBytes,
    "binary" to binary,
)

/** Shell commands run by the privileged helper, i.e. with its identity (the shell user, or root). */
class ServerShell(
    private val client: PrivilegedClient,
    override val identity: ShellIdentity = ShellIdentity.SHELL,
) : ShellBackend {
    override val name = "privileged_helper"

    override suspend fun isAvailable(): Boolean = client.isUp()

    override suspend fun exec(request: ShellRequest): ShellResult = guard("exec") {
        request.stdinSource?.let { return@guard execStreamingInput(request, it) }
        val r = client.call(Wire.EXEC, request.toJson(binary = false), timeoutMs = request.timeoutMs + 10_000)
        ShellResult(
            exitCode = r.int("exit") ?: -1,
            stdout = Wire.unb64(r.str("stdout").orEmpty()).toString(Charsets.UTF_8),
            stderr = Wire.unb64(r.str("stderr").orEmpty()).toString(Charsets.UTF_8).trimEnd(),
            truncated = r.bool("truncated") ?: false,
            timedOut = r.bool("timedOut") ?: false,
            durationMs = r.long("durationMs") ?: 0,
            identity = identity,
        )
    }

    /**
     * A command whose input is too big for one message (a root file system archive for `tar`): it runs as a
     * helper process and the input follows in chunks, each acknowledged before the next, so a slow
     * consumer holds the reader back instead of filling memory.
     */
    private suspend fun execStreamingInput(request: ShellRequest, openInput: () -> java.io.InputStream): ShellResult = coroutineScope {
        val started = System.currentTimeMillis()
        val (id, events) = client.openProc(request.command, request.cwd, request.env)
        val out = BoundedBytes(request.maxOutputBytes)
        val err = BoundedBytes(request.maxOutputBytes)
        var exitCode = -1
        val feeder = launch(Dispatchers.IO) {
            try {
                openInput().use { input ->
                    val buffer = ByteArray(INPUT_CHUNK)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        client.writeProc(id, if (n == buffer.size) buffer else buffer.copyOf(n))
                    }
                }
            } catch (_: ServerException) {
                // The process went away; its exit event says how.
            } catch (_: java.io.IOException) {
                // The source failed; the process sees a short input, and the caller's own wrapper knows why.
            } finally {
                withContext(NonCancellable) { try { client.closeProcInput(id) } catch (_: ServerException) {} }
            }
        }
        val finished = try {
            withTimeoutOrNull(request.timeoutMs) {
                for (event in events) {
                    event.str("out")?.let { out.add(Wire.unb64(it)) }
                    event.str("err")?.let { err.add(Wire.unb64(it)) }
                    event.int("exit")?.let {
                        exitCode = it
                        return@withTimeoutOrNull true
                    }
                }
                true
            }
        } finally {
            feeder.cancel()
            withContext(NonCancellable) { client.killProc(id) }
        }
        val timedOut = finished == null
        ShellResult(
            exitCode = if (timedOut) ProcessShell.TIMEOUT_EXIT_CODE else exitCode,
            stdout = out.text(), stderr = err.text().trimEnd(),
            truncated = out.truncated || err.truncated, timedOut = timedOut,
            durationMs = System.currentTimeMillis() - started, identity = identity,
        )
    }

    private class BoundedBytes(private val limit: Int) {
        private val bytes = java.io.ByteArrayOutputStream()
        var truncated = false
            private set

        fun add(chunk: ByteArray) {
            val room = limit - bytes.size()
            when {
                room <= 0 -> truncated = true
                chunk.size > room -> { bytes.write(chunk, 0, room); truncated = true }
                else -> bytes.write(chunk)
            }
        }

        fun text(): String = bytes.toString(Charsets.UTF_8)
    }

    override suspend fun execBytes(request: ShellRequest): BinaryShellResult = guard("exec") {
        val r = client.call(Wire.EXEC, request.copy(maxOutputBytes = maxOf(request.maxOutputBytes, 32 * 1024 * 1024)).toJson(binary = true), timeoutMs = request.timeoutMs + 10_000)
        BinaryShellResult(
            r.int("exit") ?: -1, Wire.unb64(r.str("stdout").orEmpty()),
            Wire.unb64(r.str("stderr").orEmpty()).toString(Charsets.UTF_8).trimEnd(), r.bool("timedOut") ?: false,
        )
    }

    override fun stream(request: ShellRequest): Flow<ShellEvent> = channelFlow {
        val (id, channel) = guard("stream") { client.openStream(Wire.STREAM_START, request.toJson(binary = false)) }
        try {
            for (obj in channel) {
                obj.str("out")?.let { send(ShellEvent.Out(it)) }
                obj.str("err")?.let { send(ShellEvent.Err(it)) }
                obj.int("exit")?.let {
                    send(ShellEvent.Exit(it))
                    return@channelFlow
                }
            }
        } finally {
            withContext(NonCancellable) { client.endStream(id) }
        }
    }
}

/**
 * Virtual displays created by the helper: real, invisible displays any app can be launched on
 * (spec §9). Listing shows every display of the device, as [systemDisplays] reports them.
 */
class ServerDisplays(
    private val client: PrivilegedClient,
    private val systemDisplays: suspend () -> List<DisplayInfo>,
) : DisplayBackend {
    override val name = "virtual_displays"

    override suspend fun isAvailable(): Boolean = client.isUp() && client.serverInfo?.bool("virtualDisplays") == true

    override suspend fun list(): List<DisplayInfo> = systemDisplays()

    override suspend fun create(spec: VirtualDisplaySpec): DisplayInfo = guard("display.create") {
        val r = client.call(
            Wire.DISPLAY_CREATE,
            jsonOf("name" to spec.name, "width" to spec.width, "height" to spec.height, "dpi" to spec.densityDpi, "owner" to spec.ownerSessionId),
            timeoutMs = 30_000,
        )
        DisplayInfo(
            id = r.int("displayId") ?: throw ServerException("error", "no display id in the reply"),
            name = r.str("name") ?: spec.name, width = r.int("width") ?: spec.width, height = r.int("height") ?: spec.height,
            densityDpi = r.int("dpi") ?: spec.densityDpi, isVirtual = true, ownerPackage = "com.android.shell",
        )
    }

    override suspend fun destroy(displayId: Int) {
        guard("display.destroy") { client.call(Wire.DISPLAY_DESTROY, jsonOf("displayId" to displayId)) }
    }

    override suspend fun ownedDisplays(): List<DisplayInfo> = guard("display.list") {
        val r = client.call(Wire.DISPLAY_LIST)
        (r.arr("displays") ?: return@guard emptyList()).map { e ->
            val d = e as JsonObject
            DisplayInfo(
                id = d.int("displayId") ?: -1, name = d.str("name"), width = d.int("width") ?: 0, height = d.int("height") ?: 0,
                densityDpi = d.int("dpi") ?: 0, isVirtual = true, ownerPackage = "com.android.shell",
            )
        }
    }
}

/** Screenshots of the helper's own virtual displays, read from the surface they render into. */
class ServerScreenshots(private val client: PrivilegedClient) : ScreenshotBackend {
    override val name = "virtual_display_capture"

    override suspend fun isAvailable(): Boolean = client.isUp()

    override suspend fun capture(displayId: Int?): CapturedImage {
        val id = displayId ?: throw BackendException(ErrorCode.UNSUPPORTED, "the helper captures only its own virtual displays")
        return guard("display.screenshot") {
            val r = client.call(Wire.DISPLAY_SCREENSHOT, jsonOf("displayId" to id, "format" to "png"), timeoutMs = 20_000)
            CapturedImage(Wire.unb64(r.str("data").orEmpty()), r.str("mime") ?: "image/png", r.int("width") ?: 0, r.int("height") ?: 0)
        }
    }
}
