package app.umbra.agent.agents.proactive

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/** Text only: embedded objects, macros, external links and formulas are never executed or fetched. */
internal object DocumentText {
    private val textExtensions = setOf("txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "yaml", "yml", "xml", "html", "htm", "log", "rst")
    private val officeExtensions = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp")
    private val officeMimes = mapOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.oasis.opendocument.text" to "odt",
        "application/vnd.oasis.opendocument.spreadsheet" to "ods",
        "application/vnd.oasis.opendocument.presentation" to "odp",
    )
    const val MAX_EXPANDED_BYTES = 2_000_000
    const val MAX_TEXT_CHARS = 200_000
    private const val MAX_ENTRIES = 1000

    private fun format(document: ContextDocument) = officeMimes[document.mime]
        ?: document.name.substringAfterLast('.', "").lowercase()

    fun supports(document: ContextDocument): Boolean = document.virtual || document.mime.startsWith("text/") ||
        document.mime in setOf("application/json", "application/xml", "application/yaml") || format(document) in textExtensions + officeExtensions

    fun extract(document: ContextDocument, bytes: ByteArray): String? = try {
        val format = format(document)
        if (!document.virtual && format in officeExtensions) office(format, bytes) else utf8(bytes)?.takeIf { '\u0000' !in it }
    } catch (_: java.io.IOException) { null } catch (_: IllegalArgumentException) { null }

    fun virtualExportType(offered: List<String>): String? =
        listOf("text/plain", "text/csv", "text/tab-separated-values").firstOrNull { it in offered }

    private fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { null }

    private fun office(format: String, bytes: ByteArray): String? {
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) return null
        val parts = linkedMapOf<String, ByteArray>()
        var entries = 0
        var expanded = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= MAX_ENTRIES)
                // Account for every entry, including ignored images, so skipping cannot inflate a ZIP bomb.
                val data = zip.readNBytes(MAX_EXPANDED_BYTES - expanded + 1)
                expanded += data.size
                require(expanded <= MAX_EXPANDED_BYTES)
                val name = entry.name
                val selected = when (format) {
                    "docx" -> name == "word/document.xml"
                    "xlsx" -> name == "xl/sharedStrings.xml" || name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml"))
                    "pptx" -> name.matches(Regex("ppt/slides/slide[0-9]+\\.xml"))
                    else -> name == "content.xml"
                }
                if (selected) { require(name !in parts); parts[name] = data }
            }
        }
        if (parts.isEmpty()) return null
        fun xml(data: ByteArray): Document {
            val document = Jsoup.parse(ByteArrayInputStream(data), null, "", Parser.xmlParser())
            val nodes = document.getAllElements()
            require(nodes.size <= 20_000)
            for (node in nodes) {
                var parent = node.parent()
                var depth = 0
                while (parent != null) { require(++depth <= 64); parent = parent.parent() }
            }
            return document
        }
        fun Element.local() = tagName().substringAfterLast(':')
        fun Element.elements(name: String) = getAllElements().filter { node -> node.local() == name &&
            node.parents().takeWhile { it !== this }.none { it.local() == name } }
        fun Element.runs() = join(elements("t"), "") { it.wholeText() }
        return when (format) {
            "docx" -> join(xml(parts["word/document.xml"] ?: return null).elements("p"), "\n") { it.runs() }
            "xlsx" -> {
                val shared = parts["xl/sharedStrings.xml"]?.let { xml(it).elements("si").map { si -> si.runs() } }.orEmpty()
                join(parts.filterKeys { it.startsWith("xl/worksheets/") }.entries.sortedBy { it.key.filter(Char::isDigit).toIntOrNull() ?: 0 }, "\n") { (_, data) -> join(xml(data).elements("row"), "\n") { row ->
                        join(row.elements("c"), "\t") { cell ->
                            val value = cell.elements("v").firstOrNull()?.text().orEmpty()
                            when (cell.attr("t")) {
                                "s" -> value.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                                "inlineStr" -> cell.runs()
                                else -> value
                            }
                        }
                    } }
            }
            "pptx" -> join(parts.entries.sortedBy { it.key.filter(Char::isDigit).toIntOrNull() ?: 0 }, "\n") { (_, data) -> join(xml(data).elements("p"), "\n") { it.runs() } }
            else -> join(xml(parts["content.xml"] ?: return null).elements("p"), "\n") { it.text() }
        }.takeIf { it.isNotBlank() && '\u0000' !in it }
    }

    private fun <T> join(items: Iterable<T>, separator: String, text: (T) -> String): String = buildString {
        for (item in items) {
            if (length >= MAX_TEXT_CHARS) break
            if (isNotEmpty()) append(separator.take(MAX_TEXT_CHARS - length))
            append(text(item).take(MAX_TEXT_CHARS - length))
        }
    }
}
