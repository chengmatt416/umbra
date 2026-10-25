package app.umbra.runtime.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GuestPathTest {
    @Test
    fun absoluteGuestPathsHaveOneRepresentation() {
        assertEquals("/home/agent/file name", guestPath("//home///agent/file name"))
        assertEquals("/", guestPath("/"))
        for (path in listOf("relative", "/../escape", "/workspace/../etc/passwd", "/./tmp", "/tmp/\u0000bad")) {
            assertFailsWith<IllegalArgumentException>(path) { guestPath(path) }
        }
    }

    @Test
    fun fileModesCannotInjectShellOrGrantSpecialPrivileges() {
        assertEquals("600", fileMode("600"))
        assertEquals("755", fileMode("755"))
        for (mode in listOf("4755", "1777", "888", "600; id", "-R", "")) {
            assertFailsWith<IllegalArgumentException>(mode) { fileMode(mode) }
        }
    }
}
