package app.umbra.platform.api

import app.umbra.core.model.action.GlobalAction

/**
 * Android key codes by name. The agent writes `enter`, `Back` or `KEYCODE_VOLUME_UP`; backends
 * need the canonical name (for `input keyevent`) or the number (for injected events).
 */
object AndroidKeys {
    private val codes: Map<String, Int> = buildMap {
        put("HOME", 3); put("BACK", 4); put("CALL", 5); put("ENDCALL", 6)
        for (d in 0..9) put("$d", 7 + d)
        put("STAR", 17); put("POUND", 18)
        put("DPAD_UP", 19); put("DPAD_DOWN", 20); put("DPAD_LEFT", 21); put("DPAD_RIGHT", 22); put("DPAD_CENTER", 23)
        put("VOLUME_UP", 24); put("VOLUME_DOWN", 25); put("POWER", 26); put("CAMERA", 27); put("CLEAR", 28)
        for ((i, c) in ('A'..'Z').withIndex()) put("$c", 29 + i)
        put("COMMA", 55); put("PERIOD", 56)
        put("ALT_LEFT", 57); put("ALT_RIGHT", 58); put("SHIFT_LEFT", 59); put("SHIFT_RIGHT", 60)
        put("TAB", 61); put("SPACE", 62); put("SYM", 63); put("EXPLORER", 64); put("ENVELOPE", 65)
        put("ENTER", 66); put("DEL", 67); put("GRAVE", 68); put("MINUS", 69); put("EQUALS", 70)
        put("LEFT_BRACKET", 71); put("RIGHT_BRACKET", 72); put("BACKSLASH", 73); put("SEMICOLON", 74)
        put("APOSTROPHE", 75); put("SLASH", 76); put("AT", 77); put("NUM", 78); put("HEADSETHOOK", 79)
        put("FOCUS", 80); put("PLUS", 81); put("MENU", 82); put("NOTIFICATION", 83); put("SEARCH", 84)
        put("MEDIA_PLAY_PAUSE", 85); put("MEDIA_STOP", 86); put("MEDIA_NEXT", 87); put("MEDIA_PREVIOUS", 88)
        put("MEDIA_REWIND", 89); put("MEDIA_FAST_FORWARD", 90); put("MUTE", 91)
        put("PAGE_UP", 92); put("PAGE_DOWN", 93)
        put("ESCAPE", 111); put("FORWARD_DEL", 112)
        put("CTRL_LEFT", 113); put("CTRL_RIGHT", 114); put("CAPS_LOCK", 115); put("SCROLL_LOCK", 116)
        put("META_LEFT", 117); put("META_RIGHT", 118); put("FUNCTION", 119); put("SYSRQ", 120); put("BREAK", 121)
        put("MOVE_HOME", 122); put("MOVE_END", 123); put("INSERT", 124); put("FORWARD", 125)
        put("MEDIA_PLAY", 126); put("MEDIA_PAUSE", 127)
        for (f in 1..12) put("F$f", 130 + f)
        put("NUM_LOCK", 143)
        for (d in 0..9) put("NUMPAD_$d", 144 + d)
        put("VOLUME_MUTE", 164)
        put("APP_SWITCH", 187)
        put("BRIGHTNESS_DOWN", 220); put("BRIGHTNESS_UP", 221)
        put("SLEEP", 223); put("WAKEUP", 224)
        put("CUT", 277); put("COPY", 278); put("PASTE", 279)
    }

    private val byCode: Map<Int, String> = codes.entries.associate { (k, v) -> v to "KEYCODE_$k" }

    private val aliases: Map<String, String> = mapOf(
        "RETURN" to "ENTER", "BACKSPACE" to "DEL", "DELETE" to "FORWARD_DEL", "ESC" to "ESCAPE",
        "UP" to "DPAD_UP", "DOWN" to "DPAD_DOWN", "LEFT" to "DPAD_LEFT", "RIGHT" to "DPAD_RIGHT",
        "OK" to "DPAD_CENTER", "SELECT" to "DPAD_CENTER", "PGUP" to "PAGE_UP", "PGDN" to "PAGE_DOWN",
        "PAGEUP" to "PAGE_UP", "PAGEDOWN" to "PAGE_DOWN", "HOME_BUTTON" to "HOME", "RECENTS" to "APP_SWITCH",
        "OVERVIEW" to "APP_SWITCH", "VOL_UP" to "VOLUME_UP", "VOL_DOWN" to "VOLUME_DOWN", "END" to "MOVE_END",
        "CTRL" to "CTRL_LEFT", "SHIFT" to "SHIFT_LEFT", "ALT" to "ALT_LEFT", "META" to "META_LEFT",
        "SUPER" to "META_LEFT", "WIN" to "META_LEFT", "PLAY_PAUSE" to "MEDIA_PLAY_PAUSE", "LOCK" to "SLEEP",
    )

    /**
     * `enter`, `Enter`, `KEYCODE_ENTER`, `keycode:66` and `volume up` all become `KEYCODE_...`; unknown
     * keys give null. A bare digit is the digit key (`5` is KEYCODE_5), never the key code 5, because
     * that is what an agent that writes "5" means.
     */
    fun normalize(spec: String): String? {
        val raw = spec.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("keycode:", ignoreCase = true)) return raw.substringAfter(':').trim().toIntOrNull()?.let { byCode[it] }
        var name = raw.uppercase().replace(' ', '_').replace('-', '_')
        if (name.startsWith("KEYCODE_")) name = name.removePrefix("KEYCODE_")
        name = aliases[name] ?: name
        return if (name in codes) "KEYCODE_$name" else null
    }

    fun code(spec: String): Int? = normalize(spec)?.removePrefix("KEYCODE_")?.let(codes::get)

    fun name(code: Int): String? = byCode[code]

    /**
     * Keys the accessibility service can perform as a global action, which is more reliable than an
     * injected key event on the default display (and works without input-injection permission).
     */
    fun asGlobalAction(spec: String): GlobalAction? = when (normalize(spec)) {
        "KEYCODE_BACK" -> GlobalAction.BACK
        "KEYCODE_HOME" -> GlobalAction.HOME
        "KEYCODE_APP_SWITCH" -> GlobalAction.RECENTS
        "KEYCODE_NOTIFICATION" -> GlobalAction.NOTIFICATIONS
        else -> null
    }

    /** Printable single characters have no key name worth guessing; the caller types them instead. */
    fun isModifier(spec: String): Boolean = normalize(spec)?.removePrefix("KEYCODE_") in
        setOf("CTRL_LEFT", "CTRL_RIGHT", "SHIFT_LEFT", "SHIFT_RIGHT", "ALT_LEFT", "ALT_RIGHT", "META_LEFT", "META_RIGHT")
}
