package app.umbra.runtime.linux.tmux

/** A colour as the terminal named it. */
sealed interface TermColor {
    data object Default : TermColor
    data class Palette(val index: Int) : TermColor
    data class Rgb(val r: Int, val g: Int, val b: Int) : TermColor
}

data class CellStyle(
    val fg: TermColor = TermColor.Default,
    val bg: TermColor = TermColor.Default,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    companion object {
        val Plain = CellStyle()
    }
}

data class Run(val text: String, val style: CellStyle)

data class ScreenLine(val runs: List<Run>) {
    val text: String get() = runs.joinToString("") { it.text }
}

/**
 * What a pane shows, as tmux renders it (`capture-pane -e`): tmux is the terminal emulator, so the app never runs
 * one of its own and every viewer sees exactly the same screen (§6.1).
 */
data class TerminalScreen(
    val lines: List<ScreenLine>,
    val cols: Int,
    val rows: Int,
    val cursorX: Int,
    val cursorY: Int,
    val cursorVisible: Boolean,
    val dead: Boolean,
    val deadStatus: Int?,
    val title: String,
    /** Full-screen programs (vim, less, the omp TUI) use the alternate screen. */
    val alternate: Boolean,
) {
    val plainText: String get() = lines.joinToString("\n") { it.text.trimEnd() }.trimEnd()
}

/** Parses tmux's SGR-only escaped capture into styled runs. Any other escape sequence is dropped. */
object SgrParser {
    fun parseLines(captured: String): List<ScreenLine> {
        var style = CellStyle.Plain
        return captured.split('\n').map { raw ->
            val runs = ArrayList<Run>()
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) {
                    runs += Run(text.toString(), style)
                    text.clear()
                }
            }
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\u001b' && i + 1 < raw.length && raw[i + 1] == '[') {
                    var j = i + 2
                    while (j < raw.length && raw[j] !in '@'..'~') j++
                    if (j < raw.length && raw[j] == 'm') {
                        flush()
                        style = apply(style, raw.substring(i + 2, j))
                    }
                    i = j + 1
                } else if (c == '\u001b') {
                    // OSC (`ESC ] … BEL` or `ESC ] … ESC \`) and other two-byte escapes: skipped.
                    if (i + 1 < raw.length && raw[i + 1] == ']') {
                        var j = i + 2
                        while (j < raw.length && raw[j] != '\u0007' && !(raw[j] == '\u001b' && j + 1 < raw.length && raw[j + 1] == '\\')) j++
                        i = if (j < raw.length && raw[j] == '\u0007') j + 1 else j + 2
                    } else {
                        i += 2
                    }
                } else if (c == '\r') {
                    i++
                } else {
                    text.append(c)
                    i++
                }
            }
            flush()
            ScreenLine(runs)
        }
    }

    fun apply(start: CellStyle, params: String): CellStyle {
        val p = if (params.isEmpty()) listOf(0) else params.split(';', ':').map { it.toIntOrNull() ?: 0 }
        var s = start
        var i = 0
        while (i < p.size) {
            when (val n = p[i]) {
                0 -> s = CellStyle.Plain
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(dim = true)
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = true)
                7 -> s = s.copy(inverse = true)
                22 -> s = s.copy(bold = false, dim = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                27 -> s = s.copy(inverse = false)
                in 30..37 -> s = s.copy(fg = TermColor.Palette(n - 30))
                39 -> s = s.copy(fg = TermColor.Default)
                in 40..47 -> s = s.copy(bg = TermColor.Palette(n - 40))
                49 -> s = s.copy(bg = TermColor.Default)
                in 90..97 -> s = s.copy(fg = TermColor.Palette(n - 90 + 8))
                in 100..107 -> s = s.copy(bg = TermColor.Palette(n - 100 + 8))
                38, 48 -> {
                    val color = when (p.getOrNull(i + 1)) {
                        5 -> TermColor.Palette(p.getOrNull(i + 2) ?: 0).also { i += 2 }
                        2 -> TermColor.Rgb(p.getOrNull(i + 2) ?: 0, p.getOrNull(i + 3) ?: 0, p.getOrNull(i + 4) ?: 0).also { i += 4 }
                        else -> null
                    }
                    if (color != null) s = if (n == 38) s.copy(fg = color) else s.copy(bg = color)
                }
            }
            i++
        }
        return s
    }
}
