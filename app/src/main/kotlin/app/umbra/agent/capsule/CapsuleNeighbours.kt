package app.umbra.agent.capsule

import app.umbra.agent.data.CapsulePlacement
import app.umbra.platform.android.TopOverlay

/**
 * Other "capsule" apps (Material Capsule, dynamicSpot, Dynamic Bar, notch apps) draw their own pill around the camera
 * and expand it for notifications and music. Two capsules on one camera fight over the same pixels and touches, so
 * Umbra's steps aside: it notices such an app by its enabled accessibility service (by label, so no package list goes
 * stale) or by its window over the camera, moves to the top corner when placement is Auto, and fades out of the way
 * while the other one is expanded.
 */
internal object CapsuleNeighbours {
    private val ISLAND = Regex("(?i)capsule|dynamic\\s*(island|spot|bar|ui)|island|notch")

    /** Other enabled accessibility services that are capsule apps, as (package, label). [enabled] is the secure setting. */
    fun services(enabled: String?, own: String, label: (String) -> String?): List<Pair<String, String>> =
        enabled.orEmpty().split(':')
            .map { it.substringBefore('/').trim() }
            .filter { it.isNotEmpty() && it != own }
            .distinct()
            .mapNotNull { pkg ->
                val name = label(pkg)?.takeIf { it.isNotBlank() } ?: pkg
                if (ISLAND.containsMatchIn(name) || ISLAND.containsMatchIn(pkg)) pkg to name else null
            }

    enum class Island { NONE, RESTING, EXPANDED }

    /**
     * What another app shows over the camera: nothing, its resting pill, or an expanded island (taller than the
     * status bar's band or most of the screen wide). Windows away from the camera's column or low on the screen are
     * someone else's business.
     */
    fun island(overlays: List<TopOverlay>, screenWidth: Int, statusTop: Int, cameraCenterX: Int?): Island {
        val centre = cameraCenterX ?: (screenWidth / 2)
        val band = maxOf(statusTop, 24)
        val reach = (screenWidth * 0.35f).toInt()
        val near = overlays.filter { o ->
            val b = o.bounds
            b.top <= band * 2 && b.left <= centre + reach && b.right >= centre - reach && b.width > 0 && b.height > 0
        }
        if (near.isEmpty()) return Island.NONE
        return if (near.any { it.bounds.height > band * 2.2f || it.bounds.width > screenWidth * 0.7f }) Island.EXPANDED else Island.RESTING
    }

    /** The corner instead of the camera: chosen by the user, or Auto with a neighbour present. */
    fun corner(placement: CapsulePlacement, neighbour: Boolean): Boolean =
        placement == CapsulePlacement.CORNER || (placement == CapsulePlacement.AUTO || placement == CapsulePlacement.CENTRE) && neighbour

    /** A plain pill at the top centre: chosen by the user, and only while no neighbour holds that space. */
    fun centre(placement: CapsulePlacement, neighbour: Boolean): Boolean = placement == CapsulePlacement.CENTRE && !neighbour
}
