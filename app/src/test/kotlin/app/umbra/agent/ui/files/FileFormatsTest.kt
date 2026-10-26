package app.umbra.agent.ui.files

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import kotlinx.serialization.json.Json

class FileFormatsTest {
    private fun zip(vararg parts: Pair<String, String>): ByteArray = zipBytes(*parts.map { it.first to it.second.toByteArray() }.toTypedArray())

    private fun zipBytes(vararg parts: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> parts.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun csvRoundTripsQuotesCommasMultilineUnicodeAndEmptyCells() {
        val rows = listOf(listOf("name", "notes", "empty"), listOf("台灣, Taipei", "said \"hello\"\nnext\r\nline", ""), listOf("", "", ""))
        assertEquals(rows, Csv.parse(Csv.write(rows)))
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), Csv.parse("a,b\r\nc,d\r\n"))
        assertEquals(listOf(listOf("a", "")), Csv.parse("a,"))
        assertEquals(listOf(listOf("")), Csv.parse("\"\""))
        assertEquals(listOf(listOf("a\tb", "c")), Csv.parse("\"a\tb\"\tc", '\t'))
        assertFailsWith<IllegalArgumentException> { Csv.parse("\"unclosed") }
        assertFailsWith<IllegalArgumentException> { Csv.parse("\"closed\"oops") }
    }

    @Test fun jsonFlatteningUsesStableEscapedPathsAndCollapsesWholeBranches() {
        val json = Json.parseToJsonElement("""{"a/b":[{"~key":true},null],"empty":{}}""")
        val rows = JsonTree.flatten(json)
        assertEquals(listOf("", "/a~1b", "/a~1b/0", "/a~1b/0/~0key", "/a~1b/1", "/empty"), rows.map { it.path })
        assertEquals(listOf(0, 1, 2, 3, 2, 1), rows.map { it.depth })
        assertEquals("true", rows[3].value)
        assertEquals(listOf("", "/a~1b", "/empty"), JsonTree.flatten(json, setOf("/a~1b")).map { it.path })
        assertFails { JsonTree.codec.parseToJsonElement("{bad}") }
    }

    @Test fun messageCapsOnlyTheQuoteAndQuotesEveryLine() {
        val message = aboutFileMessage("/workspace/report.md", AskSelection("first\nsecond", "paragraph 3"), "Summarize this")
        assertEquals("[About report.md — paragraph 3]\n> first\n> second\n\nSummarize this", message)
        val capped = aboutFileMessage("report.json", AskSelection("x".repeat(5000), "selection"), "Keep this instruction")
        assertEquals(4000, capped.substringAfter("\n> ").substringBefore("\n\n").length)
        assertTrue(capped.endsWith("\n\nKeep this instruction"))
        assertFalse(aboutFileMessage("bad\nfile", AskSelection("", "bad\nwhere"), "go").substringBefore("]").contains('\n'))
    }

    @Test fun utf8SniffRejectsBinaryAndMalformedText() {
        assertEquals("繁體中文", utf8Text("繁體中文".toByteArray()))
        assertNull(utf8Text(byteArrayOf(0xc3.toByte(), 0x28)))
        assertNull(utf8Text(byteArrayOf(0, 1, 2)))
        assertEquals("test", utf8Text("\uFEFFtest".toByteArray()))
    }

    @Test fun docxRendersStylesTablesListsImagesAndEscapesText() {
        val source = zip(
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:rPr><w:b/><w:i/><w:u w:val="single"/></w:rPr><w:t>&lt;Title&gt; &amp; more</w:t></w:r></w:p>
                <w:p><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>List item</w:t></w:r></w:p>
                <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Table cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                <w:p><w:r><w:drawing><a:blip r:embed="image1"/></w:drawing></w:r></w:p>
                </w:body></w:document>""",
            "word/_rels/document.xml.rels" to """<Relationships><Relationship Id="image1" Target="media/image.png"/><Relationship Id="remote" Target="https://example.invalid/a.png" TargetMode="External"/></Relationships>""",
            "word/media/image.png" to "fake png",
        )
        val document = DocxDocument(OfficeArchive(source))
        val html = document.html()
        assertContains(html, "<h1><u><em><strong>&lt;Title&gt; &amp; more</strong></em></u></h1>")
        assertContains(html, "<li>List item</li>")
        assertContains(html, "<table><tr><td><p>Table cell</p></td></tr></table>")
        assertContains(html, "data:image/png;base64,")
        assertFalse(html.contains("https://example.invalid"))
        assertContains(DocxDocument(OfficeArchive(document.editParagraph(3, "Caption"))).html(), "data:image/png;base64,")
    }

    @Test fun docxParagraphEditKeepsFirstRunFormattingAndUnchangedZipRecords() {
        val source = zip(
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>one</w:t></w:r><w:r><w:t>two</w:t></w:r></w:p><w:p><w:r><w:t>untouched</w:t></w:r></w:p></w:body></w:document>""",
            "word/styles.xml" to "<styles/>",
            "custom/blob.dat" to "must stay byte-identical",
        )
        val changed = DocxDocument(OfficeArchive(source)).editParagraph(0, " New & <text>\n下一行 ")
        val next = DocxDocument(OfficeArchive(changed))
        assertEquals(listOf(" New & <text>\n下一行 ", "untouched"), next.paragraphs)
        assertContains(next.html(), "<h2><strong> New &amp; &lt;text&gt;<br>下一行 </strong></h2>")
        assertContentEquals(rawRecord(source, "custom/blob.dat"), rawRecord(changed, "custom/blob.dat"))
        assertContentEquals(rawRecord(source, "word/styles.xml"), rawRecord(changed, "word/styles.xml"))
    }

    @Test fun xlsxReadsSharedAndInlineStringsAndEditsWithoutChangingOtherParts() {
        val source = xlsx()
        val document = XlsxDocument(OfficeArchive(source))
        assertEquals(listOf("Data", "Second"), document.sheets.map { it.name })
        val sheet = document.sheets[0]
        assertEquals("Shared rich text", sheet.value(0, 0))
        assertEquals("inline 中文", sheet.value(0, 1))
        assertEquals("42", sheet.value(1, 2))
        assertEquals("SUM(A1:A2)", sheet.cells[2 to 0]?.formula)
        val changed = document.edit(0, 0, 0, " literal <&> ")
        val next = XlsxDocument(OfficeArchive(changed))
        assertEquals(" literal <&> ", next.sheets[0].value(0, 0))
        assertEquals("inline 中文", next.sheets[0].value(0, 1))
        assertEquals("Shared rich text", next.sheets[1].value(0, 0))
        assertContentEquals(rawRecord(source, "xl/sharedStrings.xml"), rawRecord(changed, "xl/sharedStrings.xml"))
        assertContentEquals(rawRecord(source, "xl/worksheets/sheet2.xml"), rawRecord(changed, "xl/worksheets/sheet2.xml"))
        assertFailsWith<IllegalArgumentException> { document.edit(0, 2, 0, "no") }
        val inline = next.edit(0, 0, 1, "=not a formula")
        assertEquals("=not a formula", XlsxDocument(OfficeArchive(inline)).sheets[0].value(0, 1))
        val blank = XlsxDocument(OfficeArchive(inline)).edit(0, 4, 3, "new cell")
        assertEquals("new cell", XlsxDocument(OfficeArchive(blank)).sheets[0].value(4, 3))
        assertEquals("AA", columnName(26))
        assertEquals("XFD", columnName(16383))
    }

    private fun xlsx() = zip(
        "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Data" sheetId="1" r:id="r1"/><sheet name="Second" sheetId="2" r:id="r2"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="r1" Target="worksheets/sheet1.xml"/><Relationship Id="r2" Target="worksheets/sheet2.xml"/></Relationships>""",
        "xl/sharedStrings.xml" to """<sst><si><r><t>Shared </t></r><r><t>rich text</t></r></si></sst>""",
        "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s" s="3"><v>0</v></c><c r="B1" t="inlineStr"><is><t>inline 中文</t></is></c></row><row r="2"><c r="C2"><v>42</v></c></row><row r="3"><c r="A3"><f>SUM(A1:A2)</f><v>42</v></c></row></sheetData></worksheet>""",
        "xl/worksheets/sheet2.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c></row></sheetData></worksheet>""",
    )

    @Test fun pptxPreservesSlideOrderAndEditsShapeTextOnly() {
        val source = zip(
            "ppt/presentation.xml" to """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="r2"/><p:sldId id="257" r:id="r1"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships><Relationship Id="r1" Target="slides/slide1.xml"/><Relationship Id="r2" Target="slides/slide2.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to slide("first on disk"),
            "ppt/slides/slide2.xml" to slide("title"),
            "ppt/slides/_rels/slide2.xml.rels" to """<Relationships><Relationship Id="image1" Target="../media/image.png"/></Relationships>""",
            "ppt/media/image.png" to "image bytes",
        )
        val document = PptxDocument(OfficeArchive(source))
        assertEquals(listOf("title", "first on disk"), document.slides.map { it.shapes[0].text })
        assertEquals(1, document.slides[0].images.size)
        val changed = document.edit(0, 0, "New <title>\n第二行")
        val next = PptxDocument(OfficeArchive(changed))
        assertEquals("New <title>\n第二行", next.slides[0].shapes[0].text)
        assertEquals("body", next.slides[0].shapes[1].text)
        assertContains(OfficeArchive(changed).parts.getValue("ppt/slides/slide2.xml").toString(Charsets.UTF_8), "b=\"1\"")
        assertContentEquals(rawRecord(source, "ppt/slides/slide1.xml"), rawRecord(changed, "ppt/slides/slide1.xml"))
        assertContentEquals(rawRecord(source, "ppt/media/image.png"), rawRecord(changed, "ppt/media/image.png"))
    }

    private fun slide(title: String) = """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:cSld><p:spTree><p:sp><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr b="1"/><a:t>$title</a:t></a:r></a:p></p:txBody></p:sp><p:sp><p:txBody><a:p><a:r><a:t>body</a:t></a:r></a:p></p:txBody></p:sp><p:pic><a:blip r:embed="image1"/></p:pic></p:spTree></p:cSld></p:sld>"""

    @Test fun officeRejectsDtdAndZipBombs() {
        assertFailsWith<IllegalArgumentException> { OfficeXml.parse("<!DOCTYPE x [<!ENTITY x 'bad'>]><x>&x;</x>".toByteArray()) }
        val bomb = zipBytes("word/document.xml" to ByteArray(MAX_FILE_BYTES + 1))
        assertFailsWith<FileTooLarge> { OfficeArchive(bomb) }
    }

    /** Compare the local record, including the compressed payload and descriptor, without a ZIP rewrite. */
    private fun rawRecord(bytes: ByteArray, name: String): ByteArray {
        val end = (bytes.size - 22 downTo 0).first { bytes.i32(it) == 0x06054b50 }
        val central = bytes.i32(end + 16)
        var p = central
        val offsets = mutableMapOf<String, Int>()
        repeat(bytes.u16(end + 10)) {
            val n = bytes.u16(p + 28)
            offsets[bytes.copyOfRange(p + 46, p + 46 + n).toString(Charsets.UTF_8)] = bytes.i32(p + 42)
            p += 46 + n + bytes.u16(p + 30) + bytes.u16(p + 32)
        }
        val start = offsets.getValue(name)
        val next = offsets.values.filter { it > start }.minOrNull() ?: central
        return bytes.copyOfRange(start, next)
    }
}
