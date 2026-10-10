package app.umbra.core.model.observation

import app.umbra.core.model.Point
import app.umbra.core.model.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenSelectionTest {
    private val screen = Rect(0, 0, 1080, 2400)

    private val page = UiSnapshot(
        listOf(
            testTree(
                testNode("1:0", className = "android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400), depth = 0),
                testNode("1:0.0", "Order summary", bounds = Rect(40, 200, 600, 260)),
                testNode("1:0.1", "Total: 12.00", bounds = Rect(40, 300, 500, 350)),
                testNode("1:0.2", "Pay now", className = "android.widget.Button", bounds = Rect(40, 400, 400, 480), flags = setOf(NodeFlag.CLICKABLE)),
                testNode("1:0.3", null, className = "android.widget.EditText", hint = "Card PIN", bounds = Rect(40, 520, 700, 600), flags = setOf(NodeFlag.EDITABLE, NodeFlag.PASSWORD)),
                testNode("1:0.4", "Footer", bounds = Rect(40, 2200, 600, 2260)),
            ),
        ),
    )

    private fun loop(l: Int, t: Int, r: Int, b: Int) = listOf(Point(l, t), Point(r, t), Point(r, b), Point(l, b))

    @Test
    fun aLoopSelectsWhatItEnclosesAndNeverTheScreenContainer() {
        val sel = Lasso.select(page, loop(20, 180, 720, 620), screen)
        assertEquals(listOf("1:0.0", "1:0.1", "1:0.2", "1:0.3"), sel.nodes.map { it.id })
        assertEquals(Rect(8, 168, 733, 633), sel.region)
        assertEquals(listOf("com.example.app"), sel.apps)
        assertFalse(sel.tapped)
    }

    @Test
    fun theLoopShapeMattersNotOnlyItsBounds() {
        // A triangle over the top-left: its bounds cover "Pay now" too, but the button's centre lies outside the loop.
        val triangle = listOf(Point(20, 180), Point(700, 180), Point(20, 500))
        val sel = Lasso.select(page, triangle, screen)
        assertTrue(sel.nodes.any { it.id == "1:0.0" })
        assertFalse(sel.nodes.any { it.id == "1:0.2" })
    }

    @Test
    fun aDiagonalDragSelectsItsRectangle() {
        val drag = (0..20).map { Point(20 + it * 35, 180 + it * 22) }
        val sel = Lasso.select(page, drag, screen)
        assertEquals(listOf("1:0.0", "1:0.1", "1:0.2", "1:0.3"), sel.nodes.map { it.id })
    }

    @Test
    fun aTapSelectsTheSmallestElementUnderIt() {
        val sel = Lasso.select(page, listOf(Point(100, 440), Point(104, 442)), screen)
        assertTrue(sel.tapped)
        assertEquals(Rect(40, 400, 400, 480), sel.region)
        assertEquals(listOf("1:0.2"), sel.nodes.map { it.id })
    }

    @Test
    fun theRegionIsClampedToTheScreen() {
        val sel = Lasso.select(page, loop(-50, -50, 300, 300), screen)
        assertEquals(0, sel.region.left)
        assertEquals(0, sel.region.top)
    }

    @Test
    fun passwordContentsAreNeverDescribed() {
        val secret = UiSnapshot(listOf(testTree(testNode("1:0", "hunter2", className = "android.widget.EditText", bounds = Rect(40, 520, 700, 600), flags = setOf(NodeFlag.EDITABLE, NodeFlag.PASSWORD)))))
        val sel = Lasso.select(secret, loop(20, 500, 720, 620), screen)
        val text = Lasso.describe(sel, screen)
        assertFalse("hunter2" in text, text)
        assertTrue("password field, contents withheld" in text, text)
    }

    @Test
    fun theDescriptionSaysWhereAndWhat() {
        val sel = Lasso.select(page, loop(20, 180, 720, 500), screen)
        val text = Lasso.describe(sel, screen, appName = { "Shop" })
        assertTrue(text.startsWith("The user circled part of the screen in Shop: region 8,168–733,513 of a 1080×2400 screen."), text)
        assertTrue("- “Order summary”" in text, text)
        assertTrue("- Button “Pay now”" in text, text)
    }

    @Test
    fun anEmptySelectionSaysThePictureIsAllThereIs() {
        val sel = Lasso.select(page, loop(800, 1000, 1000, 1200), screen)
        assertTrue(sel.nodes.isEmpty())
        assertTrue("the picture is all there is" in Lasso.describe(sel, screen))
    }

    @Test
    fun containsUsesTheEvenOddRule() {
        val square = loop(0, 0, 100, 100)
        assertTrue(Lasso.contains(square, Point(50, 50)))
        assertFalse(Lasso.contains(square, Point(150, 50)))
        assertFalse(Lasso.contains(listOf(Point(0, 0), Point(10, 10)), Point(5, 5)))
    }
}
