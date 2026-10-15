package app.umbra.agent.ui

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
}
