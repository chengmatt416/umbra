package app.umbra.agent.agents.proactive

import kotlin.test.*

class DocumentSearchTest {
    private fun file(id: String, name: String = "$id.md", size: Long = -1) = ContextDocument(id, name, "text/plain", size = size)
    @Test fun chosenTreeTraversalDeduplicatesCyclesAndReturnsBoundedRelevantExcerpts() {
        val body = "x".repeat(5000) + "Invoice for the research project" + "y".repeat(5000)
        val opened = mutableListOf<String>()
        val result = DocumentSearch.search("chosen", "research invoice", 100, children = { parent, _ ->
            when (parent) {
                "chosen" -> listOf(ContextDocument("nested", "Nested", "directory", directory = true), file("public"))
                "nested" -> listOf(ContextDocument("chosen", "Cycle", "directory", directory = true), file("invoice"))
                else -> error("Visited an unselected folder")
            }
        }, read = { entry, _ -> opened += entry.id; (if (entry.id == "invoice") body else "Unrelated content").toByteArray() })
        assertEquals(listOf("public", "invoice"), opened)
        assertEquals("invoice", result.single().id)
        assertTrue(result.single().text.contains("research project"))
        assertTrue(result.single().text.length < 2700)
        assertEquals("document", result.single().entityType)
        assertEquals("drive_files", result.single().source)
    }
    @Test fun binaryOversizedAndInvalidUtf8DocumentsNeverBecomeEvidence() {
        val read = mutableListOf<String>()
        val result = DocumentSearch.search("chosen", "invoice", 100, children = { _, _ -> listOf(
            ContextDocument("pdf", "invoice.pdf", "application/pdf"), file("large", size = DocumentSearch.MAX_FILE_BYTES + 1L),
            file("invalid"), file("nul"), file("valid")) }, read = { entry, _ ->
            read += entry.id
            when (entry.id) { "invalid" -> byteArrayOf(0xc3.toByte(), 0x28); "nul" -> "invoice\u0000binary".toByteArray(); else -> "invoice text".toByteArray() }
        })
        assertEquals(listOf("invalid", "nul", "valid"), read)
        assertEquals("valid", result.single().id)
    }
    @Test fun unknownLengthStreamsConsumeASharedByteBudgetInsteadOfReadingEveryFile() {
        var consumed = 0
        var reads = 0
        val result = DocumentSearch.search("chosen", "invoice", 100, children = { _, limit ->
            (1..limit).map { file("file$it") }
        }, read = { _, limit ->
            assertTrue(limit <= DocumentSearch.MAX_FILE_BYTES + 1)
            reads++; consumed += limit
            ByteArray(limit) { 'x'.code.toByte() }
        })
        assertTrue(result.isEmpty())
        assertTrue(reads < 10)
        assertEquals(DocumentSearch.MAX_TOTAL_BYTES, consumed)
    }
    @Test fun metadataAndDepthLimitsHoldEvenWhenEveryEntryIsADirectory() {
        var visited = 0
        val result = DocumentSearch.search("chosen", "invoice", 100, children = { parent, limit ->
            visited++
            assertTrue(limit <= DocumentSearch.MAX_ENTRIES)
            listOf(ContextDocument("$parent/nested", "nested", "directory", directory = true))
        }, read = { _, _ -> error("Directories cannot be opened as files") })
        assertTrue(result.isEmpty())
        assertEquals(9, visited)
    }
    @Test fun cjkAndFilenameMatchesRetainDocumentDatesAndSupportTextExtensions() {
        val entry = ContextDocument("note", "專案會議.md", "application/octet-stream", modifiedAtMs = 42)
        val result = DocumentSearch.search("chosen", "專案會議", 100, children = { _, _ -> listOf(entry) },
            read = { _, _ -> "明天下午兩點開始。".toByteArray() })
        assertEquals(42L, result.single().atMs)
        assertTrue(result.single().text.contains("明天下午"))
    }
}
