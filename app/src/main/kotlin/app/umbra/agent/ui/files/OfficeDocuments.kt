package app.umbra.agent.ui.files

import java.util.Base64
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"
private const val SHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

internal fun htmlEscape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

internal fun OfficeArchive.relationships(path: String): Map<String, String> = parts[path]?.let { data ->
    OfficeXml.parse(data).descendants("Relationship").filter { it.getAttribute("TargetMode") != "External" }
        .associate { it.getAttribute("Id") to it.getAttribute("Target") }
}.orEmpty()

internal fun OfficeArchive.image(base: String, target: String?): String? {
    if (target == null) return null
    val path = java.nio.file.Path.of(base).resolve(target).normalize().toString()
    if (!path.startsWith("word/media/") && !path.startsWith("ppt/media/")) return null
    val mime = when (path.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> return null
    }
    return parts[path]?.let { "data:$mime;base64,${Base64.getEncoder().encodeToString(it)}" }
}

internal class DocxDocument(private val archive: OfficeArchive) {
    val paragraphs: List<String> = archive.xml("word/document.xml").descendants("p").map { it.runs() }

    fun html(): String {
        val document = archive.xml("word/document.xml")
        val rels = archive.relationships("word/_rels/document.xml.rels")
        val styles = archive.parts["word/styles.xml"]?.let { OfficeXml.parse(it).descendants("style").associateBy { style -> style.attribute("styleId") } }.orEmpty()
        val numbers = archive.parts["word/numbering.xml"]?.let { OfficeXml.parse(it) }
        val abstractIds = numbers?.descendants("num")?.associate { it.attribute("numId") to it.descendants("abstractNumId").firstOrNull()?.attribute("val") }.orEmpty()
        fun render(node: Node): String = when (node.local()) {
            "p" -> {
                val p = node as Element
                val properties = p.children().firstOrNull { it.local() == "pPr" }
                val styleId = properties?.descendants("pStyle")?.firstOrNull()?.attribute("val").orEmpty()
                val style = styles[styleId]
                val outline = properties?.descendants("outlineLvl")?.firstOrNull()?.attribute("val")?.toIntOrNull()
                    ?: style?.descendants("outlineLvl")?.firstOrNull()?.attribute("val")?.toIntOrNull()
                val heading = outline?.plus(1) ?: Regex("heading([1-6])", RegexOption.IGNORE_CASE).find(styleId)?.groupValues?.get(1)?.toIntOrNull()
                val content = p.children().filter { it.local() != "pPr" }.joinToString("") { render(it) }
                val num = properties?.descendants("numPr")?.firstOrNull()
                val numId = num?.descendants("numId")?.firstOrNull()?.attribute("val")
                val level = num?.descendants("ilvl")?.firstOrNull()?.attribute("val") ?: "0"
                val format = numbers?.descendants("abstractNum")?.firstOrNull { it.attribute("abstractNumId") == abstractIds[numId] }
                    ?.descendants("lvl")?.firstOrNull { it.attribute("ilvl") == level }?.descendants("numFmt")?.firstOrNull()?.attribute("val")
                when {
                    num != null -> "<${if (format == "bullet") "ul" else "ol"}><li>$content</li></${if (format == "bullet") "ul" else "ol"}>"
                    heading != null -> "<h${heading.coerceIn(1, 6)}>$content</h${heading.coerceIn(1, 6)}>"
                    else -> "<p>$content</p>"
                }
            }
            "r" -> {
                val r = node as Element
                val properties = r.children().firstOrNull { it.local() == "rPr" }
                var content = r.children().filter { it.local() != "rPr" }.joinToString("") { render(it) }
                listOf("b" to "strong", "i" to "em", "u" to "u").forEach { (property, tag) ->
                    properties?.children()?.firstOrNull { it.local() == property }?.let {
                        if (it.attribute("val") !in setOf("0", "false", "none", "off")) content = "<$tag>$content</$tag>"
                    }
                }
                content
            }
            "t" -> htmlEscape(node.textContent)
            "tab" -> "&#9;"
            "br", "cr" -> "<br>"
            "tbl" -> "<table>${node.children().joinToString("") { render(it) }}</table>"
            "tr" -> "<tr>${node.children().joinToString("") { render(it) }}</tr>"
            "tc" -> "<td>${node.children().joinToString("") { render(it) }}</td>"
            "blip" -> archive.image("word", rels[(node as Element).attribute("embed")])?.let { "<img src=\"$it\">" }.orEmpty()
            else -> node.children().joinToString("") { render(it) }
        }
        val body = render(document).replace("</ol><ol>", "").replace("</ul><ul>", "")
        return "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>body{font:17px sans-serif;padding:16px;line-height:1.5;overflow-wrap:anywhere}table{border-collapse:collapse}td{border:1px solid #aaa;padding:8px}img{max-width:100%;height:auto}p{white-space:pre-wrap}</style></head><body>$body</body></html>"
    }

