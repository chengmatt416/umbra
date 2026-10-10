package app.umbra.core.model.observation

import app.umbra.core.model.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiTreeRendererTest {
    private val tree = testTree(
        testNode(
            "1:0", text = "Log in", className = "android.widget.Button", resourceId = "com.example.app:id/login",
            bounds = Rect(10, 800, 410, 900), flags = setOf(NodeFlag.CLICKABLE),
        ),
        testNode(
            "1:1", text = "hunter2", className = "android.widget.EditText",
            bounds = Rect(10, 300, 410, 360), flags = setOf(NodeFlag.EDITABLE, NodeFlag.PASSWORD),
        ),
        testNode("1:2", text = "Welcome", bounds = Rect(10, 100, 410, 140)),
    )

    @Test
    fun rendersIdsLabelsTagsAndBounds() {
        val text = UiTreeRenderer.render(tree)
        assertTrue(text.startsWith("window 1 com.example.app \"Login\" [display 0, active, focused"), text)
        assertTrue("[1:0] Button \"Log in\" id=login {click} (10,800,410,900)" in text, text)
        assertTrue("[1:2] TextView \"Welcome\"" in text, text)
    }

    @Test
    fun neverRendersPasswordText() {
        val text = UiTreeRenderer.render(tree)
        assertFalse("hunter2" in text, text)
        assertTrue("\"[hidden]\"" in text && "password" in text, text)
    }

    @Test
    fun onlyInteractiveDropsPlainText() {
        val text = UiTreeRenderer.render(tree, UiTreeRenderer.Options(onlyInteractive = true))
        assertFalse("Welcome" in text)
        assertTrue("Log in" in text)
    }

    @Test
    fun reportsOmittedNodes() {
        val text = UiTreeRenderer.render(tree, UiTreeRenderer.Options(maxNodes = 1))
        assertTrue("2 more nodes omitted" in text, text)
        assertEquals(1, Regex("\\[1:\\d]").findAll(text).count())
    }

    @Test
    fun invisibleNodesAreSkipped() {
        val t = testTree(testNode("1:0", text = "ghost", bounds = Rect.EMPTY))
        assertFalse("ghost" in UiTreeRenderer.render(t))
    }
}
