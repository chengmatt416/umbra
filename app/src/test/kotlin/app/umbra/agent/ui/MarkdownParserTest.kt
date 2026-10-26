package app.umbra.agent.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarkdownParserTest {
    @Test
    fun pipeTablesBecomeTablesWithoutTheSeparatorRow() {
        val blocks = MarkdownParser.parse(
            """
            Before.

            | Layer | Owner |
            |---|:---:|
            | Agent | omp |
            | Guest | root |

            After.
            """.trimIndent(),
        )
        assertEquals(3, blocks.size)
        val table = assertIs<MarkdownParser.Block.Table>(blocks[1])
        assertEquals(listOf("Layer", "Owner"), table.header)
        assertEquals(listOf(listOf("Agent", "omp"), listOf("Guest", "root")), table.rows)
        assertIs<MarkdownParser.Block.Paragraph>(blocks[2])
    }

    @Test
    fun aPipeInsideCodeIsNotATable() {
        val blocks = MarkdownParser.parse("```\n| not | a table |\n```")
        assertIs<MarkdownParser.Block.Code>(blocks.single())
    }

    @Test
    fun htmlBreaksProduceThreeLinesInsideATableCell() {
        val table = assertIs<MarkdownParser.Block.Table>(
            MarkdownParser.parse("| 階段 |\n|---|\n| 高三（12 年級上）<br>申請與收網<br/>完成 |").single(),
        )
        assertEquals(
            listOf("高三（12 年級上）", "申請與收網", "完成"),
            MarkdownParser.inline(table.rows.single().single(), Color.Transparent).text.lines(),
        )
    }

    @Test
    fun htmlBreaksRenderInsideParagraphsAndListItems() {
        val blocks = MarkdownParser.parse("Before<br />After\n\n- First<BR />Second\n1. One<br>2. Two")
        val paragraph = assertIs<MarkdownParser.Block.Paragraph>(blocks[0])
        val bullet = assertIs<MarkdownParser.Block.Bullet>(blocks[1])
        val ordered = assertIs<MarkdownParser.Block.Bullet>(blocks[2])
        assertEquals("Before\nAfter", MarkdownParser.inline(paragraph.text, Color.Transparent).text)
        assertEquals("First\nSecond", MarkdownParser.inline(bullet.text, Color.Transparent).text)
        assertEquals("One\n2. Two", MarkdownParser.inline(ordered.text, Color.Transparent).text)
    }

    @Test
    fun htmlBreaksAllowMixedCaseAndWhitespace() {
        for (tag in listOf("<br>", "<br/>", "<br />", "<BR />", "<bR   >", "<Br  /  >")) {
            assertEquals("a\nb", MarkdownParser.inline("a${tag}b", Color.Transparent).text, tag)
        }
    }

    @Test
    fun htmlBreaksRenderInsideStyledText() {
        assertEquals(
            "a\nb c\nd e\nf",
            MarkdownParser.inline("**a<br>b** *c<BR />d* [e<br/>f](https://example.com)", Color.Transparent).text,
        )
    }

    @Test
    fun otherHtmlAndCodeStayLiteral() {
        val html = "a<brx>b <b>bold</b> <hr> <br class=\"break\"> </br>"
        assertEquals(html, MarkdownParser.inline(html, Color.Transparent).text)
        assertEquals("a<br>b\nc", MarkdownParser.inline("`a<br>b`<br>c", Color.Transparent).text)
        val code = assertIs<MarkdownParser.Block.Code>(MarkdownParser.parse("```\na<BR />b\n```").single())
        assertEquals("a<BR />b", code.text)
    }
}
