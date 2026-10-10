package app.umbra.runtime.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GuestFileSetTest {
    private val a = GuestFile("/opt/umbra/bin/umbra-gui", "#!/usr/bin/env python3\nprint('gui')\n", "755")
    private val b = GuestFile("/opt/umbra/packs/gui.apt", "xvfb\nopenbox\n", "644")

    @Test
    fun `the version does not depend on the order the files were listed in`() {
        assertEquals(GuestFileSet(listOf(a, b)).version, GuestFileSet(listOf(b, a)).version)
        assertEquals(listOf(a.path, b.path), GuestFileSet(listOf(b, a)).files.map { it.path })
    }

    @Test
    fun `any change to a file changes the version`() {
        val base = GuestFileSet(listOf(a, b)).version
        assertNotEquals(base, GuestFileSet(listOf(a.copy(content = a.content + "# changed\n"), b)).version, "content")
        assertNotEquals(base, GuestFileSet(listOf(a.copy(mode = "644"), b)).version, "mode")
        assertNotEquals(base, GuestFileSet(listOf(a.copy(path = "/opt/umbra/bin/other"), b)).version, "path")
        assertNotEquals(base, GuestFileSet(listOf(a)).version, "a file removed")
    }

    @Test
    fun `files whose contents run together do not collide`() {
        val one = GuestFileSet(listOf(GuestFile("/x", "ab"), GuestFile("/y", "c")))
        val two = GuestFileSet(listOf(GuestFile("/x", "a"), GuestFile("/y", "bc")))
        assertNotEquals(one.version, two.version)
    }
}
