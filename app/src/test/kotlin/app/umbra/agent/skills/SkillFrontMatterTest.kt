package app.umbra.agent.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkillFrontMatterTest {
    @Test fun quotedScalarsCommentsBomAndWindowsLineEndings() {
        val text = "\uFEFF---\r\nname: 'user''s-skill'\r\ndescription: \"Read \\\"quoted\\\" files\"\r\nlicense: MIT # permitted\r\n---\r\n# Body"
        assertEquals(SkillFrontMatter("user's-skill", "Read \"quoted\" files", "MIT"), SkillFrontMatterParser.parse(text))
    }

    @Test fun foldedAndLiteralDescriptions() {
        fun parse(indicator: String) = SkillFrontMatterParser.parse("""
            ---
            name: android
            description: $indicator
              Build Android apps
              and verify Kotlin code.
            license: Apache-2.0
            ---
        """.trimIndent())
        assertEquals("Build Android apps and verify Kotlin code.", parse(">-")?.description)
        assertEquals("Build Android apps\nand verify Kotlin code.", parse("|")?.description)
        assertEquals("Apache-2.0", parse(">")?.license)
    }

    @Test fun incompleteOrMissingFrontMatterIsIgnored() {
        listOf("# A skill", "---\nname: test\n---", "---\ndescription: text\n---",
            "---\nname: test\ndescription: ''\n---", "---\nname: test\ndescription: text").forEach {
            assertNull(SkillFrontMatterParser.parse(it), it)
        }
        assertNull(SkillFrontMatterParser.parse("---\nname: test\ndescription: \"bad\\q\"\n---"))
    }

    @Test fun inlineCommentsDoNotConsumeQuotedHashesOrBlockDescriptions() {
        val front = SkillFrontMatterParser.parse("---\nname: 'test' # comment\ndescription: \"Read # references\" # another comment\n---")
        assertEquals(SkillFrontMatter("test", "Read # references"), front)
        assertEquals("Read references", SkillFrontMatterParser.parse("---\nname: test\ndescription: > # comment\n  Read references\n---")?.description)
        assertNull(SkillFrontMatterParser.parse("---\nname: first\nname: second\ndescription: text\n---"))
    }
}
