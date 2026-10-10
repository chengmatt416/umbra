package app.umbra.runtime.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessTableTest {
    private val listing = """
          PID  PPID
            1     0
          100     1
          200   100
          201   100
          300   200
          400     1
    """.trimIndent()

    @Test
    fun `descendants come before their parents and the root is not included`() {
        val family = ProcessTable.descendantsOf(100, listing)
        assertEquals(setOf(200L, 201L, 300L), family.toSet())
        assertTrue(family.indexOf(300) < family.indexOf(200), "a child must be listed before its parent: $family")
        assertTrue(100L !in family)
    }

    @Test
    fun `a process with no children has no descendants`() {
        assertEquals(emptyList(), ProcessTable.descendantsOf(400, listing))
    }

    @Test
    fun `unrelated processes are never included`() {
        assertTrue(400L !in ProcessTable.descendantsOf(100, listing))
        assertTrue(1L !in ProcessTable.descendantsOf(100, listing))
    }

    @Test
    fun `an unknown pid has no descendants`() {
        assertEquals(emptyList(), ProcessTable.descendantsOf(999, listing))
    }

    @Test
    fun `noise and a corrupt cycle cannot make the walk loop`() {
        val corrupt = "PID PPID\n10 20\n20 10\n30 30\nnot a row\n\n40 10"
        val family = ProcessTable.descendantsOf(10, corrupt)
        assertEquals(setOf(20L, 40L), family.toSet())
    }
}
