package app.umbra.agent.ui.agents

import kotlin.test.Test
import kotlin.test.assertEquals

class PlainPreviewTest {
    @Test
    fun markdownMarksDoNotShowInTheAgentList() {
        assertEquals("Done: a tidy project report", plainPreview("## Done: a tidy project report\n\nI looked…"))
        assertEquals("Saved notes.md for you", plainPreview("```\ncode\n```\n**Saved** `notes.md` for you"))
        assertEquals("first point", plainPreview("- first point\n- second"))
        assertEquals("plain words", plainPreview("plain words"))
    }
}
