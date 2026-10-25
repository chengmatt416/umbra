package app.umbra.agent.capsule

import app.umbra.agent.data.CapsulePlacement
import app.umbra.core.model.Rect
import app.umbra.platform.android.TopOverlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapsuleNeighboursTest {
    private val labels = mapOf(
        "com.pryshedko.materialcapsule" to "Material Capsule",
        "com.jamworks.dynamicspot" to "dynamicSpot",
        "com.agilebits.onepassword" to "1Password",
    )

    @Test
    fun capsuleAppsAreFoundByLabelOrPackageAndUmbraIsNotItsOwnNeighbour() {
        val enabled = "com.pryshedko.materialcapsule/.CapsuleService:app.umbra.agent/app.umbra.platform.android.UmbraAccessibilityService:" +
            "com.agilebits.onepassword/.Fill:com.jamworks.dynamicspot/.Service::"
        val found = CapsuleNeighbours.services(enabled, "app.umbra.agent", labels::get)
        assertEquals(listOf("Material Capsule", "dynamicSpot"), found.map { it.second })
        assertEquals(emptyList(), CapsuleNeighbours.services(null, "app.umbra.agent", labels::get))
        // No label available: the package name still tells.
        assertEquals(listOf("com.example.dynamicisland"), CapsuleNeighbours.services("com.example.dynamicisland/.S", "app.umbra.agent") { null }.map { it.second })
    }

    @Test
    fun aRestingPillOverTheCameraIsToleratedAndAnExpandedOneIsYieldedTo() {
        val width = 1080; val status = 110; val camera = 540
        fun at(l: Int, t: Int, r: Int, b: Int) = TopOverlay("other", Rect(l, t, r, b))
        assertEquals(CapsuleNeighbours.Island.NONE, CapsuleNeighbours.island(emptyList(), width, status, camera))
        assertEquals(CapsuleNeighbours.Island.RESTING, CapsuleNeighbours.island(listOf(at(380, 10, 700, 100)), width, status, camera))
        assertEquals(CapsuleNeighbours.Island.EXPANDED, CapsuleNeighbours.island(listOf(at(40, 10, 1040, 420)), width, status, camera))
        // A window low on the screen or far from the camera's column is not a capsule over the camera.
        assertEquals(CapsuleNeighbours.Island.NONE, CapsuleNeighbours.island(listOf(at(380, 600, 700, 700)), width, status, camera))
        assertEquals(CapsuleNeighbours.Island.NONE, CapsuleNeighbours.island(listOf(at(0, 10, 120, 100)), width, status, camera))
    }

    @Test
    fun autoMovesToTheCornerOnlyWithANeighbourAndManualChoicesWin() {
        assertFalse(CapsuleNeighbours.corner(CapsulePlacement.AUTO, neighbour = false))
        assertTrue(CapsuleNeighbours.corner(CapsulePlacement.AUTO, neighbour = true))
        assertFalse(CapsuleNeighbours.corner(CapsulePlacement.CAMERA, neighbour = true))
        assertTrue(CapsuleNeighbours.corner(CapsulePlacement.CORNER, neighbour = false))
        // Top centre: centred while alone, moves to the corner beside another capsule app.
        assertTrue(CapsuleNeighbours.centre(CapsulePlacement.CENTRE, neighbour = false))
        assertFalse(CapsuleNeighbours.corner(CapsulePlacement.CENTRE, neighbour = false))
        assertFalse(CapsuleNeighbours.centre(CapsulePlacement.CENTRE, neighbour = true))
        assertTrue(CapsuleNeighbours.corner(CapsulePlacement.CENTRE, neighbour = true))
        assertFalse(CapsuleNeighbours.centre(CapsulePlacement.AUTO, neighbour = false))
    }
}
