package app.umbra.runtime.linux.tmux

import java.io.ByteArrayOutputStream

/**
 * tmux control mode (`tmux -C`), the way Umbra talks to terminals (architecture spec §6.1: tmux-centred persistence,
 * no home-made multiplexer). Every line tmux writes is either part of a command reply (`%begin` … `%end`/`%error`)
 * or a notification (`%output`, `%layout-change`, `%exit`, …).
 */
sealed interface TmuxLine {
    data class Begin(val number: Long, val ours: Boolean) : TmuxLine
    data class End(val number: Long, val error: Boolean) : TmuxLine

    /** Bytes a pane wrote, unescaped. */
    class Output(val paneId: String, val data: ByteArray) : TmuxLine

    data class Notification(val name: String, val args: List<String>) : TmuxLine

    /** A line inside a command reply. */
    data class Text(val text: String) : TmuxLine
}

object TmuxProtocol {
    fun parse(line: String): TmuxLine {
        if (!line.startsWith("%")) return TmuxLine.Text(line)
        val name = line.substringBefore(' ')
        val rest = if (line.length > name.length) line.substring(name.length + 1) else ""
        return when (name) {
            "%begin", "%end", "%error" -> {
                val parts = rest.split(' ')
                val number = parts.getOrNull(1)?.toLongOrNull() ?: -1
                val ours = parts.getOrNull(2) == "1"
                if (name == "%begin") TmuxLine.Begin(number, ours) else TmuxLine.End(number, name == "%error")
            }
            "%output" -> TmuxLine.Output(rest.substringBefore(' '), unescape(rest.substringAfter(' ', "")))
            // `%extended-output %pane age ... : value` (flow control; not enabled by Umbra, parsed for completeness).
            "%extended-output" -> TmuxLine.Output(rest.substringBefore(' '), unescape(rest.substringAfter(" : ", "")))
            else -> TmuxLine.Notification(name.removePrefix("%"), if (rest.isEmpty()) emptyList() else rest.split(' '))
        }
    }

    /** `%output` escapes bytes below 32 and the backslash as `\ooo` (octal); everything else is literal UTF-8. */
    fun unescape(value: String): ByteArray {
        val out = ByteArrayOutputStream(value.length)
        val bytes = value.toByteArray(Charsets.UTF_8)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '\\'.code.toByte() && isOctal(bytes, i + 1)) {
                out.write(((bytes[i + 1] - 48) * 64 + (bytes[i + 2] - 48) * 8 + (bytes[i + 3] - 48)) and 0xff)
                i += 4
            } else {
                out.write(b.toInt())
                i++
            }
        }
        return out.toByteArray()
    }

    private fun isOctal(bytes: ByteArray, at: Int): Boolean =
        at + 2 < bytes.size && (0..2).all { bytes[at + it] in '0'.code.toByte()..'7'.code.toByte() }

    private val SAFE = Regex("[A-Za-z0-9_@%$:./,=+-]+")

    /** An argument Umbra puts on a tmux command line: ids, numbers, paths. Anything else is refused, never quoted. */
    fun arg(value: String): String {
        require(SAFE.matches(value)) { "refusing an unsafe tmux argument: $value" }
        return value
    }

    /** `send-keys -H` input: each byte as two hex digits. */
    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
}
