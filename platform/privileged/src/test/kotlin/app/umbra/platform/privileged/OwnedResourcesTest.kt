package app.umbra.platform.privileged

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnedResourcesTest {
    @Test
    fun twoConnectionsMayUseTheSameIdWithoutControllingEachOther() {
        val stoppedA = mutableListOf<String>()
        val stoppedB = mutableListOf<String>()
        val a = OwnedResources<String>(2, stoppedA::add)
        val b = OwnedResources<String>(2, stoppedB::add)
        a.attach(assertNotNull(a.reserve("p1")), "A")
        b.attach(assertNotNull(b.reserve("p1")), "B")
        a.cancel("p1")
        a.close()
        assertEquals(listOf("A"), stoppedA)
        assertEquals(emptyList(), stoppedB)
        assertEquals("B", b["p1"])
        b.close()
        assertEquals(listOf("B"), stoppedB)
    }

    @Test
    fun lateCreationAfterDisconnectIsDisposedAndDuplicateIdsAreRejected() {
        val stopped = mutableListOf<String>()
        val resources = OwnedResources<String>(1, stopped::add)
        val pending = assertNotNull(resources.reserve("p1"))
        assertNull(resources.reserve("p1"))
        assertNull(resources.reserve("p2"))
        resources.close()
        assertFalse(resources.attach(pending, "late process"))
        assertEquals(listOf("late process"), stopped)
        assertNull(resources.reserve("p3"))
        assertTrue(resources.isEmpty())
    }

    @Test
    fun oldCleanupCannotRemoveAReplacement() {
        val resources = OwnedResources<String>(1) { }
        val old = assertNotNull(resources.reserve("same"))
        resources.attach(old, "old")
        resources.cancel("same")
        val replacement = assertNotNull(resources.reserve("same"))
        resources.attach(replacement, "new")
        resources.release(old)
        assertEquals("new", resources["same"])
        resources.release(replacement)
        assertTrue(resources.isEmpty())
    }
}
