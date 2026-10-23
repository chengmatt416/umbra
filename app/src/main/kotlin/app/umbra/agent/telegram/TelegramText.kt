package app.umbra.agent.telegram

/** Preserve complete approvals and UTF-16 pairs; only the last part carries decision buttons. */
internal fun telegramMessageParts(text: String): List<String> = buildList {
    var from = 0
    while (from < text.length) {
        var end = (from + 4000).coerceAtMost(text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        add(text.substring(from, end))
        from = end
    }
}
