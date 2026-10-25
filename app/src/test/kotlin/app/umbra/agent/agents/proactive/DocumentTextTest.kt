package app.umbra.agent.agents.proactive

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class DocumentTextTest {
    private fun zip(vararg parts: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> parts.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()
    private fun document(extension: String) = ContextDocument("file", "invoice.$extension", "application/octet-stream")

    @Test fun wordAndSlidesPreserveUnicodeAndRichTextWithoutFollowingLinks() {
        val word = zip("word/document.xml" to """<w:document xmlns:w="urn:word"><w:body><w:p><w:r><w:t>研究 </w:t></w:r><w:hyperlink r:id="external"><w:r><w:t>invoice</w:t></w:r></w:hyperlink></w:p><w:p><w:r><w:t>42</w:t></w:r></w:p></w:body></w:document>""",
            "word/_rels/document.xml.rels" to """<Relationships><Relationship Target="https://example.invalid/private" TargetMode="External"/></Relationships>""")
        assertEquals("研究 invoice\n42", DocumentText.extract(document("docx"), word))
        val slides = zip("ppt/slides/slide10.xml" to "<a:p><a:r><a:t>Ten</a:t></a:r></a:p>",
            "ppt/slides/slide2.xml" to "<a:p><a:r><a:t>Two</a:t></a:r></a:p>")
        assertEquals("Two\nTen", DocumentText.extract(document("pptx"), slides))
    }

    @Test fun spreadsheetsResolveSharedAndInlineStringsAndUseOnlyCachedFormulaValues() {
        val bytes = zip("xl/sharedStrings.xml" to "<sst><si><r><t>Research </t></r><r><t>invoice</t></r></si></sst>",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row><c t="s"><v>0</v></c><c><v>42</v></c><c t="inlineStr"><is><t>中文</t></is></c><c><f>HYPERLINK("https://example.invalid")</f><v>cached</v></c></row></sheetData></worksheet>""")
        assertEquals("Research invoice\t42\t中文\tcached", DocumentText.extract(document("xlsx"), bytes))
    }

    @Test fun openDocumentsAreSupportedByMimeEvenWithoutAnExtension() {
        val doc = ContextDocument("file", "An invoice", "application/vnd.oasis.opendocument.text")
        assertTrue(DocumentText.supports(doc))
        assertEquals("Research invoice", DocumentText.extract(doc, zip("content.xml" to "<office:text><text:p>Research <text:span>invoice</text:span></text:p></office:text>")))
    }

    @Test fun corruptUnsupportedAndInflatingArchivesProduceNoEvidence() {
        assertFalse(DocumentText.supports(document("doc")))
        assertNull(DocumentText.extract(document("docx"), "fake word file".toByteArray()))
        assertNull(DocumentText.extract(document("docx"), zip("unrelated.xml" to "invoice")))
        assertNull(DocumentText.extract(document("docx"), zip("word/document.xml" to "<p><t>invoice</t></p>",
            "ignored/image.bin" to "a".repeat(DocumentText.MAX_EXPANDED_BYTES))))
        assertNull(DocumentText.extract(document("docx"), zip("word/document.xml" to "<p>".repeat(100) + "invoice" + "</p>".repeat(100))))
        assertNull(DocumentText.extract(document("docx"), zip("word/document.xml" to "<p>" + "<t>x</t>".repeat(20_001) + "</p>")))
    }

    @Test fun extractedOfficeTextUsesTheSameAuthorizedTraversalAndEvidenceLimits() {
        val doc = document("docx")
        val bytes = zip("word/document.xml" to "<p><t>Research invoice total 42</t></p>")
        val result = DocumentSearch.search("chosen", "research", 100, children = { _, _ -> listOf(doc) }, read = { _, _ -> bytes })
        assertEquals("file", result.single().id)
        assertTrue("Research invoice total 42" in result.single().text)
        assertEquals("drive_files", result.single().source)
    }

    @Test fun cloudDocumentsUseOnlyProviderOfferedTextExportsAndBoundTheExportBytes() {
        assertEquals("text/plain", DocumentText.virtualExportType(listOf("application/pdf", "text/csv", "text/plain")))
        assertEquals("text/csv", DocumentText.virtualExportType(listOf("text/csv")))
        assertNull(DocumentText.virtualExportType(listOf("application/pdf", "text/html", "application/javascript")))
        val doc = ContextDocument("cloud", "Invoice.xlsx", "application/vnd.google-apps.spreadsheet", size = 0, virtual = true)
        val result = DocumentSearch.search("chosen", "invoice", 100, children = { _, _ -> listOf(doc) },
            read = { _, _ -> "Research invoice\n42".toByteArray() })
        assertTrue("Research invoice" in result.single().text)
        val oversized = DocumentSearch.search("chosen", "invoice", 100, children = { _, _ -> listOf(doc.copy(size = 10)) },
            read = { _, limit -> ByteArray(limit) { 'x'.code.toByte() } })
        assertTrue(oversized.isEmpty())
    }

    @Test fun repeatedSharedStringsCannotExpandTheExtractedTextWithoutBound() {
        val bytes = zip("xl/sharedStrings.xml" to "<sst><si><t>" + "invoice".repeat(1000) + "</t></si></sst>",
            "xl/worksheets/sheet1.xml" to "<worksheet><row>" + "<c t=\"s\"><v>0</v></c>".repeat(2000) + "</row></worksheet>")
        val text = assertNotNull(DocumentText.extract(document("xlsx"), bytes))
        assertEquals(DocumentText.MAX_TEXT_CHARS, text.length)
        assertTrue(text.startsWith("invoice"))
    }
}
