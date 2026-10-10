package app.umbra.platform.shell

import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.observation.DisplayInfo
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.DisplayBackend
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.VirtualDisplaySpec
import app.umbra.platform.api.exec
import kotlinx.coroutines.delay

/**
 * Secondary displays made with the system's own "simulate secondary displays" setting
 * (`overlay_display_devices`). It needs nothing but shell access, so it is the fallback when the
 * privileged server (which can create real, invisible virtual displays) is not running.
 *
 * Limits: the system shows each one as a window on top of the main screen, and it recreates all
 * overlay displays whenever the setting changes, so only one is supported at a time.
 */
class OverlayDisplays(private val shell: ShellBackend, private val pollMs: Long = 250) : DisplayBackend {
    override val name = "overlay_displays"

    override suspend fun isAvailable() = shell.isAvailable()

    override suspend fun list(): List<DisplayInfo> {
        val r = shell.exec("cmd display get-displays", timeoutMs = 15_000, maxOutputBytes = 512 * 1024)
        if (!r.ok) throw BackendException(ErrorCode.COMMAND_FAILED, "cmd display get-displays failed: ${r.combined.take(200)}")
        return DisplayParsers.parse(r.stdout)
    }

    override suspend fun ownedDisplays(): List<DisplayInfo> = list().filter { it.uniqueId?.startsWith("overlay:") == true }

    override suspend fun create(spec: VirtualDisplaySpec): DisplayInfo {
        val existing = ownedDisplays()
        if (existing.isNotEmpty()) {
            throw BackendException(
                ErrorCode.UNSUPPORTED,
                "An overlay display (${existing.first().id}) already exists; only one is supported without the privileged display server. Destroy it first.",
            )
        }
        val before = list().map { it.id }.toSet()
        val entry = "${spec.width}x${spec.height}/${spec.densityDpi}"
        val r = shell.exec("settings put global overlay_display_devices ${app.umbra.platform.api.ShellInput.quote(entry)}")
        if (!r.ok) throw BackendException(ErrorCode.PERMISSION_DENIED, "Could not create a display: ${r.combined.take(200)}")
        return waitFor(timeoutMs = 10_000) { list().firstOrNull { it.id !in before && it.uniqueId?.startsWith("overlay:") == true } }
            ?: throw BackendException(ErrorCode.TIMEOUT, "The system did not create the display within 10 s.", retryable = true)
    }

    override suspend fun destroy(displayId: Int) {
        val target = list().firstOrNull { it.id == displayId } ?: throw BackendException(ErrorCode.NOT_FOUND, "no display $displayId")
        if (target.uniqueId?.startsWith("overlay:") != true) {
            throw BackendException(ErrorCode.PERMISSION_DENIED, "Display $displayId (${target.name}) was not created by this app and is not removed.")
        }
        shell.exec("settings put global overlay_display_devices ''")
        waitFor(timeoutMs = 10_000) { if (list().none { it.id == displayId }) true else null }
            ?: throw BackendException(ErrorCode.TIMEOUT, "The system did not remove display $displayId within 10 s.", retryable = true)
    }

    private suspend fun <T : Any> waitFor(timeoutMs: Long, check: suspend () -> T?): T? {
        var waited = 0L
        while (true) {
            check()?.let { return it }
            if (waited >= timeoutMs) return null
            delay(pollMs)
            waited += pollMs
        }
    }
}
