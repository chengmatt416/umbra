package app.umbra.core.safety

/**
 * A deliberately small POSIX-shell tokenizer: enough to split a command line into simple commands,
 * strip quoting, see redirections and pipes, and skip heredoc bodies so file contents are never
 * mistaken for commands. It does not expand anything; constructs it cannot see through are flagged
 * `dynamic` so the caller can treat them conservatively.
 */
internal object ShellLexer {
    data class Redirect(val operator: String, val target: String)

    data class Segment(
        /** Words with quotes removed; the first is the command word. */
        val words: List<String>,
        val redirects: List<Redirect>,
        /** Contains `$(...)`, backticks or `${...}`: the real command is not statically known. */
        val dynamic: Boolean,
        /** The previous operator was a single `|`. */
        val pipedFromPrevious: Boolean,
        /** Body of a heredoc attached to this command, if any. */
        val heredoc: String?,
    )

    fun parse(command: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val words = mutableListOf<String>()
        val redirects = mutableListOf<Redirect>()
        var dynamic = false
        var piped = false
        var heredocBody: String? = null
        val pendingHeredocs = mutableListOf<String>()
        var pendingRedirect: String? = null

        val current = StringBuilder()
        var hasWord = false

        fun finishWord() {
            if (!hasWord) return
            val w = current.toString()
            current.setLength(0)
            hasWord = false
            val op = pendingRedirect
            if (op != null) {
                pendingRedirect = null
                if (op == "<<" || op == "<<-") pendingHeredocs += w else redirects += Redirect(op, w)
            } else {
                words += w
            }
        }

        fun finishSegment(nextPiped: Boolean) {
            finishWord()
            if (words.isNotEmpty() || redirects.isNotEmpty()) {
                segments += Segment(words.toList(), redirects.toList(), dynamic, piped, heredocBody)
            }
            words.clear()
            redirects.clear()
            dynamic = false
            heredocBody = null
            piped = nextPiped
        }

        var i = 0
        val n = command.length
        while (i < n) {
            val c = command[i]
            when {
                c == '\\' && i + 1 < n -> {
                    if (command[i + 1] == '\n') { i += 2; continue } // line continuation
                    current.append(command[i + 1]); hasWord = true; i += 2
                }
                c == '\'' -> {
                    hasWord = true
                    i++
                    while (i < n && command[i] != '\'') { current.append(command[i]); i++ }
                    i++ // closing quote
                }
                c == '"' -> {
                    hasWord = true
                    i++
                    while (i < n && command[i] != '"') {
                        val d = command[i]
                        if (d == '\\' && i + 1 < n) { current.append(command[i + 1]); i += 2; continue }
                        if (d == '`' || (d == '$' && i + 1 < n && (command[i + 1] == '(' || command[i + 1] == '{'))) dynamic = true
                        current.append(d); i++
                    }
                    i++
                }
                c == '`' -> {
                    // Keep the whole backtick substitution inside the current word.
                    val close = command.indexOf('`', i + 1)
                    val end = if (close < 0) n else close + 1
                    current.append(command, i, end)
                    dynamic = true; hasWord = true; i = end
                }
                c == '$' && i + 1 < n && command[i + 1] == '(' -> {
                    val end = skipBalanced(command, i + 1, '(', ')')
                    current.append(command, i, end)
                    dynamic = true; hasWord = true; i = end
                }
                c == '$' && i + 1 < n && command[i + 1] == '{' -> {
                    val end = skipBalanced(command, i + 1, '{', '}')
                    current.append(command, i, end)
                    dynamic = true; hasWord = true; i = end
                }
                c == '#' && !hasWord -> {
                    while (i < n && command[i] != '\n') i++
                }
                c == '\n' -> {
                    finishSegment(false)
                    if (pendingHeredocs.isNotEmpty()) {
                        // Consume heredoc bodies belonging to the segment that just ended.
                        i++
                        val bodies = StringBuilder()
                        for (delimiter in pendingHeredocs) {
                            while (i < n) {
                                val end = command.indexOf('\n', i).let { if (it < 0) n else it }
                                val line = command.substring(i, end)
                                i = minOf(end + 1, n)
                                if (line.trim() == delimiter) break
                                bodies.append(line).append('\n')
                            }
                        }
                        pendingHeredocs.clear()
                        if (segments.isNotEmpty()) {
                            val last = segments.removeAt(segments.lastIndex)
                            segments += last.copy(heredoc = bodies.toString())
                        }
                        continue
                    }
                    i++
                }
                c == ';' -> { finishSegment(false); i++ }
                c == '&' && i + 1 < n && command[i + 1] == '&' -> { finishSegment(false); i += 2 }
                c == '&' && i + 1 < n && command[i + 1] == '>' -> {
                    finishWord(); pendingRedirect = "&>"; i += 2
                    if (i < n && command[i] == '>') { pendingRedirect = "&>>"; i++ }
                }
                c == '&' -> { finishSegment(false); i++ }
                c == '|' && i + 1 < n && command[i + 1] == '|' -> { finishSegment(false); i += 2 }
                c == '|' -> { finishSegment(true); i++ }
                c == '(' || c == ')' -> { finishSegment(false); i++ }
                c == '>' || c == '<' -> {
                    // A bare number directly before the operator is a file descriptor, not an argument.
                    if (hasWord && current.all { it.isDigit() }) { current.setLength(0); hasWord = false } else finishWord()
                    val op = StringBuilder().append(c)
                    i++
                    while (i < n && op.length < 3 && (command[i] == c || command[i] == '|' || command[i] == '&' || (c == '<' && command[i] == '-'))) {
                        op.append(command[i]); i++
                    }
                    pendingRedirect = op.toString()
                }
                c == ' ' || c == '\t' || c == '\r' -> { finishWord(); i++ }
                else -> { current.append(c); hasWord = true; i++ }
            }
        }
        finishSegment(false)
        return segments
    }

    /** Index just past the delimiter that closes the one at [start], honouring nesting and quotes. */
    private fun skipBalanced(s: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = start
        var inSingle = false
        var inDouble = false
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && !inSingle -> i++
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                !inSingle && c == open -> depth++
                !inSingle && c == close -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return s.length
    }
}