    fun editParagraph(index: Int, value: String): ByteArray {
        val document = archive.xml("word/document.xml")
        val paragraph = document.descendants("p")[index]
        val firstRun = paragraph.descendants("r").firstOrNull()
        val run = document.createElementNS(WORD, "w:r")
        firstRun?.children()?.firstOrNull { it.local() == "rPr" }?.let { run.appendChild(it.cloneNode(true)) }
        paragraph.descendants("r").forEach { old ->
            old.children().filter { it.local() !in setOf("rPr", "drawing", "pict", "object") }.forEach { old.removeChild(it) }
            if (old.children().all { it.local() == "rPr" }) old.parentNode.removeChild(old)
        }
        paragraph.insertBefore(run, paragraph.children().firstOrNull { it.local() != "pPr" })
        value.split('\n').forEachIndexed { i, line ->
            if (i > 0) run.appendChild(document.createElementNS(WORD, "w:br"))
            OfficeXml.text(run, WORD, "w:t", line)
        }
        return archive.rewrite("word/document.xml", document)
    }
}

internal data class SheetCell(val value: String, val formula: String? = null)
internal data class FileSheet(val name: String, val part: String, val cells: Map<Pair<Int, Int>, SheetCell>, val rowCount: Int, val columnCount: Int) {
    fun value(row: Int, column: Int) = cells[row to column]?.value.orEmpty()
}

internal fun columnName(column: Int): String {
    var n = column + 1
    var result = ""
    while (n > 0) { result = ('A' + (n - 1) % 26) + result; n = (n - 1) / 26 }
    return result
}

internal fun cellAddress(row: Int, column: Int) = "${columnName(column)}${row + 1}"

internal class XlsxDocument(private val archive: OfficeArchive) {
    val sheets: List<FileSheet>
    init {
        val shared = archive.parts["xl/sharedStrings.xml"]?.let { OfficeXml.parse(it).descendants("si").map { si -> si.runs() } }.orEmpty()
        val rels = archive.relationships("xl/_rels/workbook.xml.rels")
        sheets = archive.xml("xl/workbook.xml").descendants("sheet").map { sheet ->
            val target = rels[sheet.attribute("id")] ?: error("Missing worksheet")
            val path = if (target.startsWith('/')) target.removePrefix("/") else java.nio.file.Path.of("xl").resolve(target).normalize().toString()
            require(path.startsWith("xl/worksheets/"))
            val cells = linkedMapOf<Pair<Int, Int>, SheetCell>()
            archive.xml(path).descendants("c").forEach { cell ->
                val ref = cell.getAttribute("r")
                val match = Regex("([A-Z]+)([1-9][0-9]*)").matchEntire(ref) ?: error("Invalid cell address")
                val column = match.groupValues[1].fold(0) { n, c -> Math.addExact(Math.multiplyExact(n, 26), c - 'A' + 1) } - 1
                val row = match.groupValues[2].toInt() - 1
                require(row in 0..99999 && column in 0..16383 && cells.size < 100_000)
                val raw = cell.children().firstOrNull { it.local() == "v" }?.textContent.orEmpty()
                val formula = cell.children().firstOrNull { it.local() == "f" }?.textContent
                val value = when (cell.getAttribute("t")) {
                    "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                    "inlineStr" -> cell.runs()
                    "b" -> if (raw == "1") "TRUE" else "FALSE"
                    else -> raw
                }
                cells[row to column] = SheetCell(value, formula)
            }
            FileSheet(sheet.getAttribute("name"), path, cells, (cells.keys.maxOfOrNull { it.first } ?: 0) + 1, (cells.keys.maxOfOrNull { it.second } ?: 0) + 1)
        }
    }

