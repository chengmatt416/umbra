package app.umbra.runtime.linux

import app.umbra.core.model.Point
import app.umbra.core.model.action.WindowAction
import app.umbra.core.model.observation.NodeAction
import app.umbra.core.model.observation.NodeFlag
import app.umbra.platform.api.SnapshotOptions
import app.umbra.platform.api.exec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A real graphical Linux desktop on the Android 16 emulator: the `gui` pack (Xvfb, openbox, AT-SPI,
 * xdotool, ImageMagick) is installed with apt inside the guest, a GTK program is started on the
 * virtual screen, and the desktop backend reads its accessibility tree, types into it, clicks, moves
 * windows and takes a picture. Needs network (apt downloads a few hundred MB the first time).
 */
class GuestGuiLiveTest {
    @Test
    fun aGtkProgramCanBeSeenAndOperatedOnTheVirtualDesktop(): Unit = runBlocking {
        val runtime = LiveGuest.installed()
        runtime.mount()
        if (!runtime.exec(listOf("test", "-x", "/usr/bin/Xvfb")).ok) {
            val install = runtime.installPack("gui")
            assertTrue(install.ok, "the gui pack installs with apt: ${install.combined.takeLast(600)}")
        }
        if (!runtime.exec(listOf("test", "-x", "/usr/bin/mousepad")).ok) {
            val install = runtime.exec(listOf("bash", "-c", "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends mousepad"), asRoot = true, timeoutMs = 20 * 60_000L, maxOutputBytes = 1024 * 1024)
            assertTrue(install.ok, "a small GTK3 editor installs: ${install.combined.takeLast(600)}")
        }

        val gui = GuestGuiBackend(runtime)
        try {
            assertTrue(gui.start(), "the virtual desktop starts")
            assertTrue(gui.isAvailable())

            val pid = gui.launch("mousepad")
            assertNotNull(pid, "the program started")
            val window = withTimeout(60_000) {
                var found: app.umbra.core.model.observation.GuiWindowInfo? = null
                while (found == null) {
                    found = gui.windows(null).firstOrNull { it.title?.contains("Mousepad", ignoreCase = true) == true || it.appName?.contains("mousepad", ignoreCase = true) == true }
                    if (found == null) delay(500)
                }
                found
            }
            assertTrue(window.bounds.width > 100 && window.bounds.height > 100, "the window has a real size: ${window.bounds}")

            // The semantic tree: the editor's text area is an editable element.
            val editor = withTimeout(60_000) {
                var node: app.umbra.core.model.observation.UiNode? = null
                while (node == null) {
                    val snapshot = gui.snapshot(SnapshotOptions(maxNodes = 800))
                    node = snapshot.windows.flatMap { it.nodes }.firstOrNull { it.has(NodeFlag.EDITABLE) && it.isVisible }
                    if (node == null) delay(700)
                }
                node
            }

            // Semantic action: set the text through AT-SPI, then read it back from the tree.
            assertTrue(gui.performAction(editor, NodeAction.SET_TEXT, app.umbra.platform.api.NodeActionArgs(text = "hello from the agent")), "AT-SPI accepted the text")
            delay(500)
            val after = gui.snapshot(SnapshotOptions(maxNodes = 800)).windows.flatMap { it.nodes }
            assertTrue(after.any { it.text?.contains("hello from the agent") == true }, "the new text is in the tree: ${after.filter { it.has(NodeFlag.EDITABLE) }.map { it.text }}")

            // Input: click into the editor and type with the keyboard.
            gui.tap(null, editor.bounds.center)
            gui.key(null, "ctrl+End")
            gui.typeText(null, " and typed")
            delay(600)
            val typed = gui.snapshot(SnapshotOptions(maxNodes = 800)).windows.flatMap { it.nodes }
            assertTrue(typed.any { it.text?.contains("and typed") == true }, "typing reached the program: ${typed.filter { it.has(NodeFlag.EDITABLE) }.map { it.text }}")

            // A picture of the desktop.
            val image = gui.capture(null)
            assertTrue(image.bytes.size > 5_000 && image.width > 200 && image.height > 200, "a real screenshot: ${image.bytes.size} bytes ${image.width}x${image.height}")
            assertEquals("image/png", image.mime)

            // Windows: move it, then close it.
            assertTrue(gui.windowAction(WindowAction.MOVE, id = window.id, rect = app.umbra.core.model.Rect(40, 60, 40 + window.bounds.width, 60 + window.bounds.height)))
            assertTrue(gui.windowAction(WindowAction.CLOSE, id = window.id))
            withTimeout(20_000) { while (gui.windows(null).any { it.id == window.id }) delay(400) }

            gui.clipboardSet("clip from the agent")
            assertEquals("clip from the agent", gui.clipboardGet()?.trim())
            gui.rightClick(null, Point(100, 100))
        } finally {
            runCatching { gui.stop() }
        }
    }
}
