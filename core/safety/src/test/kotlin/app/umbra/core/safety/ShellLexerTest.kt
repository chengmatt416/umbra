package app.umbra.core.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellLexerTest {
    private fun words(cmd: String) = ShellLexer.parse(cmd).map { it.words }

    @Test
    fun splitsWordsAndStripsQuotes() {
        assertEquals(listOf(listOf("rm", "-rf", "my dir")), words("""rm -rf "my dir""""))
        assertEquals(listOf(listOf("echo", "a; b && c")), words("echo 'a; b && c'"))
        assertEquals(listOf(listOf("echo", "it's")), words("""echo "it's""""))
        assertEquals(listOf(listOf("echo", "a b")), words("""echo a\ b"""))
    }

    @Test
    fun splitsOnOperatorsAndMarksPipes() {
        val segments = ShellLexer.parse("a && b || c; d | e & f")
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), segments.map { it.words.single() })
        assertEquals(listOf(false, false, false, false, true, false), segments.map { it.pipedFromPrevious })
    }

    @Test
    fun subshellParenthesesSeparateCommands() {
        assertEquals(listOf(listOf("cd", "/tmp"), listOf("rm", "-rf", "x")), words("(cd /tmp; rm -rf x)"))
    }

    @Test
    fun capturesRedirectionsAndDropsFileDescriptors() {
        val seg = ShellLexer.parse("echo hi > /tmp/x 2>&1 >> log.txt").single()
        assertEquals(listOf("echo", "hi"), seg.words)
        assertEquals(
            listOf(ShellLexer.Redirect(">", "/tmp/x"), ShellLexer.Redirect(">&", "1"), ShellLexer.Redirect(">>", "log.txt")),
            seg.redirects,
        )
    }

    @Test
    fun inputRedirectionIsNotAWriteTarget() {
        val seg = ShellLexer.parse("xargs rm -rf < list.txt").single()
        assertEquals(listOf("xargs", "rm", "-rf"), seg.words)
        assertEquals(listOf(ShellLexer.Redirect("<", "list.txt")), seg.redirects)
    }

    @Test
    fun heredocBodyIsNotParsedAsCommands() {
        val script = "cat > notes.txt <<'EOF'\nrm -rf /\nEOF\nls -la"
        val segments = ShellLexer.parse(script)
        assertEquals(2, segments.size, segments.toString())
        assertEquals(listOf("cat"), segments[0].words)
        assertEquals("rm -rf /\n", segments[0].heredoc)
        assertEquals(listOf("ls", "-la"), segments[1].words)
    }

    @Test
    fun detectsCommandSubstitutionOutsideSingleQuotes() {
        assertTrue(ShellLexer.parse("echo \$(date)").single().dynamic)
        assertTrue(ShellLexer.parse("echo `date`").single().dynamic)
        assertTrue(ShellLexer.parse("echo \"\$(date)\"").single().dynamic)
        assertFalse(ShellLexer.parse("echo '\$(date)'").single().dynamic)
        assertFalse(ShellLexer.parse("echo \$HOME").single().dynamic)
    }

    @Test
    fun commentsAreIgnored() {
        assertEquals(listOf(listOf("ls")), words("ls # rm -rf /"))
    }

    @Test
    fun lineContinuationJoinsLines() {
        assertEquals(listOf(listOf("rm", "-rf", "build")), words("rm \\\n -rf build"))
    }

    @Test
    fun environmentAssignmentsStayAsWords() {
        assertEquals(listOf(listOf("FOO=bar", "ls")), words("FOO=bar ls"))
    }

    @Test
    fun emptyAndWhitespaceInputProduceNoSegments() {
        assertTrue(ShellLexer.parse("").isEmpty())
        assertTrue(ShellLexer.parse("  \n ; ").isEmpty())
        assertNull(ShellLexer.parse("ls").single().heredoc)
    }
}