    fun edit(sheetIndex: Int, row: Int, column: Int, value: String): ByteArray {
        require(row in 0..99999 && column in 0..16383)
        val sheet = sheets[sheetIndex]
        require(sheet.cells[row to column]?.formula == null)
        val document = archive.xml(sheet.part)
        val data = document.descendants("sheetData").single()
        val rowNode = data.children().firstOrNull { it.getAttribute("r") == (row + 1).toString() }
            ?: document.createElementNS(SHEET, "row").apply {
                setAttribute("r", (row + 1).toString())
                data.insertBefore(this, data.children().firstOrNull { (it.getAttribute("r").toIntOrNull() ?: 0) > row + 1 })
            }
        val address = cellAddress(row, column)
        val cell = rowNode.children().firstOrNull { it.getAttribute("r") == address }
            ?: document.createElementNS(SHEET, "c").apply {
                setAttribute("r", address)
                rowNode.insertBefore(this, rowNode.children().firstOrNull {
                    val col = it.getAttribute("r").takeWhile(Char::isLetter).fold(0) { n, c -> n * 26 + (c - 'A' + 1) } - 1
                    col > column
                })
            }
        require(cell.children().none { it.local() == "f" })
        // Inline strings avoid changing shared strings used by other cells and preserve literal '=' input.
        cell.setAttribute("t", "inlineStr")
        cell.children().filter { it.local() in setOf("v", "is") }.forEach { cell.removeChild(it) }
        val inline = document.createElementNS(SHEET, "is")
        cell.appendChild(inline)
        OfficeXml.text(inline, SHEET, "t", value)
        document.descendants("dimension").firstOrNull()?.setAttribute("ref", "A1:${cellAddress(maxOf(row, sheet.rowCount - 1), maxOf(column, sheet.columnCount - 1))}")
        return archive.rewrite(sheet.part, document)
    }
}

internal data class SlideShape(val index: Int, val text: String)
internal data class FileSlide(val part: String, val shapes: List<SlideShape>, val images: List<String>)

internal class PptxDocument(private val archive: OfficeArchive) {
    val slides: List<FileSlide>
    init {
        val rels = archive.relationships("ppt/_rels/presentation.xml.rels")
        val paths = archive.xml("ppt/presentation.xml").descendants("sldId").map { slide ->
            val target = rels[slide.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")] ?: error("Missing slide")
            if (target.startsWith('/')) target.removePrefix("/") else java.nio.file.Path.of("ppt").resolve(target).normalize().toString()
        }
        slides = paths.map { path ->
            require(path.matches(Regex("ppt/slides/[^/]+\\.xml")))
            val doc = archive.xml(path)
            val shapes = doc.descendants("sp").mapIndexed { index, shape -> SlideShape(index, shape.descendants("p").joinToString("\n") { it.runs() }) }
            val images = archive.relationships("ppt/slides/_rels/${path.substringAfterLast('/')}.rels")
            FileSlide(path, shapes, doc.descendants("blip").mapNotNull { archive.image("ppt/slides", images[it.attribute("embed")]) })
        }
    }

    fun edit(slide: Int, shape: Int, text: String): ByteArray {
        val path = slides[slide].part
        val doc = archive.xml(path)
        val body = doc.descendants("sp")[shape].children().firstOrNull { it.local() == "txBody" } ?: error("Shape has no text")
        val first = body.descendants("r").firstOrNull()?.children()?.firstOrNull { it.local() == "rPr" }?.cloneNode(true)
        val paragraphProperties = body.descendants("p").firstOrNull()?.children()?.firstOrNull { it.local() == "pPr" }?.cloneNode(true)
        body.children().filter { it.local() == "p" }.forEach { body.removeChild(it) }
        text.split('\n').forEach { line ->
            val p = doc.createElementNS(DRAWING, "a:p")
            paragraphProperties?.let { p.appendChild(it.cloneNode(true)) }
            val r = doc.createElementNS(DRAWING, "a:r")
            first?.let { r.appendChild(it.cloneNode(true)) }
            OfficeXml.text(r, DRAWING, "a:t", line)
            p.appendChild(r); body.appendChild(p)
        }
        return archive.rewrite(path, doc)
    }
}
