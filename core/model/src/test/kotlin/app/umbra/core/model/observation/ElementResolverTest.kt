package app.umbra.core.model.observation

import app.umbra.core.model.Rect
import app.umbra.core.model.action.ElementQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ElementResolverTest {
    private val title = testNode("1:0", text = "Log in", bounds = Rect(0, 100, 400, 160))
    private val loginButton = testNode(
        "1:1", text = "Log in", className = "android.widget.Button", resourceId = "com.example.app:id/login",
        bounds = Rect(0, 800, 400, 900), flags = setOf(NodeFlag.CLICKABLE),
    )
    private val google = testNode(
        "1:2", text = "Log in with Google", className = "android.widget.Button",
        bounds = Rect(0, 920, 400, 1000), flags = setOf(NodeFlag.CLICKABLE),
    )
    private val email = testNode(
        "1:3", hint = "Email", className = "android.widget.EditText", resourceId = "com.example.app:id/email",
        bounds = Rect(0, 200, 400, 260), flags = setOf(NodeFlag.EDITABLE, NodeFlag.FOCUSABLE),
    )
    private val password = testNode(
        "1:4", text = "secret-value", className = "android.widget.EditText", resourceId = "com.example.app:id/password",
        bounds = Rect(0, 300, 400, 360), flags = setOf(NodeFlag.EDITABLE, NodeFlag.PASSWORD),
    )
    private val hidden = testNode("1:5", text = "Log in", bounds = Rect.EMPTY)
    private val tree = testTree(title, loginButton, google, email, password, hidden)

    @Test
    fun exactTextMatchesLabelAndIsCaseInsensitive() {
        val matches = tree.find(ElementQuery(text = "log IN"))
        assertEquals(listOf("1:0", "1:1", "1:5"), matches.map { it.id }, "reading order, invisible node last")
    }

    @Test
    fun resourceIdMatchesBySuffix() {
        assertEquals(listOf("1:1"), tree.find(ElementQuery(resourceId = "login")).map { it.id })
        assertEquals(listOf("1:1"), tree.find(ElementQuery(resourceId = "com.example.app:id/login")).map { it.id })
        assertTrue(tree.find(ElementQuery(resourceId = "ogin")).isEmpty(), "suffix must align on a boundary")
    }

    @Test
    fun classNameMatchesShortName() {
        assertEquals(listOf("1:1", "1:2"), tree.find(ElementQuery(className = "Button")).map { it.id })
    }

    @Test
    fun plainTextQueryIsAmbiguousBetweenTitleAndButton() {
        val resolution = ElementResolver.resolve(tree, ElementQuery(text = "Log in"))
        assertIs<Resolution.Ambiguous>(resolution)
    }

    @Test
    fun preferringClickablePicksTheButton() {
        val resolution = ElementResolver.resolve(tree, ElementQuery(text = "Log in"), prefer = NodeFlag.CLICKABLE)
        assertIs<Resolution.Unique>(resolution)
        assertEquals("1:1", resolution.node.id)
    }

    @Test
    fun indexSelectsNthMatchInReadingOrder() {
        val resolution = ElementResolver.resolve(tree, ElementQuery(className = "Button", index = 1))
        assertIs<Resolution.Unique>(resolution)
        assertEquals("1:2", resolution.node.id)
        assertEquals(Resolution.NotFound, ElementResolver.resolve(tree, ElementQuery(className = "Button", index = 5)))
    }

    @Test
    fun notFoundWhenNothingMatches() {
        assertEquals(Resolution.NotFound, ElementResolver.resolve(tree, ElementQuery(text = "Register")))
    }

    @Test
    fun emptyQueryMatchesNothing() {
        assertTrue(tree.find(ElementQuery()).isEmpty())
    }

    @Test
    fun editableFilterFindsInputsOnly() {
        assertEquals(listOf("1:3", "1:4"), tree.find(ElementQuery(editable = true)).map { it.id })
    }

    @Test
    fun snapshotResolvesNodeBySnapshotId() {
        val snapshot = UiSnapshot(listOf(tree))
        assertEquals("1:3", snapshot.node("1:3")?.id)
        assertEquals(null, snapshot.node("9:0"))
        assertEquals(null, snapshot.node("garbage"))
    }

    @Test
    fun containsTextIgnoresInvisibleNodes() {
        val onlyHidden = testTree(hidden)
        assertEquals(false, onlyHidden.containsText("Log in"))
        assertEquals(true, tree.containsText("Log in with"))
    }
}
