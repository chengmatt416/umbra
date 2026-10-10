package app.umbra.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class UmbraTest {
    @Test
    fun applicationIdIsStable() {
        // The applicationId is baked into the Termux $PREFIX and every rebuilt package (ADR 0002).
        assertEquals("app.umbra.agent", Umbra.APPLICATION_ID)
    }
}
