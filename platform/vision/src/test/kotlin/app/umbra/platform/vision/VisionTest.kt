package app.umbra.platform.vision

import app.umbra.core.model.Rect
import app.umbra.core.model.observation.NodeFlag
import app.umbra.core.model.observation.UiNode
import app.umbra.core.model.observation.UiTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VisionTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()

    // ---- PixelImage ----

    @Test
    fun pixelAccessAndCopyAreIndependent() {
        val a = PixelImage.filled(4, 3, white)
        val b = a.copy()
        b[1, 1] = red
        assertEquals(white, a[1, 1])
        assertEquals(red, b[1, 1])
        assertFailsWith<IllegalArgumentException> { PixelImage(2, 2, IntArray(3)) }
        assertFailsWith<IllegalArgumentException> { PixelImage(0, 2, IntArray(0)) }
    }

    @Test
    fun fillRectClipsToTheImageAndBlendsAlpha() {
        val img = PixelImage.filled(10, 10, black)
        img.fillRect(-5, -5, 3, 3, red)
        assertEquals(red, img[0, 0])
        assertEquals(red, img[2, 2])
        assertEquals(black, img[3, 3])

        val half = PixelImage.filled(2, 2, black)
        half.fillRect(0, 0, 2, 2, 0x80FFFFFF.toInt())
        val gray = half[0, 0] and 0xFF
        assertTrue(gray in 126..130, "50% white over black is mid gray, was $gray")
    }

    @Test
    fun strokeDrawsOnlyTheBorder() {
        val img = PixelImage.filled(20, 20, black)
        img.strokeRect(2, 2, 12, 12, 2, red)
        assertEquals(red, img[2, 2])
        assertEquals(red, img[11, 11])
        assertEquals(red, img[7, 2])
        assertEquals(black, img[7, 7], "the inside stays untouched")
        assertEquals(black, img[12, 12])
    }

    @Test
    fun cropReturnsTheRequestedRegion() {
        val img = PixelImage(4, 4, IntArray(16) { it })
        val c = img.crop(Rect(1, 1, 3, 3))
        assertEquals(2, c.width)
        assertEquals(listOf(5, 6, 9, 10), c.pixels.toList())
        assertEquals(1, img.crop(Rect(10, 10, 20, 20)).width, "out-of-range crops are clamped to a pixel, not an exception")
    }

    // ---- ImageOps ----

    @Test
    fun scaleFactorOnlyShrinks() {
        assertEquals(1f, ImageOps.scaleFor(800, 600, 1280))
        assertEquals(0.5f, ImageOps.scaleFor(2560, 1440, 1280))
        assertEquals(0.5f, ImageOps.scaleFor(1440, 2560, 1280))
    }

    @Test
    fun downscaleAveragesAreaAndKeepsAspect() {
        val img = PixelImage(4, 2, intArrayOf(black, white, black, white, black, white, black, white))
        val small = ImageOps.resize(img, 2, 1)
        assertEquals(2, small.width)
        val v = small[0, 0] and 0xFF
        assertTrue(v in 126..129, "average of black and white is gray, was $v")

        val big = PixelImage.filled(2000, 1000, red)
        val out = ImageOps.downscale(big, 1000)
        assertEquals(1000, out.width)
        assertEquals(500, out.height)
        assertEquals(red, out[10, 10])
        assertTrue(ImageOps.downscale(big, 5000) === big, "no work when it already fits")
    }

    @Test
    fun diffRatioMeasuresHowMuchChanged() {
        val a = PixelImage.filled(100, 100, white)
        val same = PixelImage.filled(100, 100, white)
        assertEquals(0f, ImageOps.diffRatio(a, same))

        val b = a.copy()
        b.fillRect(0, 0, 50, 100, black)
        assertEquals(0.5f, ImageOps.diffRatio(a, b), 0.02f)

        assertEquals(1f, ImageOps.diffRatio(a, PixelImage.filled(50, 50, white)), "different sizes are entirely different")

        val faint = PixelImage.filled(100, 100, 0xFFF8F8F8.toInt())
        assertEquals(0f, ImageOps.diffRatio(a, faint), "tiny colour noise is below the threshold")
    }

    @Test
    fun lumaTellsLightFromDarkScreens() {
        assertTrue(ImageOps.meanLuma(PixelImage.filled(50, 50, white)) > 250)
        assertTrue(ImageOps.meanLuma(PixelImage.filled(50, 50, black)) < 5)
    }

    // ---- set of marks ----

    private fun node(id: String, bounds: Rect, label: String? = null, clickable: Boolean = true, editable: Boolean = false, depth: Int = 1, enabled: Boolean = true) =
        UiNode(
            id = id, depth = depth, text = label, bounds = bounds,
            flags = buildSet {
                add(NodeFlag.VISIBLE)
                if (enabled) add(NodeFlag.ENABLED)
                if (clickable) add(NodeFlag.CLICKABLE)
                if (editable) add(NodeFlag.EDITABLE)
            },
        )

    private fun tree(vararg nodes: UiNode) = UiTree(displayId = 0, windowId = 1, nodes = nodes.toList())

    @Test
    fun marksAreNumberedInReadingOrderFromOne() {
        val t = tree(
            node("1:2", Rect(600, 100, 900, 200), "Right"),
            node("1:1", Rect(100, 100, 400, 200), "Left"),
            node("1:3", Rect(100, 500, 400, 600), "Lower"),
        )
        val marks = SetOfMarks.markersFor(t, 1080, 2400)
        assertEquals(listOf(1, 2, 3), marks.map { it.index })
        assertEquals(listOf("Left", "Right", "Lower"), marks.map { it.label })
        assertEquals(listOf("1:1", "1:2", "1:3"), marks.map { it.nodeId })
    }

    @Test
    fun onlyVisibleEnabledInteractiveSizableNodesAreMarked() {
        val t = tree(
            node("1:1", Rect(0, 0, 200, 100), "Ok"),
            node("1:2", Rect(0, 200, 200, 300), "Static", clickable = false),
            node("1:3", Rect(0, 400, 200, 500), "Disabled", enabled = false),
            node("1:4", Rect(0, 600, 5, 605), "Tiny"),
            node("1:5", Rect(2000, 2000, 2200, 2100), "Offscreen"),
        )
        assertEquals(listOf("Ok"), SetOfMarks.markersFor(t, 1080, 2400).map { it.label })
    }

    @Test
    fun duplicateBoxesKeepTheLabelledOne() {
        val t = tree(
            node("1:1", Rect(100, 100, 400, 200), label = null),
            node("1:2", Rect(100, 100, 400, 200), label = "Sign in", depth = 2),
        )
        val marks = SetOfMarks.markersFor(t, 1080, 2400)
        assertEquals(1, marks.size)
        assertEquals("Sign in", marks.single().label)
    }

    @Test
    fun aListThatWrapsMarkedRowsIsNotMarkedItself() {
        // The Settings screen: a scrolling container whose first row's title would otherwise name it.
        val list = UiNode(id = "1:0.0", depth = 1, bounds = Rect(0, 100, 1080, 2300), flags = setOf(NodeFlag.VISIBLE, NodeFlag.ENABLED, NodeFlag.SCROLLABLE))
        val first = UiNode(id = "1:0.0.1", depth = 2, text = "Network & internet", bounds = Rect(0, 300, 1080, 500), flags = setOf(NodeFlag.VISIBLE, NodeFlag.ENABLED, NodeFlag.CLICKABLE))
        val second = UiNode(id = "1:0.0.2", depth = 2, text = "Apps", bounds = Rect(0, 500, 1080, 700), flags = setOf(NodeFlag.VISIBLE, NodeFlag.ENABLED, NodeFlag.CLICKABLE))
        val marks = SetOfMarks.markersFor(tree(list, first, second), 1080, 2400)
        assertEquals(listOf("Network & internet", "Apps"), marks.map { it.label })
        assertEquals(listOf("1:0.0.1", "1:0.0.2"), marks.map { it.nodeId })
    }

    @Test
    fun aScrollerWithNothingMarkedInsideKeepsItsOwnMark() {
        val canvas = UiNode(id = "1:0.0", depth = 1, bounds = Rect(0, 100, 1080, 2300), flags = setOf(NodeFlag.VISIBLE, NodeFlag.ENABLED, NodeFlag.SCROLLABLE))
        val marks = SetOfMarks.markersFor(tree(canvas), 1080, 2400)
        assertEquals(listOf("1:0.0"), marks.map { it.nodeId })
    }

    @Test
    fun theMarkerBoxIsClippedToTheScreen() {
        val t = tree(node("1:1", Rect(-50, 100, 300, 400), "Edge"))
        assertEquals(Rect(0, 100, 300, 400), SetOfMarks.markersFor(t, 1080, 2400).single().bounds)
    }

    @Test
    fun tooManyElementsAreCappedKeepingTheMostInformative() {
        val nodes = (1..100).map { i ->
            node("1:$i", Rect(10, 20 * i, 200, 20 * i + 15), label = if (i % 2 == 0) "Item $i" else null)
        }
        val marks = SetOfMarks.markersFor(tree(*nodes.toTypedArray()), 1080, 4000, SetOfMarks.Options(maxMarkers = 30))
        assertEquals(30, marks.size)
        assertTrue(marks.all { it.label != null }, "labelled elements win the cap")
        assertEquals((1..30).toList(), marks.map { it.index })
    }

    @Test
    fun drawingPutsTheBoxAndNumberWhereTheMarkerSays() {
        val image = PixelImage.filled(400, 400, white)
        val marker = app.umbra.core.model.observation.Marker(1, "1:1", Rect(100, 100, 300, 200), "Ok")
        val out = SetOfMarks.draw(image, listOf(marker))

        assertEquals(white, image[101, 101], "the input is not modified")
        assertNotEquals(white, out[100, 150], "left edge of the box")
        assertNotEquals(white, out[299, 150], "right edge")
        assertNotEquals(white, out[200, 100], "top edge")
        assertNotEquals(white, out[200, 199], "bottom edge")
        assertEquals(white, out[200, 150], "the inside is left readable")
        assertNotEquals(white, out[102, 102], "the number plate is in the top-left corner")
    }

    @Test
    fun drawingScalesMarkerCoordinatesAndStaysInsideTheImage() {
        val image = PixelImage.filled(200, 200, white)
        val markers = listOf(
            app.umbra.core.model.observation.Marker(7, null, Rect(0, 0, 100, 100)),
            app.umbra.core.model.observation.Marker(12, null, Rect(900, 1900, 1000, 2000)),
        )
        val out = SetOfMarks.draw(image, markers, scale = 0.1f)
        assertEquals(200, out.width)
        assertNotEquals(white, out[0, 0], "the first marker's box and number plate sit in the top-left corner")
        assertEquals(white, out[60, 5], "unscaled, the first box would have reached x=100")
        assertNotEquals(white, out[95, 195], "the second marker lands near the bottom right")
        assertEquals(white, out[150, 100], "nothing is drawn away from the markers")
    }

    @Test
    fun theGridLabelsTheEdgesWithDisplayCoordinates() {
        val image = PixelImage.filled(540, 1200, white)
        val out = SetOfMarks.drawGrid(image, scale = 0.5f, stepPx = 200, displayWidth = 1080, displayHeight = 2400)
        assertNotEquals(white, out[100, 600], "the vertical line at display x=200 is drawn at x=100 in the half-size image")
        assertNotEquals(white, out[300, 200], "the horizontal line at display y=400")
        assertEquals(white, out[50, 650], "away from lines and labels the picture is untouched")
        assertFailsWith<IllegalArgumentException> { SetOfMarks.drawGrid(image, 1f, 0, 10, 10) }
    }

    @Test
    fun digitsRenderEveryGlyphWithoutOverflowing() {
        val img = PixelImage.filled(200, 20, black)
        Digits.draw(img, "0123456789", 2, 2, 1, white)
        for (d in 0..9) {
            val x0 = 2 + d * (Digits.WIDTH + 1)
            var lit = 0
            for (y in 2 until 2 + Digits.HEIGHT) for (x in x0 until x0 + Digits.WIDTH) if (img[x, y] == white) lit++
            assertTrue(lit in 8..25, "glyph $d has $lit lit pixels")
        }
        assertEquals(10 * 6 - 1, Digits.width("0123456789", 1))
    }
}
