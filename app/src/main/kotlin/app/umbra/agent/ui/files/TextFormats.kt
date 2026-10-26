package app.umbra.agent.ui.files

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

internal const val MAX_FILE_BYTES = 20 * 1024 * 1024

data class AskSelection(val quote: String, val where: String, val image: ByteArray? = null)

/** Keep selections bounded so a whole document cannot crowd the instruction out of context. */
fun aboutFileMessage(file: String, selection: AskSelection, instruction: String): String {
    val name = file.substringAfterLast('/').replace('\n', ' ').replace('\r', ' ')
    val where = selection.where.replace('\n', ' ').replace('\r', ' ')
    val quote = selection.quote.take(4000).replace("\n", "\n> ")
    return "[About $name — $where]\n> $quote\n\n$instruction"
}

internal fun utf8Text(bytes: ByteArray): String? = runCatching {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF").takeIf { text ->
            text.none { it == '\u0000' || (it.code < 32 && it !in "\n\r\t\u000C\b") }
        }
}.getOrNull()

internal object Csv {
    fun parse(text: String, separator: Char = ','): List<List<String>> {
        if (text.isEmpty()) return emptyList()
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var closed = false
        var i = 0
        fun field() { row += cell.toString(); cell.clear(); closed = false }
        while (i < text.length) {
            val c = text[i++]
            if (quoted) {
                if (c == '"') {
                    if (i < text.length && text[i] == '"') { cell.append('"'); i++ }
                    else { quoted = false; closed = true }
                } else cell.append(c)
            } else when (c) {
                '"' -> { require(cell.isEmpty() && !closed); quoted = true }
                separator -> field()
                '\r', '\n' -> {
                    if (c == '\r' && i < text.length && text[i] == '\n') i++
                    field(); rows += row; row = mutableListOf()
                }
                else -> { require(!closed); cell.append(c) }
            }
            require(rows.size <= 100_000 && row.size <= 16_384)
        }
        require(!quoted)
        if (cell.isNotEmpty() || row.isNotEmpty() || closed || text.last() == separator) { field(); rows += row }
        return rows
    }

    fun write(rows: List<List<String>>, separator: Char = ','): String = rows.joinToString("\r\n") { row ->
        row.joinToString(separator.toString()) { cell ->
            if (cell.isEmpty() || cell.any { it == separator || it in "\"\r\n" }) "\"${cell.replace("\"", "\"\"") }\"" else cell
        }
    }
}

internal data class JsonRow(val path: String, val label: String, val value: String, val depth: Int, val branch: Boolean)

internal object JsonTree {
    val codec = Json { prettyPrint = true }

    fun flatten(root: JsonElement, collapsed: Set<String> = emptySet()): List<JsonRow> = buildList {
        fun walk(node: JsonElement, path: String, label: String, depth: Int) {
            require(depth <= 128 && size < 100_000)
            val branch = node is JsonObject || node is JsonArray
            add(JsonRow(path, label, when (node) {
                is JsonObject -> "{${node.size}}"
                is JsonArray -> "[${node.size}]"
                else -> node.toString()
            }, depth, branch))
            if (path in collapsed) return
            when (node) {
                is JsonObject -> node.forEach { (key, child) -> walk(child, "$path/${key.replace("~", "~0").replace("/", "~1")}", key, depth + 1) }
                is JsonArray -> node.forEachIndexed { i, child -> walk(child, "$path/$i", "[$i]", depth + 1) }
                else -> Unit
            }
        }
        walk(root, "", "$", 0)
    }
}
