package app.umbra.platform.api

import app.umbra.core.model.action.GlobalAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidKeysTest {
    @Test
    fun commonSpellingsNormaliseToTheCanonicalName() {
        for (spec in listOf("enter", "ENTER", "Enter", "KEYCODE_ENTER", "keycode:66", " return ")) {
            assertEquals("KEYCODE_ENTER", AndroidKeys.normalize(spec), spec)
        }
        assertEquals("KEYCODE_VOLUME_UP", AndroidKeys.normalize("volume up"))
        assertEquals("KEYCODE_DEL", AndroidKeys.normalize("backspace"))
        assertEquals("KEYCODE_FORWARD_DEL", AndroidKeys.normalize("delete"))
        assertEquals("KEYCODE_ESCAPE", AndroidKeys.normalize("esc"))
        assertEquals("KEYCODE_DPAD_LEFT", AndroidKeys.normalize("left"))
        assertEquals("KEYCODE_F5", AndroidKeys.normalize("f5"))
        assertEquals("KEYCODE_A", AndroidKeys.normalize("a"))
        assertEquals("KEYCODE_7", AndroidKeys.normalize("KEYCODE_7"))
        assertEquals("KEYCODE_5", AndroidKeys.normalize("5"), "a bare digit is the digit key, not key code 5 (CALL)")
    }

    @Test
    fun knownCodesMatchTheFrameworkConstants() {
        assertEquals(3, AndroidKeys.code("home"))
        assertEquals(4, AndroidKeys.code("back"))
        assertEquals(66, AndroidKeys.code("enter"))
        assertEquals(67, AndroidKeys.code("del"))
        assertEquals(29, AndroidKeys.code("a"))
        assertEquals(54, AndroidKeys.code("z"))
        assertEquals(7, AndroidKeys.code("0"))
        assertEquals(131, AndroidKeys.code("f1"))
        assertEquals(187, AndroidKeys.code("app_switch"))
        assertEquals(279, AndroidKeys.code("paste"))
        assertEquals("KEYCODE_TAB", AndroidKeys.name(61))
    }

    @Test
    fun unknownKeysAreRejectedRatherThanGuessed() {
        assertNull(AndroidKeys.normalize("ctrl+l"))
        assertNull(AndroidKeys.normalize(""))
        assertNull(AndroidKeys.normalize("banana"))
        assertNull(AndroidKeys.normalize("99999"))
        assertNull(AndroidKeys.normalize("keycode:99999"))
        assertNull(AndroidKeys.normalize("66"), "multi-digit numbers are not guessed to be key codes")
    }

    @Test
    fun navigationKeysMapToGlobalActions() {
        assertEquals(GlobalAction.BACK, AndroidKeys.asGlobalAction("back"))
        assertEquals(GlobalAction.HOME, AndroidKeys.asGlobalAction("KEYCODE_HOME"))
        assertEquals(GlobalAction.RECENTS, AndroidKeys.asGlobalAction("recents"))
        assertNull(AndroidKeys.asGlobalAction("enter"))
    }

    @Test
    fun everyCanonicalNameRoundTrips() {
        for (code in 0..400) {
            val name = AndroidKeys.name(code) ?: continue
            assertEquals(code, AndroidKeys.code(name), name)
            assertEquals(name, AndroidKeys.normalize(name))
        }
    }
}
