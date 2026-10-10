package app.umbra.platform.api

import app.umbra.core.model.Point
import app.umbra.core.model.Rect
import app.umbra.core.model.action.Direction
import app.umbra.core.model.action.WindowAction
import app.umbra.core.model.observation.GuiWindowInfo

/**
 * A graphical Linux session (Termux:X11 or Xvfb) driven through a helper in the guest: AT-SPI for the
 * semantic tree, X11 input for everything else (spec §13). It is a [UiBackend] too, so the same
 * semantic-first pipeline applies; the `displayId` in its trees is the X display number.
 */
interface LinuxGuiBackend : UiBackend, ScreenshotBackend {
    suspend fun windows(display: Int? = null): List<GuiWindowInfo>

    /** Returns false when the window manager refused or the window was not found. */
    suspend fun windowAction(action: WindowAction, id: String? = null, title: String? = null, rect: Rect? = null): Boolean

    /** Starts a GUI program in the session and returns its pid when known. */
    suspend fun launch(command: String, args: List<String> = emptyList(), cwd: String? = null, env: Map<String, String> = emptyMap()): Int?

    suspend fun rightClick(display: Int?, point: Point)

    /** Mouse wheel at [point]; positive [Direction] amounts are in wheel clicks. */
    suspend fun wheel(display: Int?, point: Point, direction: Direction, clicks: Int)

    suspend fun clipboardGet(): String?

    suspend fun clipboardSet(text: String)
}
