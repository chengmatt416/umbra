package app.umbra.agent.ui

import app.umbra.agent.ui.porcelain.pc
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The subset of Markdown agents actually write: paragraphs, headings, lists, code blocks, bold, italics, inline code. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val inlineCode = MaterialTheme.colorScheme.surfaceVariant
    SelectionContainer {
        Column(modifier) {
            blocks.forEachIndexed { index, block ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                when (block) {
                    is MarkdownParser.Block.Code -> Text(
                        block.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(codeBackground, RoundedCornerShape(8.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        color = color,
                    )
                    is MarkdownParser.Block.Heading -> Text(
                        MarkdownParser.inline(block.text, inlineCode),
                        style = if (block.level <= 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        color = color,
                    )
                    is MarkdownParser.Block.Bullet -> Row {
                        Text(if (block.ordered != null) "${block.ordered}." else "•", Modifier.padding(end = 8.dp, start = (block.depth * 12).dp), color = color)
                        Text(MarkdownParser.inline(block.text, inlineCode), style = MaterialTheme.typography.bodyMedium, color = color)
                    }
                    is MarkdownParser.Block.Paragraph -> Text(MarkdownParser.inline(block.text, inlineCode), style = MaterialTheme.typography.bodyMedium, color = color)
                    is MarkdownParser.Block.Table -> MarkdownTable(block, color, inlineCode)
                    MarkdownParser.Block.Rule -> androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

/** A table that keeps its columns: it scrolls sideways on a phone instead of squeezing every cell. */
@Composable
private fun MarkdownTable(table: MarkdownParser.Block.Table, color: Color, inlineCode: Color) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(pc(0x66F2F6EF), RoundedCornerShape(10.dp)).padding(vertical = 4.dp)) {
        (listOf(table.header) + table.rows).forEachIndexed { r, cells ->
            Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                for (c in 0 until columns) {
                    Text(
                        MarkdownParser.inline(cells.getOrElse(c) { "" }, inlineCode),
                        Modifier.width(if (c == 0) 120.dp else 168.dp).padding(end = 10.dp),
                        style = MaterialTheme.typography.bodySmall, color = color,
                        fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            if (r == 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 8.dp))
        }
    }
}

internal object MarkdownParser {
    sealed interface Block {
        data class Paragraph(val text: String) : Block
        data class Heading(val level: Int, val text: String) : Block
        data class Bullet(val text: String, val depth: Int, val ordered: Int?) : Block
        data class Code(val text: String) : Block
        /** A pipe table: the header row, then the body rows; the |---| separator line is dropped. */
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block
        data object Rule : Block
    }

    private val BULLET = Regex("""^(\s*)([-*+])\s+(.*)$""")
    private val ORDERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val TABLE_SEPARATOR = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$""")

    fun parse(text: String): List<Block> {
        val blocks = ArrayList<Block>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += Block.Paragraph(paragraph.toString().trimEnd()).also { paragraph.clear() }
        }
        var inCode = false
        val code = StringBuilder()
        val table = ArrayList<List<String>>()
        fun flushTable() {
            if (table.isEmpty()) return
            blocks += Block.Table(table.first(), table.drop(1))
            table.clear()
        }
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (!inCode && trimmed.startsWith("|") && trimmed.length > 1) {
                flush()
                if (!TABLE_SEPARATOR.matches(trimmed)) table += trimmed.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
                continue
            }
            flushTable()
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    blocks += Block.Code(code.toString().trimEnd('\n'))
                    code.clear()
                    inCode = false
                } else {
                    flush()
                    inCode = true
                }
                continue
            }
            if (inCode) {
                code.appendLine(line)
                continue
            }
            val heading = HEADING.find(line)
            val bullet = BULLET.find(line)
            val ordered = ORDERED.find(line)
            when {
                line.isBlank() -> flush()
                line.trim().matches(Regex("""[-*_]{3,}""")) -> { flush(); blocks += Block.Rule }
                heading != null -> { flush(); blocks += Block.Heading(heading.groupValues[1].length, heading.groupValues[2]) }
                bullet != null -> { flush(); blocks += Block.Bullet(bullet.groupValues[3], bullet.groupValues[1].length / 2, null) }
                ordered != null -> { flush(); blocks += Block.Bullet(ordered.groupValues[3], ordered.groupValues[1].length / 2, ordered.groupValues[2].toInt()) }
                else -> { if (paragraph.isNotEmpty()) paragraph.append('\n'); paragraph.append(line) }
            }
        }
        if (inCode) blocks += Block.Code(code.toString().trimEnd('\n'))
        flushTable()
        flush()
        return blocks
    }

    private val INLINE = Regex("""`([^`]+)`|\*\*([^*]+)\*\*|\*([^*\s][^*]*)\*|\[([^\]]+)]\(([^)]+)\)""")
    private val LINE_BREAK = Regex("""<br\s*/?\s*>""", RegexOption.IGNORE_CASE)

    /** Agent replies use HTML breaks inside Markdown; keep all other HTML literal. */
    private fun lineBreaks(text: String): String = LINE_BREAK.replace(text, "\n")

    fun inline(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
        var last = 0
        for (m in INLINE.findAll(text)) {
            append(lineBreaks(text.substring(last, m.range.first)))
            when {
                m.groups[1] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, fontSize = 13.sp)) { append(m.groupValues[1]) }
                m.groups[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lineBreaks(m.groupValues[2])) }
                m.groups[3] != null -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(lineBreaks(m.groupValues[3])) }
                m.groups[4] != null -> withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) { append(lineBreaks(m.groupValues[4])) }
            }
            last = m.range.last + 1
        }
        append(lineBreaks(text.substring(last)))
    }
}
