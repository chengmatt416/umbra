package app.umbra.platform.api

import app.umbra.core.model.Point

/** `input` command lines, the last-resort way to drive a display when no UI backend can (spec §7 root/shell fallback). */
object ShellInput {
    private fun base(displayId: Int?) = "input" + (displayId?.let { " -d $it" } ?: "")

    fun tap(displayId: Int?, p: Point) = "${base(displayId)} tap ${p.x} ${p.y}"

    fun swipe(displayId: Int?, from: Point, to: Point, durationMs: Long) =
        "${base(displayId)} swipe ${from.x} ${from.y} ${to.x} ${to.y} $durationMs"

    /** A press that never moves is a swipe from a point to itself. */
    fun longPress(displayId: Int?, p: Point, durationMs: Long) = swipe(displayId, p, p, durationMs)

    fun drag(displayId: Int?, from: Point, to: Point, durationMs: Long) =
        "${base(displayId)} draganddrop ${from.x} ${from.y} ${to.x} ${to.y} $durationMs"

    fun key(displayId: Int?, keycode: String, longPress: Boolean = false) =
        "${base(displayId)} keyevent${if (longPress) " --longpress" else ""} $keycode"

    /**
     * `input text` can only send printable ASCII, and reads `%s` as a space. Anything else (newlines,
     * accents, CJK) needs a different strategy.
     */
    fun canInputText(text: String): Boolean = text.isNotEmpty() && text.all { it.code in 0x20..0x7E } && !text.contains("%s")

    /** Single-quote [s] for a POSIX shell. */
    fun quote(s: String): String =
        if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "._-/:@+=,%" }) s else "'" + s.replace("'", "'\\''") + "'"

    fun text(displayId: Int?, text: String): String = "${base(displayId)} text ${quote(text.replace(" ", "%s"))}"
}
