package app.umbra.platform.shell

import app.umbra.core.model.action.ErrorCode
import app.umbra.core.model.observation.DisplayInfo
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.DisplayBackend
import app.umbra.platform.api.VirtualDisplaySpec

/**
 * Several ways of providing displays, best first (the privileged helper's invisible virtual displays,
 * then the system's visible overlay display). New displays come from the best one that works; a
 * display is destroyed by the provider that created it.
 */
class CompositeDisplays(private val providers: List<DisplayBackend>) : DisplayBackend {
    init {
        require(providers.isNotEmpty()) { "no display providers" }
    }

    override val name: String get() = "displays(${providers.joinToString { it.name }})"

    private suspend fun available(): List<DisplayBackend> = providers.filter { it.isAvailable() }

    override suspend fun isAvailable(): Boolean = available().isNotEmpty()

    override suspend fun list(): List<DisplayInfo> = (available().firstOrNull() ?: throw unavailable()).list()

    override suspend fun create(spec: VirtualDisplaySpec): DisplayInfo {
        var last: BackendException? = null
        for (p in available()) {
            try {
                return p.create(spec)
            } catch (e: BackendException) {
                last = e
            }
        }
        throw last ?: unavailable()
    }

    override suspend fun destroy(displayId: Int) {
        for (p in available()) {
            if (p.ownedDisplays().any { it.id == displayId }) return p.destroy(displayId)
        }
        throw BackendException(ErrorCode.PERMISSION_DENIED, "Display $displayId was not created by this app and is not removed.")
    }

    override suspend fun ownedDisplays(): List<DisplayInfo> = available().flatMap { it.ownedDisplays() }

    private fun unavailable() = BackendException(ErrorCode.BACKEND_UNAVAILABLE, "No display provider is available (${providers.joinToString { it.name }}).")
}
