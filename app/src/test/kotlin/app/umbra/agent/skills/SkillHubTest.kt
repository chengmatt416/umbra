package app.umbra.agent.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillHubTest {
    private fun node(path: String, type: String = "blob", mode: String = "100644", size: Long = 1) = SkillTreeNode(path, type, mode, "abc", size)

    @Test fun eachTopicIsSearchedSeparatelyAndEmptyQueriesSortPopularRepos() {
        assertEquals(listOf("agent-skills", "claude-skills", "claude-code-skills", "codex-skills").map { "topic:$it" }, SkillHub.searchQueries("  "))
        assertTrue(SkillHub.searchQueries(" android ").all { it.startsWith("android in:name,description,readme topic:") })
        assertEquals(2, SkillHub.searchQueries("android").size)
        assertFalse(SkillHub.searchQueries("android").any { " OR " in it })
    }

    @Test fun mergingDeduplicatesByFullNameAndSortsByStars() {
        val repos = listOf(SkillRepo("owner/b", stars = 20), SkillRepo("owner/a", stars = 50), SkillRepo("OWNER/B", stars = 10), SkillRepo("owner/c", stars = 50))
        assertEquals(listOf("owner/a", "owner/c", "owner/b"), SkillHub.merge(repos).map { it.fullName })
    }

    @Test fun skillDiscoverySkipsDependenciesLinksAndUnsafePaths() {
        val tree = listOf(node("SKILL.md"), node("skills/a/SKILL.md"), node("skills/b/NOT-SKILL.md"),
            node("node_modules/a/SKILL.md"), node("a/vendor/b/SKILL.md"), node(".git/SKILL.md"),
            node("link/SKILL.md", mode = "120000"), node("../SKILL.md"), node("a\\SKILL.md"), node("a/SKILL.md", type = "tree", mode = "040000"))
        assertEquals(listOf("SKILL.md", "skills/a/SKILL.md"), SkillHub.skillPaths(tree))
    }

    @Test fun filesStayInsideTheExactFolderAndIncludeAllItsResources() {
        val tree = listOf(node("a/SKILL.md"), node("a/scripts/run.sh", mode = "100755"), node("a/references", "tree", "040000", 0),
            node("a/references/docs.md"), node("ab/secret"), node("other/SKILL.md"))
        assertEquals(listOf("a/SKILL.md", "a/references/docs.md", "a/scripts/run.sh"), SkillHub.installFiles(tree, "a").map { it.path })
        assertFailsWith<IllegalArgumentException> { SkillHub.installFiles(tree, "") }
        assertEquals(listOf("SKILL.md"), SkillHub.installFiles(listOf(node("SKILL.md")), "").map { it.path })
    }

    @Test fun unsafePathsSymlinksSubmodulesAndDuplicateFilesAreRefused() {
        listOf(node("a/link", mode = "120000"), node("a/module", type = "commit", mode = "160000"),
            node("a/../escape"), node("a//escape"), node("a/\\escape"), node("a/\u0000escape"), node("a/SKILL.md")).forEach { bad ->
            assertFailsWith<IllegalArgumentException> { SkillHub.installFiles(listOf(node("a/SKILL.md"), bad), "a") }
        }
        listOf("../a", "/a", "a/", "a/./b").forEach { folder ->
            assertFailsWith<IllegalArgumentException> { SkillHub.installFiles(listOf(node("a/SKILL.md")), folder) }
        }
    }

    @Test fun exactFileAndByteCapsAreAllowedButOverflowAndNegativeSizesAreRefused() {
        val files = listOf(node("a/SKILL.md", size = SkillHub.MAX_INSTALL_BYTES)) + (1 until 300).map { node("a/f$it", size = 0) }
        assertEquals(300, SkillHub.installFiles(files, "a").size)
        assertFailsWith<IllegalArgumentException> { SkillHub.installFiles(files + node("a/extra", size = 0), "a") }
        assertFailsWith<IllegalArgumentException> { SkillHub.installFiles(files.dropLast(1) + node("a/extra"), "a") }
        assertFailsWith<IllegalArgumentException> { SkillHub.installFiles(listOf(node("a/SKILL.md", size = -1)), "a") }
        assertFailsWith<IllegalArgumentException> { SkillHub.installFiles(listOf(node("a/SKILL.md", size = Long.MAX_VALUE)), "a") }
    }
}
